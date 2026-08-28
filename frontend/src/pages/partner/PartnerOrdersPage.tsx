import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  formatMoney,
  partnerApi,
  type PartnerOrder,
  type PartnerOrderPage,
} from '@/api/partner';
import { downloadBlob } from '@/lib/fileTransfer';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 파트너 주문 내역 — 입점사가 자기 주문·결제를 건별로 보고 CSV 로 받는 화면.
 *
 * <p><b>행의 기준이 주문이 아니라 결제다.</b> 이 콘솔이 셀러를 알 수 있는 유일한 경로가
 * 결제 이벤트라서, 결제되지 않은 주문은 여기에 아예 나타나지 않는다. 화면에 그렇게 적는다 —
 * 적지 않으면 입점사는 목록을 "우리 주문 전부" 로 읽고, 안 보이는 주문을 누락으로 신고한다.
 *
 * <p><b>내려받기가 잘렸으면 반드시 말한다.</b> 서버는 큰 기간을 잘라 주되 잘랐다는 사실을
 * 응답 헤더로 알린다. 그 헤더를 화면이 무시하면 파일은 열리고 숫자도 들어 있으므로 틀렸다는
 * 신호가 어디에도 남지 않는다 — 그 CSV 가 회계로 간다. 레퍼런스 백오피스는 자르지도 않아
 * 큰 기간을 고르면 백오피스가 통째로 멎었고, 그 대응이 "자른다"였다면 이건 "자른 걸 알린다" 다.
 *
 * <p><b>주문번호로 직접 찾는 칸이 있다.</b> 목록은 기간에 매이지만 단건 조회는 기간에 매이지
 * 않는다 — 예전 주문의 번호를 알고 있을 때 기간을 맞춰 가며 찾게 만들지 않는다.
 */

const PAGE_SIZE = 20;

const dash = (value: string | null) => value ?? '—';

function OrderRow({ order, onSelect }: { order: PartnerOrder; onSelect: (id: number) => void }) {
  return (
    <tr className="border-t border-gray-100">
      <td className="px-3 py-2">
        <button
          type="button"
          onClick={() => onSelect(order.orderId)}
          className="text-blue-600 hover:underline"
          data-testid={`partner-order-${order.orderId}`}
        >
          {order.orderId}
        </button>
      </td>
      <td className="px-3 py-2">
        {order.capturedAt.slice(0, 16).replace('T', ' ')}
        {/* 추정 표시는 행마다 붙인다. 상단에 한 번만 적으면 어느 행이 추정인지 알 수 없어
            "이 목록 전체를 못 믿는다" 가 된다. */}
        {order.capturedAtEstimated && (
          <span className="ml-1 text-xs text-amber-700" title="결제시각이 전달되지 않아 수신 시각으로 집계">추정</span>
        )}
      </td>
      <td className="px-3 py-2">
        {order.productName ?? (order.productId === null ? '미확인 상품' : `상품 ${order.productId}`)}
      </td>
      {/* 주문상태가 null 이면 'CREATED' 로 채우지 않는다 — 모르는 상태를 아는 척하면 취소된
          주문이 정상으로 보인다. */}
      <td className="px-3 py-2">{dash(order.orderStatus)}</td>
      <td className="px-3 py-2">{dash(order.paymentMethod)}</td>
      <td className="px-3 py-2 text-right">{formatMoney(order.amount)}</td>
      <td className="px-3 py-2 text-right">{formatMoney(order.refundedAmount)}</td>
      <td className="px-3 py-2 text-right font-medium">{formatMoney(order.netAmount)}</td>
    </tr>
  );
}

function OrderDetail({ order, onClose }: { order: PartnerOrder; onClose: () => void }) {
  return (
    <section className="rounded-lg border border-blue-200 bg-blue-50 p-4" data-testid="partner-order-detail">
      <div className="flex items-center justify-between">
        <h2 className="font-semibold text-gray-900">주문 {order.orderId} · 결제 {order.paymentId}</h2>
        <button type="button" onClick={onClose} className="text-sm text-gray-600 hover:underline">닫기</button>
      </div>
      <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
        <dt className="text-gray-600">결제일시</dt>
        <dd>{order.capturedAt.replace('T', ' ')}{order.capturedAtEstimated && ' (추정)'}</dd>
        <dt className="text-gray-600">상품</dt>
        <dd>{order.productName ?? (order.productId === null ? '미확인' : `상품 ${order.productId}`)}</dd>
        <dt className="text-gray-600">주문상태</dt>
        <dd>{dash(order.orderStatus)}</dd>
        <dt className="text-gray-600">결제수단</dt>
        <dd>{dash(order.paymentMethod)}</dd>
        <dt className="text-gray-600">결제금액</dt>
        <dd>{formatMoney(order.amount)}</dd>
        <dt className="text-gray-600">환불금액</dt>
        <dd>{formatMoney(order.refundedAmount)}</dd>
        <dt className="text-gray-600">실매출</dt>
        <dd className="font-semibold">{formatMoney(order.netAmount)}</dd>
      </dl>
    </section>
  );
}

export default function PartnerOrdersPage() {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [orderIdInput, setOrderIdInput] = useState('');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<PartnerOrderPage | null>(null);
  const [selected, setSelected] = useState<PartnerOrder | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [exporting, setExporting] = useState(false);

  // 빈 문자열은 "조건 없음" 이다. 그대로 보내면 서버가 빈 날짜를 파싱하려다 400 을 낸다.
  const filter = {
    from: from === '' ? null : from,
    to: to === '' ? null : to,
    orderId: orderIdInput === '' ? null : Number(orderIdInput),
  };

  const load = useCallback(async (nextPage: number, current: typeof filter) => {
    setLoading(true);
    setError(null);
    try {
      setResult(await partnerApi.orders(current, nextPage, PAGE_SIZE));
      setPage(nextPage);
    } catch (err) {
      setError(apiErrorMessage(err, '주문 내역을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
    // filter 는 매 렌더 새 객체라 의존성에 넣으면 무한 루프가 된다. 인자로 받는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => { void load(0, { from: null, to: null, orderId: null }); }, [load]);

  const search = () => { setSelected(null); void load(0, filter); };

  const openDetail = async (orderId: number) => {
    setError(null);
    try {
      setSelected(await partnerApi.order(orderId));
    } catch (err) {
      setError(apiErrorMessage(err, '주문을 찾을 수 없습니다.'));
    }
  };

  const exportCsv = async () => {
    setExporting(true);
    setNotice(null);
    setError(null);
    try {
      const csv = await partnerApi.exportOrders(filter);
      await downloadBlob(csv.blob, csv.fileName);
      setNotice(csv.truncated
        // 잘렸다는 사실을 알리는 것으로 끝내지 않고 무엇을 해야 하는지까지 적는다. "잘렸습니다"
        // 만 있으면 사용자는 그 파일을 그냥 쓴다.
        ? `조건에 맞는 ${csv.totalMatched.toLocaleString('ko-KR')}건 중 일부만 받았습니다. `
          + '기간을 나눠 여러 번 받으세요 — 이 파일은 전체가 아닙니다.'
        : `${csv.totalMatched.toLocaleString('ko-KR')}건을 받았습니다.`);
    } catch (err) {
      setError(apiErrorMessage(err, '내려받기에 실패했습니다.'));
    } finally {
      setExporting(false);
    }
  };

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-4">
      <header className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-xl font-semibold text-gray-900">주문 내역</h1>
        <Link to="/partner" className="text-sm text-blue-600 hover:underline">← 파트너 콘솔</Link>
      </header>

      {/* 계약의 한계를 화면에 적는다. 이 한 줄이 없으면 "결제 전 주문이 안 보인다" 가
          누락 신고로 온다. */}
      <p className="text-xs text-gray-500">
        결제가 완료된 건만 집계됩니다. 결제 전 주문은 이 목록에 나타나지 않습니다.
      </p>

      <div className="flex flex-wrap items-end gap-2">
        <label className="text-sm text-gray-700">
          시작일
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)}
                 data-testid="orders-from" className="ml-2 rounded border border-gray-300 px-2 py-1" />
        </label>
        <label className="text-sm text-gray-700">
          종료일
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)}
                 data-testid="orders-to" className="ml-2 rounded border border-gray-300 px-2 py-1" />
        </label>
        <label className="text-sm text-gray-700">
          주문번호
          <input type="text" inputMode="numeric" value={orderIdInput}
                 onChange={(e) => setOrderIdInput(e.target.value.replace(/\D/g, ''))}
                 data-testid="orders-order-id" placeholder="예: 10231"
                 className="ml-2 w-28 rounded border border-gray-300 px-2 py-1" />
        </label>
        <button type="button" onClick={search} data-testid="orders-search"
                className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700">
          조회
        </button>
        <button type="button" onClick={() => void exportCsv()} disabled={exporting}
                data-testid="orders-export"
                className="rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-800 disabled:opacity-50">
          {exporting ? '내려받는 중…' : 'CSV 내려받기'}
        </button>
      </div>

      {notice !== null && (
        <p className="rounded bg-amber-50 p-3 text-sm text-amber-800" data-testid="orders-notice">{notice}</p>
      )}
      {error !== null && (
        <p className="rounded bg-red-50 p-3 text-sm text-red-700" data-testid="orders-error">{error}</p>
      )}

      {selected !== null && <OrderDetail order={selected} onClose={() => setSelected(null)} />}

      {loading && result === null
        ? <p className="text-sm text-gray-500" data-testid="orders-loading">불러오는 중…</p>
        : result === null || result.content.length === 0
          ? <p className="text-sm text-gray-500" data-testid="orders-empty">조회된 결제가 없습니다.</p>
          : (
            <>
              <div className="overflow-x-auto rounded-lg bg-white shadow">
                <table className="min-w-full text-sm" data-testid="orders-table">
                  <thead className="bg-gray-50 text-left text-gray-600">
                    <tr>
                      <th className="px-3 py-2">주문번호</th>
                      <th className="px-3 py-2">결제일시</th>
                      <th className="px-3 py-2">상품</th>
                      <th className="px-3 py-2">주문상태</th>
                      <th className="px-3 py-2">결제수단</th>
                      <th className="px-3 py-2 text-right">결제금액</th>
                      <th className="px-3 py-2 text-right">환불</th>
                      <th className="px-3 py-2 text-right">실매출</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.content.map((order) => (
                      <OrderRow key={order.paymentId} order={order}
                                onSelect={(id) => void openDetail(id)} />
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
                          onClick={() => void load(page - 1, filter)}
                          className="rounded border border-gray-300 px-2 py-1 disabled:opacity-40">이전</button>
                  <button type="button" disabled={page + 1 >= result.totalPages} data-testid="orders-next"
                          onClick={() => void load(page + 1, filter)}
                          className="rounded border border-gray-300 px-2 py-1 disabled:opacity-40">다음</button>
                </span>
              </div>
            </>
          )}
    </div>
  );
}
