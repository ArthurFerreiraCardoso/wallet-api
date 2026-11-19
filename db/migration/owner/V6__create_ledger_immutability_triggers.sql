-- Extrato imutavel: lancamento aplicado nao pode ser alterado nem apagado, e lancamento novo so entra pelo
-- PKG_WALLET. Vale tambem para SQL direto do owner.
--
-- Limites conhecidos: o dono do schema pode usar DDL sobre as proprias tabelas e triggers (ALTER TRIGGER ... DISABLE,
-- DROP/CREATE OR REPLACE TRIGGER, FLASHBACK TABLE, que desliga triggers por padrao, alteracao de constraints).
-- Triggers ON SCHEMA so pegam DDL emitido pelo proprio owner; um DBA com privilegios ANY passa direto.
-- A protecao contra isso e de papel: o owner so e usado pelas migrations versionadas, e a aplicacao conecta como
-- WALLET_APP, que nao tem DML nem DDL.
--
-- -20006 e o c_err_ledger_immutable do PKG_WALLET, repetido como literal para os triggers nao dependerem do package.

-- O guard de INSERT le o contexto; o package de contexto precisa autorizar o novo trigger.
CREATE OR REPLACE PACKAGE pkg_wallet_context AUTHID DEFINER
  ACCESSIBLE BY (PACKAGE pkg_wallet, TRIGGER trg_wallets_balance_audit, TRIGGER trg_wallet_tx_insert_guard)
AS

  PROCEDURE set_current(p_transaction_id IN NUMBER, p_transaction_type IN VARCHAR2);

  PROCEDURE clear;

  FUNCTION current_transaction_id RETURN NUMBER;

  FUNCTION current_transaction_type RETURN VARCHAR2;

END pkg_wallet_context;
/

-- Recriar a spec invalida o corpo dela e o do PKG_WALLET; recompilados aqui para o schema terminar a migration valido.
ALTER PACKAGE pkg_wallet_context COMPILE BODY;
ALTER PACKAGE pkg_wallet COMPILE BODY;

-- So aceita o lancamento que o PKG_WALLET acabou de publicar no contexto: INSERT direto, ou com outro id, e recusado.
CREATE OR REPLACE TRIGGER trg_wallet_tx_insert_guard
  BEFORE INSERT ON wallet_transactions
  FOR EACH ROW
BEGIN
  IF pkg_wallet_context.current_transaction_id IS NULL
     OR pkg_wallet_context.current_transaction_id <> :NEW.id THEN
    RAISE_APPLICATION_ERROR(-20006, 'Lancamentos so podem ser gravados pelo PKG_WALLET');
  END IF;
END;
/

CREATE OR REPLACE TRIGGER trg_wallet_tx_immutable
  BEFORE UPDATE OR DELETE ON wallet_transactions
  FOR EACH ROW
BEGIN
  RAISE_APPLICATION_ERROR(-20006, 'Extrato e imutavel: lancamentos nao podem ser alterados nem apagados');
END;
/

-- O id da carteira e a referencia do extrato e da auditoria; trocar o id moveria o saldo sem deixar trilha.
CREATE OR REPLACE TRIGGER trg_wallets_id_immutable
  BEFORE UPDATE OF id ON wallets
  FOR EACH ROW
BEGIN
  IF :NEW.id <> :OLD.id THEN
    RAISE_APPLICATION_ERROR(-20006, 'O id da carteira e imutavel');
  END IF;
END;
/

-- TRUNCATE e DDL e nao dispara trigger de linha; este trigger de schema cobre o caso para as duas tabelas.
CREATE OR REPLACE TRIGGER trg_wallet_tables_no_truncate
  BEFORE TRUNCATE ON SCHEMA
BEGIN
  IF ora_dict_obj_owner = 'WALLET_OWNER' AND ora_dict_obj_name IN ('WALLET_TRANSACTIONS', 'WALLETS') THEN
    RAISE_APPLICATION_ERROR(-20006, 'Extrato e saldos nao podem ser truncados');
  END IF;
END;
/
