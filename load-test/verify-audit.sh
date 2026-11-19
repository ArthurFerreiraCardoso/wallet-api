#!/usr/bin/env bash
# Confere a trilha de auditoria de uma carteira contra o saldo e o extrato da API.
# A API nao enxerga o schema WALLET_AUDIT (de proposito), entao a trilha e lida direto no banco, como WALLET_AUDIT.
#
# Uso, com o compose no ar: bash load-test/verify-audit.sh <id-da-carteira>
# Usa WALLET_AUDIT_PASSWORD, WALLET_API_KEY e APP_PORT do ambiente; o que nao estiver definido vem do arquivo de
# ambiente da raiz do projeto.
set -euo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."

WALLET_ID="${1:?informe o id da carteira (aparece no fim da saida do k6)}"
[[ "$WALLET_ID" =~ ^[0-9]+$ ]] || { echo "id invalido: $WALLET_ID" >&2; exit 1; }

if [ -f .env ]; then
  # Le so as tres variaveis usadas, ignora CR (arquivo editado no Windows) e nao sobrescreve o ambiente.
  while IFS='=' read -r name value; do
    case "$name" in WALLET_AUDIT_PASSWORD|WALLET_API_KEY|APP_PORT) ;; *) continue ;; esac
    [ -n "${!name:-}" ] || export "$name=$value"
  done < <(tr -d '\r' < .env)
fi
: "${WALLET_AUDIT_PASSWORD:?defina WALLET_AUDIT_PASSWORD}"
: "${WALLET_API_KEY:?defina WALLET_API_KEY}"
API="http://localhost:${APP_PORT:-8080}"

api_get() {
  # A chave vai pela entrada padrao (-H @-), e nao pela linha de comando.
  curl -fsS -H @- "${API}$1" <<< "X-API-Key: ${WALLET_API_KEY}"
}

# --- Trilha de auditoria (como WALLET_AUDIT) ---
# Primeira linha: totais; demais linhas: transaction_id;transaction_type;balance_after, na ordem de gravacao.
# bash -c: no Git Bash do Windows, um argumento solto como /nolog seria convertido num caminho do Windows.
audit=$(docker compose exec -T oracle bash -c 'sqlplus -s /nolog' <<SQL
SET DEFINE OFF
WHENEVER SQLERROR EXIT FAILURE
WHENEVER OSERROR EXIT FAILURE
CONNECT WALLET_AUDIT/"${WALLET_AUDIT_PASSWORD}"@//localhost:1521/FREEPDB1
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 VERIFY OFF LINESIZE 200 TRIMSPOOL ON
SELECT 'T;' || COUNT(*) || ';' ||
       SUM(CASE WHEN change_source <> 'PKG_WALLET' OR dml_operation <> 'UPDATE' THEN 1 ELSE 0 END) || ';' ||
       SUM(CASE WHEN prev_after IS NOT NULL AND balance_before <> prev_after THEN 1 ELSE 0 END) || ';' ||
       TO_CHAR(SUM(balance_after - balance_before), 'FM9999999999999990.00')
  FROM (SELECT change_source, dml_operation, balance_before, balance_after,
               LAG(balance_after) OVER (ORDER BY id) AS prev_after
          FROM balance_audit
         WHERE wallet_id = ${WALLET_ID});
SELECT transaction_id || ';' || transaction_type || ';' || TO_CHAR(balance_after, 'FM9999999999999990.00')
  FROM balance_audit
 WHERE wallet_id = ${WALLET_ID}
 ORDER BY id;
EXIT
SQL
) || { echo "Falha ao consultar a auditoria (senha ou banco indisponivel?)" >&2; exit 1; }
audit=$(printf '%s\n' "$audit" | tr -d '\r' | sed '/^$/d')

totals=$(printf '%s\n' "$audit" | grep '^T;' || true)
[[ "$totals" =~ ^T\;[0-9]+\;[0-9]+\;[0-9]+\;-?[0-9]+\.[0-9]{2}$ ]] || { echo "Saida inesperada do banco: $totals" >&2; exit 1; }
IFS=';' read -r _ rows not_pkg chain_breaks delta_sum <<< "$totals"
audit_rows=$(printf '%s\n' "$audit" | grep -v '^T;')

# --- Extrato e saldo pela API ---
balance=$(api_get "/wallets/${WALLET_ID}" | sed -E 's/.*"balance":([0-9.]+).*/\1/')
balance=$(printf '%.2f' "$balance")
statement=""
page=0
while :; do
  body=$(api_get "/wallets/${WALLET_ID}/transactions?size=100&page=${page}")
  statement+=$(printf '%s' "$body" \
    | grep -oE '"id":[0-9]+,"walletId":[0-9]+,"type":"(CREDIT|DEBIT)","amount":[0-9.]+,"balanceAfter":[0-9.]+' \
    | sed -E 's/"id":([0-9]+),"walletId":[0-9]+,"type":"([A-Z]+)","amount":[0-9.]+,"balanceAfter":([0-9.]+)/\1;\2;\3/')$'\n'
  total_pages=$(printf '%s' "$body" | sed -E 's/.*"totalPages":([0-9]+).*/\1/')
  page=$((page + 1))
  [ "$page" -lt "$total_pages" ] || break
done
statement=$(printf '%s' "$statement" | sed '/^$/d' | sort -t';' -k1,1n)
entries=$(printf '%s\n' "$statement" | grep -c .)

# --- Conferencia ---
fail=0
verify() {
  if [ "$2" = "$3" ]; then echo "ok    $1 ($2)"; else echo "FALHA $1: obtido=$2 esperado=$3"; fail=1; fi
}
verify "uma linha de auditoria por lancamento do extrato" "$rows" "$entries"
verify "toda alteracao foi um UPDATE vindo do PKG_WALLET" "$not_pkg" "0"
verify "cada linha comeca onde a anterior terminou" "$chain_breaks" "0"
verify "soma das variacoes = saldo da carteira" "$delta_sum" "$balance"
if [ "$audit_rows" = "$statement" ]; then
  echo "ok    cada linha aponta para o lancamento certo, com o mesmo tipo e o mesmo saldo apos"
else
  echo "FALHA linhas de auditoria nao batem com o extrato (lancamento;tipo;saldo apos):"
  diff <(printf '%s\n' "$audit_rows") <(printf '%s\n' "$statement") | head -10
  fail=1
fi

[ "$fail" -eq 0 ] && echo "Auditoria: OK" || { echo "Auditoria: FALHOU"; exit 1; }
