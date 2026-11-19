// Teste de carga e de consistencia: milhares de operacoes concorrentes na MESMA carteira.
//
// Cada iteracao i do teste tem uma operacao fixa, calculada por operationFor(i): tipo, valor e chave. Assim, no fim
// do teste, o teardown sabe exatamente o que deveria estar no extrato e confere contra a API. A trilha de auditoria
// (que a API nao enxerga) e conferida depois por load-test/verify-audit.sh.

import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.WALLET_API_KEY;
const ITERATIONS = 2000;
const INITIAL_CENTS = 10000; // 100.00
const DUPLICATED_KEYS = ITERATIONS / 100; // 20 chaves, cada uma enviada 10 vezes
const CONSISTENCY_CHECKS = 7;

export const options = {
  scenarios: {
    // shared-iterations: os 50 VUs pegam as 2000 iteracoes de uma fila comum. As primeiras 50 (i = 0..49) saem
    // juntas; entre elas ja ha 5 copias de "repetida-0", que disputam a mesma chave ao mesmo tempo.
    mesma_carteira: {
      executor: 'shared-iterations',
      vus: 50,
      iterations: ITERATIONS,
      maxDuration: '3m',
    },
  },
  thresholds: {
    // Respostas fora de 200/201/422 (ou 503 que nao se resolveu nos retries) derrubam o teste.
    unexpected_status: ['count==0'],
    // Corpo de cada 201/200 confere com a operacao enviada; 200 sempre com Idempotent-Replayed.
    'checks{fase:respostas}': ['rate==1'],
    // As verificacoes do teardown: todas executadas e todas aprovadas.
    'checks{fase:consistencia}': ['rate==1'],
    consistency_checks_run: [`count==${CONSISTENCY_CHECKS}`],
    // Valores conhecidos de antemao: 800 creditos unicos + 20 chaves repetidas aplicadas uma vez; 9 replays por chave.
    applied_credits: ['count==820'],
    replays: [`count==${DUPLICATED_KEYS * 9}`],
    // Debitos que o cliente ouviu como aplicados (+1 a cada 201) menos debitos gravados no extrato (no teardown).
    // Tem de fechar em zero: nem debito "aplicado" que sumiu, nem debito "recusado" que ficou gravado.
    debit_mismatch: ['count==0'],
  },
};

const appliedCredits = new Counter('applied_credits');
const appliedDebits = new Counter('applied_debits');
const replays = new Counter('replays');
const rejected = new Counter('rejected_insufficient_funds');
const unexpected = new Counter('unexpected_status');
const retries503 = new Counter('retries_503');
const debitMismatch = new Counter('debit_mismatch');
const consistencyChecksRun = new Counter('consistency_checks_run');

const headers = (extra = {}) => Object.assign({ 'X-API-Key': API_KEY, 'Content-Type': 'application/json' }, extra);
const expected = { responseCallback: http.expectedStatuses(200, 201, 422) };

/** Operacao fixa da iteracao i (0..ITERATIONS-1). Valores em centavos para nao depender de ponto flutuante. */
export function operationFor(i) {
  if (i % 10 === 0) {
    return { type: 'CREDIT', cents: 500, key: `repetida-${Math.floor(i / 100)}` };
  }
  if (i % 2 === 1) {
    return { type: 'DEBIT', cents: 1200, key: `debito-${i}` };
  }
  return { type: 'CREDIT', cents: 1000, key: `credito-${i}` };
}

const toAmount = (cents) => (cents / 100).toFixed(2);
const toCents = (amount) => Math.round(Number(amount) * 100);

function getOk(path) {
  const res = http.get(`${BASE_URL}${path}`, { headers: headers() });
  if (res.status !== 200) {
    exec.test.abort(`GET ${path} respondeu ${res.status}: verificacao de consistencia impossivel`);
  }
  return res.json();
}

export function setup() {
  if (!API_KEY) {
    exec.test.abort('Defina WALLET_API_KEY');
  }
  const wallet = http.post(`${BASE_URL}/wallets`, JSON.stringify({ holderName: 'Teste de carga' }), { headers: headers() });
  if (wallet.status !== 201) {
    exec.test.abort(`Criacao da carteira respondeu ${wallet.status}`);
  }
  const walletId = wallet.json('id');
  const initial = http.post(`${BASE_URL}/wallets/${walletId}/transactions`,
    JSON.stringify({ type: 'CREDIT', amount: toAmount(INITIAL_CENTS) }), { headers: headers({ 'Idempotency-Key': 'inicial' }) });
  if (initial.status !== 201) {
    exec.test.abort(`Credito inicial respondeu ${initial.status}`);
  }
  return { walletId };
}

export default function (data) {
  const op = operationFor(exec.scenario.iterationInTest);
  const body = JSON.stringify({ type: op.type, amount: Number(toAmount(op.cents)) });

  // Cliente correto: diante de 503, repete a MESMA requisicao (mesma chave) depois do Retry-After.
  // Os 503 desta API (lock nao obtido, pool sem conexao) acontecem antes de gravar, entao o retry e seguro.
  let res;
  for (let attempt = 1; attempt <= 5; attempt++) {
    res = http.post(`${BASE_URL}/wallets/${data.walletId}/transactions`, body,
      Object.assign({ headers: headers({ 'Idempotency-Key': op.key }) }, expected));
    if (res.status !== 503) {
      break;
    }
    retries503.add(1);
    sleep(Number(res.headers['Retry-After']) || 1);
  }

  if (res.status === 201 || res.status === 200) {
    check(res, {
      'resposta descreve a operacao enviada': (r) => r.json('idempotencyKey') === op.key
        && r.json('type') === op.type && toCents(r.json('amount')) === op.cents,
      'Idempotent-Replayed presente so no replay': (r) => (r.headers['Idempotent-Replayed'] === 'true') === (r.status === 200),
    }, { fase: 'respostas' });
  }

  if (res.status === 201) {
    (op.type === 'CREDIT' ? appliedCredits : appliedDebits).add(1);
    if (op.type === 'DEBIT') {
      debitMismatch.add(1);
    }
  } else if (res.status === 200) {
    replays.add(1);
  } else if (res.status === 422 && op.type === 'DEBIT' && res.json('code') === 'INSUFFICIENT_FUNDS') {
    rejected.add(1);
  } else {
    unexpected.add(1, { status: String(res.status) });
  }
}

export function teardown(data) {
  const wallet = getOk(`/wallets/${data.walletId}`);
  const statement = [];
  let totalElements = 0;
  for (let page = 0; ; page++) {
    const body = getOk(`/wallets/${data.walletId}/transactions?size=100&page=${page}`);
    statement.push(...body.content);
    totalElements = body.totalElements;
    if (page + 1 >= body.totalPages) {
      break;
    }
  }
  statement.sort((a, b) => a.id - b.id);

  // Operacao esperada para cada chave.
  const expectedByKey = { inicial: { type: 'CREDIT', cents: INITIAL_CENTS } };
  for (let i = 0; i < ITERATIONS; i++) {
    const op = operationFor(i);
    expectedByKey[op.key] = op;
  }

  let running = 0;
  let neverNegative = true;
  let balanceAfterMatches = true;
  let everyEntryMatchesItsOperation = true;
  const seen = {};
  for (const entry of statement) {
    const cents = toCents(entry.amount);
    running += entry.type === 'CREDIT' ? cents : -cents;
    neverNegative = neverNegative && running >= 0;
    balanceAfterMatches = balanceAfterMatches && toCents(entry.balanceAfter) === running;
    const op = expectedByKey[entry.idempotencyKey];
    everyEntryMatchesItsOperation = everyEntryMatchesItsOperation && op !== undefined && op.type === entry.type && op.cents === cents;
    seen[entry.idempotencyKey] = (seen[entry.idempotencyKey] || 0) + 1;
  }
  const creditKeys = Object.keys(expectedByKey).filter((k) => expectedByKey[k].type === 'CREDIT');

  // Fecha a conta do debit_mismatch: cada 201 de debito somou 1; aqui sai 1 por debito gravado.
  debitMismatch.add(-statement.filter((e) => e.type === 'DEBIT').length);

  const verifications = {
    'extrato lido por inteiro (todas as paginas)': () => statement.length === totalElements,
    'nenhuma chave aplicada mais de uma vez': () => Object.values(seen).every((n) => n === 1),
    'todo credito aplicado (inclusive as chaves repetidas)': () => creditKeys.every((k) => seen[k] === 1),
    'cada lancamento tem o tipo e o valor da sua operacao': () => everyEntryMatchesItsOperation,
    'balance_after coerente lancamento a lancamento': () => balanceAfterMatches,
    'saldo nunca negativo': () => neverNegative,
    'saldo da carteira = soma do extrato': () => toCents(wallet.balance) === running,
  };
  for (const [name, verification] of Object.entries(verifications)) {
    check(null, { [name]: verification }, { fase: 'consistencia' });
    consistencyChecksRun.add(1);
  }

  console.log(`carteira=${data.walletId} saldo=${wallet.balance} lancamentos=${statement.length}`);
}

/** Resumo legivel no console, com o que falhou (se falhou). */
export function handleSummary(data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count || 0 : 0);
  const failedThresholds = Object.entries(data.metrics)
    .filter(([, metric]) => metric.thresholds && Object.values(metric.thresholds).some((t) => !t.ok))
    .map(([name]) => name);
  const failedChecks = [];
  const collect = (group) => {
    (group.checks || []).filter((c) => c.fails > 0).forEach((c) => failedChecks.push(`${c.name} (${c.fails} falhas)`));
    (group.groups || []).forEach(collect);
  };
  collect(data.root_group);
  const duration = data.metrics.http_req_duration.values;
  const reqs = data.metrics.http_reqs.values;

  const lines = [
    '',
    `Resultado: ${failedThresholds.length === 0 ? 'PASSOU' : 'FALHOU'}`,
    '',
    `Requisicoes HTTP ........ ${reqs.count} (${reqs.rate.toFixed(1)} req/s)`,
    `Latencia p50/p95/max .... ${duration.med.toFixed(1)} / ${duration['p(95)'].toFixed(1)} / ${duration.max.toFixed(1)} ms`,
    `Creditos aplicados ...... ${count('applied_credits')} (esperado 820)`,
    `Debitos aplicados ....... ${count('applied_debits')}`,
    `Debitos recusados (422) . ${count('rejected_insufficient_funds')}`,
    `Replays (200) ........... ${count('replays')} (esperado ${DUPLICATED_KEYS * 9})`,
    `Debitos 201 - extrato ... ${count('debit_mismatch')} (esperado 0)`,
    `Respostas inesperadas ... ${count('unexpected_status')}`,
    `Retries apos 503 ........ ${count('retries_503')}`,
    `Verificacoes no fim ..... ${count('consistency_checks_run')} executadas (esperado ${CONSISTENCY_CHECKS})`,
  ];
  if (failedThresholds.length > 0) {
    lines.push('', `Thresholds violados: ${failedThresholds.join(', ')}`);
  }
  if (failedChecks.length > 0) {
    lines.push('Verificacoes que falharam:', ...failedChecks.map((c) => `  - ${c}`));
  }
  return { stdout: `${lines.join('\n')}\n\n` };
}
