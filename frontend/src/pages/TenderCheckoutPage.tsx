import { useEffect, useMemo, useState } from 'react';
import { orderApi } from '@/api/order';
import { pointApi } from '@/api/point';
import { giftCardApi } from '@/api/giftCard';
import {
  tenderPaymentApi,
  AWAITS_DEPOSIT,
  INTERNAL_BALANCE,
  type TenderType,
  type TenderPaymentView,
} from '@/api/tenderPayment';
import { useAuth } from '@/contexts/useAuth';
import { apiErrorMessage } from '@/lib/apiError';
import Spinner from '@/components/Spinner';
import type { OrderResponse } from '@/types';

/**
 * 텐더 결제 — 포인트·상품권·카드를 나눠 내는 체크아웃.
 *
 * <p><b>왜 별도 화면인가</b>: 포인트·상품권 원장과 선점까지 백엔드가 다 갖췄는데도 그것을 부르는
 * 화면이 없어, 고객은 "내 포인트"를 볼 수만 있고 쓸 수는 없었다.
 *
 * <p>화면이 지키는 규칙 셋 — 셋 다 서버 불변식의 거울이다.
 * <ol>
 *   <li><b>합계는 주문 금액과 정확히 일치</b>해야 한다. 서버가 거절하기 전에 여기서 막는다.
 *   <li><b>잔액을 넘겨 배분할 수 없다.</b> 내부 잔액(포인트·상품권)은 넘기면 결제가 실패한다.
 *   <li><b>입금 대기 수단이 섞이면 즉시 결제가 아니다.</b> 그 사실을 누르기 <b>전에</b> 알린다 —
 *       누른 뒤에 알면 "왜 주문이 아직 미결제지"가 된다.
 * </ol>
 *
 * <p>잔액을 <b>합쳐 보여 주지 않는다</b>(내 잔액 화면과 같은 판단). 포인트와 상품권은 회계에서도
 * 다른 계정이고 사용 규칙도 다르다.
 */

const TENDER_LABEL: Record<TenderType, string> = {
  POINT: '포인트',
  GIFT_CARD: '상품권',
  CARD: '카드',
  BANK_TRANSFER: '무통장 입금',
  VIRTUAL_ACCOUNT: '가상계좌',
};

const ORDERABLE: TenderType[] = ['POINT', 'GIFT_CARD', 'CARD', 'VIRTUAL_ACCOUNT', 'BANK_TRANSFER'];

/** 결제 대상 — 아직 돈이 오가지 않은 주문만. */
const PAYABLE_STATUS = 'CREATED';

const won = (n: number) => `${n.toLocaleString()}원`;

export default function TenderCheckoutPage() {
  const { userId, loading: authLoading } = useAuth();

  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [selected, setSelected] = useState<OrderResponse | null>(null);
  const [pointAvailable, setPointAvailable] = useState(0);
  const [giftCardAvailable, setGiftCardAvailable] = useState(0);
  const [allocation, setAllocation] = useState<Partial<Record<TenderType, number>>>({});
  const [result, setResult] = useState<TenderPaymentView | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (authLoading) return;
    if (userId === null) {
      setLoading(false);
      return;
    }
    (async () => {
      try {
        const [orderList, point, giftCard] = await Promise.all([
          orderApi.getUserOrders(userId),
          pointApi.myBalance(),
          giftCardApi.myBalance(),
        ]);
        setOrders(orderList.filter((o) => o.status === PAYABLE_STATUS).sort((a, b) => b.id - a.id));
        setPointAvailable(point.available);
        setGiftCardAvailable(giftCard.available);
      } catch (err) {
        setError(apiErrorMessage(err, '결제 정보를 불러오지 못했습니다.'));
      } finally {
        setLoading(false);
      }
    })();
  }, [authLoading, userId]);

  /** 내부 잔액 수단의 배분 상한 — 넘겨 보내면 서버가 잔액 부족으로 결제를 실패시킨다. */
  const capOf = (type: TenderType): number | null => {
    if (type === 'POINT') return pointAvailable;
    if (type === 'GIFT_CARD') return giftCardAvailable;
    return null;
  };

  const lines = useMemo(
    () => ORDERABLE
      .map((type) => ({ type, amount: allocation[type] ?? 0 }))
      .filter((line) => line.amount > 0),
    [allocation],
  );

  const total = lines.reduce((sum, line) => sum + line.amount, 0);
  const orderAmount = selected?.amount ?? 0;
  const remaining = orderAmount - total;
  const awaitsDeposit = lines.some((line) => AWAITS_DEPOSIT.has(line.type));
  const overCap = lines.some((line) => {
    const cap = capOf(line.type);
    return cap !== null && line.amount > cap;
  });
  const canSubmit = selected !== null && lines.length > 0 && remaining === 0 && !overCap && !busy;

  const setAmount = (type: TenderType, raw: string) => {
    const parsed = Number(raw.replace(/[^0-9]/g, ''));
    setAllocation((prev) => ({ ...prev, [type]: Number.isFinite(parsed) ? parsed : 0 }));
  };

  /** 남은 금액을 이 수단에 몰아준다 — 잔액이 있으면 그 한도까지만. */
  const fillRemaining = (type: TenderType) => {
    const cap = capOf(type);
    const current = allocation[type] ?? 0;
    const room = remaining + current;
    setAllocation((prev) => ({ ...prev, [type]: cap === null ? Math.max(room, 0) : Math.max(Math.min(room, cap), 0) }));
  };

  const pick = (order: OrderResponse) => {
    setSelected(order);
    setAllocation({});
    setResult(null);
    setError(null);
  };

  const submit = async () => {
    if (!selected) return;
    setBusy(true);
    setError(null);
    try {
      setResult(await tenderPaymentApi.create(selected.id, lines));
    } catch (err) {
      setError(apiErrorMessage(err, '결제를 처리하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const confirmDeposit = async () => {
    if (!result) return;
    setBusy(true);
    setError(null);
    try {
      setResult(await tenderPaymentApi.confirmDeposit(result.payment.id));
    } catch (err) {
      setError(apiErrorMessage(err, '입금 확인을 처리하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  if (authLoading || loading) return <Spinner size="md" message="결제 정보 불러오는 중..." />;

  if (userId === null) {
    return (
      <div className="max-w-3xl mx-auto px-4 py-10">
        <p className="text-sm text-gray-600">로그인해야 결제할 수 있습니다.</p>
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto px-4 py-8 space-y-6">
      <header>
        <h1 className="text-2xl font-bold text-gray-900">나눠 결제</h1>
        <p className="mt-1 text-sm text-gray-500">
          포인트·상품권·카드를 <b>한 주문에 나눠</b> 낼 수 있습니다. 포인트만으로 전액 결제하는 것도 됩니다.
        </p>
      </header>

      {/* 잔액 — 합쳐 보여 주지 않는다(회계상 다른 계정, 사용 규칙도 다르다) */}
      <section className="grid grid-cols-2 gap-3">
        <div className="rounded-xl border border-gray-200 bg-white p-4">
          <p className="text-xs text-gray-500">쓸 수 있는 포인트</p>
          <p data-testid="point-available" className="mt-1 text-lg font-semibold text-gray-900">
            {won(pointAvailable)}
          </p>
        </div>
        <div className="rounded-xl border border-gray-200 bg-white p-4">
          <p className="text-xs text-gray-500">쓸 수 있는 상품권</p>
          <p data-testid="giftcard-available" className="mt-1 text-lg font-semibold text-gray-900">
            {won(giftCardAvailable)}
          </p>
        </div>
      </section>

      {error && (
        <div className="rounded-lg bg-red-50 border border-red-200 p-3 text-sm text-red-800">{error}</div>
      )}

      {/* 결제할 주문 */}
      <section className="rounded-xl border border-gray-200 bg-white p-5">
        <h2 className="text-sm font-semibold text-gray-900 mb-3">결제할 주문</h2>
        {orders.length === 0 ? (
          <p data-testid="no-payable-order" className="py-6 text-center text-sm text-gray-400">
            결제를 기다리는 주문이 없습니다.
          </p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {orders.map((order) => (
              <li key={order.id} className="flex items-center justify-between gap-3 py-2.5">
                <span className="text-sm text-gray-800">
                  주문 #{order.id} · {won(order.amount)}
                </span>
                <button
                  type="button"
                  onClick={() => pick(order)}
                  className={`rounded-lg px-3 py-1.5 text-xs font-semibold ${
                    selected?.id === order.id
                      ? 'bg-blue-600 text-white'
                      : 'border border-gray-200 text-gray-700 hover:bg-gray-50'
                  }`}
                >
                  {selected?.id === order.id ? '선택됨' : '선택'}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* 배분 */}
      {selected && !result && (
        <section className="rounded-xl border border-gray-200 bg-white p-5 space-y-4">
          <div className="flex items-baseline justify-between">
            <h2 className="text-sm font-semibold text-gray-900">지불수단 배분</h2>
            <span className="text-sm text-gray-500">주문 금액 {won(orderAmount)}</span>
          </div>

          <div className="space-y-2">
            {ORDERABLE.map((type) => {
              const cap = capOf(type);
              const amount = allocation[type] ?? 0;
              const exceeded = cap !== null && amount > cap;
              return (
                <div key={type} className="flex items-center gap-3">
                  <span className="w-24 text-sm text-gray-700">{TENDER_LABEL[type]}</span>
                  <input
                    type="text"
                    inputMode="numeric"
                    aria-label={`${TENDER_LABEL[type]} 금액`}
                    value={amount === 0 ? '' : String(amount)}
                    onChange={(e) => setAmount(type, e.target.value)}
                    className={`w-40 rounded-lg border px-3 py-1.5 text-right text-sm ${
                      exceeded ? 'border-red-300 bg-red-50 text-red-700' : 'border-gray-200'
                    }`}
                    placeholder="0"
                  />
                  <button
                    type="button"
                    onClick={() => fillRemaining(type)}
                    className="rounded-lg border border-gray-200 px-2 py-1 text-xs text-gray-600 hover:bg-gray-50"
                  >
                    남은 금액
                  </button>
                  {cap !== null && (
                    <span className={`text-xs ${exceeded ? 'text-red-600' : 'text-gray-400'}`}>
                      잔액 {won(cap)}
                      {exceeded && ' — 잔액을 넘었습니다'}
                    </span>
                  )}
                  {INTERNAL_BALANCE.has(type) || (
                    <span className="text-xs text-gray-400">외부 결제</span>
                  )}
                </div>
              );
            })}
          </div>

          <div className="flex items-center justify-between border-t border-gray-100 pt-3 text-sm">
            <span className="text-gray-600">합계 {won(total)}</span>
            <span
              data-testid="remaining"
              className={remaining === 0 ? 'text-emerald-600' : 'text-red-600'}
            >
              {remaining === 0 ? '주문 금액과 일치합니다' : `${won(Math.abs(remaining))} ${remaining > 0 ? '부족' : '초과'}`}
            </span>
          </div>

          {/* 누르기 전에 알린다 — 누른 뒤에 알면 "왜 주문이 아직 미결제지"가 된다 */}
          {awaitsDeposit && (
            <p data-testid="awaits-deposit-notice" className="rounded-lg bg-amber-50 border border-amber-200 p-3 text-xs text-amber-900">
              가상계좌·무통장이 포함돼 <b>지금 결제가 확정되지 않습니다.</b> 입금이 확인돼야 주문이
              결제 완료로 넘어갑니다. 그동안 포인트·상품권은 차감되지 않고 <b>선점</b>되며, 입금
              기한이 지나면 자동으로 풀립니다.
            </p>
          )}

          <button
            type="button"
            disabled={!canSubmit}
            onClick={submit}
            className="w-full rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-40"
          >
            {awaitsDeposit ? '결제 신청' : '결제하기'}
          </button>
        </section>
      )}

      {/* 결과 */}
      {result && (
        <section className="rounded-xl border border-gray-200 bg-white p-5 space-y-3">
          <h2 className="text-sm font-semibold text-gray-900">결제 #{result.payment.id}</h2>
          {result.payment.status === 'CAPTURED' ? (
            <p data-testid="payment-captured" className="text-sm text-emerald-700">
              결제가 완료됐습니다 — {won(result.payment.amount)}.
            </p>
          ) : (
            <div className="space-y-2">
              <p data-testid="payment-pending" className="text-sm text-amber-800">
                입금 대기 중입니다. 입금이 확인되면 주문이 결제 완료로 넘어갑니다.
              </p>
              <button
                type="button"
                disabled={busy}
                onClick={confirmDeposit}
                className="rounded-lg border border-amber-300 px-3 py-1.5 text-xs font-semibold text-amber-900 hover:bg-amber-50 disabled:opacity-40"
              >
                입금 확인 처리
              </button>
              <p className="text-xs text-gray-400">
                실제로는 PG 입금 통보가 이 자리를 대신합니다. 연동 전까지 수동으로 확인합니다.
              </p>
            </div>
          )}

          <ul className="divide-y divide-gray-100 text-xs">
            {result.tenders.map((tender) => (
              <li key={tender.id} className="flex justify-between py-1.5">
                <span className="text-gray-700">{TENDER_LABEL[tender.type] ?? tender.type}</span>
                <span className="text-gray-500">
                  {won(tender.amount)} · {tender.status}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
