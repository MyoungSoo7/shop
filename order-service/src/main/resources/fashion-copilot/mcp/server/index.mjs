#!/usr/bin/env node
/**
 * fashion-copilot MCP server (stdio, zero-dependency).
 *
 * order-service 가 이미 노출하는 읽기 전용 API/메트릭만 프록시한다.
 * - 쓰기 API 는 어떤 것도 라우팅하지 않는다 (read-only by construction).
 * - 응답은 서버 측에서 PII(배송지/연락처 포함) 마스킹 후 에이전트에 전달한다.
 * - 금액은 JSON number 로 재해석하지 않고 원문 그대로(문자열 포함) 넘긴다.
 *
 * env:
 *   ORDER_BASE_URL      (default http://localhost:8088)
 *   INTERNAL_API_KEY    (선택 — order /internal/recon/* 용 X-Internal-Api-Key)
 *   COPILOT_ADMIN_TOKEN (선택 — actuator 인증 환경용 Bearer JWT)
 */

import { createInterface } from 'node:readline';

const ORDER = (process.env.ORDER_BASE_URL ?? 'http://localhost:8088').replace(/\/$/, '');
const ADMIN_TOKEN = process.env.COPILOT_ADMIN_TOKEN ?? '';
const INTERNAL_KEY = process.env.INTERNAL_API_KEY ?? '';

// ── PII masking (키 이름 기반 — 금액 필드 오탐 방지) ─────────────────────────
const PII_KEY = /(account|acct|resident|ssn|card.*(no|num)|phone|mobile|address|recipient|receiver|zipcode|계좌|주민|카드번호|연락처|배송지|수취인|주소)/i;

function maskValue(v) {
  const s = String(v);
  if (s.length <= 4) return '****';
  return '*'.repeat(s.length - 4) + s.slice(-4);
}

function deepMask(node) {
  if (Array.isArray(node)) return node.map(deepMask);
  if (node && typeof node === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(node)) {
      out[k] = PII_KEY.test(k) && (typeof v === 'string' || typeof v === 'number')
        ? maskValue(v)
        : deepMask(v);
    }
    return out;
  }
  return node;
}

// ── HTTP (GET only — 이 서버는 구조적으로 읽기 전용) ─────────────────────────
async function getJson(base, path, { internal = false } = {}) {
  const headers = { Accept: 'application/json' };
  if (internal && INTERNAL_KEY) headers['X-Internal-Api-Key'] = INTERNAL_KEY;
  else if (ADMIN_TOKEN) headers['Authorization'] = `Bearer ${ADMIN_TOKEN}`;

  const res = await fetch(base + path, { headers, signal: AbortSignal.timeout(10_000) });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`${base}${path} → HTTP ${res.status}: ${text.slice(0, 300)}`);
  }
  try { return deepMask(JSON.parse(text)); } catch { return text; }
}

async function getPrometheus(base, metricPrefixes) {
  const res = await fetch(base + '/actuator/prometheus', {
    headers: ADMIN_TOKEN ? { Authorization: `Bearer ${ADMIN_TOKEN}` } : {},
    signal: AbortSignal.timeout(10_000),
  });
  if (!res.ok) throw new Error(`${base}/actuator/prometheus → HTTP ${res.status}`);
  const body = await res.text();
  const lines = body.split('\n').filter(l =>
    !l.startsWith('#') && metricPrefixes.some(p => l.startsWith(p)));
  return lines.map(l => {
    const sp = l.lastIndexOf(' ');
    return { metric: l.slice(0, sp), value: l.slice(sp + 1) };
  });
}

// 카운터 합산 (건수 전용 — 금액 메트릭에는 사용하지 않는다)
function sumCounters(rows, prefix) {
  return rows
    .filter(r => r.metric.startsWith(prefix))
    .reduce((acc, r) => acc + (Number(r.value) || 0), 0);
}

function ratio(part, whole) {
  return whole > 0 ? (part / whole).toFixed(4) : null;
}

// ── 로컬 시뮬레이션 공통 (BigInt — money-safety: float 금지, KRW 정수) ───────
function toKrw(name, v, { required = true } = {}) {
  if (v === undefined || v === null || v === '') {
    if (required) throw new Error(`${name} 은(는) 필수입니다`);
    return null;
  }
  if (!/^\d+$/.test(String(v))) throw new Error(`${name} 은(는) KRW 정수(원 단위)여야 합니다: ${v}`);
  return BigInt(v);
}

// ── coupon_simulate — Coupon.calculateDiscount / calculateDiscountForRefund 미러
// 근거: coupon.domain.CouponType (FIXED=min, PERCENTAGE=FLOOR·100% 초과 금지),
//       coupon.domain.Coupon.calculateDiscount (절사 후 maxDiscountAmount 클램프),
//       Coupon.calculateDiscountForRefund (총할인×환불액÷주문액 FLOOR)
function couponSimulate(args) {
  const type = String(args.type ?? '').toUpperCase();
  if (type !== 'FIXED' && type !== 'PERCENTAGE') throw new Error(`unknown type: ${args.type} (FIXED|PERCENTAGE)`);
  const order = toKrw('orderAmount', args.orderAmount);
  const value = toKrw('discountValue', args.discountValue);
  const maxCap = toKrw('maxDiscountAmount', args.maxDiscountAmount, { required: false });
  const minOrder = toKrw('minOrderAmount', args.minOrderAmount, { required: false });

  if (minOrder !== null && order < minOrder) {
    return {
      input: { type, orderAmount: order.toString(), discountValue: value.toString() },
      eligible: false,
      discount: '0',
      paidAmount: order.toString(),
      note: `minOrderAmount(${minOrder}) 미달 — 쿠폰 적용 불가 (Coupon.validate)`,
    };
  }

  let raw;
  if (type === 'FIXED') {
    raw = value < order ? value : order; // 주문액 초과 할인 금지
  } else {
    if (value > 100n) throw new Error('PERCENTAGE 는 100 을 초과할 수 없습니다 (Coupon 생성 검증)');
    raw = (order * value) / 100n; // BigInt 나눗셈 = FLOOR (코드베이스 표준 절사)
  }
  const discount = maxCap !== null && raw > maxCap ? maxCap : raw; // 절사 후 상한 클램프
  const paid = order - discount;

  const result = {
    input: {
      type,
      orderAmount: order.toString(),
      discountValue: value.toString(),
      maxDiscountAmount: maxCap === null ? null : maxCap.toString(),
      minOrderAmount: minOrder === null ? null : minOrder.toString(),
    },
    eligible: true,
    rawDiscount: raw.toString(),
    discount: discount.toString(),
    clampedByMax: maxCap !== null && raw > maxCap,
    paidAmount: paid.toString(),
    rounding: 'FLOOR (절사) — CouponType.PERCENTAGE 표준',
    basis: 'CouponType.rawDiscount → maxDiscountAmount 클램프 (Coupon.calculateDiscount)',
  };

  const refund = toKrw('refundAmount', args.refundAmount, { required: false });
  if (refund !== null) {
    if (refund > order) throw new Error('refundAmount 는 orderAmount 를 초과할 수 없습니다');
    const prorated = order > 0n ? (discount * refund) / order : 0n; // FLOOR
    result.refundProration = {
      refundAmount: refund.toString(),
      proratedDiscount: prorated.toString(),
      cashRefund: (refund - prorated).toString(),
      basis: '총할인 × 환불액 ÷ 주문액 FLOOR (Coupon.calculateDiscountForRefund) — 안분 생략 시 마진 누수',
    };
  }
  return result;
}

// ── refund_simulate — RefundPaymentUseCase 규칙 미러 ─────────────────────────
// 근거: 전액=자동 멱등키 payment-{id}-full, 부분=호출자 필수(MissingIdempotencyKeyException),
//       초과 시 RefundExceedsPaymentException, 전액 도달 시에만 주문 REFUNDED
function refundSimulate(args) {
  const payment = toKrw('paymentAmount', args.paymentAmount);
  const refunded = toKrw('refundedAmount', args.refundedAmount ?? '0');
  if (refunded > payment) throw new Error('refundedAmount 가 paymentAmount 를 초과 — 데이터 이상(초과 환불) 의심, 원본 조회 필요');
  const refundable = payment - refunded;

  const isFull = args.requestAmount === undefined || args.requestAmount === null || args.requestAmount === '';
  const request = isFull ? refundable : toKrw('requestAmount', args.requestAmount);
  const exceeds = request > refundable;
  const allowed = request > 0n && !exceeds;
  const fullReached = allowed && refunded + request === payment;
  const paymentId = String(args.paymentId ?? '{paymentId}');

  return {
    input: {
      paymentAmount: payment.toString(),
      refundedAmount: refunded.toString(),
      requestAmount: isFull ? null : request.toString(),
    },
    mode: isFull ? 'FULL' : 'PARTIAL',
    refundableAmount: refundable.toString(),
    refundAmount: request.toString(),
    allowed,
    ...(exceeds && { rejection: `RefundExceedsPaymentException — 요청액(${request}) > 환불 가능액(${refundable})` }),
    ...(!exceeds && request === 0n && { rejection: '환불 가능액 0 — 이미 전액 환불됨' }),
    idempotencyKey: isFull
      ? { policy: 'AUTO', key: `payment-${paymentId}-full` }
      : { policy: 'CALLER_REQUIRED', note: '부분 환불은 호출자가 Idempotency-Key 를 반드시 제공 (MissingIdempotencyKeyException)' },
    orderStatusAfter: allowed
      ? (fullReached ? 'REFUNDED (전액 도달 시에만 전이)' : '변경 없음 (부분 환불은 주문 상태 불변)')
      : '변경 없음 (환불 거부)',
    basis: 'RefundPaymentUseCase 3-Phase — 락 안 재확정(loadByIdForUpdate) 후 이 규칙으로 판정',
  };
}

// ── Tool registry ────────────────────────────────────────────────────────────
const TOOLS = [
  {
    name: 'refund_recon',
    description: '반품·환불 일일 대사 — 해당 날짜의 CAPTURED 결제 vs COMPLETED 환불의 금액·건수. 환불 금액/건수 급증은 반품 이상(사이즈 이슈·어뷰징) 신호. (order /internal/recon/daily-totals + daily-counts)',
    inputSchema: {
      type: 'object',
      properties: { date: { type: 'string', description: 'ISO date, e.g. 2026-07-07' } },
      required: ['date'],
    },
    run: async ({ date }) => {
      const d = encodeURIComponent(date);
      const [totals, counts] = await Promise.allSettled([
        getJson(ORDER, `/internal/recon/daily-totals?date=${d}`, { internal: true }),
        getJson(ORDER, `/internal/recon/daily-counts?date=${d}`, { internal: true }),
      ]);
      return {
        date,
        totals: totals.status === 'fulfilled' ? totals.value : { error: String(totals.reason?.message ?? totals.reason) },
        counts: counts.status === 'fulfilled' ? counts.value : { error: String(counts.reason?.message ?? counts.reason) },
        note: 'totals=금액 축, counts=건수 축. 두 축이 함께 어긋나면 데이터 유실, 금액만 어긋나면 부분환불/할인 안분 축 조사',
      };
    },
  },
  {
    name: 'refund_health',
    description: '환불 파이프라인 건강도 — 요청/완료/실패 카운터, 실패율, 멱등키 재사용(재시도 폭풍 신호), 처리시간. failed{reason} 태그 분포로 원인 축을 좁힌다. (Prometheus refund_*)',
    inputSchema: { type: 'object', properties: {} },
    run: async () => {
      const rows = await getPrometheus(ORDER, [
        'refund_requests', 'refund_completed', 'refund_failed',
        'refund_idempotency_key_reuse', 'refund_amount', 'refund_processing_duration',
      ]);
      const requests = sumCounters(rows, 'refund_requests');
      const completed = sumCounters(rows, 'refund_completed');
      const failed = sumCounters(rows, 'refund_failed');
      const idemReuse = sumCounters(rows, 'refund_idempotency_key_reuse');
      return {
        summary: {
          requests, completed, failed,
          failureRate: ratio(failed, requests),
          idempotencyKeyReuse: idemReuse,
          note: 'failureRate 상승 → failed{reason} 분포 확인. idempotencyKeyReuse 급증 = 클라이언트 재시도 폭풍(멱등 방어는 동작 중)',
        },
        metrics: rows,
      };
    },
  },
  {
    name: 'stock_pulse',
    description: '재고 차감 성공/거절 펄스 — 드랍 중 rejected 급증=품절 러시(정상 방어), 평시 rejected 지속=품절 상품 노출 버그 의심. variant/product 두 축 모두 요약. (Prometheus variant_stock_decrease_*, product_stock_decrease_*)',
    inputSchema: { type: 'object', properties: {} },
    run: async () => {
      const rows = await getPrometheus(ORDER, ['variant_stock_decrease', 'product_stock_decrease']);
      const axis = (p) => {
        const success = sumCounters(rows, `${p}_success`);
        const rejected = sumCounters(rows, `${p}_rejected`);
        return { success, rejected, rejectedRatio: ratio(rejected, success + rejected) };
      };
      return {
        variant: axis('variant_stock_decrease'),
        product: axis('product_stock_decrease'),
        metrics: rows,
        note: 'rejected = 원자적 조건부 UPDATE(stock >= qty) 거절. 오버셀 0 의 증거이자 품절/노출 상태의 신호',
      };
    },
  },
  {
    name: 'coupon_simulate',
    description: '쿠폰 할인 dry-run 계산기 — FIXED(min)/PERCENTAGE(FLOOR 절사)·상한 클램프·최소주문·환불 안분을 도메인 정책(CouponType, Coupon.calculateDiscount[ForRefund])대로 계산. 네트워크 불필요, 코드 리뷰 시 기대값 검증용.',
    inputSchema: {
      type: 'object',
      properties: {
        type: { type: 'string', enum: ['FIXED', 'PERCENTAGE'] },
        discountValue: { type: 'string', description: 'FIXED=할인액(원), PERCENTAGE=할인율(0~100)' },
        orderAmount: { type: 'string', description: 'KRW 정수 주문 금액(원), e.g. "33333"' },
        maxDiscountAmount: { type: 'string', description: '선택 — 할인 상한(원)' },
        minOrderAmount: { type: 'string', description: '선택 — 최소 주문 금액(원)' },
        refundAmount: { type: 'string', description: '선택 — 부분 환불액(원). 주면 할인 안분·현금 환불액까지 계산' },
      },
      required: ['type', 'discountValue', 'orderAmount'],
    },
    run: (args) => couponSimulate(args),
  },
  {
    name: 'refund_simulate',
    description: '환불 dry-run 판정기 — 결제액/기환불액/요청액으로 환불 가능액·전액/부분 판정·멱등키 요구·주문 상태 전이를 도메인 규칙(RefundPaymentUseCase)대로 계산. 네트워크 불필요.',
    inputSchema: {
      type: 'object',
      properties: {
        paymentAmount: { type: 'string', description: 'KRW 정수 결제 금액(원)' },
        refundedAmount: { type: 'string', description: '기존 환불 누계(원), 기본 "0"' },
        requestAmount: { type: 'string', description: '선택 — 환불 요청액(원). 생략 시 전액 환불(FULL)' },
        paymentId: { type: 'string', description: '선택 — 자동 멱등키 표기용' },
      },
      required: ['paymentAmount'],
    },
    run: (args) => refundSimulate(args),
  },
];

// ── JSON-RPC over stdio (newline-delimited) ──────────────────────────────────
function send(msg) { process.stdout.write(JSON.stringify(msg) + '\n'); }

function toolResult(id, payload, isError = false) {
  send({
    jsonrpc: '2.0', id,
    result: {
      content: [{ type: 'text', text: typeof payload === 'string' ? payload : JSON.stringify(payload, null, 2) }],
      isError,
    },
  });
}

const rl = createInterface({ input: process.stdin, terminal: false });
rl.on('line', async (line) => {
  line = line.trim();
  if (!line) return;
  let req;
  try { req = JSON.parse(line); } catch { return; }
  const { id, method, params } = req;

  try {
    if (method === 'initialize') {
      send({
        jsonrpc: '2.0', id,
        result: {
          protocolVersion: params?.protocolVersion ?? '2025-03-26',
          capabilities: { tools: {} },
          serverInfo: { name: 'fashion-copilot', version: '0.1.0' },
        },
      });
    } else if (method === 'notifications/initialized' || method?.startsWith('notifications/')) {
      // no response for notifications
    } else if (method === 'tools/list') {
      send({
        jsonrpc: '2.0', id,
        result: { tools: TOOLS.map(({ name, description, inputSchema }) => ({ name, description, inputSchema })) },
      });
    } else if (method === 'tools/call') {
      const tool = TOOLS.find(t => t.name === params?.name);
      if (!tool) return toolResult(id, `unknown tool: ${params?.name}`, true);
      try {
        toolResult(id, await tool.run(params?.arguments ?? {}));
      } catch (e) {
        toolResult(id, `tool error: ${e.message}`, true);
      }
    } else if (id !== undefined) {
      send({ jsonrpc: '2.0', id, error: { code: -32601, message: `method not found: ${method}` } });
    }
  } catch (e) {
    if (id !== undefined) send({ jsonrpc: '2.0', id, error: { code: -32603, message: e.message } });
  }
});
