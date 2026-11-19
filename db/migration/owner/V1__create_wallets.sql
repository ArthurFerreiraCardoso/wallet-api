-- Carteiras. Executada como WALLET_OWNER.
-- O id vem de SEQ_WALLET_ID, chamado dentro do PKG_WALLET (sem DEFAULT na coluna).

CREATE SEQUENCE seq_wallet_id START WITH 1 INCREMENT BY 1 NOCYCLE CACHE 20;

CREATE TABLE wallets (
  id           NUMBER(19)         CONSTRAINT pk_wallets PRIMARY KEY,
  holder_name  VARCHAR2(120 CHAR),
  balance      NUMBER(19,2)       DEFAULT 0 NOT NULL,
  created_at   TIMESTAMP(6)       DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,
  updated_at   TIMESTAMP(6)       DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,
  CONSTRAINT ck_wallets_balance_non_negative CHECK (balance >= 0),
  CONSTRAINT ck_wallets_holder_name_not_blank CHECK (holder_name IS NULL OR TRIM(holder_name) IS NOT NULL)
);

COMMENT ON TABLE wallets IS 'Carteiras digitais em BRL';
COMMENT ON COLUMN wallets.balance IS 'Saldo atual em BRL; alterado somente pelo PKG_WALLET e auditado em WALLET_AUDIT';
COMMENT ON COLUMN wallets.created_at IS 'UTC';
COMMENT ON COLUMN wallets.updated_at IS 'UTC';
