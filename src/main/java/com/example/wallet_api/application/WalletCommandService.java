package com.example.wallet_api.application;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet_api.domain.RegisteredTransaction;
import com.example.wallet_api.domain.Wallet;
import com.example.wallet_api.domain.port.WalletCommandPort;
import com.example.wallet_api.domain.port.WalletQueryPort;

/**
 * Cada operacao e uma transacao curta: o PKG_WALLET nao comita, entao o lock da carteira dura ate o fim
 * deste metodo. Nada externo (rede, fila) acontece aqui dentro.
 */
@Service
public class WalletCommandService {

    private final WalletCommandPort commandPort;
    private final WalletQueryPort queryPort;

    /**
     * Teto da transacao (vira query timeout nos comandos JDBC): cobre os 5 s de espera pelo lock no package
     * com folga, e impede que uma requisicao fique presa se o banco travar.
     */
    static final int TRANSACTION_TIMEOUT_SECONDS = 10;

    public WalletCommandService(WalletCommandPort commandPort, WalletQueryPort queryPort) {
        this.commandPort = commandPort;
        this.queryPort = queryPort;
    }

    /** Relida na mesma transacao para devolver exatamente o que foi gravado (titular normalizado, datas). */
    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public Wallet createWallet(String holderName) {
        long walletId = commandPort.createWallet(holderName);
        return queryPort.findWallet(walletId)
                .orElseThrow(() -> new IllegalStateException("Carteira recem-criada nao encontrada: " + walletId));
    }

    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public RegisteredTransaction registerTransaction(long walletId, String type, BigDecimal amount, String idempotencyKey) {
        return commandPort.registerTransaction(walletId, type, amount, idempotencyKey);
    }
}
