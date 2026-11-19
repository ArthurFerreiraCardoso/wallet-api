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

## Registro de operacoes: `PKG_WALLET`

Toda escrita passa pelo package `WALLET_OWNER.PKG_WALLET`. O `WALLET_APP` tem `EXECUTE` nele e nada mais de escrita.

| Procedure | O que faz |
|---|---|
| `create_wallet(p_holder_name, o_wallet_id, o_balance, o_created_at)` | cria a carteira com saldo `0.00` e id de `SEQ_WALLET_ID` |
| `register_transaction(p_wallet_id, p_type, p_amount, p_idempotency_key, o_transaction_id, o_balance_after, o_created_at, o_replayed)` | aplica um credito ou debito |

Passo a passo do `register_transaction`:

1. valida tipo, valor (maior que zero, no maximo 2 casas) e formato da chave, antes de tocar em qualquer tabela;
2. trava a linha da carteira com `SELECT ... FOR UPDATE WAIT 5`;
3. com o lock na mao, procura a chave na carteira:
   - se ja existe com o mesmo tipo e valor, devolve o lancamento original (`o_replayed = 'Y'`) sem alterar o saldo;
   - se existe com tipo ou valor diferente, recusa com `-20005`;
4. recusa o debito que deixaria o saldo negativo (`-20004`);
5. grava o lancamento (id de `SEQ_WALLET_TRANSACTION_ID`, com `balance_after`) e atualiza o saldo.

O package nao faz `COMMIT`: quem chama controla a transacao, e o lock da carteira dura ate o commit ou rollback.
Se qualquer passo falhar, o package volta ao savepoint do inicio da chamada antes de devolver o erro, entao nunca
sobra lancamento sem o saldo correspondente, mesmo que o chamador capture a excecao e faca commit. Os codigos de
erro estao em [docs/ERROR-CODES.md](docs/ERROR-CODES.md).

A chave de idempotencia diferencia maiusculas de minusculas e e comparada em binario, independente do NLS da
sessao (`NLS_COMP`/`NLS_SORT`), igual a constraint `UNIQUE`.

O package pressupoe que o chamador use `READ COMMITTED` (padrao do Oracle), mexa em uma carteira por transacao e
encerre a transacao logo apos um erro. A aplicacao segue isso com um `@Transactional` curto por operacao.

### Por que isso garante as invariantes sob concorrencia

- **Saldo nunca negativo:** a decisao de aceitar um debito e tomada sobre o saldo lido com a linha travada; nenhuma
  outra sessao altera aquela carteira entre a leitura e a gravacao. O `CHECK (balance >= 0)` e a segunda barreira.
- **Nada aplicado em dobro:** a busca pela chave acontece depois do lock, entao duas chamadas com a mesma chave na
  mesma carteira sao serializadas e a segunda ve o lancamento da primeira. A `UNIQUE (wallet_id, idempotency_key)`
  e a segunda barreira.
- **Saldo final consistente:** cada operacao parte do saldo deixado pela anterior. Carteiras diferentes nao
  disputam o mesmo lock.
- **Ids em ordem:** o `NEXTVAL` do lancamento e obtido dentro do lock, entao, na mesma carteira, os ids crescem na
  ordem em que as operacoes foram aplicadas. Sequences podem ter lacunas (cache, rollback), entao os ids nao sao
  contagem nem indicam horario; o extrato ordena por `(created_at, id)`.

## Testes

```bash
./mvnw verify
```

Os testes de integracao sobem um Oracle Free proprio via Testcontainers, com a mesma imagem, o mesmo `db/init` e
as mesmas migrations do compose (senhas geradas na hora). Precisam apenas do Docker rodando; nao dependem do
`docker compose`. Entre outras coisas, eles provam que:

- o `WALLET_APP` so le as views e nao consegue escrever em nenhuma tabela nem enxergar o schema de auditoria;
- cada regra do `PKG_WALLET` funciona, incluindo replay, conflito de chave, saldo insuficiente com saldo intacto,
  espera pelo lock e timeout (`PkgWalletIT`);
- com 16 sessoes disparando 70 operacoes na mesma carteira ao mesmo tempo (incluindo 10 retries da mesma chave e
  debitos alem do saldo), o saldo final bate com a soma do que foi aplicado, a chave repetida vale uma vez so e,
  refazendo o extrato lancamento a lancamento, nenhum `balance_after` diverge nem fica negativo
  (`PkgWalletConcurrencyIT`, repetido 5 vezes por execucao).
