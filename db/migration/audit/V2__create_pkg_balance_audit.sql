-- Gravacao da trilha de auditoria. Executada como WALLET_AUDIT.
--
-- O WALLET_OWNER recebe EXECUTE, mas ACCESSIBLE BY so deixa o trigger de saldo do owner chamar o package:
-- uma chamada ad-hoc numa sessao do owner e recusada, e o owner nao tem INSERT na tabela.
-- Limite: ACCESSIBLE BY confia no nome do objeto. Quem controla o schema owner pode redefinir ou desabilitar
-- o trigger. A mitigacao e de papel (o owner so roda migrations versionadas), documentada no README.
-- Sem COMMIT e sem transacao autonoma: a linha de auditoria entra e sai junto com a alteracao de saldo.

CREATE OR REPLACE PACKAGE pkg_balance_audit AUTHID DEFINER
  ACCESSIBLE BY (TRIGGER wallet_owner.trg_wallets_balance_audit)
AS

  -- p_transaction_id preenchido = alteracao feita pelo PKG_WALLET; nulo = SQL direto.
  PROCEDURE record_change(
    p_wallet_id         IN NUMBER,
    p_dml_operation     IN VARCHAR2,
    p_balance_before    IN NUMBER,
    p_balance_after     IN NUMBER,
    p_transaction_id    IN NUMBER,
    p_transaction_type  IN VARCHAR2
  );

END pkg_balance_audit;
/

CREATE OR REPLACE PACKAGE BODY pkg_balance_audit AS

  PROCEDURE record_change(
    p_wallet_id         IN NUMBER,
    p_dml_operation     IN VARCHAR2,
    p_balance_before    IN NUMBER,
    p_balance_after     IN NUMBER,
    p_transaction_id    IN NUMBER,
    p_transaction_type  IN VARCHAR2
  ) IS
  BEGIN
    INSERT INTO balance_audit (
      id, wallet_id, dml_operation, balance_before, balance_after,
      change_source, transaction_id, transaction_type,
      changed_at, db_user, os_user, client_module
    ) VALUES (
      seq_balance_audit_id.NEXTVAL, p_wallet_id, p_dml_operation, p_balance_before, p_balance_after,
      CASE WHEN p_transaction_id IS NOT NULL THEN 'PKG_WALLET' ELSE 'DIRECT_SQL' END,
      p_transaction_id, p_transaction_type,
      SYS_EXTRACT_UTC(SYSTIMESTAMP),
      SYS_CONTEXT('USERENV', 'SESSION_USER'),
      -- SUBSTRB: as colunas medem bytes; um valor com acentos cortado por caractere poderia estourar o tamanho
      -- e, como a auditoria esta na mesma transacao, derrubar a alteracao de saldo.
      SUBSTRB(SYS_CONTEXT('USERENV', 'OS_USER'), 1, 128),
      SUBSTRB(SYS_CONTEXT('USERENV', 'MODULE'), 1, 64)
    );
  END record_change;

END pkg_balance_audit;
/

GRANT EXECUTE ON pkg_balance_audit TO wallet_owner;

COMMENT ON COLUMN balance_audit.os_user IS 'Informado pelo cliente; informativo, nao serve como prova';
COMMENT ON COLUMN balance_audit.client_module IS 'Informado pelo cliente; informativo, nao serve como prova';

-- A trilha tambem e imutavel.
CREATE OR REPLACE TRIGGER trg_balance_audit_immutable
  BEFORE UPDATE OR DELETE ON balance_audit
  FOR EACH ROW
BEGIN
  RAISE_APPLICATION_ERROR(-20006, 'Trilha de auditoria e imutavel: linhas nao podem ser alteradas nem apagadas');
END;
/

-- TRUNCATE nao dispara trigger de linha; este trigger de schema cobre o caso.
CREATE OR REPLACE TRIGGER trg_balance_audit_no_truncate
  BEFORE TRUNCATE ON SCHEMA
BEGIN
  IF ora_dict_obj_owner = 'WALLET_AUDIT' AND ora_dict_obj_name = 'BALANCE_AUDIT' THEN
    RAISE_APPLICATION_ERROR(-20006, 'Trilha de auditoria e imutavel: TRUNCATE nao permitido');
  END IF;
END;
/
