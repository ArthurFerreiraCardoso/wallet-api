# wallet-api

Servico de carteira digital (saldo em BRL, creditos e debitos) exposto por HTTP. As regras de consistencia
(saldo nunca negativo, idempotencia, concorrencia, extrato imutavel e auditoria) ficam no Oracle Database, em
PL/SQL, triggers e sequences. A aplicacao Spring Boot e a camada de API sobre elas.

## Inicio rapido

```bash
cp .env.example .env          # troque as senhas dos usuarios WALLET_* e a WALLET_API_KEY (placeholders sao recusados)
docker compose up -d --build  # sobe Oracle, migrations e a API em http://localhost:8080
docker compose ps -a          # oracle "healthy", migrate "Exited (0)", app "healthy"
```

Testes (nao dependem do compose: sobem o proprio Oracle via Testcontainers):

```bash
./mvnw verify
```

Com o compose e os testes no ar ao mesmo tempo, a maquina precisa de memoria para dois bancos; se faltar, rode
`docker compose down` antes do `./mvnw verify`. Para chamar a API, veja [Rodando a aplicacao](#rodando-a-aplicacao).

## Mapa da documentacao

| Documento | Conteudo |
|---|---|
| este README | como subir, rodar e testar; modelo de dados; objetos de banco; API |
| [DECISIONS.md](DECISIONS.md) | cada decisao tecnica: o que escolhi, por que, o que descartei, o que faria com mais tempo, limitacoes |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | camadas, fluxo de uma operacao, modelo de dados e privilegios, com diagramas |
| [docs/CONCURRENCY.md](docs/CONCURRENCY.md) | linhas do tempo de operacoes concorrentes: lock, idempotencia, replay, timeout |
| [docs/ERROR-CODES.md](docs/ERROR-CODES.md) | catalogo de erros: codigo Oracle, chave da API, status HTTP |
| Swagger UI (`/swagger-ui.html`) | contrato interativo da API |

## Estrategia de concorrencia e idempotencia, em resumo

- **Lock por carteira, no banco:** o `PKG_WALLET` trava a linha da carteira com `SELECT ... FOR UPDATE WAIT 5` antes de
  qualquer decisao. Operacoes na mesma carteira sao serializadas; carteiras diferentes nao disputam nada.
- **Idempotencia por carteira:** header `Idempotency-Key` obrigatorio, com `UNIQUE (wallet_id, idempotency_key)`. Com a
  carteira travada, o package procura a chave: mesmo tipo e valor e replay (mesma resposta, sem aplicar de novo);
  operacao diferente e conflito (409).
- **Transacao da aplicacao:** o package nao comita. O `@Transactional` curto da aplicacao comita, e esse commit libera o
  lock. Em erro, o package volta ao savepoint do inicio da chamada, entao lancamento e saldo andam sempre juntos.
- **Constraints como segunda barreira:** saldo `>= 0`, valor `> 0` e chave unica valem ate para SQL direto no banco.

Detalhes em [Registro de operacoes](#registro-de-operacoes-pkg_wallet), [docs/CONCURRENCY.md](docs/CONCURRENCY.md) e
[DECISIONS.md](DECISIONS.md).

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
  BALANCE_AUDIT         id, wallet_id, dml_operation, balance_before, balance_after, change_source (PKG_WALLET|DIRECT_SQL),
                        transaction_id, transaction_type, changed_at, db_user, os_user, client_module
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
| `WALLET_API_KEY` | chave que os clientes mandam no header `X-API-Key` (minimo 16 caracteres; sem ela a aplicacao nao sobe) |
| `APP_PORT` | porta local da API no compose (padrao 8080) |

Senhas dos usuarios `WALLET_*`: 8 a 30 caracteres, comecando com letra, apenas letras, numeros e `_`. Os valores do
`.env.example` tem hifen de proposito: se nao forem trocados, o script de init recusa criar os usuarios. A
`ORACLE_PASSWORD` (SYS) e validada pela propria imagem do Oracle; troque-a tambem.

**Quem le o `.env`:** so o `docker compose`, que repassa a cada container apenas o que ele precisa. O container da
aplicacao recebe `WALLET_APP_PASSWORD` e `WALLET_API_KEY` (e a URL do banco ja apontando para o servico `oracle`); as
senhas do owner, do audit e do SYS nunca chegam a ele. A aplicacao em si nao le o arquivo: rodando fora do Docker, as
variaveis vem do shell.

## Rodando a aplicacao

### Pelo Docker (recomendado)

`docker compose up -d --build` ja sobe a API em `http://localhost:8080` (porta configuravel em `APP_PORT`), depois do
banco saudavel e das migrations aplicadas. A imagem e construida pelo `Dockerfile` em duas etapas (compila com JDK e
Maven; a imagem final leva so a JRE e o jar) e roda sem root. Exporte a mesma chave do `.env` para usar nos exemplos
abaixo: `export WALLET_API_KEY='<valor do .env>'`.

### Fora do Docker

Com o banco no ar e o servico `app` parado (`docker compose up -d oracle migrate`):

```bash
# Linux, macOS ou Git Bash
export WALLET_APP_PASSWORD='<mesma senha do .env>'
export WALLET_API_KEY="$(openssl rand -hex 24)"
./mvnw spring-boot:run
```

```powershell
# Windows PowerShell
$env:WALLET_APP_PASSWORD = '<mesma senha do .env>'
$bytes = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$env:WALLET_API_KEY = -join ($bytes | ForEach-Object { $_.ToString('x2') })
.\mvnw.cmd spring-boot:run
```

A aplicacao sobe em `http://localhost:8080`.

- Swagger UI: <http://localhost:8080/swagger-ui.html> (clique em **Authorize** e informe a API key)
- OpenAPI: <http://localhost:8080/v3/api-docs>
- Health: <http://localhost:8080/actuator/health>

Exemplo completo com `curl` (em Bash, com `WALLET_API_KEY` exportada como acima):

```bash
H="X-API-Key: $WALLET_API_KEY"

curl -s -H "$H" -H 'Content-Type: application/json' -d '{"holderName":"Maria Silva"}' localhost:8080/wallets
# {"id":1,"holderName":"Maria Silva","balance":0.00,...}

curl -s -H "$H" -H 'Idempotency-Key: pedido-1' -H 'Content-Type: application/json' \
     -d '{"type":"CREDIT","amount":100.00}' localhost:8080/wallets/1/transactions
# 201 {"id":1,"walletId":1,"type":"CREDIT","amount":100.00,"balanceAfter":100.00,...}

curl -s -i -H "$H" -H 'Idempotency-Key: pedido-1' -H 'Content-Type: application/json' \
     -d '{"type":"CREDIT","amount":100.00}' localhost:8080/wallets/1/transactions
# HTTP/1.1 200, Idempotent-Replayed: true, mesmo corpo da primeira chamada

curl -s -H "$H" -H 'Idempotency-Key: pedido-2' -H 'Content-Type: application/json' \
     -d '{"type":"DEBIT","amount":150.00}' localhost:8080/wallets/1/transactions
# 422 {"code":"INSUFFICIENT_FUNDS",...}

curl -s -H "$H" "localhost:8080/wallets/1/transactions?page=0&size=20"
```

## Registro de operacoes: `PKG_WALLET`

Toda escrita passa pelo package `WALLET_OWNER.PKG_WALLET`. O `WALLET_APP` tem `EXECUTE` nele e nada mais de escrita.

| Procedure | O que faz |
|---|---|
| `create_wallet(p_holder_name, o_wallet_id, o_balance, o_created_at)` | cria a carteira com saldo `0.00` e id de `SEQ_WALLET_ID` |
| `register_transaction(p_wallet_id, p_type, p_amount, p_idempotency_key, o_transaction_id, o_balance_after, o_created_at, o_replayed)` | aplica um credito ou debito |

Passo a passo do `register_transaction`:

1. valida tipo, valor (maior que zero, no maximo 2 casas) e formato da chave, antes de tocar em qualquer tabela;
2. trava a linha da carteira com `SELECT ... FOR UPDATE WAIT 5` (carteira inexistente: `-20001`; lock nao obtido em
   5 s: `-20007`);
3. com o lock na mao, procura a chave na carteira:
   - se ja existe com o mesmo tipo e valor, devolve o lancamento original (`o_replayed = 'Y'`) sem alterar o saldo;
   - se existe com tipo ou valor diferente, recusa com `-20005`;
4. recusa o debito que deixaria o saldo negativo (`-20004`) e o credito que passaria do saldo maximo (`-20010`);
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

## Triggers: extrato imutavel e trilha de auditoria

| Trigger | Schema | O que garante |
|---|---|---|
| `TRG_WALLET_TX_IMMUTABLE` | owner | `UPDATE`/`DELETE`/`MERGE` em `WALLET_TRANSACTIONS` falham com `-20006`, inclusive para o proprio owner |
| `TRG_WALLET_TX_INSERT_GUARD` | owner | lancamento so entra pelo `PKG_WALLET`: o `INSERT` precisa ter o id que o package publicou no contexto |
| `TRG_WALLETS_ID_IMMUTABLE` | owner | o id da carteira nao muda (trocar o id moveria o saldo sem deixar trilha) |
| `TRG_WALLET_TABLES_NO_TRUNCATE` | owner | `TRUNCATE` do extrato e das carteiras falha (trigger de schema; `TRUNCATE` nao dispara trigger de linha) |
| `TRG_WALLETS_BALANCE_AUDIT` | owner | toda alteracao de saldo em `WALLETS` (insert, update ou delete) grava uma linha na auditoria |
| `TRG_BALANCE_AUDIT_IMMUTABLE` / `TRG_BALANCE_AUDIT_NO_TRUNCATE` | audit | a propria trilha nao pode ser alterada, apagada nem truncada |

Como a auditoria e gravada:

- o trigger de saldo chama `WALLET_AUDIT.PKG_BALANCE_AUDIT.record_change`. O owner tem `EXECUTE` nesse package, mas
  `ACCESSIBLE BY` so deixa o trigger chamar: uma chamada ad-hoc numa sessao do owner e recusada, e o owner nao tem
  `INSERT` na tabela;
- a operacao causadora vem do `PKG_WALLET_CONTEXT`, preenchido pelo `PKG_WALLET` antes de gravar. Alteracao fora do
  package fica registrada como `DIRECT_SQL`, com o usuario do banco que a fez;
- a linha de auditoria entra na mesma transacao da alteracao: se a operacao for desfeita, a auditoria tambem e;
- nao gera linha o que nao muda saldo: criacao de carteira (saldo 0), replay, operacao recusada.

**Limites conhecidos:** triggers nao protegem contra DDL do dono do schema sobre as proprias tabelas e triggers:
`ALTER TRIGGER ... DISABLE`, `DROP` ou `CREATE OR REPLACE TRIGGER`, `FLASHBACK TABLE` (que desliga os triggers por
padrao), alteracao de constraints. Como o `ACCESSIBLE BY` confia no nome do objeto, um trigger redefinido com o
mesmo nome tambem conseguiria gravar na auditoria. Os triggers de `TRUNCATE` so pegam DDL do proprio dono; um DBA
com privilegios `ANY` passa direto. Nada disso se resolve com trigger. A mitigacao aqui e de papel: o `WALLET_OWNER` so e usado pelas
migrations versionadas, a aplicacao nao tem DML nem DDL, e a trilha mora num schema separado, cujo dono nao
consegue alterar as proprias linhas.

## API

Documentacao interativa no Swagger UI (`/swagger-ui.html`) e contrato em `/v3/api-docs`.

### Autenticacao

Toda rota de `/wallets` exige o header `X-API-Key` com a chave configurada em `WALLET_API_KEY`. Sem chave ou com
chave errada, a resposta e `401` com `code` `UNAUTHORIZED` (a mensagem e a mesma nos dois casos). A comparacao e
feita em tempo constante e a chave nunca e logada.

A API e stateless: nao ha sessao nem cookie, por isso nao ha CSRF. Ficam abertos sem chave apenas
`/actuator/health` (sem detalhes) e a documentacao OpenAPI, que descreve o contrato e nao expoe dados.

### Leitura

| Metodo e rota | Descricao |
|---|---|
| `GET /wallets/{id}` | carteira com o saldo atual |
| `GET /wallets/{id}/transactions` | extrato paginado, do lancamento mais recente para o mais antigo |

Parametros do extrato:

| Parametro | Padrao | Regra |
|---|---|---|
| `page` | `0` | `>= 0` |
| `size` | `20` | de 1 a 100 |
| `from` | (aberto) | ISO-8601 com fuso, inclusivo (ex.: `2026-11-19T00:00:00-03:00`; na URL, `+` vira `%2B`) |
| `to` | (aberto) | ISO-8601 com fuso, exclusivo: o periodo e `[from, to)` |

Exemplo de resposta do extrato:

```json
{
  "content": [
    { "id": 31, "walletId": 7, "type": "DEBIT", "amount": 20.00, "balanceAfter": 130.50,
      "idempotencyKey": "pedido-123", "createdAt": "2026-11-19T13:00:00Z" }
  ],
  "page": 0, "size": 20, "totalElements": 41, "totalPages": 3
}
```

Valores monetarios saem sempre com duas casas; datas em UTC, ISO-8601. Carteira inexistente devolve 404 tanto na
consulta quanto no extrato (e nao uma pagina vazia).

### Escrita

| Metodo e rota | Corpo | Sucesso |
|---|---|---|
| `POST /wallets` | `{"holderName": "Maria Silva"}` (opcional, ate 120 caracteres) | `201` + `Location: /wallets/{id}`, carteira com saldo `0.00` |
| `POST /wallets/{id}/transactions` | `{"type": "CREDIT" ou "DEBIT", "amount": 10.50}` + header `Idempotency-Key` | `201` com o lancamento aplicado |

Idempotencia em `POST /wallets/{id}/transactions`:

- o header `Idempotency-Key` e obrigatorio (1 a 64 caracteres entre letras, numeros, `_` e `-`; diferencia
  maiusculas). E ele que torna o retry seguro: sem chave, um timeout deixaria o cliente sem saber se a operacao
  entrou;
- a chave vale por carteira. Repetir a mesma chave com o mesmo tipo e valor devolve `200`, o **mesmo corpo** da
  primeira resposta e o header `Idempotent-Replayed: true`; o saldo nao muda de novo;
- repetir a chave com tipo ou valor diferente devolve `409 IDEMPOTENCY_KEY_CONFLICT`;
- `503 WALLET_LOCKED` (outra operacao segurou a carteira por mais de 5 s) vem com `Retry-After: 1` e pode ser
  repetido com a mesma chave sem risco de aplicar duas vezes.

`amount` e numero JSON com no maximo duas casas (`10.005` e recusado, nao arredondado). As regras de valor, tipo e
chave sao validadas pelo `PKG_WALLET`, nesta ordem: tipo, valor, chave (chave ausente com valor invalido responde
`INVALID_AMOUNT`). A API recusa antes so o que esta malformado (JSON invalido, campo ausente) e entrada absurda.
Corpo acima de 16 KB responde `413`. Para numero ou texto absurdo (mais de 20 digitos inteiros ou 10 decimais,
texto com mais de 1000 caracteres), a resposta e a mesma que o banco daria para a regra: `amount` com 11 casas
responde `422 INVALID_AMOUNT`, igual a `10.005`.

| Situacao | Status | `code` |
|---|---|---|
| debito maior que o saldo | 422 | `INSUFFICIENT_FUNDS` |
| valor zero, negativo ou com mais de 2 casas | 422 | `INVALID_AMOUNT` |
| tipo diferente de `CREDIT`/`DEBIT` | 422 | `INVALID_TRANSACTION_TYPE` |
| chave ausente ou fora do formato | 400 | `INVALID_IDEMPOTENCY_KEY` |
| mesma chave, operacao diferente | 409 | `IDEMPOTENCY_KEY_CONFLICT` |
| carteira inexistente | 404 | `WALLET_NOT_FOUND` |
| `type` ou `amount` ausente | 400 | `VALIDATION_ERROR` (com a lista `errors`) |
| JSON invalido ou `amount` nao numerico | 400 | `MALFORMED_REQUEST` |
| corpo acima de 16 KB | 413 | `PAYLOAD_TOO_LARGE` |
| sem conexao livre no pool (muitas requisicoes esperando o lock de uma carteira) | 503 + `Retry-After` | `SERVICE_BUSY` |

Catalogo completo em [docs/ERROR-CODES.md](docs/ERROR-CODES.md).

### Formato de erro

Todo erro sai como `application/problem+json` (RFC 9457), com tres campos a mais: `code` (estavel, para o cliente
tratar), `traceId` (o mesmo do header `X-Trace-Id` e dos logs) e `timestamp`.

```json
{
  "title": "Carteira nao encontrada", "status": 404,
  "detail": "Carteira nao encontrada", "instance": "/wallets/99",
  "code": "WALLET_NOT_FOUND", "traceId": "6f1c2a9e-0b7d-4c55-9a51-2f0c1e8d4b3a", "timestamp": "2026-11-19T13:00:00.123456Z"
}
```

A resposta nunca traz stack trace nem mensagem do banco. Erros nao previstos viram 500 com `code` `INTERNAL_ERROR`;
o detalhe tecnico fica so no log, junto com o `traceId`. Se o cliente mandar um `X-Trace-Id` (8 a 64 caracteres
entre letras, numeros e `-`), ele e reaproveitado, para correlacionar chamadas entre sistemas.

## Observabilidade

| O que | Onde | Acesso |
|---|---|---|
| Saude (banco incluso) | `GET /actuator/health` | aberto, sem detalhes; usado tambem pelo `HEALTHCHECK` do container |
| Metricas | `GET /actuator/metrics` | com API key |
| Contador de operacoes | `GET /actuator/metrics/wallet.transactions?tag=outcome:applied` | com API key |
| Latencia por endpoint | `GET /actuator/metrics/http.server.requests` (medido pelo proprio Spring) | com API key |
| Logs | saida padrao do container | `docker compose logs app` |

- `wallet.transactions` conta cada registro de operacao pelo resultado: `outcome` = `applied`, `replayed` ou o codigo
  do erro (`INSUFFICIENT_FUNDS`, `IDEMPOTENCY_KEY_CONFLICT`...), e `type` = `CREDIT`, `DEBIT` ou `other`. O tipo vem do
  cliente; para nao criar uma serie nova a cada valor estranho, so `CREDIT` e `DEBIT` viram tag.
- Dentro do container, os logs saem em JSON (formato ECS), um evento por linha, com o `traceId` de cada requisicao: da
  para filtrar todos os logs de uma chamada pelo mesmo valor devolvido no header `X-Trace-Id`. Rodando fora do Docker,
  os logs ficam no formato de texto padrao, com o `traceId` entre colchetes.
- Nenhum log traz senha, API key ou corpo de requisicao.

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
- com 16 sessoes disparando 70 operacoes na mesma carteira ao mesmo tempo (incluindo 10 copias da mesma chave e
  debitos alem do saldo), o saldo final bate com a soma do que foi aplicado, a chave repetida vale uma vez so e,
  refazendo o extrato lancamento a lancamento, nenhum `balance_after` diverge nem fica negativo, e a trilha de
  auditoria conta exatamente a mesma historia (`PkgWalletConcurrencyIT`, repetido 5 vezes por execucao);
- o extrato e a auditoria nao podem ser alterados nem por SQL direto dos donos, e toda alteracao de saldo,
  inclusive por SQL direto, aparece na trilha (`ImmutabilityAndAuditIT`);
- a mesma garantia vale de ponta a ponta pela API: 16 clientes HTTP de verdade contra o servidor real disparam 62
  operacoes na mesma carteira (12 copias da mesma `Idempotency-Key` disparadas junto com as demais, debitos alem do saldo), repetindo
  com a mesma chave quando recebem 503. Exatamente uma copia e aplicada e as outras onze recebem o mesmo corpo;
  cada resposta e igual ao lancamento gravado; saldo, extrato completo e trilha de auditoria contam a mesma
  historia; e o retry tardio de um debito aplicado continua sendo replay mesmo com o saldo zerado
  (`ApiConcurrencyIT`, 3 rodadas por execucao). Um teste a parte segura o lock da carteira por JDBC e mostra o
  `503 WALLET_LOCKED` seguido do replay depois do commit.

Para conferir que esses testes pegam os erros que prometem pegar, rodei o `ApiConcurrencyIT` contra duas versoes
quebradas do package: sem o `FOR UPDATE` (perda de atualizacao: saldo final divergente nas 3 rodadas) e com o
saldo verificado antes da chave de idempotencia (retry de debito respondendo 422: falha nas 3 rodadas).

Rodar so os testes de concorrencia:

```bash
./mvnw verify -Dit.test='PkgWalletConcurrencyIT,ApiConcurrencyIT'
```

O CI (GitHub Actions) roda `./mvnw verify` completo a cada push na `main` e em todo pull request para a `main`.

## Estrutura do repositorio

```
db/init/                   script de criacao dos usuarios (roda como SYS na 1a inicializacao do container)
db/migration/audit/        migrations do schema WALLET_AUDIT (Flyway, como WALLET_AUDIT)
db/migration/owner/        migrations do schema WALLET_OWNER: tabelas, views, PKG_WALLET, triggers, grants
docs/                      arquitetura, concorrencia e catalogo de erros
src/main/java/.../api/              HTTP: controller, DTOs, erros, filtros, seguranca, OpenAPI
src/main/java/.../application/      casos de uso (transacao curta na escrita)
src/main/java/.../domain/           modelo, catalogo de erros e portas
src/main/java/.../infrastructure/   adaptadores Oracle (package, views) e tradutor de erros
src/test/java/.../db/               testes do banco: privilegios, constraints, package, triggers, concorrencia
src/test/java/.../api/              testes da API: contrato, seguranca, leitura, escrita, concorrencia via HTTP
docker-compose.yml         Oracle Free + servico de migrations + API
Dockerfile                 imagem da API (build em duas etapas, sem root)
.env.example               variaveis de ambiente (copie para .env)
```

## Troubleshooting

| Sintoma | Causa provavel e solucao |
|---|---|
| Container `oracle` reinicia ou nunca fica `healthy` | Pouca memoria para o Docker. Garanta pelo menos 4 GB (Docker Desktop > Settings > Resources). Com menos de 2 GB o Oracle nao inicializa. |
| Log do `oracle` com `01-create-users: ... deve ter 8-30 caracteres` | Senhas do `.env` ainda com os placeholders ou fora do formato (letras, numeros e `_`, comecando com letra). Corrija e recrie o volume: `docker compose down -v && docker compose up -d`. |
| Mudei senhas no `.env` e nada mudou | O script de init so roda com o volume vazio. Recrie: `docker compose down -v && docker compose up -d` (apaga os dados). |
| `migrate` termina com erro | Veja `docker compose logs migrate`. Se o banco foi recriado com outras senhas, o `.env` e o volume precisam estar alinhados (`down -v`). |
| `port is already allocated` na porta 1521 | Outro Oracle local. Mude `ORACLE_PORT` no `.env` e exporte `WALLET_DB_URL` com a mesma porta antes de rodar a aplicacao (ela nao le o `.env`). |
| `bad interpreter` ou `\r: command not found` no init (Windows) | O script foi salvo com CRLF. O `.gitattributes` forca LF em `*.sh`; se o arquivo foi editado ou copiado por fora do git, converta para LF ou clone o repositorio de novo. |
| Aplicacao nao sobe: `apiKey` invalida | `WALLET_API_KEY` ausente, com menos de 16 caracteres ou com o placeholder. Gere uma: `openssl rand -hex 24` (no compose, troque no `.env` e rode `docker compose up -d app`). |
| Servico `app` nao fica `healthy` | Veja `docker compose logs app`. A API so sobe depois de `oracle` saudavel e `migrate` com codigo 0; se o `migrate` falhou, o `app` nao inicia. |
| `./mvnw verify` falha nos testes de integracao com erro de Docker | O Docker precisa estar rodando; os testes sobem o proprio Oracle (a primeira execucao baixa a imagem, cerca de 1,3 GB). |
