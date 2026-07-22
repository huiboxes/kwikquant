package com.kwikquant.account.application;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.CcxtProxyApplier;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.infra.ProxyProperties;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import io.github.ccxt.exchanges.pro.Binance;
import io.github.ccxt.exchanges.pro.Bitget;
import io.github.ccxt.exchanges.pro.Okx;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CCXT 鉴权 Exchange 工厂。从原 {@code BalanceService.createAuthenticatedExchange} 抽出,供
 * {@link BalanceService}(fetchBalance 拉 SPOT 余额)与 {@code DefaultCcxtOrderAdapter}(4a.3/4a.4 PERP
 * 下单)共用,避免 DRY 重复解密 + 按交易所构造 + proxy 注入逻辑。
 *
 * <p><strong>架构边界修正</strong>:放在 {@code account.application}(跟 BalanceService /
 * KeyManagementService 同层),<em>不</em>放 shared/infra —— shared 模块不能依赖 account.domain 的
 * {@link KeyManagementService}(ArchUnit ModularityTests 会挂,trading→shared→account 循环)。
 * trading 已依赖 account.application(如 ExchangeAccountService),factory 在此包天然可达。
 *
 * <p>PAPER 账户走 {@code PaperBalanceAdapter},不该进此 factory —— 调用方在进 factory 前已按
 * {@code isPaperTrading()} 分流;factory 内部对 PAPER 仍抛 {@link ExchangeException}(防御性,避免
 * 解密空 secret 浪费 + 清晰报错)。
 *
 * <p>对应阶段 4a.2 重构任务。原 BalanceService.createAuthenticatedExchange 逻辑(第 140-182 行)
 * 整体迁入此处,新增 {@code marketType} 参数:PERP 加 {@code options.defaultType=swap}(参考
 * {@code CcxtExchangeRegistry.createExchange} 第 146-168 行),SPOT 不加。
 */
@Component
public class CcxtAuthExchangeFactory {

    private static final Logger log = LoggerFactory.getLogger(CcxtAuthExchangeFactory.class);

    private final KeyManagementService keyManagementService;
    private final ProxyProperties proxyProperties;

    public CcxtAuthExchangeFactory(KeyManagementService keyManagementService, ProxyProperties proxyProperties) {
        this.keyManagementService = keyManagementService;
        this.proxyProperties = proxyProperties;
    }

    /**
     * 创建已鉴权的 CCXT Exchange 实例。复用原 BalanceService.createAuthenticatedExchange 逻辑 +
     * 新增 {@code marketType} 参数控制 {@code options.defaultType}。
     *
     * <p>步骤:① 解密 secret/passphrase(解密后 Arrays.fill 清零,防内存残留)→ ② buildConfig
     * 塞 proxy + PERP defaultType → ③ 按交易所 new Binance/Okx/Bitget + apiKey/secret/password →
     * ④ applyWs 设 wsSocksProxy。
     *
     * @param account   交易所账户(含加密 apiKey/secret/passphrase)
     * @param marketType SPOT 不加 defaultType;PERP 加 {@code options.defaultType=swap}
     * @return 已注入鉴权字段 + proxy 的 CCXT Exchange 实例
     * @throws ExchangeException PAPER 账户(不应进此 factory)或不支持的交易所
     */
    public io.github.ccxt.Exchange createAuthExchange(ExchangeAccount account, MarketType marketType) {
        // PAPER 防御性拒绝:调用方应在进 factory 前按 isPaperTrading() 分流到 PaperBalanceAdapter
        if (account.getExchange() == Exchange.PAPER) {
            throw new ExchangeException("PAPER 无需 CCXT 鉴权 exchange", true);
        }

        String apiKey = account.getApiKey();
        byte[] secretBytes = keyManagementService.decryptSecret(account);
        String secret = new String(secretBytes, StandardCharsets.UTF_8);
        Arrays.fill(secretBytes, (byte) 0);
        byte[] passphraseBytes = keyManagementService.decryptPassphrase(account);
        String passphrase = passphraseBytes != null ? new String(passphraseBytes, StandardCharsets.UTF_8) : null;
        if (passphraseBytes != null) Arrays.fill(passphraseBytes, (byte) 0);

        ProxyProperties.ProxyConfig p = proxyProperties.resolve(account.getExchange());
        Map<String, Object> config = buildConfig(p, marketType);

        io.github.ccxt.Exchange ex =
                switch (account.getExchange()) {
                    case BINANCE -> {
                        var e = new Binance(config);
                        e.apiKey = apiKey;
                        e.secret = secret;
                        yield e;
                    }
                    case OKX -> {
                        var e = new Okx(config);
                        e.apiKey = apiKey;
                        e.secret = secret;
                        if (passphrase != null) e.password = passphrase;
                        yield e;
                    }
                    case BITGET -> {
                        var e = new Bitget(config);
                        e.apiKey = apiKey;
                        e.secret = secret;
                        if (passphrase != null) e.password = passphrase;
                        yield e;
                    }
                        // PAPER 已在方法入口拦截;未来新增交易所若忘加 case 也走此 default 报错
                    default -> throw new ExchangeException("unsupported exchange: " + account.getExchange(), true);
                };

        CcxtProxyApplier.applyWs(ex, p);
        // B 路线(testnet)预留:如启用 setSandboxMode(true),在此处开启。当前路线 A(自建 paper)不启用。
        return ex;
    }

    /**
     * 纯函数:构建 CCXT Exchange 构造用的 config map。独立抽出便于单测 —— 实际 {@code new Okx(config)}
     * 走集成(同 BalanceService 原 createAuthenticatedExchange 排除 JaCoCo);defaultType 分支与
     * proxy 注入逻辑可纯函数覆盖。参考 {@code CcxtExchangeRegistry.indexByCanonical/resolveOrThrow}
     * 纯函数抽法。
     *
     * @param p          proxy 配置(null 或 direct 跳过)
     * @param marketType PERP 加 {@code options.defaultType=swap};SPOT 不加
     * @return CCXT 构造用 config map(调用方 new Xxx(config) 时读取)
     */
    static Map<String, Object> buildConfig(ProxyProperties.ProxyConfig p, MarketType marketType) {
        Map<String, Object> config = new HashMap<>();
        if (marketType == MarketType.PERP) {
            config.put("options", Map.of("defaultType", "swap"));
        }
        CcxtProxyApplier.applyRest(config, p);
        return config;
    }
}
