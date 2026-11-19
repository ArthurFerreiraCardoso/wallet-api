-- Extrato (lancamentos aplicados). Executada como WALLET_OWNER.

CREATE SEQUENCE seq_wallet_transaction_id START WITH 1 INCREMENT BY 1 NOCYCLE CACHE 20;

CREATE TABLE wallet_transactions (
  id                NUMBER(19)        CONSTRAINT pk_wallet_transactions PRIMARY KEY,
  wallet_id         NUMBER(19)        NOT NULL CONSTRAINT fk_wtx_wallet REFERENCES wallets (id),
  transaction_type  VARCHAR2(6 CHAR)  NOT NULL,
  amount            NUMBER(19,2)      NOT NULL,
  balance_after     NUMBER(19,2)      NOT NULL,
  idempotency_key   VARCHAR2(64 CHAR) NOT NULL,
  created_at        TIMESTAMP(6)      DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,
  CONSTRAINT ck_wtx_type CHECK (transaction_type IN ('CREDIT', 'DEBIT')),
  CONSTRAINT ck_wtx_amount_positive CHECK (amount > 0),
  CONSTRAINT ck_wtx_balance_after_non_negative CHECK (balance_after >= 0),
  -- Mesmo formato validado pelo package e pela API; comparacao binaria (diferencia maiusculas).
  -- ASCIISTR troca caracteres fora do ASCII por \XXXX: sem ele, o intervalo A-Z do REGEXP aceitaria letras acentuadas.
  CONSTRAINT ck_wtx_idempotency_key_format CHECK (REGEXP_LIKE(ASCIISTR(idempotency_key), '^[A-Za-z0-9_-]{1,64}$')),
  -- Idempotencia por carteira. Tambem serve de indice para a FK.
  CONSTRAINT uk_wtx_wallet_idempotency_key UNIQUE (wallet_id, idempotency_key)
);

-- Extrato por carteira e periodo, ordenado por (created_at, id).
CREATE INDEX ix_wtx_wallet_created ON wallet_transactions (wallet_id, created_at, id);

COMMENT ON TABLE wallet_transactions IS 'Extrato imutavel: cada linha e uma operacao aplicada';
COMMENT ON COLUMN wallet_transactions.amount IS 'NUMBER(19,2) arredonda valores com mais casas; o PKG_WALLET rejeita escala > 2 antes de gravar';
COMMENT ON COLUMN wallet_transactions.balance_after IS 'Saldo da carteira logo apos esta operacao';
COMMENT ON COLUMN wallet_transactions.idempotency_key IS 'Chave enviada pelo cliente; unica por carteira';
COMMENT ON COLUMN wallet_transactions.created_at IS 'UTC';
