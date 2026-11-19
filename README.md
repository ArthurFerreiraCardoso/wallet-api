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
docker compose ps           # aguarde o servico oracle ficar "healthy"
```

Na primeira inicializacao, `db/init/01-create-users.sh` cria no PDB `FREEPDB1` os usuarios:

| Usuario | Papel |
|---|---|
| `WALLET_OWNER` | dono das tabelas, sequences, package e triggers; usado so pelas migrations |
| `WALLET_APP` | usuario da aplicacao; apenas `CREATE SESSION` + grants de objeto minimos |
| `WALLET_AUDIT` | dono da trilha de auditoria; a aplicacao nao tem acesso |

A porta e publicada apenas em `127.0.0.1`. O script so roda com o volume vazio. Para recriar o banco do zero: `docker compose down -v && docker compose up -d`.

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

Os testes de integracao sobem um Oracle Free proprio via Testcontainers (mesma imagem e mesmo script de init
do compose, com senhas geradas na hora). Precisam apenas do Docker rodando; nao dependem do `docker compose`.
