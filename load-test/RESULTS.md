# Resultado do teste de carga

Execucao de referencia, na stack do `docker compose` (Oracle Free, migrations e API em containers) numa maquina de
desenvolvimento com Windows 11 e Docker Desktop.

## Carga e consistencia (k6)

```
docker compose --profile load run --rm k6
```

```
Resultado: PASSOU

Requisicoes HTTP ........ 2019 (260.3 req/s)
Latencia p50/p95/max .... 148.6 / 382.7 / 1147.5 ms
Creditos aplicados ...... 820 (esperado 820)
Debitos aplicados ....... 681
Debitos recusados (422) . 319
Replays (200) ........... 180 (esperado 180)
Debitos 201 - extrato ... 0 (esperado 0)
Respostas inesperadas ... 0
Retries apos 503 ........ 0
Verificacoes no fim ..... 7 executadas (esperado 7)
```

As 2000 operacoes foram feitas na mesma carteira, com 50 usuarios virtuais simultaneos.

- Cada chave repetida foi aplicada uma unica vez.
- Cada debito respondido como aplicado esta no extrato, e nenhum recusado esta.
- Refeito lancamento a lancamento, o saldo nunca fica negativo e bate com o saldo da carteira.

**O que muda e o que nao muda entre execucoes:**

| Tipo de numero | Comportamento |
|---|---|
| Fixos: 820 creditos aplicados e 180 replays | os thresholds exigem exatamente esses valores |
| Debitos aplicados x recusados (681/319 nesta execucao) | variam com a ordem em que as requisicoes chegam, mas sempre somam 1000 |
| Saldo final | sempre `100,00 + 8.100,00 - 12,00 x debitos aplicados` (aqui, 28,00) |
| Desempenho (req/s, latencia) | depende da maquina; em execucoes diferentes ficou entre 250 e 320 req/s |

As requisicoes por segundo refletem o pior caso: todas disputam o lock de uma unica carteira, entao sao serializadas
pelo banco. Carteiras diferentes nao disputam esse lock.

## Trilha de auditoria

```
bash load-test/verify-audit.sh 1
```

```
ok    uma linha de auditoria por lancamento do extrato (1502)
ok    toda alteracao foi um UPDATE vindo do PKG_WALLET (0)
ok    cada linha comeca onde a anterior terminou (0)
ok    soma das variacoes = saldo da carteira (28.00)
ok    cada linha aponta para o lancamento certo, com o mesmo tipo e o mesmo saldo apos
Auditoria: OK
```

1502 = 1 credito inicial + 820 creditos + 681 debitos.

## O teste pega o erro que promete pegar

Para conferir que as verificacoes nao passam por acaso, rodei o mesmo teste contra uma versao do `PKG_WALLET` sem o
`SELECT ... FOR UPDATE` (o lock da carteira). O k6 terminou com codigo 99:

```
Resultado: FALHOU
Replays (200) ........... 176 (esperado 180)
Respostas inesperadas ... 4
Thresholds violados: checks{fase:consistencia}, replays, unexpected_status
Verificacoes que falharam:
  - balance_after coerente lancamento a lancamento (1 falhas)
  - saldo nunca negativo (1 falhas)
  - saldo da carteira = soma do extrato (1 falhas)
```

O que aconteceu, peca por peca:

- **Perda de atualizacao.** Sem o lock, sessoes concorrentes leram o mesmo saldo e decidiram sobre ele. Debitos foram
  aceitos com base num saldo ja desatualizado, e uma gravacao de saldo sobrescreveu a outra. Refazendo o saldo pelo
  extrato, ele chega a ficar negativo e nao bate com o saldo da carteira. O `CHECK (balance >= 0)` nao pega isso,
  porque cada valor gravado, isoladamente, e um saldo valido.
- **A chave repetida nao foi aplicada duas vezes:** a verificacao "nenhuma chave aplicada mais de uma vez" passou. Quem
  segurou foi a `UNIQUE (wallet_id, idempotency_key)`. A segunda gravacao da mesma chave caiu em `ORA-00001`, que esta
  fora do catalogo de erros e virou `500`. Essas sao as 4 respostas inesperadas, e por isso houve 176 replays em vez de
  180. E a defesa em profundidade funcionando: sem o lock, a idempotencia ainda nao quebrou, mas o cliente recebeu um
  erro em vez do replay.
- A conferencia da auditoria tambem falhou: o numero de linhas e a correspondencia com o extrato nao batem.

Depois o package foi restaurado; os resultados das secoes anteriores sao da versao correta.
