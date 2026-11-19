-- Trilha de auditoria de saldo. Executada como WALLET_AUDIT, antes das migrations do owner.
-- A aplicacao (WALLET_APP) nao recebe nenhum grant neste schema.
-- A trilha e autocontida: guarda tipo e id da operacao causadora sem depender de consultar o owner.

CREATE SEQUENCE seq_balance_audit_id START WITH 1 INCREMENT BY 1 NOCYCLE;

CREATE TABLE balance_audit (
  id                NUMBER(19)        CONSTRAINT pk_balance_audit PRIMARY KEY,
  wallet_id         NUMBER(19)        NOT NULL,
  dml_operation     VARCHAR2(6 CHAR)  NOT NULL,
  balance_before    NUMBER(19,2)      NOT NULL,
  balance_after     NUMBER(19,2)      NOT NULL,
  change_source     VARCHAR2(20 CHAR) NOT NULL,
  transaction_id    NUMBER(19),
  transaction_type  VARCHAR2(6 CHAR),
  changed_at        TIMESTAMP(6)      DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,
  db_user           VARCHAR2(128)     NOT NULL,
  os_user           VARCHAR2(128),
  client_module     VARCHAR2(64),
  CONSTRAINT ck_balance_audit_dml CHECK (dml_operation IN ('INSERT', 'UPDATE', 'DELETE')),
  CONSTRAINT ck_balance_audit_source CHECK (
    (change_source = 'PKG_WALLET' AND transaction_id IS NOT NULL
      AND transaction_type IS NOT NULL AND transaction_type IN ('CREDIT', 'DEBIT'))
    OR (change_source = 'DIRECT_SQL' AND transaction_id IS NULL AND transaction_type IS NULL)
  )
);

-- Sem FK para WALLET_OWNER.WALLETS: a trilha nao depende do schema auditado.
CREATE INDEX ix_balance_audit_wallet ON balance_audit (wallet_id, changed_at, id);

COMMENT ON TABLE balance_audit IS 'Toda alteracao de saldo em WALLET_OWNER.WALLETS, gravada por trigger';
COMMENT ON COLUMN balance_audit.dml_operation IS 'INSERT (balance_before = 0), UPDATE ou DELETE (balance_after = 0)';
COMMENT ON COLUMN balance_audit.change_source IS 'PKG_WALLET ou DIRECT_SQL (alteracao feita fora do package)';
COMMENT ON COLUMN balance_audit.transaction_id IS 'Lancamento que causou a alteracao; NULL quando DIRECT_SQL';
COMMENT ON COLUMN balance_audit.transaction_type IS 'CREDIT ou DEBIT do lancamento causador; NULL quando DIRECT_SQL';
COMMENT ON COLUMN balance_audit.changed_at IS 'Momento da alteracao em UTC';
COMMENT ON COLUMN balance_audit.db_user IS 'SESSION_USER da sessao que alterou o saldo';
