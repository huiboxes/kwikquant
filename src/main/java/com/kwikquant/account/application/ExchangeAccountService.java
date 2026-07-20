package com.kwikquant.account.application;

import com.kwikquant.account.domain.ApiKeyEncryptor;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.infrastructure.ExchangeAccountMapper;
import com.kwikquant.account.infrastructure.PaperBalanceAdapter;
import com.kwikquant.account.infrastructure.RefreshTokenMapper;
import com.kwikquant.shared.infra.Auditable;
import com.kwikquant.shared.infra.OwnershipCheck;
import com.kwikquant.shared.infra.QuoteCurrencyProperties;
import com.kwikquant.shared.types.Exchange;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExchangeAccountService {

    private final ExchangeAccountMapper mapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final KeyManagementService keyService;
    private final PaperBalanceAdapter paperBalanceAdapter;
    private final QuoteCurrencyProperties quoteCurrencyProperties;

    public ExchangeAccountService(
            ExchangeAccountMapper mapper,
            RefreshTokenMapper refreshTokenMapper,
            KeyManagementService keyService,
            PaperBalanceAdapter paperBalanceAdapter,
            QuoteCurrencyProperties quoteCurrencyProperties) {
        this.mapper = mapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.keyService = keyService;
        this.paperBalanceAdapter = paperBalanceAdapter;
        this.quoteCurrencyProperties = quoteCurrencyProperties;
    }

    /**
     * 创建交易所账户。{@code exchange} 只表示撮合/定价参考哪个真实交易所的公开行情，禁止取值
     * {@link Exchange#PAPER}（该值没有行情源，{@code CcxtExchangeRegistry} 会拒绝）——是否模拟盘由
     * {@code paperTrading} 独立决定，跟前端"参考交易所"下拉框绑的是同一个 {@code exchange} 字段，不需要
     * 第二个字段表达"基准交易所"。
     *
     * <p>实盘（{@code paperTrading=false}）必须提供 {@code apiKey}/{@code apiSecret}；模拟盘可不填，
     * 因为撮合走 {@code exchange} 指向的匿名公开行情连接，不需要鉴权，对应列存真 {@code NULL}（不是占位
     * 空 byte[]，避免让人误以为模拟盘账户真的持有一份"空的"密文）。模拟盘建号成功后立即调
     * {@link PaperBalanceAdapter#initBalance} 播种初始余额。
     */
    @Transactional
    @Auditable(action = "ACCOUNT_CREATED", targetType = "exchange_account", targetId = "#cmd.label")
    public ExchangeAccount create(CreateAccountCommand cmd) {
        return create(
                cmd.userId(),
                cmd.exchange(),
                cmd.label(),
                cmd.apiKey(),
                cmd.apiSecret(),
                cmd.passphrase(),
                cmd.paperTrading());
    }

    /** 内部实现：保留原签名以兼容测试和可能的内部调用。 */
    private ExchangeAccount create(
            long userId,
            Exchange exchange,
            String label,
            String apiKey,
            String apiSecret,
            String passphrase,
            boolean paperTrading) {
        if (exchange == Exchange.PAPER) {
            throw new IllegalArgumentException("exchange must not be PAPER; use paperTrading=true instead");
        }
        if (!paperTrading && (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank())) {
            throw new IllegalArgumentException("apiKey/apiSecret are required for a live (non-paper) account");
        }
        // FE-TD-038: 同交易所单账户不变量（DB UNIQUE(user_id, exchange) 兜底）。预检给清晰 409，竞态由约束兜底。
        if (mapper.findByUserAndExchange(userId, exchange.name()) != null) {
            throw new com.kwikquant.shared.infra.ResourceStateConflictException(
                    "exchange_account already exists for user=" + userId + " exchange=" + exchange);
        }

        ExchangeAccount account = new ExchangeAccount();
        account.setUserId(userId);
        account.setExchange(exchange);
        account.setLabel(label);
        account.setPaperTrading(paperTrading);
        account.setStatus("ACTIVE");

        if (apiSecret != null && !apiSecret.isBlank()) {
            EncryptionPack pack = encryptCredentials(apiSecret, passphrase);
            account.setApiKey(apiKey);
            account.setApiSecret(pack.encryptedSecret);
            account.setPassphrase(pack.encryptedPassphrase);
            account.setNonce(pack.secretNonce);
            account.setPassphraseNonce(pack.passphraseNonce);
            account.setKeyVersion(keyService.getCurrentKeyVersion());
        }

        try {
            mapper.insert(account);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 竞态：预检通过但并发插入先到，UNIQUE(user_id, exchange) 约束兜底 → 409
            throw new com.kwikquant.shared.infra.ResourceStateConflictException(
                    "exchange_account already exists for user=" + userId + " exchange=" + exchange);
        }

        if (paperTrading) {
            paperBalanceAdapter.initBalance(account.getId(), quoteCurrencyProperties.primaryQuoteCurrency());
        }

        return account;
    }

    public List<ExchangeAccountView> listByUser(long userId) {
        return mapper.findByUserId(userId).stream()
                .map(a -> new ExchangeAccountView(
                        a.getId(), a.getExchange(), a.getLabel(), a.getApiKey(), a.isPaperTrading(), a.getStatus()))
                .toList();
    }

    public ExchangeAccount findById(long accountId) {
        return mapper.findById(accountId);
    }

    /**
     * Wave 8 §3.7 R4:Worker→Java POST /api/v1/orders 从 WorkerTokenFilter 注入的 (userId, exchange) 推导
     * ExchangeAccount。返回 null 表示该 (user, exchange) 尚无账户,Controller 应拒单。
     *
     * <p>FE-TD-038:依赖 exchange_accounts 的 UNIQUE(user_id, exchange) 不变量（同交易所单账户产品规则）,
     * 故返回值唯一无歧义;多账户场景已被产品规则排除。
     */
    public ExchangeAccount findByUserAndExchange(long userId, String exchange) {
        return mapper.findByUserAndExchange(userId, exchange);
    }

    /** 内部用：启动恢复遍历所有账户。不对外暴露（无鉴权）。 */
    public List<ExchangeAccount> findAll() {
        return mapper.findAll();
    }

    public ExchangeAccount getOwned(long accountId, long userId) {
        ExchangeAccount account = mapper.findById(accountId);
        return OwnershipCheck.requireOwned(
                account, account == null ? 0 : account.getUserId(), userId, "exchange_account");
    }

    @Transactional
    @Auditable(action = "ACCOUNT_DELETED", targetType = "exchange_account", targetId = "#accountId")
    public void delete(long accountId, long userId) {
        ExchangeAccount account = getOwned(accountId, userId);
        // 深度防御消费：deleteByIdAndUser WHERE 含 user_id，返回 0 = 并发 owner 变更或已被删除
        int deleted = mapper.deleteByIdAndUser(account.getId(), userId);
        if (deleted == 0) {
            throw new com.kwikquant.shared.infra.ResourceStateConflictException("exchange_account " + accountId);
        }
        refreshTokenMapper.revokeAllByUserId(userId);
    }

    @Transactional
    @Auditable(action = "ACCOUNT_UPDATED", targetType = "exchange_account", targetId = "#accountId")
    public ExchangeAccountView update(
            long accountId, long userId, String label, String apiKey, String apiSecret, String passphrase) {
        ExchangeAccount account = getOwned(accountId, userId);
        EncryptionPack pack = encryptCredentials(apiSecret, passphrase);
        account.setLabel(label);
        account.setApiKey(apiKey);
        account.setApiSecret(pack.encryptedSecret);
        account.setNonce(pack.secretNonce);
        // passphrase 未传（null）= 维持原值，不覆盖清空；OKX/Bitget 等必须携带 passphrase 才能通过
        // 交易所鉴权，若无条件覆盖会导致只改 label/apiKey 的更新静默清空已存的 passphrase。
        if (passphrase != null) {
            account.setPassphrase(pack.encryptedPassphrase);
            account.setPassphraseNonce(pack.passphraseNonce);
        }
        account.setKeyVersion(keyService.getCurrentKeyVersion());
        // exchange 不可变，update 不写它
        // 深度防御消费：update WHERE 含 user_id，返回 0 = 并发 owner 变更
        int updated = mapper.update(account);
        if (updated == 0) {
            throw new com.kwikquant.shared.infra.ResourceStateConflictException("exchange_account " + accountId);
        }
        refreshTokenMapper.revokeAllByUserId(userId);
        return new ExchangeAccountView(
                account.getId(), account.getExchange(), label, apiKey, account.isPaperTrading(), account.getStatus());
    }

    private EncryptionPack encryptCredentials(String apiSecret, String passphrase) {
        byte[] currentKey = keyService.getCurrentKey();
        byte[] secretNonce = ApiKeyEncryptor.generateNonce();
        byte[] encryptedSecret =
                ApiKeyEncryptor.encrypt(apiSecret.getBytes(StandardCharsets.UTF_8), currentKey, secretNonce);
        byte[] encryptedPassphrase = null;
        byte[] passphraseNonce = null;
        if (passphrase != null) {
            passphraseNonce = ApiKeyEncryptor.generateNonce();
            encryptedPassphrase =
                    ApiKeyEncryptor.encrypt(passphrase.getBytes(StandardCharsets.UTF_8), currentKey, passphraseNonce);
        }
        return new EncryptionPack(encryptedSecret, secretNonce, encryptedPassphrase, passphraseNonce);
    }

    private record EncryptionPack(
            byte[] encryptedSecret, byte[] secretNonce, byte[] encryptedPassphrase, byte[] passphraseNonce) {}

    public record ExchangeAccountView(
            @io.swagger.v3.oas.annotations.media.Schema(description = "账户 ID", example = "42") Long id,
            @io.swagger.v3.oas.annotations.media.Schema(
                            description =
                                    "参考交易所（枚举: BINANCE | OKX | BITGET）——仅表示撮合/定价参考哪个交易所的公开行情，" + "不表示是否模拟盘，不接受 PAPER",
                            example = "BINANCE")
                    Exchange exchange,
            @io.swagger.v3.oas.annotations.media.Schema(description = "账户标签", example = "主账户") String label,
            @io.swagger.v3.oas.annotations.media.Schema(description = "API key 脱敏后缀，完整 key 不出后端", example = "...a1b2")
                    String apiKey,
            @io.swagger.v3.oas.annotations.media.Schema(description = "是否模拟盘", example = "false") boolean paperTrading,
            @io.swagger.v3.oas.annotations.media.Schema(description = "账户状态", example = "ACTIVE") String status) {}
}
