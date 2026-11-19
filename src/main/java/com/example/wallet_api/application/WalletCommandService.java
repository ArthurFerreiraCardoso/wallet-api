package com.example.wallet_api.application;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.wallet_api.domain.RegisteredTransaction;
import com.example.wallet_api.domain.TransactionType;
import com.example.wallet_api.domain.Wallet;
import com.example.wallet_api.domain.WalletException;
import com.example.wallet_api.domain.port.WalletCommandPort;
import com.example.wallet_api.domain.port.WalletQueryPort;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Cada operacao e uma transacao curta: o PKG_WALLET nao comita, entao o lock da carteira dura ate o fim
 * deste metodo. Nada externo (rede, fila) acontece aqui dentro.
 */
@Service
public class WalletCommandService {

    /** Contador de operacoes por resultado: applied, replayed ou o codigo do erro de negocio. */
    static final String TRANSACTIONS_METRIC = "wallet.transactions";

    /**
     * Teto da transacao (vira query timeout nos comandos JDBC): cobre os 5 s de espera pelo lock no package
     * com folga, e impede que uma requisicao fique presa se o banco travar.
     */
    static final int TRANSACTION_TIMEOUT_SECONDS = 10;

    private final WalletCommandPort commandPort;
    private final WalletQueryPort queryPort;
    private final MeterRegistry meterRegistry;

    public WalletCommandService(WalletCommandPort commandPort, WalletQueryPort queryPort, MeterRegistry meterRegistry) {
        this.commandPort = commandPort;
        this.queryPort = queryPort;
        this.meterRegistry = meterRegistry;
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
        try {
            RegisteredTransaction result = commandPort.registerTransaction(walletId, type, amount, idempotencyKey);
            count(result.replayed() ? "replayed" : "applied", type);
            return result;
        } catch (WalletException e) {
            count(e.code().name(), type);
            throw e;
        }
    }

    private void count(String outcome, String type) {
        meterRegistry.counter(TRANSACTIONS_METRIC, "outcome", outcome, "type", knownType(type)).increment();
    }

    /** O tipo vem do cliente: so vira tag se for um valor conhecido, para nao criar series sem limite. */
    private static String knownType(String type) {
        for (TransactionType known : TransactionType.values()) {
            if (known.name().equals(type)) {
                return type;
            }
        }
        return "other";
    }
}
