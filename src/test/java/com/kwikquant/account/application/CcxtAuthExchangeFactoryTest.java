package com.kwikquant.account.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.infra.ProxyProperties;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * CcxtAuthExchangeFactory 单元测试。覆盖可单测部分:
 * <ul>
 *   <li>{@link #buildConfig} 纯函数:PERP 加 {@code options.defaultType=swap},SPOT 不加。
 *       参考纯函数抽法({@code CcxtExchangeRegistry.indexByCanonical})。</li>
 *   <li>{@link #createAuthExchange} PAPER 防御性拒绝(在 {@code new Xxx()} 前抛,不走网络)。</li>
 * </ul>
 *
 * <p>真实交易所(OKX/Binance/Bitget)的 createAuthExchange 走 {@code new Okx(config)} + 网络鉴权,
 * 不可单测(同原 BalanceService.createAuthenticatedExchange,JaCoCo 排除)。
 */
class CcxtAuthExchangeFactoryTest {

    @Test
    void buildConfig_perp_addsDefaultTypeSwap() {
        Map<String, Object> config = CcxtAuthExchangeFactory.buildConfig(null, MarketType.PERP);

        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) config.get("options");
        assertThat(options).containsEntry("defaultType", "swap");
    }

    @Test
    void buildConfig_spot_doesNotAddDefaultType() {
        Map<String, Object> config = CcxtAuthExchangeFactory.buildConfig(null, MarketType.SPOT);

        // SPOT 不加 options.defaultType(走 spot 命名空间,BTC/USDT 直接合法)
        assertThat(config).doesNotContainKey("options");
    }

    @Test
    void createAuthExchange_paper_throwsBeforeAnyNetwork() {
        // PAPER 账户应在进 new Xxx() 之前就抛(防御性,避免解密空 secret 浪费 + 清晰报错)
        CcxtAuthExchangeFactory factory =
                new CcxtAuthExchangeFactory(mock(KeyManagementService.class), new ProxyProperties(null, Map.of()));
        ExchangeAccount paper = new ExchangeAccount();
        paper.setExchange(Exchange.PAPER);

        assertThatThrownBy(() -> factory.createAuthExchange(paper, MarketType.PERP))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("PAPER");
    }
}
