-- Toda escrita da aplicacao passa por este package. Regras resolvidas aqui:
--   validacao de tipo, valor e chave; lock da carteira; idempotencia; rejeicao de debito sem saldo;
--   gravacao do lancamento e atualizacao do saldo na mesma transacao.
--
-- O package NAO faz COMMIT: quem chama controla a transacao (na aplicacao, um @Transactional curto).
-- O lock da carteira vale ate o COMMIT/ROLLBACK do chamador. Se a chamada falhar, register_transaction
-- volta ao savepoint do inicio antes de propagar o erro, entao nunca fica lancamento sem saldo atualizado,
-- mesmo que o chamador capture a excecao e faca COMMIT.
--
-- Premissas do chamador: READ COMMITTED (sob SERIALIZABLE pode surgir ORA-08177), uma carteira por transacao
-- e encerrar a transacao logo apos um erro (quem espera o lock so e liberado no COMMIT/ROLLBACK).
--
-- Erros de negocio saem com RAISE_APPLICATION_ERROR na faixa -20001..-20010 (catalogo em docs/ERROR-CODES.md).

CREATE OR REPLACE PACKAGE pkg_wallet AUTHID DEFINER AS

  c_err_wallet_not_found         CONSTANT PLS_INTEGER := -20001;
  c_err_invalid_amount           CONSTANT PLS_INTEGER := -20002;
  c_err_invalid_type             CONSTANT PLS_INTEGER := -20003;
  c_err_insufficient_funds       CONSTANT PLS_INTEGER := -20004;
  c_err_idempotency_conflict     CONSTANT PLS_INTEGER := -20005;
  c_err_ledger_immutable         CONSTANT PLS_INTEGER := -20006;
  c_err_wallet_locked            CONSTANT PLS_INTEGER := -20007;
  c_err_invalid_idempotency_key  CONSTANT PLS_INTEGER := -20008;
  c_err_invalid_holder           CONSTANT PLS_INTEGER := -20009;
  c_err_balance_limit_exceeded   CONSTANT PLS_INTEGER := -20010;

  -- Maior valor que cabe em NUMBER(19,2).
  c_max_amount                   CONSTANT NUMBER := 99999999999999999.99;

  PROCEDURE create_wallet(
    p_holder_name  IN  VARCHAR2,
    o_wallet_id    OUT NUMBER,
    o_balance      OUT NUMBER,
    o_created_at   OUT TIMESTAMP
  );

  -- Replay (mesma carteira, mesma chave, mesmo tipo e valor): devolve o lancamento original com o_replayed = 'Y'
  -- e nao altera o saldo. Mesma chave com tipo ou valor diferente: -20005.
  PROCEDURE register_transaction(
    p_wallet_id        IN  NUMBER,
    p_type             IN  VARCHAR2,
    p_amount           IN  NUMBER,
    p_idempotency_key  IN  VARCHAR2,
    o_transaction_id   OUT NUMBER,
    o_balance_after    OUT NUMBER,
    o_created_at       OUT TIMESTAMP,
    o_replayed         OUT VARCHAR2
  );

END pkg_wallet;
/

CREATE OR REPLACE PACKAGE BODY pkg_wallet AS

  -- Timeout do FOR UPDATE WAIT: ORA-30006 nas versoes antigas, ORA-00054 no 23ai.
  e_lock_timeout EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_lock_timeout, -30006);
  e_resource_busy EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_resource_busy, -54);

  PROCEDURE validate_type(p_type IN VARCHAR2) IS
  BEGIN
    IF p_type IS NULL OR p_type NOT IN ('CREDIT', 'DEBIT') THEN
      RAISE_APPLICATION_ERROR(c_err_invalid_type, 'Tipo de operacao invalido; use CREDIT ou DEBIT');
    END IF;
  END validate_type;

  -- NUMBER(19,2) arredondaria 10.005 para 10.01 em silencio; por isso a escala e conferida antes de qualquer gravacao
  -- e antes da comparacao de replay.
  PROCEDURE validate_amount(p_amount IN NUMBER) IS
  BEGIN
    IF p_amount IS NULL OR p_amount <= 0 OR p_amount <> ROUND(p_amount, 2) OR p_amount > c_max_amount THEN
      RAISE_APPLICATION_ERROR(c_err_invalid_amount, 'Valor deve ser maior que zero, com no maximo 2 casas decimais');
    END IF;
  END validate_amount;

  PROCEDURE validate_idempotency_key(p_key IN VARCHAR2) IS
  BEGIN
    IF p_key IS NULL OR NOT REGEXP_LIKE(ASCIISTR(p_key), '^[A-Za-z0-9_-]{1,64}$') THEN
      RAISE_APPLICATION_ERROR(c_err_invalid_idempotency_key,
        'Chave de idempotencia deve ter de 1 a 64 caracteres entre letras, numeros, _ e -');
    END IF;
  END validate_idempotency_key;

  PROCEDURE create_wallet(
    p_holder_name  IN  VARCHAR2,
    o_wallet_id    OUT NUMBER,
    o_balance      OUT NUMBER,
    o_created_at   OUT TIMESTAMP
  ) IS
    v_holder_name  wallets.holder_name%TYPE;
  BEGIN
    IF LENGTH(TRIM(p_holder_name)) > 120 THEN
      RAISE_APPLICATION_ERROR(c_err_invalid_holder, 'Nome do titular deve ter no maximo 120 caracteres');
    END IF;
    -- Nome so com espacos e tratado como ausente.
    v_holder_name := TRIM(p_holder_name);

    o_wallet_id := seq_wallet_id.NEXTVAL;
    o_created_at := SYS_EXTRACT_UTC(SYSTIMESTAMP);
    o_balance := 0;

    INSERT INTO wallets (id, holder_name, balance, created_at, updated_at)
    VALUES (o_wallet_id, v_holder_name, o_balance, o_created_at, o_created_at);
  END create_wallet;

  PROCEDURE register_transaction(
    p_wallet_id        IN  NUMBER,
    p_type             IN  VARCHAR2,
    p_amount           IN  NUMBER,
    p_idempotency_key  IN  VARCHAR2,
    o_transaction_id   OUT NUMBER,
    o_balance_after    OUT NUMBER,
    o_created_at       OUT TIMESTAMP,
    o_replayed         OUT VARCHAR2
  ) IS
    v_balance      wallets.balance%TYPE;
    v_new_balance  NUMBER;
    v_existing     wallet_transactions%ROWTYPE;
    v_found        BOOLEAN := FALSE;
  BEGIN
    SAVEPOINT sp_register_transaction;

    validate_type(p_type);
    validate_amount(p_amount);
    validate_idempotency_key(p_idempotency_key);

    -- Serializa as operacoes da mesma carteira. Carteiras diferentes nao disputam lock entre si.
    -- Espera ate 5 segundos pelo lock e depois desiste com -20007, que o cliente pode tentar de novo.
    BEGIN
      SELECT balance
        INTO v_balance
        FROM wallets
       WHERE id = p_wallet_id
         FOR UPDATE WAIT 5;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        RAISE_APPLICATION_ERROR(c_err_wallet_not_found, 'Carteira nao encontrada');
      WHEN e_lock_timeout OR e_resource_busy THEN
        RAISE_APPLICATION_ERROR(c_err_wallet_locked,
          'Carteira ocupada por outra operacao; tente novamente');
    END;

    -- Com o lock na mao, nenhuma outra sessao consegue gravar a mesma chave nesta carteira ao mesmo tempo.
    -- A UNIQUE (wallet_id, idempotency_key) continua como rede de seguranca.
    -- Comparacao binaria explicita: o SQL do package usa o NLS da sessao, e com NLS_COMP=LINGUISTIC
    -- 'abc' casaria com 'ABC', ao contrario da UNIQUE e do CHECK.
    BEGIN
      SELECT *
        INTO v_existing
        FROM wallet_transactions
       WHERE wallet_id = p_wallet_id
         AND NLSSORT(idempotency_key, 'NLS_SORT=BINARY') = NLSSORT(p_idempotency_key, 'NLS_SORT=BINARY');
      v_found := TRUE;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        v_found := FALSE;
    END;

    IF v_found THEN
      IF v_existing.transaction_type = p_type AND v_existing.amount = p_amount THEN
        o_transaction_id := v_existing.id;
        o_balance_after := v_existing.balance_after;
        o_created_at := v_existing.created_at;
        o_replayed := 'Y';
        RETURN;
      END IF;
      RAISE_APPLICATION_ERROR(c_err_idempotency_conflict,
        'Chave de idempotencia ja usada nesta carteira para uma operacao diferente');
    END IF;

    v_new_balance := CASE p_type WHEN 'CREDIT' THEN v_balance + p_amount ELSE v_balance - p_amount END;

    IF v_new_balance < 0 THEN
      RAISE_APPLICATION_ERROR(c_err_insufficient_funds, 'Saldo insuficiente para o debito');
    END IF;
    IF v_new_balance > c_max_amount THEN
      RAISE_APPLICATION_ERROR(c_err_balance_limit_exceeded, 'Credito excede o saldo maximo suportado');
    END IF;

    -- NEXTVAL dentro do lock: os ids de uma mesma carteira crescem na ordem em que as operacoes foram aplicadas.
    o_transaction_id := seq_wallet_transaction_id.NEXTVAL;
    o_created_at := SYS_EXTRACT_UTC(SYSTIMESTAMP);

    -- Publicado antes das gravacoes: os triggers do extrato e do saldo sabem que a alteracao veio daqui.
    pkg_wallet_context.set_current(o_transaction_id, p_type);

    INSERT INTO wallet_transactions (id, wallet_id, transaction_type, amount, balance_after, idempotency_key, created_at)
    VALUES (o_transaction_id, p_wallet_id, p_type, p_amount, v_new_balance, p_idempotency_key, o_created_at);

    UPDATE wallets
       SET balance = v_new_balance,
           updated_at = o_created_at
     WHERE id = p_wallet_id;

    pkg_wallet_context.clear;

    o_balance_after := v_new_balance;
    o_replayed := 'N';
  EXCEPTION
    WHEN OTHERS THEN
      -- Desfaz o que esta chamada gravou (e libera o lock obtido por ela), mesmo se o chamador capturar o erro.
      ROLLBACK TO sp_register_transaction;
      -- Sem isso, um UPDATE direto posterior na mesma sessao seria atribuido a este lancamento na auditoria.
      pkg_wallet_context.clear;
      RAISE;
  END register_transaction;

END pkg_wallet;
/

GRANT EXECUTE ON pkg_wallet TO wallet_app;
