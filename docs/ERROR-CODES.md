# Catalogo de erros

Erros de negocio nascem no banco, no `PKG_WALLET`, via `RAISE_APPLICATION_ERROR` na faixa `-20001` a `-20010`.
Cada codigo tem uma constante nomeada no package (`c_err_*`). A aplicacao le o codigo pelo `SQLException.getErrorCode()`
(nunca pela mensagem) e traduz para status HTTP e payload padronizado.

| Codigo | Constante no package | Chave na API | Quando acontece | HTTP |
|---|---|---|---|---|
| -20001 | `c_err_wallet_not_found` | `WALLET_NOT_FOUND` | carteira inexistente | 404 |
| -20002 | `c_err_invalid_amount` | `INVALID_AMOUNT` | valor nulo, menor ou igual a zero, com mais de 2 casas ou acima de 99999999999999999.99 | 422 |
| -20003 | `c_err_invalid_type` | `INVALID_TRANSACTION_TYPE` | tipo diferente de `CREDIT` ou `DEBIT` | 422 |
| -20004 | `c_err_insufficient_funds` | `INSUFFICIENT_FUNDS` | debito deixaria o saldo negativo | 422 |
| -20005 | `c_err_idempotency_conflict` | `IDEMPOTENCY_KEY_CONFLICT` | chave ja usada na carteira para tipo ou valor diferente | 409 |
| -20006 | `c_err_ledger_immutable` | `LEDGER_IMMUTABLE` | tentativa de alterar, apagar ou forjar lancamento, alterar a trilha de auditoria, trocar o id de uma carteira ou truncar essas tabelas (so acontece por SQL direto no banco; os triggers usam o literal) | 500 |
| -20007 | `c_err_wallet_locked` | `WALLET_LOCKED` | outra operacao segurou o lock da carteira por mais de 5 segundos | 503 + `Retry-After` |
| -20008 | `c_err_invalid_idempotency_key` | `INVALID_IDEMPOTENCY_KEY` | chave ausente ou fora de `[A-Za-z0-9_-]{1,64}` | 400 |
| -20009 | `c_err_invalid_holder` | `INVALID_HOLDER` | nome do titular com mais de 120 caracteres | 422 |
| -20010 | `c_err_balance_limit_exceeded` | `BALANCE_LIMIT_EXCEEDED` | credito levaria o saldo acima de 99999999999999999.99 | 422 |

## Codigos que nao vem do banco

| Chave na API | Quando acontece | HTTP |
|---|---|---|
| `VALIDATION_ERROR` | parametro fora da faixa aceita (ex.: `size=101`, `page=-1`) ou campo obrigatorio ausente no corpo (com a lista `errors`) | 400 |
| `INVALID_PERIOD` | `from` igual ou depois de `to`, ou data fora dos anos 0001 a 9999 | 400 |
| `MALFORMED_REQUEST` | valor que nem da para interpretar (ex.: `size=muitos`, id nao numerico na rota, data fora do ISO-8601, JSON invalido) | 400 |
| `UNAUTHORIZED` | header `X-API-Key` ausente ou com chave errada (mesma resposta nos dois casos; header `WWW-Authenticate: ApiKey header="X-API-Key"`). Sem chave, qualquer rota nao publica responde 401, inclusive as inexistentes | 401 |
| `FORBIDDEN` | reservado: hoje todo cliente autenticado tem o mesmo acesso, entao nao acontece | 403 |
| `NOT_FOUND` | rota inexistente | 404 |
| `METHOD_NOT_ALLOWED` | metodo HTTP nao suportado na rota | 405 |
| `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` diferente de JSON | 415 |
| `PAYLOAD_TOO_LARGE` | corpo acima de 16 KB | 413 |
| `SERVICE_BUSY` | nenhuma conexao livre no pool em 5 s (ex.: muitas requisicoes esperando o lock de uma carteira) | 503 + `Retry-After` |
| `NOT_ACCEPTABLE` | `Accept` que exclui JSON | 406 |
| `REQUEST_ERROR` | outro erro 4xx do proprio Spring, sem codigo especifico | 4xx |
| `INTERNAL_ERROR` | qualquer erro nao previsto | 500 |

## Criterio dos status

- **400**: a requisicao esta malformada e da para saber sem consultar o estado do sistema (JSON invalido, campo
  ou header obrigatorio ausente, chave fora do formato).
- **401**: sem API key valida. Vem antes de qualquer outra verificacao.
- **404**: o recurso nao existe.
- **409**: conflito com algo que ja existe (a chave de idempotencia ja foi usada para outra operacao).
- **422**: a requisicao e bem formada, mas viola uma regra de negocio (valor invalido, saldo insuficiente).
- **503**: contencao passageira; o cliente pode repetir a mesma requisicao, com a mesma chave, sem risco de
  aplicar duas vezes.

Erros que nao estao no catalogo viram 500 generico: a resposta nao traz mensagem do Oracle e o detalhe fica so no
log, junto com o `traceId` devolvido ao cliente.

## Detalhes de implementacao

- O timeout do `SELECT ... FOR UPDATE WAIT 5` aparece como `ORA-30006` em versoes anteriores e como `ORA-00054` no
  23ai; o package trata os dois e devolve `-20007`.
- Replay (mesma chave, mesmo tipo e mesmo valor) nao e erro: o package devolve o lancamento original com a flag
  `o_replayed = 'Y'`. A comparacao do valor e numerica (`25` e `25.00` sao o mesmo valor).
