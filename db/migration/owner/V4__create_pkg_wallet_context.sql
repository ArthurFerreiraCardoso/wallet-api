-- Contexto da operacao em andamento na sessao, lido pelo trigger de auditoria de saldo.
-- O PKG_WALLET publica o lancamento antes de alterar o saldo e limpa logo depois (inclusive em erro).
-- Sem grant para WALLET_APP. ACCESSIBLE BY restringe o uso ao PKG_WALLET e ao trigger de auditoria: nem uma sessao
-- manual como WALLET_OWNER consegue marcar um UPDATE direto como se tivesse vindo do package.

CREATE OR REPLACE PACKAGE pkg_wallet_context AUTHID DEFINER
  ACCESSIBLE BY (PACKAGE pkg_wallet, TRIGGER trg_wallets_balance_audit)
AS

  PROCEDURE set_current(p_transaction_id IN NUMBER, p_transaction_type IN VARCHAR2);

  PROCEDURE clear;

  FUNCTION current_transaction_id RETURN NUMBER;

  FUNCTION current_transaction_type RETURN VARCHAR2;

END pkg_wallet_context;
/

CREATE OR REPLACE PACKAGE BODY pkg_wallet_context AS

  g_transaction_id    NUMBER(19);
  g_transaction_type  VARCHAR2(6);

  PROCEDURE set_current(p_transaction_id IN NUMBER, p_transaction_type IN VARCHAR2) IS
  BEGIN
    g_transaction_id := p_transaction_id;
    g_transaction_type := p_transaction_type;
  END set_current;

  PROCEDURE clear IS
  BEGIN
    g_transaction_id := NULL;
    g_transaction_type := NULL;
  END clear;

  FUNCTION current_transaction_id RETURN NUMBER IS
  BEGIN
    RETURN g_transaction_id;
  END current_transaction_id;

  FUNCTION current_transaction_type RETURN VARCHAR2 IS
  BEGIN
    RETURN g_transaction_type;
  END current_transaction_type;

END pkg_wallet_context;
/
