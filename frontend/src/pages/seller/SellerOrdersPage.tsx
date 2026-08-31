import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  formatMoney,
  sellerApi,
  type OrderFilter,
  type SellerOrder,
  type SellerOrderPage,
} from '@/api/seller';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 셀러 주문 · 출고.
 *
 * <p><b>파트너 주문 화면과 겉은 닮았지만 목적이 다르다.</b> 파트너는 "얼마 팔렸나" 를 본다.
 * 셀러는 "무엇을 아직 안 보냈나" 를 본다. 그래서 기본 필터가 미출고이고, 날짜보다 출고 여부가
 * 먼저 온다.
 *
 * <p><b>추정 결제시각을 별표로 드러낸다.</b> 결제 이벤트에 시각이 없어 수신 시각으로 채운 행이
 * 있는데, 셀러는 이 날짜로 출고 기한을 센다. 추정값을 확정처럼 그리면 기한을 잘못 세고도
 * 아무도 모른다.
 *
 * <p><b>송장 등록은 202 다.</b> 서버가 "접수했다" 만 답하고 실제 출고 전이는 order-service 가
 * 이벤트를 받아 한다. 그래서 등록 직후 화면은 낙관적으로 '출고됨' 으로 바꾸지 않고 목록을 다시
 * 읽는다 — 실패한 등록이 화면에서만 성공으로 보이는 상태를 만들지 않는다.
 */

const PAGE_SIZE = 20;

const formatDate = (value: string | null): string => {
  if (value === null) return '—';
  const at = new Date(value);
  return Number.isNaN(at.getTime()) ? value : at.toLocaleString('ko-KR');
};

function ShipmentForm({ order, onDone }: { order: SellerOrder; onDone: () => void }) {
  const [carrier, setCarrier] = useState('');
  const [trackingNumber, setTrackingNumber] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setSaving(true);
    setError(null);
    try {
      await sellerApi.registerShipment(order.orderId, carrier.trim(), trackingNumber.trim());
      setCarrier('');
      setTrackingNumber('');
      onDone();
    } catch (err) {
      setError(apiErrorMessage(err, '송장 등록에 실패했습니다.'));
    } finally {
      setSaving(false);
    }
  };

  const ready = carrier.trim() !== '' && trackingNumber.trim() !== '';

  return (
    <div className="space-y-2" data-testid={`shipment-form-${order.orderId}`}>
      <div className="flex flex-wrap gap-2">
        <input type="text" value={carrier} placeholder="택배사" data-testid="shipment-carrier"
               className="rounded border border-gray-300 px-2 py-1 text-sm"
               onChange={(e) => setCarrier(e.target.value)} />
        <input type="text" value={trackingNumber} placeholder="송장번호" data-testid="shipment-tracking"
               className="rounded border border-gray-300 px-2 py-1 text-sm"
               onChange={(e) => setTrackingNumber(e.target.value)} />
        <button type="button" onClick={() => void submit()} disabled={saving || !ready}
                data-testid="shipment-save"
                className="rounded bg-gray-900 px-3 py-1 text-sm text-white disabled:opacity-40">
          {saving ? '등록 중…' : '송장 등록'}
        </button>
      </div>
      {error !== null && <p className="text-xs text-red-600" data-testid="shipment-error">{error}</p>}
    </div>
  );
}

export default function SellerOrdersPage() {
  const [result, setResult] = useState<SellerOrderPage | null>(null);
  const [detail, setDetail] = useState<SellerOrder | null>(null);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [orderId, setOrderId] = useState('');
  const [unshippedOnly, setUnshippedOnly] = useState(true);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 필터는 렌더마다 새 객체가 되므로 의존성이 아니라 인자로 넘긴다.
  const load = useCallback(async (filter: OrderFilter, nextPage: number) => {
    setLoading(true);
    setError(null);
    try {
      setResult(await sellerApi.orders(filter, nextPage, PAGE_SIZE));
      setPage(nextPage);
    } catch (err) {
      setError(apiErrorMessage(err, '주문을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  const currentFilter = (): OrderFilter => ({
    from: from === '' ? null : from,
    to: to === '' ? null : to,
    orderId: orderId === '' ? null : Number(orderId),
    unshippedOnly,
  });

  useEffect(() => {
    void load({ unshippedOnly: true }, 0);
  }, [load]);

  const openDetail = async (id: number) => {
    setError(null);
    try {
      setDetail(await sellerApi.order(id));
    } catch (err) {
      setError(apiErrorMessage(err, '주문 상세를 불러오지 못했습니다.'));
    }
  };

  const afterShipment = () => {
    setDetail(null);
    void load(currentFilter(), page);
  };

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-4">
      <header className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-xl font-semibold text-gray-900">주문 · 출고</h1>
        <Link to="/seller/products" className="text-sm text-blue-600 hover:underline">← 상품 등록</Link>
      </header>

      <section className="flex flex-wrap items-end gap-2 rounded-lg bg-white p-4 shadow">
        <label className="text-sm text-gray-700">
          시작일
          <input type="date" value={from} data-testid="filter-from"
                 className="block rounded border border-gray-300 px-2 py-1"
                 onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="text-sm text-gray-700">
          종료일
          <input type="date" value={to} data-testid="filter-to"
                 className="block rounded border border-gray-300 px-2 py-1"
                 onChange={(e) => setTo(e.target.value)} />
        </label>
        <label className="text-sm text-gray-700">
          주문번호
          <input type="number" value={orderId} data-testid="filter-order-id" placeholder="전체"
                 className="block w-32 rounded border border-gray-300 px-2 py-1"
                 onChange={(e) => setOrderId(e.target.value)} />
        </label>
        <label className="flex items-center gap-2 pb-1 text-sm text-gray-700">
          <input type="checkbox" checked={unshippedOnly} data-testid="filter-unshipped"
                 onChange={(e) => setUnshippedOnly(e.target.checked)} />
          미출고만
        </label>
        <button type="button" onClick={() => void load(currentFilter(), 0)} data-testid="filter-apply"
                className="rounded bg-gray-900 px-3 py-1.5 text-sm text-white">조회</button>
      </section>

      {error !== null && (
        <p className="rounded bg-red-50 p-3 text-sm text-red-700" data-testid="orders-error">{error}</p>
      )}

      {loading
        ? <p className="text-sm text-gray-500" data-testid="orders-loading">불러오는 중…</p>
        : result === null || result.content.length === 0
          ? <p className="text-sm text-gray-500" data-testid="orders-empty">
              {unshippedOnly ? '보낼 주문이 없습니다.' : '주문이 없습니다.'}
            </p>
          : (
            <>
              <div className="overflow-x-auto rounded-lg bg-white shadow">
                <table className="min-w-full text-sm" data-testid="orders-table">
                  <thead className="bg-gray-50 text-left text-gray-600">
                    <tr>
                      <th className="px-3 py-2">주문번호</th>
                      <th className="px-3 py-2">결제일시</th>
                      <th className="px-3 py-2">상품</th>
                      <th className="px-3 py-2 text-right">결제액</th>
                      <th className="px-3 py-2 text-right">환불</th>
                      <th className="px-3 py-2">주문상태</th>
                      <th className="px-3 py-2">출고</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.content.map((order) => (
                      <tr key={order.orderId} className="border-t border-gray-100">
                        <td className="px-3 py-2">
                          <button type="button" onClick={() => void openDetail(order.orderId)}
                                  data-testid={`order-${order.orderId}`}
                                  className="text-blue-600 hover:underline">{order.orderId}</button>
                        </td>
                        <td className="px-3 py-2">
                          {formatDate(order.capturedAt)}
                          {/* 추정값 표시 — 확정 시각과 같은 글자로 그리지 않는다. */}
                          {order.capturedAtEstimated && (
                            <span className="ml-1 text-xs text-amber-700"
                                  title="결제 이벤트에 시각이 없어 수신 시각으로 대체된 값"
                                  data-testid={`estimated-${order.orderId}`}>*</span>
                          )}
                        </td>
                        <td className="px-3 py-2">{order.productName ?? `상품 ${order.productId ?? '미상'}`}</td>
                        <td className="px-3 py-2 text-right">{formatMoney(order.amount)}</td>
                        <td className="px-3 py-2 text-right">
                          {order.refundedAmount === 0 ? '—' : formatMoney(order.refundedAmount)}
                        </td>
                        {/* order.created 가 아직 안 온 행은 'CREATED' 로 채우지 않는다 — 취소 건이 정상으로 보인다. */}
                        <td className="px-3 py-2">{order.orderStatus ?? '확인 중'}</td>
                        <td className="px-3 py-2">
                          {order.shipmentRegistered
                            ? <span data-testid={`shipped-${order.orderId}`}>
                                {order.carrier ?? '—'} {order.trackingNumber ?? ''}
                              </span>
                            : <span className="text-amber-700" data-testid={`unshipped-${order.orderId}`}>미출고</span>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div className="flex items-center justify-between text-sm text-gray-600">
                <span data-testid="orders-total">
                  전체 {result.totalElements.toLocaleString('ko-KR')}건 · {page + 1}/{Math.max(result.totalPages, 1)}쪽
                </span>
                <span className="space-x-2">
                  <button type="button" disabled={page === 0} data-testid="orders-prev"
                          onClick={() => void load(currentFilter(), page - 1)}
                          className="rounded border border-gray-300 px-2 py-1 disabled:opacity-40">이전</button>
                  <button type="button" disabled={page + 1 >= result.totalPages} data-testid="orders-next"
                          onClick={() => void load(currentFilter(), page + 1)}
                          className="rounded border border-gray-300 px-2 py-1 disabled:opacity-40">다음</button>
                </span>
              </div>
            </>
          )}

      {detail !== null && (
        <section className="rounded-lg bg-white p-4 shadow" data-testid="order-detail">
          <div className="flex items-center justify-between">
            <h2 className="font-semibold text-gray-900">주문 {detail.orderId}</h2>
            <button type="button" onClick={() => setDetail(null)} data-testid="detail-close"
                    className="text-sm text-gray-500">닫기</button>
          </div>
          <dl className="mt-3 grid gap-2 text-sm sm:grid-cols-2">
            <div><dt className="text-gray-500">결제번호</dt><dd>{detail.paymentId}</dd></div>
            <div><dt className="text-gray-500">결제수단</dt><dd>{detail.paymentMethod ?? '—'}</dd></div>
            <div><dt className="text-gray-500">결제일시</dt>
              <dd>{formatDate(detail.capturedAt)}{detail.capturedAtEstimated && ' (추정)'}</dd></div>
            <div><dt className="text-gray-500">순매출</dt><dd>{formatMoney(detail.netAmount)}</dd></div>
            <div><dt className="text-gray-500">상품</dt>
              <dd>{detail.productName ?? '—'} ({detail.productId ?? '—'})</dd></div>
            <div><dt className="text-gray-500">출고 요청</dt>
              <dd>{detail.shipmentRegistered ? formatDate(detail.shipmentRequestedAt) : '미등록'}</dd></div>
          </dl>

          {detail.shipmentRegistered
            ? (
              // 이미 등록된 건에는 입력칸을 그리지 않는다. 서버가 거절하는 조작을 화면이 권하면
              // 셀러는 자기가 잘못한 줄 안다.
              <p className="mt-3 text-sm text-gray-600" data-testid="detail-shipped">
                {detail.carrier ?? '—'} · {detail.trackingNumber ?? '—'} 로 이미 등록되어 있습니다.
              </p>
            )
            : <div className="mt-3"><ShipmentForm order={detail} onDone={afterShipment} /></div>}
        </section>
      )}
    </div>
  );
}
