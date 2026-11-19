# wallet-api

Servico de carteira digital (saldo em BRL, creditos e debitos) exposto por HTTP. As regras de consistencia —
saldo nunca negativo, idempotencia, concorrencia, extrato imutavel e auditoria — ficam no Oracle Database
(PL/SQL, triggers e sequences). A aplicacao Spring Boot e a camada de API sobre elas.

> Documentacao em construcao: este README sera completado conforme as partes do projeto forem entrando.

## Pre-requisitos

- Java 21 (o Maven Wrapper ja vem no projeto)
- Docker com **pelo menos 4 GB de memoria** disponivel para o container. Com menos de 2 GB o Oracle nao inicializa.
  - Windows: Docker Desktop com backend WSL2.
  - macOS: Intel ou Apple Silicon (a imagem `gvenzl/oracle-free` e publicada para amd64 e arm64).

## Subindo o banco

```bash
cp .env.example .env        # troque as senhas
docker compose up -d
docker compose ps -a        # oracle "healthy" e migrate "Exited (0)"
```

O `docker compose up -d` faz duas coisas:

1. sobe o Oracle; na primeira inicializacao, `db/init/01-create-users.sh` cria os usuarios no PDB `FREEPDB1`;
2. quando o banco fica saudavel (o healthcheck da imagem so passa depois do init), o servico `migrate`
   (`flyway/flyway:12.4.0`) aplica as migrations de `db/migration/audit` como `WALLET_AUDIT` e depois as de
   `db/migration/owner` como `WALLET_OWNER`, e termina. Rodar de novo e seguro: o Flyway so aplica o que falta.

Usuarios:

| Usuario | Papel |
|---|---|
| `WALLET_OWNER` | dono das tabelas, sequences, package e triggers; usado so pelas migrations |
| `WALLET_APP` | usuario da aplicacao; apenas `CREATE SESSION` + grants de objeto minimos |
| `WALLET_AUDIT` | dono da trilha de auditoria; a aplicacao nao tem acesso |

A porta e publicada apenas em `127.0.0.1`. O script de init so roda com o volume vazio. Para recriar o banco do
zero: `docker compose down -v && docker compose up -d`.

## Modelo de dados

```
WALLET_OWNER
  WALLETS               id, holder_name (opcional), balance NUMBER(19,2) >= 0, created_at, updated_at
  WALLET_TRANSACTIONS   id, wallet_id (FK), transaction_type (CREDIT|DEBIT), amount > 0, balance_after >= 0,
                        idempotency_key, created_at; UNIQUE (wallet_id, idempotency_key)
  SEQ_WALLET_ID, SEQ_WALLET_TRANSACTION_ID
  V_WALLETS, V_WALLET_TRANSACTIONS   views WITH READ ONLY; o WALLET_APP tem apenas READ nelas

WALLET_AUDIT
  BALANCE_AUDIT         wallet_id, dml_operation, balance_before, balance_after, change_source (PKG_WALLET|DIRECT_SQL),
                        transaction_id, transaction_type, changed_at, db_user
  SEQ_BALANCE_AUDIT_ID
```

Alguns detalhes de privilegio que valem citar:

- O `WALLET_APP` recebe `READ` (e nao `SELECT`) nas views: `SELECT` tambem permitiria `SELECT ... FOR UPDATE` e
  `LOCK TABLE`, e a aplicacao poderia segurar locks que o package precisa obter.
- O init revoga o `INHERIT PRIVILEGES` que o Oracle concede a `PUBLIC` em todo usuario novo.
- A chave de idempotencia aceita apenas `[A-Za-z0-9_-]{1,64}`, validada tambem por constraint.
- `NUMBER(19,2)` arredonda valores com mais de duas casas antes de qualquer `CHECK`; por isso a rejeicao de escala
  maior que 2 fica no package, antes de gravar.

Datas sao gravadas em UTC (`TIMESTAMP(6)` com `SYS_EXTRACT_UTC(SYSTIMESTAMP)`). As constraints (`CHECK`, `FK`,
`UNIQUE`) sao uma segunda barreira: mesmo SQL executado direto no banco nao deixa saldo negativo, valor zero ou
chave de idempotencia repetida na mesma carteira.

## Variaveis de ambiente

| Variavel | Uso |
|---|---|
| `ORACLE_PASSWORD` | senha do SYS/SYSTEM do container |
| `WALLET_OWNER_PASSWORD` | senha do `WALLET_OWNER` |
| `WALLET_APP_PASSWORD` | senha do `WALLET_APP` (tambem usada pela aplicacao) |
| `WALLET_AUDIT_PASSWORD` | senha do `WALLET_AUDIT` |
| `ORACLE_PORT` | porta local do Oracle (padrao 1521) |
| `WALLET_DB_URL` | URL JDBC da aplicacao (padrao `jdbc:oracle:thin:@//localhost:1521/FREEPDB1`) |

Senhas: 8 a 30 caracteres, comecando com letra, apenas letras, numeros e `_`. Os valores do `.env.example`
tem hifen de proposito: se nao forem trocados, o script de init recusa criar os usuarios.

A aplicacao nao le o `.env` inteiro: ela recebe apenas `WALLET_APP_PASSWORD` e `WALLET_DB_URL` por variavel de
ambiente, para que as senhas do owner, do audit e do SYS nunca cheguem ao processo dela.

## Testes

```bash
./mvnw verify
```

Os testes de integracao sobem um Oracle Free proprio via Testcontainers, com a mesma imagem, o mesmo `db/init` e
as mesmas migrations do compose (senhas geradas na hora). Precisam apenas do Docker rodando; nao dependem do
`docker compose`. Entre outras coisas, eles provam que o `WALLET_APP` so le as views e nao consegue escrever em
nenhuma tabela nem enxergar o schema de auditoria.
