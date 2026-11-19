# Decisoes tecnicas

Cada decisao tem o contexto, o que escolhi, por que, o que descartei, o custo da escolha e o que eu faria diferente com
mais tempo. Comeco pelas que respondem ao centro do desafio (consistencia sob concorrencia dentro do banco) e termino
nas de API, testes e ambiente.

Sumario:

1. [Onde moram as regras: no banco, com o Java como camada fina](#1-onde-moram-as-regras)
2. [Quem controla a transacao](#2-quem-controla-a-transacao)
3. [Como a carteira e travada](#3-como-a-carteira-e-travada)
4. [Idempotencia: escopo, origem da chave e comparacao do payload](#4-idempotencia)
5. [Resposta do replay](#5-resposta-do-replay)
6. [Tipo monetario e escala](#6-tipo-monetario-e-escala)
7. [Defesa em profundidade: constraints alem do package](#7-defesa-em-profundidade)
8. [Extrato imutavel e seus limites](#8-extrato-imutavel-e-seus-limites)
9. [Trilha de auditoria: como o banco sabe qual operacao alterou o saldo](#9-trilha-de-auditoria)
10. [Separacao de schemas e menor privilegio](#10-separacao-de-schemas-e-menor-privilegio)
11. [Sequences](#11-sequences)
12. [Migrations e criacao dos usuarios](#12-migrations-e-criacao-dos-usuarios)
13. [Leitura pela aplicacao: JDBC em views, sem JPA](#13-leitura-pela-aplicacao)
14. [Contrato HTTP e mapa de erros](#14-contrato-http-e-mapa-de-erros)
15. [Paginacao e periodo do extrato](#15-paginacao-e-periodo)
16. [Datas e fuso](#16-datas-e-fuso)
17. [API key](#17-api-key)
18. [Contencao: pool de conexoes, timeouts e 503](#18-contencao)
19. [Arquitetura do codigo Java](#19-arquitetura-do-codigo-java)
20. [Estrategia de testes](#20-estrategia-de-testes)
21. [Ambiente Docker e portabilidade](#21-ambiente-docker)
22. [Imagem da aplicacao e observabilidade](#22-imagem-da-aplicacao-e-observabilidade)
23. [Limitacoes conhecidas, em um lugar so](#23-limitacoes-conhecidas)

---

## 1. Onde moram as regras

**Contexto.** O enunciado pede que as regras de consistencia sejam resolvidas no banco, com PL/SQL, triggers e
sequences, e a aplicacao como camada de API.

**Decisao.** Toda regra que protege o saldo e a idempotencia esta no `PKG_WALLET` e nas constraints. O Java nao
reimplementa nenhuma delas: valida so o que e formato (JSON valido, campo presente) e traduz erros. Valor, tipo e chave
de idempotencia sao repassados crus ao package, que responde com o codigo do catalogo.

**Por que.** Uma regra em dois lugares diverge com o tempo. Com uma unica fonte, a mesma garantia vale para a API, para
um script de manutencao que chame o package e para qualquer outro cliente futuro.

**Alternativa descartada.** Validar tambem no Java (Bean Validation para valor > 0, escala, tipo). Daria mensagens um
pouco mais cedo, mas duplicaria regra e abriria espaco para a API aceitar algo que o banco recusa, ou o contrario.

**Trade-off.** Uma requisicao invalida faz uma ida ao banco. Para evitar que entrada absurda chegue ao driver (texto de
dezenas de KB, numero como `1e999999999`), existem barreiras folgadas: limite de corpo de 16 KB, `@Digits(20,10)` no
valor e uma guarda de tamanho no adaptador. Quando uma barreira dispara, a resposta e a mesma que o banco daria para a
regra (por exemplo, 11 casas decimais responde `422 INVALID_AMOUNT`, igual a `10.005`), entao o cliente nao enxerga
duas regras.

**Com mais tempo.** Gerar o catalogo de erros (constantes do package, `ErrorCode`, docs) a partir de uma fonte unica,
para que um codigo novo nao precise ser escrito em tres lugares.

## 2. Quem controla a transacao

**Contexto.** O package poderia comitar por conta propria ou deixar o commit para quem chama; o enunciado deixa a escolha em aberto.

**Decisao.** O package nao faz `COMMIT`. A aplicacao abre um `@Transactional` curto (timeout de 10 s) em volta de cada
chamada, e o commit no fim do metodo e o que libera o lock da carteira.

**Por que.** `COMMIT` escondido dentro de procedure e uma armadilha: impede compor operacoes numa unidade maior (uma
transferencia, por exemplo, seria debito + credito na mesma transacao) e surpreende quem chama dentro de uma transacao
propria. A procedure continua atomica por contrato: ela marca um savepoint no inicio e, em qualquer erro, volta a ele
antes de propagar a excecao. Assim, nem um chamador que capture a excecao e faca commit deixa um lancamento sem o saldo
correspondente (ha teste exatamente disso).

**Alternativa descartada.** Procedure com `COMMIT`. Mais simples de chamar, mas a transacao vira propriedade do banco e
qualquer composicao futura exige `PRAGMA AUTONOMOUS_TRANSACTION`, que quebraria a atomicidade.

**Trade-off.** O chamador precisa encerrar a transacao logo depois da chamada; enquanto ela estiver aberta, o lock fica
seguro. No codigo, isso e garantido pelo escopo do `@Transactional`, sem nada externo (rede, fila) dentro dele.

**Com mais tempo.** Um teste que prove que remover o `@Transactional` quebraria algo. Hoje, com autocommit, a chamada
comitaria sozinha e os testes continuariam passando; o motivo de ele existir (lock ate o commit da aplicacao, releitura
da carteira recem-criada na mesma conexao) esta documentado, mas nao verificado por teste.

## 3. Como a carteira e travada

**Contexto.** Varias operacoes podem chegar ao mesmo tempo na mesma carteira. O saldo nunca pode ficar negativo
e nada pode ser aplicado em dobro.

**Decisao.** `SELECT balance ... FROM wallets WHERE id = :id FOR UPDATE WAIT 5` na linha da carteira, dentro do package,
antes de qualquer decisao. Toda leitura de saldo e toda busca de chave acontecem com a linha travada. Se o lock nao vier
em 5 s, o package devolve `-20007` e a API responde `503 WALLET_LOCKED` com `Retry-After: 1`.

**Por que.** O lock pessimista na linha serializa so as operacoes da mesma carteira; carteiras diferentes nao disputam
nada. Como a decisao (aceitar ou recusar o debito, aplicar ou devolver replay) e tomada sobre o saldo e o extrato vistos
com a linha travada, nenhuma outra sessao consegue mudar esse estado entre a leitura e a gravacao.

**Alternativas descartadas.**
- Lock otimista (coluna de versao, `UPDATE ... WHERE version = :v`): sob contencao na mesma carteira, cada conflito vira
  retry, e o retry fica com o cliente ou com um laco no codigo. O lock pessimista resolve a fila no proprio banco.
- `NOWAIT`: falharia na primeira disputa, mesmo quando a espera seria de milissegundos.
- `WAIT` sem limite: uma sessao travada seguraria as outras indefinidamente.
- `DBMS_LOCK`: lock manual por nome, sem necessidade, ja que a linha da carteira e o recurso natural.

**Trade-off.** Uma carteira muito disputada vira um ponto de serializacao (e o objetivo). Cada requisicao esperando o
lock segura uma conexao do pool; isso esta tratado na decisao 18.

**Detalhe de versao.** No Oracle 23ai, o timeout do `FOR UPDATE WAIT` aparece como `ORA-00054`, e nao mais como o
`ORA-30006` das versoes anteriores. O package trata os dois. Descobri isso porque o teste de timeout falhou.

**Com mais tempo.** Medir o tempo medio com a linha travada sob carga e ajustar o `WAIT` com dado, em vez dos 5 s
escolhidos por bom senso.

## 4. Idempotencia

**Contexto.** A mesma operacao pode chegar duas vezes (retry, timeout). O saldo deve ser afetado uma vez e a segunda
chamada deve devolver o mesmo resultado. Reusar a chave para uma operacao diferente e conflito.

**Decisao.**
- **Origem da chave:** header `Idempotency-Key`, obrigatorio, 1 a 64 caracteres entre letras, numeros, `_` e `-`. Sem
  chave, `400 INVALID_IDEMPOTENCY_KEY`.
- **Escopo:** por carteira, com `UNIQUE (wallet_id, idempotency_key)`.
- **Comparacao do payload:** tipo e valor da operacao gravada contra tipo e valor da nova chamada, depois do lock. Iguais:
  replay. Diferentes: `-20005` -> `409 IDEMPOTENCY_KEY_CONFLICT`.
- **Comparacao da chave:** binaria (`NLSSORT(..., 'NLS_SORT=BINARY')`), sensivel a maiusculas e independente do NLS da
  sessao.

**Por que.**
- Header, e nao campo no corpo: e a convencao mais comum (Stripe, rascunho IETF) e separa o "o que fazer" do "como
  deduplicar".
- Por carteira: como a busca acontece com a carteira travada, a checagem fica serializada sem disputa entre carteiras. A
  `UNIQUE` continua como rede de seguranca caso alguem grave por fora do package.
- Comparar tipo e valor, sem hash: o payload relevante tem dois campos. Comparar as colunas e exato e dispensa coluna
  extra e `DBMS_CRYPTO`.
- Comparacao binaria: o SQL dentro do package usa o NLS da sessao. Com `NLS_COMP=LINGUISTIC` e `NLS_SORT=BINARY_CI`, que qualquer sessao pode
  ativar sem privilegio, `abc` casaria com `ABC`. Um teste ativa esse modo e prova que a comparacao continua binaria;
  com a comparacao ingenua, ele falha.

**Alternativas descartadas.** Chave global (exigiria lock global ou tratar `DUP_VAL_ON_INDEX` como fluxo normal); hash
do corpo normalizado (util com payload grande, desnecessario aqui); chave opcional (sem ela, um timeout deixa o cliente
sem saber se a operacao entrou).

**Trade-off.** As chaves ficam para sempre no extrato; nao ha expiracao. Para o volume do desafio isso e irrelevante.

**Com mais tempo.** Politica de retencao das chaves e uma tabela de idempotencia separada se o extrato crescer muito.

## 5. Resposta do replay

**Decisao.** A primeira chamada responde `201`. O replay responde `200` com **o mesmo corpo** (mesmo id de lancamento,
mesmo `balanceAfter`, mesma data) e o header `Idempotent-Replayed: true`.

**Por que.** "Mesmo resultado" quer dizer que o cliente consegue tratar as duas respostas igual. O status diferente e o
header ajudam quem quer saber que foi replay, sem obrigar ninguem a tratar.

**Alternativa descartada.** Repetir o `201`: igualmente valido, mas esconde uma informacao util para diagnostico.

**Detalhe que um teste pegou.** A ordem dentro do package importa: a chave e procurada **antes** de checar o saldo. Na
ordem inversa, o retry de um debito ja aplicado, feito depois que o saldo caiu, responderia `422`; o cliente acharia que
a operacao falhou e repetiria com outra chave, cobrando duas vezes. Ha um teste que zera o saldo e repete um debito
aplicado; rodei-o contra a versao com a ordem invertida e ele falhou.

## 6. Tipo monetario e escala

**Decisao.** `NUMBER(19,2)` no banco, `BigDecimal` no Java, numero JSON com duas casas na resposta. Valor com mais de
duas casas e **recusado** (`422 INVALID_AMOUNT`), nao arredondado. Teto de `99999999999999999.99`, o maximo de
`NUMBER(19,2)`, para valor e saldo (`-20010 BALANCE_LIMIT_EXCEEDED` se um credito estourar).

**Por que.** Arredondar dinheiro em silencio e o tipo de erro que so aparece na conciliacao. E ha um efeito colateral
importante: `NUMBER(19,2)` arredonda `10.005` para `10.01` **antes** de qualquer `CHECK`. Se o package nao recusasse a
escala, `10.005` seguido de `10.01` com a mesma chave viraria um replay legitimo, e um retry identico de `10.005` viraria
falso conflito. Por isso a escala e conferida no package, antes da busca de replay e de qualquer gravacao. Um teste deixa
explicito o arredondamento da coluna.

**Alternativa descartada.** Coluna `NUMBER` sem escala com `CHECK (amount = ROUND(amount, 2))`: protegeria contra SQL
direto, mas foge do que o enunciado sugere e o package ja fecha o caminho principal.

**Trade-off.** `25.00000000000` (11 casas, todas zero) e recusado pela barreira de entrada, apesar de ser o mesmo valor
que `25.00`. Aceitei a regra simples "no maximo 10 casas na entrada".

**Com mais tempo.** Aceitar o valor como string JSON de forma explicita e documentada (hoje o Jackson aceita string
numerica por coercao, sem que isso seja parte do contrato).

## 7. Defesa em profundidade

**Decisao.** Alem do package, constraints em todas as invariantes: `CHECK (balance >= 0)`, `CHECK (amount > 0)`,
`CHECK (balance_after >= 0)`, `CHECK` do tipo, formato da chave de idempotencia, `FK` do extrato para a carteira e a
`UNIQUE` da chave. O package da o erro de negocio legivel; a constraint segura se alguem passar por fora.

**Por que.** SQL executado direto pelo owner nao passa pelo package. Os testes de constraint fazem exatamente isso,
inclusive com o trigger de guarda desligado, simulando o limite da decisao 8.

**Detalhe que os testes pegaram.**
- `IN (...)` com `NULL` resulta em UNKNOWN, e o Oracle aceita um `CHECK` com resultado UNKNOWN. Um `CHECK` de coerencia da
  auditoria aceitava `NULL` sem querer; ganhou `IS NOT NULL` explicito.
- No Oracle, o intervalo `[A-Za-z]` de `REGEXP_LIKE` aceita letras acentuadas. A constraint da chave usa
  `REGEXP_LIKE(ASCIISTR(chave), ...)`, que troca caracteres fora do ASCII por `\XXXX`.

## 8. Extrato imutavel e seus limites

**Decisao.** Triggers no owner:
- `TRG_WALLET_TX_IMMUTABLE` (`BEFORE UPDATE OR DELETE`, por linha): qualquer alteracao ou exclusao de lancamento falha
  com `-20006`, inclusive `MERGE` e `UPDATE` que regrava o mesmo valor.
- `TRG_WALLET_TX_INSERT_GUARD` (`BEFORE INSERT`): lancamento so entra se tiver o id que o `PKG_WALLET` acabou de publicar
  no contexto da sessao. Um `INSERT` direto, com lancamento forjado, e recusado.
- `TRG_WALLET_TABLES_NO_TRUNCATE` (`BEFORE TRUNCATE ON SCHEMA`): `TRUNCATE` nao dispara trigger de linha, entao um trigger
  de schema recusa o `TRUNCATE` do extrato e das carteiras.
- `TRG_WALLETS_ID_IMMUTABLE`: o id da carteira nao muda (trocar o id moveria o saldo para outra chave sem deixar trilha).

**Limites.** Trigger nao protege contra DDL do dono do schema sobre os proprios objetos:
`ALTER TRIGGER ... DISABLE`, `DROP` ou `CREATE OR REPLACE TRIGGER`, `FLASHBACK TABLE` (que desliga triggers por padrao),
alteracao de constraints. Um trigger `ON SCHEMA` tambem nao pega DDL de um DBA com privilegios `ANY`.

**Mitigacao.** Separacao de papeis: o `WALLET_OWNER` so e usado pelas migrations versionadas; a aplicacao conecta como
`WALLET_APP`, que nao tem DML nem DDL; e a auditoria mora em outro schema, cujo dono nao consegue alterar as proprias
linhas. A protecao contra um owner mal-intencionado e organizacional (quem tem a senha, quem aprova migrations), nao
tecnica.

**Alternativa descartada.** Trigger de DDL bloqueando `ALTER`/`DROP` dos objetos protegidos: eleva a barra, mas o owner
pode derrubar o proprio trigger de DDL, e isso atrapalharia migrations legitimas.

**Com mais tempo.** Oracle Database Vault ou Unified Auditing para registrar e restringir DDL no schema owner, e a
conciliacao periodica (extrato x saldo x auditoria, um dos diferenciais sugeridos no enunciado) para detectar qualquer supressao.

## 9. Trilha de auditoria

**Contexto.** Toda alteracao de saldo precisa gerar trilha com saldo anterior, saldo novo, operacao causadora e momento,
alimentada pelo banco, sem acesso da aplicacao.

**Decisao.**
- Trigger `TRG_WALLETS_BALANCE_AUDIT`, `AFTER INSERT OR UPDATE OF balance OR DELETE ON wallets FOR EACH ROW`. Por ficar
  na tabela de saldo, pega tambem alteracao feita por SQL direto, por `MERGE` ou por `UPDATE` de varias linhas.
- **Operacao causadora:** o package publica id e tipo do lancamento num package de contexto (`PKG_WALLET_CONTEXT`) antes
  de gravar, e limpa logo depois, inclusive em erro. Contexto vazio significa alteracao fora do package, registrada como
  `DIRECT_SQL` com o usuario do banco.
- **Gravacao:** o trigger chama `WALLET_AUDIT.PKG_BALANCE_AUDIT.record_change`. O owner tem `EXECUTE` nesse package, nao
  `INSERT` na tabela, e `ACCESSIBLE BY (TRIGGER wallet_owner.trg_wallets_balance_audit)` deixa so o trigger chamar.
- **Mesma transacao:** sem `AUTONOMOUS_TRANSACTION`. Se a operacao for desfeita, a linha de auditoria tambem e.
- Nao gera linha o que nao muda saldo (criacao de carteira com saldo 0, replay, operacao recusada, `UPDATE` que regrava o
  mesmo valor).

**Por que.** Trigger no extrato nao pegaria um `UPDATE` direto do saldo. Package de contexto, e nao variavel global no
`PKG_WALLET`, porque o contexto tem `ACCESSIBLE BY` restrito ao package e aos triggers: nem uma sessao manual do owner
consegue marcar um `UPDATE` direto como se viesse do package. A trilha guarda o tipo da operacao, alem do id, para ser
legivel sem consultar o schema do owner (por isso tambem nao tem FK para ele).

**Limites.** `ACCESSIBLE BY` confia no nome do objeto: quem controla o owner pode redefinir o trigger com o mesmo nome e
gravar o que quiser, ou desabilita-lo. `OS_USER` e `MODULE` sao informados pelo cliente e ficam como informacao auxiliar;
o campo confiavel e `db_user`. O contexto e estado de package: recompilar o package com sessoes abertas no pool gera
`ORA-04068` na primeira chamada de cada sessao.

**Alternativas descartadas.** `GRANT INSERT` do audit para o owner (mais simples, mas o owner poderia gravar qualquer
linha); application context com `DBMS_SESSION.SET_CONTEXT` (sobrevive a recompilacao, mas exige `CREATE ANY CONTEXT` ou
que o script de init crie o contexto apontando para um package que ainda nao existe).

**Com mais tempo.** Application context criado pelo DBA, Unified Auditing como segunda trilha independente e a
conciliacao periodica do extrato com o saldo.

## 10. Separacao de schemas e menor privilegio

**Decisao.**

| Usuario | Privilegios de sistema | Grants de objeto |
|---|---|---|
| `WALLET_OWNER` | `CREATE SESSION`, `TABLE`, `SEQUENCE`, `PROCEDURE`, `TRIGGER`, `VIEW`; quota em `USERS` | recebe `EXECUTE` em `WALLET_AUDIT.PKG_BALANCE_AUDIT` |
| `WALLET_APP` | `CREATE SESSION`; sem quota | `EXECUTE` em `PKG_WALLET`; `READ` em `V_WALLETS` e `V_WALLET_TRANSACTIONS` |
| `WALLET_AUDIT` | `CREATE SESSION`, `TABLE`, `SEQUENCE`, `PROCEDURE`, `TRIGGER`; quota em `USERS` | nenhum para a aplicacao |

Nenhum role, nenhum privilegio `ANY`, nada para `PUBLIC`. Os testes de integracao provam os privilegios de sistema, as
quotas, a ausencia de roles, cada operacao negada ao `WALLET_APP` e que owner e audit nao concedem nada a `PUBLIC`.

**Decisoes menos obvias.**
- **`READ` e nao `SELECT` nas views:** `SELECT` tambem permite `SELECT ... FOR UPDATE` e `LOCK TABLE`; com ele, a
  aplicacao conseguiria segurar locks que o `PKG_WALLET` precisa obter. Com `READ`, isso falha (ha teste).
- **Views `WITH READ ONLY`:** mesmo que alguem conceda DML por engano, a view nao aceita escrita.
- **`INHERIT PRIVILEGES` revogado:** o Oracle concede por padrao `INHERIT PRIVILEGES ON USER <novo> TO PUBLIC`. Sem
  revogar, codigo invoker rights de outro schema chamado por esses usuarios executaria com os privilegios deles. Um teste
  que confere grants a `PUBLIC` encontrou isso.
- **A aplicacao so recebe a propria senha:** ela nao le o `.env` (quem le e o `docker compose`); recebe por variavel de
  ambiente apenas `WALLET_APP_PASSWORD`, `WALLET_DB_URL` e `WALLET_API_KEY`.

**Detalhe de versao.** No 23ai, DML sem grant em objeto visivel responde `ORA-41900` ("missing privilege"), e nao o
`ORA-01031` de versoes anteriores.

## 11. Sequences

**Decisao.** Sequences explicitas (`SEQ_WALLET_ID`, `SEQ_WALLET_TRANSACTION_ID`, `SEQ_BALANCE_AUDIT_ID`), `NOCYCLE`,
`CACHE 20`. O `NEXTVAL` e chamado no codigo PL/SQL (as colunas de id nao tem `DEFAULT`); o do lancamento, em
particular, sai depois do lock da carteira.

**Comportamento relevante para o desenho.**
- **Ids tem lacunas:** cache perdido no restart, `NEXTVAL` consumido por uma chamada que depois falha e volta ao
  savepoint. Ids nao sao contagem de operacoes nem indicam horario.
- **Ordem por carteira:** como o `NEXTVAL` do lancamento sai com a carteira travada, dentro de uma mesma carteira os ids
  crescem na ordem em que as operacoes foram aplicadas. Os testes de concorrencia usam isso para refazer o saldo
  lancamento a lancamento.
- **Entre carteiras** a ordem dos ids nao significa nada, e em RAC sem `ORDER` nem a ordem global seria garantida.
- **Extrato:** ordenado por `(created_at, id)`. O `id` desempata e, dentro da carteira, e confiavel; `created_at` vem do
  relogio do servidor e, em teoria, um ajuste de relogio para tras poderia inverter dois lancamentos proximos.

**Alternativa descartada.** `IDENTITY` nas colunas: funciona igual por baixo, mas o enunciado pede sequences explicitas e,
com elas, o momento do `NEXTVAL` (dentro do lock) fica visivel no codigo.

**Com mais tempo.** Ids publicos nao sequenciais (UUID) na API: hoje os ids sao previsiveis, o que permite enumerar
carteiras existentes a quem tem a API key.

## 12. Migrations e criacao dos usuarios

**Decisao.**
- `db/init/01-create-users.sh`, executado pela imagem como SYS na primeira inicializacao, cria os tres usuarios no
  `FREEPDB1` (`ALTER SESSION SET CONTAINER` explicito), com senhas vindas de variavel de ambiente. E `.sh` e nao `.sql`
  para conseguir ler as variaveis; valida o formato das senhas antes de interpola-las, e os placeholders do
  `.env.example` sao recusados de proposito.
- Flyway 12.4.0 num servico `migrate` do compose, separado da aplicacao. Duas passadas: `db/migration/audit` como
  `WALLET_AUDIT`, depois `db/migration/owner` como `WALLET_OWNER`. Os testes rodam as mesmas migrations, na mesma
  ordem; a imagem do compose foi escolhida na mesma versao do Flyway que o Spring Boot gerencia para os testes (12.4.0).
- `spring.flyway` ausente na aplicacao; `ddl-auto` nem existe (sem JPA).

**Por que.** Rodar o Flyway na subida da aplicacao obrigaria o processo dela a conhecer a senha do owner. Num
servico separado, `docker compose up -d` entrega o banco com schemas e objetos prontos e a aplicacao nunca ve
essa senha. Objetos do audit por migration propria, executada pelo proprio `WALLET_AUDIT`, para que o owner nao precise
de privilegio `ANY`.

**Detalhe verificado.** O `healthcheck.sh` da imagem so fica saudavel depois que os scripts de init terminam (o script
retorna "nao saudavel" enquanto eles rodam), entao o `depends_on: service_healthy` do `migrate` ja garante que os usuarios
existem. Conferi lendo o script da imagem.

**Alternativa descartada.** Liquibase: equivalente para o caso; Flyway tem SQL puro e PL/SQL com `/` sem camada extra.

**Trade-off.** Migration ja aplicada nunca e editada (mudaria o checksum). Por isso, quando um ajuste precisou mexer num
package criado numa migration anterior, ele entrou numa migration nova.

## 13. Leitura pela aplicacao

**Decisao.** `JdbcClient` nas views `V_WALLETS` e `V_WALLET_TRANSACTIONS`, com SQL explicito e binds. Sem JPA.

**Por que.** Sao duas consultas simples e nenhuma escrita por ORM; JPA traria mapeamento, `ddl-auto` e um modelo de
entidade que nao agrega. O `WALLET_APP` so tem `READ` nas views, entao qualquer escrita acidental falharia de qualquer
jeito.

**Trade-off.** O extrato faz uma consulta de existencia, um `COUNT` e a pagina. Com escrita concorrente, `totalElements`
pode divergir da pagina por alguns lancamentos. Com paginacao por offset isso e aceitavel.

## 14. Contrato HTTP e mapa de erros

**Decisao.** Erros em `application/problem+json` (RFC 9457) com tres campos a mais: `code` (estavel), `traceId` (mesmo do
header `X-Trace-Id` e dos logs) e `timestamp`. Criterio de status:

| Status | Quando |
|---|---|
| 400 | malformado: da para saber sem consultar o estado do sistema (JSON invalido, campo ausente, chave fora do formato) |
| 401 | sem API key valida |
| 404 | recurso inexistente |
| 409 | conflito com algo que ja existe (chave reutilizada para outra operacao) |
| 422 | bem formado, mas viola regra de negocio (valor invalido, saldo insuficiente) |
| 503 + `Retry-After` | contencao passageira; pode repetir com a mesma chave |

**Traducao dos erros Oracle.** Uma classe (`OracleErrorTranslator`) percorre a cadeia de causas ate achar a
`SQLException` e usa so `getErrorCode()`: -20001..-20010, mais `ORA-00054`/`ORA-30006` (timeout de lock que escapasse
do package) como `WALLET_LOCKED`. A mensagem que vai ao cliente vem do catalogo, nunca do Oracle. Qualquer outro codigo
vira `500 INTERNAL_ERROR` generico, com o detalhe so no log. Os testes unitarios do tradutor e do handler e os testes de
integracao de escrita conferem que as respostas de erro nao trazem `ORA-`, `WALLET_OWNER`, `PKG_` ou `SQL`.

**Decisoes discutiveis, e por que fiquei com elas.**
- Saldo insuficiente e `422`, nao `409`: o estado da carteira nao "conflita" com a requisicao; a requisicao viola uma
  regra.
- Timeout de lock e `503`, nao `409`: e contencao que passa sozinha, nao conflito de estado.
- Chave ausente e `400`, mas o banco valida na ordem tipo, valor, chave: chave ausente com valor invalido responde
  `422 INVALID_AMOUNT`. Documentado no README e no OpenAPI.

## 15. Paginacao e periodo

**Decisao.** Offset (`page`, `size` de 1 a 100, padrao 20), ordem do mais recente para o mais antigo por
`(created_at, id)`, filtro de periodo semiaberto `[from, to)` em ISO-8601 com fuso, indice `(wallet_id, created_at, id)`.
Datas fora dos anos 0001 a 9999 sao recusadas com 400 antes de chegar ao banco.

**Por que.** Offset e simples de usar e suficiente para o volume do desafio. Semiaberto evita contar duas vezes o
lancamento que cai exatamente no limite entre dois periodos consecutivos.

**Com mais tempo.** Paginacao por chave (`created_at`, `id`): custo constante em paginas profundas e sem divergencia sob
escrita concorrente.

## 16. Datas e fuso

**Decisao.** `TIMESTAMP(6)` com valor UTC (`SYS_EXTRACT_UTC(SYSTIMESTAMP)`), lido como `LocalDateTime` e convertido para
`Instant` explicitamente; na API, ISO-8601 UTC com fracao de segundo.

**Por que.** `TIMESTAMP WITH TIME ZONE` funcionaria, mas indice sobre ele vira indice de funcao e a comparacao exige mais
cuidado. Gravar sempre em UTC e converter numa camada so e mais simples de raciocinar.

**Detalhe que uma revisao pegou.** A escrita lia a data com `getTimestamp().toLocalDateTime()`, que passa pelo fuso da
JVM, e a leitura com `getObject(LocalDateTime.class)`, que nao passa. Num fuso com horario de verao, um horario na hora
"pulada" sairia diferente no `POST` e no `GET`. Unifiquei em `getObject`.

## 17. API key

**Decisao.** Filtro proprio na cadeia do Spring Security, header `X-API-Key`.
- Comparacao sobre o SHA-256 das duas chaves com `MessageDigest.isEqual`: tempo constante e lados do mesmo tamanho.
- Chave nunca logada; `toString` da configuracao mascara.
- A aplicacao nao sobe sem chave, com chave curta (menos de 16 caracteres) ou com o placeholder do `.env.example`.
- `401` no mesmo formato dos outros erros, escrito na propria cadeia de seguranca; chave ausente e chave errada recebem a
  mesma resposta.
- Stateless: sem sessao, sem cookie e, por isso, sem CSRF. Headers de seguranca padrao mantidos (`no-store`, `nosniff`).
- Sem usuario em memoria: o Spring deixa de gerar e logar uma senha padrao.
- Abertos sem chave: `/actuator/health` (sem detalhes) e a documentacao OpenAPI, que descreve o contrato e nao expoe dados.

**Alternativa descartada.** OAuth2/JWT: fora do escopo do desafio (autenticacao de usuario final esta explicitamente fora).

**Com mais tempo.** Uma chave por cliente (com identificacao no log e revogacao individual), rotacao sem downtime,
rate limit e Swagger desligado em producao (`springdoc.api-docs.enabled=false`).

## 18. Contencao

**Contexto.** Cada requisicao esperando o lock de uma carteira disputada segura uma conexao do pool por ate 5 s. Com o
pool cheio, requisicoes para **outras** carteiras ficariam esperando conexao.

**Decisao.**
- `connection-timeout` do Hikari em 5 s (o padrao e 30 s). Sem conexao livre nesse tempo, a resposta e
  `503 SERVICE_BUSY` com `Retry-After: 1`, e nao `500`.
- `@Transactional(timeout = 10)` no service: vira query timeout nos comandos JDBC e impede que uma requisicao fique presa
  se o banco travar.
- `WALLET_LOCKED` e logado em INFO, para dar visibilidade a contencao sem poluir o nivel de erro.

**Trade-off.** Uma carteira muito disputada ainda consegue ocupar o pool inteiro por alguns segundos. Isolar isso de
verdade exigiria limitar a concorrencia por carteira antes do banco.

**Com mais tempo.** Medir sob carga, dimensionar o pool com dado e, se necessario, um limitador de concorrencia
por carteira na aplicacao.

## 19. Arquitetura do codigo Java

**Decisao.** Hexagonal leve: `api` (controllers, DTOs, erros, filtros, seguranca) -> `application` (casos de uso) ->
`domain` (records, `ErrorCode`, portas) <- `infrastructure.oracle` (adaptadores do package e das views, tradutor de
erros). Detalhes em [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

**Por que.** O dominio Java e pequeno porque as regras estao no banco; mesmo assim, separar "o que a API precisa" (portas)
de "como o Oracle faz" (adaptadores) deixa o tradutor de erros, o mapeamento HTTP e os casos de uso testaveis sem banco.

**Alternativa descartada.** Camadas tradicionais com repositorios JPA e servicos ricos: traria estrutura para regras que,
aqui, nao existem no Java.

**Decisoes de detalhe.**
- O mapeamento `ErrorCode` -> HTTP e um `switch` sem `default`: um codigo novo sem mapeamento nao compila.
- A barreira contra texto absurdo fica no adaptador do banco, que e quem precisa proteger o driver. Nos DTOs, o limite
  folgado aparecia no OpenAPI no lugar do limite real.

## 20. Estrategia de testes

**Decisao.**
- **Unitarios** (Surefire, `*Test`): tradutor de erros (todos os codigos, codigo desconhecido, cadeia de causas,
  mensagem sem `ORA-`), mapeamento HTTP, handler, filtros, validacoes, casos de uso com portas mockadas e contrato HTTP
  com `@WebMvcTest`, passando pela cadeia de seguranca real.
- **Integracao** (Failsafe, `*IT`) contra Oracle Free real via Testcontainers, com a mesma imagem, o mesmo `db/init` e as
  mesmas migrations do compose, senhas e API key geradas a cada execucao: privilegios (cada negacao), constraints,
  package, triggers, auditoria, API de ponta a ponta e o contrato OpenAPI conferido no JSON gerado.
- **Concorrencia**, nos dois niveis:
  - `PkgWalletConcurrencyIT` (direto no package, 5 rodadas) e `ApiConcurrencyIT` (clientes HTTP reais, 3 rodadas).
  - Mesma chave repetida em paralelo e debitos alem do saldo.
  - Asserts sobre saldo, extrato refeito lancamento a lancamento, respostas e auditoria.
- **Teste dos testes.** Para os pontos mais importantes, rodei o teste contra uma versao quebrada do codigo e confirmei
  que ele falha: comparacao de chave sensivel a NLS, package sem `FOR UPDATE` (perda de atualizacao nas 3 rodadas) e
  saldo verificado antes da chave (retry respondendo 422).

**Por que.** O desafio pede prova de comportamento sob concorrencia, e prova so vale se o teste pegaria o erro. Banco em
memoria esta fora de cogitacao: o comportamento que importa (lock, NLS, triggers, privilegios, codigos de erro do 23ai) e
especifico do Oracle.

**Licoes registradas.** Dois testes que eu tinha escrito eram falsos positivos e foram pegos em revisao: um de espera
pelo lock que passava com ou sem lock, e a primeira versao do teste concorrente via HTTP, em que a chave repetida
podia chegar sempre com saldo zerado e nada era testado. Os dois foram reescritos.

**Teste de carga.** Alem dos testes de integracao, um teste com k6 (`load-test/`) dispara 2000 operacoes na mesma
carteira com 50 usuarios virtuais e confere saldo, extrato e auditoria contra operacoes conhecidas de antemao. Rodei-o
tambem contra o package sem o `FOR UPDATE`, e ele falhou.

**Com mais tempo.** Mutation testing automatizado (PIT) na camada Java, o teste de carga rodando no CI e um job de CI
que rode os testes de concorrencia varias vezes seguidas para medir instabilidade.

## 21. Ambiente Docker

**Decisao.**
- `gvenzl/oracle-free:23.26.4-slim-faststart`: tag fixa, publicada para amd64 e arm64 (Apple Silicon). Conferi no Docker
  Hub antes de usar.
- Porta publicada so em `127.0.0.1`: o banco nao fica exposto na rede local.
- Senhas so por variavel de ambiente; placeholders recusados.
- `.gitattributes` forca LF em `*.sh` e `*.sql`: script com CRLF quebra dentro do container Linux.
- CI no GitHub Actions: valida o compose e roda `./mvnw verify` (unitarios + integracao contra Oracle real) a cada push na `main`
  e em todo PR para a `main`; actions fixadas por SHA.

**Detalhe que o CI pegou.** Dois contextos Spring em cache, cada um com seu pool, mais o teste concorrente esgotavam as
sessoes do Oracle Free (`ORA-12516`) so no CI, por causa da ordem dos testes. Todos os testes de integracao passaram a
compartilhar um contexto, com pool de 4 conexoes.

**Com mais tempo.** Imagens fixadas por digest, alem da tag.

## 22. Imagem da aplicacao e observabilidade

**Decisao.**
- `Dockerfile` em duas etapas: a primeira compila com JDK e Maven Wrapper (dependencias numa camada propria, para o
  cache do Docker), a segunda leva so a JRE e o jar. Imagens base com tag fixa, usuario sem root (UID 10001), heap em 75% da
  memoria do container e `HEALTHCHECK` no `/actuator/health`.
- `.dockerignore` em forma de lista do que entra (pom, wrapper, `src/main`), e nao do que sai: nada alem do necessario
  chega ao contexto do build.
- Servico `app` no compose, que so sobe depois do banco saudavel e do `migrate` concluido com sucesso. Recebe apenas a
  propria senha, a API key e a URL do banco. Container endurecido: sistema de arquivos so leitura (so `/tmp` gravavel),
  sem capabilities do Linux e sem escalada de privilegio.
- Observabilidade: health (aberto), metricas do Actuator (com API key), um contador de negocio `wallet.transactions`
  por resultado e logs em JSON (ECS) no container, com o `traceId` de cada requisicao.

**Por que.** Com a aplicacao no compose, quem avalia sobe tudo com um comando. A imagem final sem JDK, Maven e codigo
fonte e menor e expoe menos. O contador responde perguntas que a latencia do Spring nao responde: quantas operacoes
foram aplicadas, quantas eram replay e quantas foram recusadas por saldo ou por conflito de chave.

**Detalhes que apareceram ao construir.**
- Sem `unzip` na imagem de build, o Maven Wrapper baixa o `.tar.gz` do Maven, e o checksum configurado (do `.zip`)
  nao confere. A etapa de build instala o `unzip` em vez de remover a verificacao de integridade.
- O `maven-wrapper.properties` passou a ter LF forcado pelo `.gitattributes`: ele e lido dentro do container Linux.
- O tipo da operacao vem do cliente; so `CREDIT` e `DEBIT` viram tag da metrica (o resto vira `other`), para um valor
  estranho nao criar uma serie nova.

**Alternativas descartadas.**
- Imagem nativa (GraalVM) ou jlink: imagem menor e partida mais rapida, mas build bem mais lento e mais complexo de
  manter para este caso.
- Endpoint Prometheus e tracing distribuido (OpenTelemetry): uteis com um coletor de verdade, que este ambiente nao tem.

**Com mais tempo.** Imagens base fixadas por digest e atualizadas por bot; scan de vulnerabilidades da imagem no CI
(Trivy); imagem final distroless, sem shell nem curl; senhas como secret do orquestrador em vez de variavel de ambiente
(hoje aparecem no `docker inspect`); endpoint Prometheus com um painel; tracing com OpenTelemetry propagando o
`traceId`; e a imagem
publicada num registry pelo CI (hoje o CI so constroi para validar o `Dockerfile`).

## 23. Limitacoes conhecidas

Reunidas aqui para quem quer saber onde o desenho para:

- O dono do schema pode desabilitar, remover ou redefinir triggers, e `FLASHBACK TABLE` desliga triggers por padrao
  (decisoes 8 e 9). A protecao e de papel, nao tecnica.
- `ACCESSIBLE BY` confia no nome do objeto (decisao 9).
- Recompilar o package de contexto com sessoes abertas no pool gera `ORA-04068` na primeira chamada de cada sessao
  (decisao 9).
- Ids sequenciais e previsiveis permitem enumerar carteiras a quem tem a API key (decisao 11).
- Uma unica API key para todos os clientes, sem rate limit (decisao 17).
- Uma carteira muito disputada pode ocupar o pool por alguns segundos (decisao 18).
- Corpo chunked (sem `Content-Length`) nao passa pelo limite de 16 KB; esse limite fica para o proxy. A guarda de tamanho
  no adaptador evita que um texto enorme chegue ao driver.
- `totalElements` do extrato pode divergir da pagina sob escrita concorrente (decisao 13).
- Nao ha teste que prove a necessidade do `@Transactional` (decisao 2).
