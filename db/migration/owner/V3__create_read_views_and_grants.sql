-- Views de leitura da aplicacao. WALLET_APP recebe acesso apenas nelas, nunca nas tabelas.
-- WITH READ ONLY: mesmo que alguem conceda DML por engano, a view nao aceita escrita.
-- READ (e nao SELECT): SELECT tambem permitiria SELECT ... FOR UPDATE e LOCK TABLE, e a aplicacao
-- poderia segurar locks que o PKG_WALLET precisa obter.

CREATE VIEW v_wallets AS
  SELECT id, holder_name, balance, created_at, updated_at
    FROM wallets
  WITH READ ONLY;

CREATE VIEW v_wallet_transactions AS
  SELECT id, wallet_id, transaction_type, amount, balance_after, idempotency_key, created_at
    FROM wallet_transactions
  WITH READ ONLY;

GRANT READ ON v_wallets TO wallet_app;
GRANT READ ON v_wallet_transactions TO wallet_app;
