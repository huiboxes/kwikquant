package com.kwikquant.account.application;

import com.kwikquant.account.domain.ApiKeyEncryptor;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.infrastructure.ExchangeAccountMapper;
import com.kwikquant.account.infrastructure.PaperBalanceAdapter;
import com.kwikquant.account.infrastructure.RefreshTokenMapper;
import com.kwikquant.shared.infra.Auditable;
import com.kwikquant.shared.infra.OwnershipCheck;
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

    public ExchangeAccountService(
            ExchangeAccountMapper mapper,
            RefreshTokenMapper refreshTokenMapper,
            KeyManagementService keyService,
            PaperBalanceAdapter paperBalanceAdapter) {
        this.mapper = mapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.keyService = keyService;
        this.paperBalanceAdapter = paperBalanceAdapter;
    }

    /**
     * 创建交易所账户。PAPER 账户必填 referenceExchange(BINANCE/BITGET/OKX,行情基准,不可变);
     * 真实交易所账户 referenceExchange 必须为 null(exchange 自身即行情源)。
     *
     * <p>修硬编码 bug:原 create 对所有账户 setPaperTrading(true),现改为 PAPER→true / 真实→false
     * (OrderRouter.route 按 isPaperTrading 分流的前提)。
     *
     * <p>PAPER 账户跳过 AES-256-GCM 加密(占位 secret 无意义):apiSecret 存空 byte[],nonce null;
     * 创建后调 paperBalanceAdapter.initBalance 初始化 10 万 USDT。
     */
    @Transactional
    @Auditable(action = "ACCOUNT_CREATED", targetType = "exchange_account", targetId = "#label")
    public ExchangeAccount create(
            long userId,
            Exchange exchange,
            String label,
            String apiKey,
            String apiSecret,
            String passphrase,
            Exchange referenceExchange) {
        boolean isPaper = exchange == Exchange.PAPER;
        if (isPaper && referenceExchange == null) {
            throw new IllegalArgumentException("PAPER account requires referenceExchange");
        }
        if (!isPaper && referenceExchange != null) {
            throw new IllegalArgumentException("real exchange account must have null referenceExchange");
        }

        ExchangeAccount account = new ExchangeAccount();
        account.setUserId(userId);
        account.setExchange(exchange);
        account.setReferenceExchange(referenceExchange);
        account.setLabel(label);
        account.setApiKey(apiKey);
        account.setPaperTrading(isPaper);
        account.setStatus("ACTIVE");

        if (isPaper) {
            account.setApiSecret(new byte[0]);
            account.setPassphrase(null);
            account.setNonce(null);
            account.setPassphraseNonce(null);
        } else {
            EncryptionPack pack = encryptCredentials(apiSecret, passphrase);
            account.setApiSecret(pack.encryptedSecret);
            account.setPassphrase(pack.encryptedPassphrase);
            account.setNonce(pack.secretNonce);
            account.setPassphraseNonce(pack.passphraseNonce);
            account.setKeyVersion(keyService.getCurrentKeyVersion());
        }

        mapper.insert(account);

        if (isPaper) {
            paperBalanceAdapter.initBalance(account.getId());
        }

        return account;
    }

    public List<ExchangeAccountView> listByUser(long userId) {
        return mapper.findByUserId(userId).stream()
                .map(a -> new ExchangeAccountView(
                        a.getId(),
                        a.getExchange(),
                        a.getLabel(),
                        a.getApiKey(),
                        a.isPaperTrading(),
                        a.getStatus(),
                        a.getReferenceExchange()))
                .toList();
    }

    public ExchangeAccount findById(long accountId) {
        return mapper.findById(accountId);
    }

    /**
     * Wave 8 §3.7 R4:Worker→Java POST /api/v1/orders 从 WorkerTokenFilter 注入的 (userId, exchange) 推导
     * ExchangeAccount。返回 null 表示该 (user, exchange) 尚无账户,Controller 应拒单。
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
        account.setPassphrase(pack.encryptedPassphrase);
        account.setNonce(pack.secretNonce);
        account.setPassphraseNonce(pack.passphraseNonce);
        account.setKeyVersion(keyService.getCurrentKeyVersion());
        // reference_exchange 不可变,update 不写它(同 market_type 模式)
        // 深度防御消费：update WHERE 含 user_id，返回 0 = 并发 owner 变更
        int updated = mapper.update(account);
        if (updated == 0) {
            throw new com.kwikquant.shared.infra.ResourceStateConflictException("exchange_account " + accountId);
        }
        refreshTokenMapper.revokeAllByUserId(userId);
        return new ExchangeAccountView(
                account.getId(),
                account.getExchange(),
                label,
                apiKey,
                account.isPaperTrading(),
                account.getStatus(),
                account.getReferenceExchange());
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
                            description = "交易所（枚举: PAPER | BINANCE | BITGET | OKX）",
                            example = "BINANCE")
                    Exchange exchange,
            @io.swagger.v3.oas.annotations.media.Schema(description = "账户标签", example = "主账户") String label,
            @io.swagger.v3.oas.annotations.media.Schema(description = "API key 脱敏后缀，完整 key 不出后端", example = "...a1b2")
                    String apiKey,
            @io.swagger.v3.oas.annotations.media.Schema(description = "是否模拟盘", example = "false") boolean paperTrading,
            @io.swagger.v3.oas.annotations.media.Schema(description = "账户状态", example = "ACTIVE") String status,
            @io.swagger.v3.oas.annotations.media.Schema(
                            description = "基准交易所（仅 PAPER 账户: BINANCE/BITGET/OKX;真实交易所账户为 null）",
                            example = "BINANCE")
                    Exchange referenceExchange) {}
}
