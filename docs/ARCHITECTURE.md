# Arquitetura

## Visao geral

```mermaid
flowchart LR
    client[Sistema cliente] -->|HTTP + X-API-Key| api

    subgraph app [Aplicacao Spring Boot - conecta como WALLET_APP]
        api[api: controllers, DTOs, erros, filtros, seguranca]
        appl[application: casos de uso transacionais]
        dom[domain: records, ErrorCode, portas]
        infra[infrastructure.oracle: adaptadores e tradutor de erros]
        api --> appl --> dom
        infra -.implementa.-> dom
    end

    subgraph db [Oracle Free - PDB FREEPDB1]
        subgraph owner [WALLET_OWNER]
            pkg[PKG_WALLET]
            ctx[PKG_WALLET_CONTEXT]
            tables[(WALLETS / WALLET_TRANSACTIONS)]
            views[V_WALLETS / V_WALLET_TRANSACTIONS]
            trg[triggers de imutabilidade e de auditoria]
        end
        subgraph audit [WALLET_AUDIT]
            pkga[PKG_BALANCE_AUDIT]
            audtab[(BALANCE_AUDIT)]
        end
    end

    infra -->|EXECUTE| pkg
    infra -->|READ| views
    pkg --> ctx
    pkg --> tables
    tables --> trg
    trg -->|EXECUTE, ACCESSIBLE BY| pkga --> audtab
```

As regras de consistencia (saldo nunca negativo, idempotencia, serializacao por carteira, extrato imutavel, auditoria)
estao no banco. A aplicacao autentica, valida o formato da requisicao, abre uma transacao curta, chama o package e
traduz o resultado para HTTP.

## Camadas da aplicacao

| Pacote | Responsabilidade | Depende de |
|---|---|---|
| `api` | `WalletController`, DTOs, `GlobalExceptionHandler` e `ErrorMapping` (ProblemDetail e status), `api.web` (`TraceIdFilter`, limite de corpo), `api.security` (API key), `OpenApiConfig` | `application`, `domain` |
| `application` | `WalletQueryService` (leitura) e `WalletCommandService` (escrita, `@Transactional` curto) | `domain` |
| `domain` | `Wallet`, `WalletTransaction`, `TransactionType`, `RegisteredTransaction`, `Period`, `PageRequest`, `PageResult`, `ErrorCode` + `WalletException`, portas `WalletQueryPort` e `WalletCommandPort` | nada (sem Spring nem JDBC) |
| `infrastructure.oracle` | `OracleWalletQueryAdapter` (JdbcClient nas views), `PkgWalletAdapter` (CallableStatement no package), `OracleErrorTranslator` | `domain`, Spring JDBC |

Filtros, na ordem em que rodam:

1. `TraceIdFilter`: gera ou reaproveita o `X-Trace-Id` e o coloca no MDC.
2. `RequestSizeLimitFilter`: recusa corpo acima de 16 KB com 413.
3. Cadeia do Spring Security com `ApiKeyAuthenticationFilter`: 401 se a chave estiver ausente ou errada.

Os dois que recusam requisicoes (413 e 401) escrevem o erro no mesmo formato ProblemDetail do `GlobalExceptionHandler`.

## Fluxo de `POST /wallets/{id}/transactions`

```mermaid
sequenceDiagram
    autonumber
    participant C as Cliente
    participant F as Filtros (traceId, tamanho, API key)
    participant W as WalletController
    participant S as WalletCommandService
    participant A as PkgWalletAdapter
    participant P as PKG_WALLET
    participant T as TRG_WALLETS_BALANCE_AUDIT
    participant AU as WALLET_AUDIT

    C->>F: POST + X-API-Key + Idempotency-Key + {type, amount}
    F->>W: autenticado
    W->>S: registerTransaction(...)
    Note over S: @Transactional (timeout 10 s)
    S->>A: registerTransaction(...)
    A->>P: register_transaction(...)
    P->>P: SAVEPOINT, depois valida tipo, valor (ate 2 casas) e chave
    P->>P: SELECT ... FOR UPDATE WAIT 5 (linha da carteira)
    P->>P: procura (wallet_id, chave) com comparacao binaria
    alt chave existe com mesmo tipo e valor
        P-->>A: lancamento original, replayed = Y
    else chave existe com tipo ou valor diferente
        P-->>A: ORA-20005
    else chave nova
        P->>P: debito sem saldo? ORA-20004
        P->>P: NEXTVAL e publica id e tipo no PKG_WALLET_CONTEXT
        P->>P: INSERT no extrato (guard confere o id)
        P->>P: UPDATE do saldo
        P-->>T: AFTER UPDATE OF balance
        T->>AU: record_change(antes, depois, id, tipo)
        P->>P: limpa o contexto
        P-->>A: id, balance_after, created_at, replayed = N
    end
    A-->>S: RegisteredTransaction ou WalletException (tradutor)
    Note over S: COMMIT libera o lock (ou ROLLBACK em erro)
    S-->>W: resultado
    W-->>C: 201 / 200 + Idempotent-Replayed / ProblemDetail
```

## Modelo de dados

```mermaid
erDiagram
    WALLETS ||--o{ WALLET_TRANSACTIONS : "tem"
    WALLETS {
        NUMBER_19 id PK "SEQ_WALLET_ID"
        VARCHAR2_120 holder_name "opcional"
        NUMBER_19_2 balance "CHECK >= 0"
        TIMESTAMP_6 created_at "UTC"
        TIMESTAMP_6 updated_at "UTC"
    }
    WALLET_TRANSACTIONS {
        NUMBER_19 id PK "SEQ_WALLET_TRANSACTION_ID"
        NUMBER_19 wallet_id FK
        VARCHAR2_6 transaction_type "CREDIT ou DEBIT"
        NUMBER_19_2 amount "CHECK > 0"
        NUMBER_19_2 balance_after "CHECK >= 0"
        VARCHAR2_64 idempotency_key "UNIQUE com wallet_id"
        TIMESTAMP_6 created_at "UTC"
    }
    BALANCE_AUDIT {
        NUMBER_19 id PK "SEQ_BALANCE_AUDIT_ID"
        NUMBER_19 wallet_id "sem FK, de proposito"
        VARCHAR2_6 dml_operation "INSERT, UPDATE ou DELETE"
        NUMBER_19_2 balance_before
        NUMBER_19_2 balance_after
        VARCHAR2_20 change_source "PKG_WALLET ou DIRECT_SQL"
        NUMBER_19 transaction_id "NULL se DIRECT_SQL"
        VARCHAR2_6 transaction_type "NULL se DIRECT_SQL"
        TIMESTAMP_6 changed_at "UTC"
        VARCHAR2_128 db_user "SESSION_USER"
        VARCHAR2_128 os_user "informado pelo cliente"
        VARCHAR2_64 client_module "informado pelo cliente"
    }
```

`BALANCE_AUDIT` fica em outro schema e nao tem FK para `WALLETS`: a trilha nao depende do schema auditado.

Indices: PK em todas as tabelas; `UNIQUE (wallet_id, idempotency_key)`, que tambem atende a FK; `(wallet_id, created_at,
id)` para o extrato; `(wallet_id, changed_at, id)` na auditoria.

## Objetos de banco

| Objeto | Schema | Criado em |
|---|---|---|
| `WALLET_OWNER`, `WALLET_APP`, `WALLET_AUDIT` (usuarios, quotas, privilegios de sistema) | - | `db/init/01-create-users.sh` |
| `BALANCE_AUDIT`, `SEQ_BALANCE_AUDIT_ID` | audit | `db/migration/audit/V1` |
| `PKG_BALANCE_AUDIT`, `TRG_BALANCE_AUDIT_IMMUTABLE`, `TRG_BALANCE_AUDIT_NO_TRUNCATE`, grant ao owner | audit | `db/migration/audit/V2` |
| `WALLETS`, `SEQ_WALLET_ID` | owner | `db/migration/owner/V1` |
| `WALLET_TRANSACTIONS`, `SEQ_WALLET_TRANSACTION_ID`, indice do extrato | owner | `db/migration/owner/V2` |
| `V_WALLETS`, `V_WALLET_TRANSACTIONS` + `READ` ao app | owner | `db/migration/owner/V3` |
| `PKG_WALLET_CONTEXT` | owner | `db/migration/owner/V4` (spec ampliada em `V6`) |
| `PKG_WALLET` + `EXECUTE` ao app | owner | `db/migration/owner/V5` |
| `TRG_WALLET_TX_IMMUTABLE`, `TRG_WALLET_TX_INSERT_GUARD`, `TRG_WALLETS_ID_IMMUTABLE`, `TRG_WALLET_TABLES_NO_TRUNCATE` | owner | `db/migration/owner/V6` |
| `TRG_WALLETS_BALANCE_AUDIT` | owner | `db/migration/owner/V7` |

## Privilegios

```mermaid
flowchart TB
    app[WALLET_APP<br/>CREATE SESSION, sem quota]
    owner[WALLET_OWNER<br/>criacao dos proprios objetos]
    audit[WALLET_AUDIT<br/>criacao dos proprios objetos]

    app -->|EXECUTE| pkg[PKG_WALLET]
    app -->|READ| v[V_WALLETS / V_WALLET_TRANSACTIONS]
    app -. sem acesso .-> audtab[(BALANCE_AUDIT)]
    app -. sem DML .-> tables[(WALLETS / WALLET_TRANSACTIONS)]
    owner -->|EXECUTE, so pelo trigger via ACCESSIBLE BY| pkga[PKG_BALANCE_AUDIT]
    owner -. sem INSERT .-> audtab
```

Nada e concedido a `PUBLIC`, inclusive o `INHERIT PRIVILEGES` que o Oracle concede por padrao, revogado no init.
Detalhes e motivos em [DECISIONS.md](../DECISIONS.md), decisao 10.
