# Teste de carga e de consistencia (k6)

O teste dispara 2000 operacoes, com 50 usuarios virtuais ao mesmo tempo, todas na **mesma carteira**, e no fim confere
que saldo, extrato e trilha de auditoria batem exatamente com o esperado.

## Como rodar

Com a stack no ar (`docker compose up -d --build`, servico `app` healthy):

```bash
docker compose --profile load run --rm k6         # carga + verificacoes de consistencia
bash load-test/verify-audit.sh <id-da-carteira>   # trilha de auditoria x extrato (o id aparece no fim da saida do k6)
```

- O k6 roda pela imagem oficial (`grafana/k6:1.8.3`), sem instalar nada, dentro da rede do compose: chama
  `http://app:8080` e le o script montado somente leitura.
- O resumo sai no console. Os dois comandos terminam com codigo diferente de zero se qualquer verificacao falhar.
- O `verify-audit.sh` precisa de Bash (Linux, macOS ou Git Bash no Windows). Usa `WALLET_AUDIT_PASSWORD`,
  `WALLET_API_KEY` e `APP_PORT` do ambiente; o que nao estiver definido vem do arquivo de ambiente da raiz.

## Como o teste funciona

**Operacoes conhecidas de antemao.** Cada iteracao `i` (de 0 a 1999) tem uma operacao fixa, calculada pelo proprio
numero (`operationFor(i)` em `wallet-load.js`):

| Iteracoes | Operacao | Chave |
|---|---|---|
| multiplos de 10 (200) | credito de 5,00 | `repetida-0` a `repetida-19`: cada chave e enviada 10 vezes |
| impares (1000) | debito de 12,00 | unica por iteracao |
| pares restantes (800) | credito de 10,00 | unica por iteracao |

- **Debitos alem do que entra:** com saldo inicial de 100,00, os debitos (12.000,00) passam muito do que entra
  (8.100,00). Boa parte precisa ser recusada por saldo insuficiente, e a carteira fica o tempo todo perto de zero, que e
  onde um erro de concorrencia apareceria.
- **Mesma chave ao mesmo tempo:** os 50 usuarios virtuais pegam as iteracoes de uma fila comum, entao as primeiras 50
  saem juntas. Entre elas ja ha 5 copias de `repetida-0`, que disputam a mesma chave no mesmo instante.

**Durante o teste**, o k6 classifica cada resposta: `201` aplicada, `200` replay, `422 INSUFFICIENT_FUNDS` recusada.
Qualquer outra resposta conta como inesperada.
- Diante de `503`, ele repete a mesma requisicao com a mesma chave, respeitando o `Retry-After`, como um cliente correto
  faria.
- Para cada `201`/`200`, confere que o corpo descreve a operacao enviada (chave, tipo, valor) e que o header
  `Idempotent-Replayed` aparece so no replay.

**No fim (teardown)**, o k6 le a carteira e o extrato completo pela API e confere:

1. o extrato foi lido por inteiro (todas as paginas);
2. nenhuma chave aplicada mais de uma vez;
3. todo credito aplicado, inclusive cada chave repetida (credito nunca e recusado);
4. cada lancamento tem o tipo e o valor da operacao daquela chave;
5. refazendo o saldo lancamento a lancamento, cada `balanceAfter` bate;
6. o saldo nunca fica negativo;
7. o saldo da carteira e a soma do extrato.

**Thresholds** (qualquer um que falhe derruba o teste):

| Threshold | Exigencia |
|---|---|
| respostas inesperadas | zero |
| verificacoes de resposta e as 7 do fim | todas executadas e todas aprovadas |
| creditos aplicados | exatamente 820 (800 unicos + 20 chaves repetidas) |
| replays | exatamente 180 (9 por chave repetida) |
| `debit_mismatch` | zero |

O `debit_mismatch` soma 1 a cada debito respondido com `201` e, no fim, subtrai um por debito encontrado no extrato. Se o
cliente ouviu "aplicado" e o debito nao esta la, ou ouviu "recusado" e ele esta, a conta nao fecha em zero.

**Trilha de auditoria.** A API nao enxerga o schema de auditoria, de proposito. Por isso o `verify-audit.sh` consulta a
`BALANCE_AUDIT` como `WALLET_AUDIT`, le o extrato pela API e confere:

- uma linha de auditoria por lancamento;
- todas sao `UPDATE` vindos do `PKG_WALLET`;
- cada linha comeca onde a anterior terminou;
- a soma das variacoes e o saldo da carteira;
- cada linha aponta para o lancamento certo, com o mesmo tipo e o mesmo saldo apos (comparacao linha a linha).

## Resultado

Ver [RESULTS.md](RESULTS.md): execucao de referencia, trilha de auditoria e o mesmo teste rodado contra o package sem o
lock da carteira, que falha.
