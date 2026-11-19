-- Toda alteracao de saldo gera uma linha em WALLET_AUDIT.BALANCE_AUDIT, venha ela do PKG_WALLET ou de SQL direto.
--
-- A operacao causadora vem do PKG_WALLET_CONTEXT, que o package preenche antes de gravar e limpa logo depois.
-- Contexto vazio = alteracao fora do package (DIRECT_SQL).
--
-- Nao geram linha, porque o saldo nao muda: a criacao de carteira (INSERT com saldo 0), a remocao de carteira com
-- saldo 0 e um UPDATE que regrava o mesmo valor.

CREATE OR REPLACE TRIGGER trg_wallets_balance_audit
  AFTER INSERT OR UPDATE OF balance OR DELETE ON wallets
  FOR EACH ROW
DECLARE
  v_operation  VARCHAR2(6);
  v_before     NUMBER;
  v_after      NUMBER;
BEGIN
  IF INSERTING THEN
    v_operation := 'INSERT';
    v_before := 0;
    v_after := :NEW.balance;
  ELSIF UPDATING THEN
    v_operation := 'UPDATE';
    v_before := :OLD.balance;
    v_after := :NEW.balance;
  ELSE
    v_operation := 'DELETE';
    v_before := :OLD.balance;
    v_after := 0;
  END IF;

  IF v_before <> v_after THEN
    wallet_audit.pkg_balance_audit.record_change(
      p_wallet_id        => NVL(:NEW.id, :OLD.id),
      p_dml_operation    => v_operation,
      p_balance_before   => v_before,
      p_balance_after    => v_after,
      p_transaction_id   => pkg_wallet_context.current_transaction_id,
      p_transaction_type => pkg_wallet_context.current_transaction_type
    );
  END IF;
END;
/
