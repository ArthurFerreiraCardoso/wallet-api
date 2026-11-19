package com.example.wallet_api.infrastructure.oracle;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.wallet_api.domain.ErrorCode;
import com.example.wallet_api.domain.WalletException;

/**
 * Traduz os erros de negocio do PKG_WALLET (RAISE_APPLICATION_ERROR -20001..-20010) para WalletException.
 *
 * <p>Usa so o codigo numerico ({@link SQLException#getErrorCode()}), nunca a mensagem do Oracle: a mensagem
 * devolvida ao cliente e a do catalogo ({@link ErrorCode#defaultMessage()}). Qualquer outro erro nao e traduzido
 * e segue como falha interna.
 */
@Component
public class OracleErrorTranslator {

    private static final Map<Integer, ErrorCode> CATALOG = Map.of(
            20001, ErrorCode.WALLET_NOT_FOUND,
            20002, ErrorCode.INVALID_AMOUNT,
            20003, ErrorCode.INVALID_TRANSACTION_TYPE,
            20004, ErrorCode.INSUFFICIENT_FUNDS,
            20005, ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
            20006, ErrorCode.LEDGER_IMMUTABLE,
            20007, ErrorCode.WALLET_LOCKED,
            20008, ErrorCode.INVALID_IDEMPOTENCY_KEY,
            20009, ErrorCode.INVALID_HOLDER,
            20010, ErrorCode.BALANCE_LIMIT_EXCEEDED);

    /** Protege contra cadeia de causas ciclica. */
    private static final int MAX_CAUSE_DEPTH = 20;

    public Optional<WalletException> translate(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            if (current instanceof SQLException sql) {
                ErrorCode code = switch (sql.getErrorCode()) {
                    // Timeout de lock que escapasse do package (ORA-00054 no 23ai, ORA-30006 antes) tambem e contencao.
                    case 54, 30006 -> ErrorCode.WALLET_LOCKED;
                    default -> CATALOG.get(sql.getErrorCode());
                };
                if (code != null) {
                    return Optional.of(new WalletException(code, code.defaultMessage(), error));
                }
            }
        }
        return Optional.empty();
    }
}
