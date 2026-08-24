import React, { useCallback, useEffect, useState } from 'react';
import { adminApi } from '@/api/admin';
import {
  shippingApi,
  Shipment,
  ShippingStatus,
  ShippingAddressRequest,
  BulkTrackingResult,
  SHIPPING_STATUS_LABEL,
  nextShippingActions,
} from '@/api/shipping';
import { OrderResponse } from '@/types';
import Spinner from '@/components/Spinner';
import { errorDetail, apiErrorStatus } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';

type ShipmentState = Shipment | 'none';

const ACTION_LABEL: Record<'ship' | 'in-transit' | 'delivered' | 'returned', string> = {
  ship: '출고 처리',
  'in-transit': '배송 중으로',
  delivered: '배송 완료',
  returned: '반품 처리',
};

const statusClass = (status: ShippingStatus): string => {
  switch (status) {
    case 'DELIVERED':
      return 'bg-green-100 text-green-800';
    case 'RETURNED':
      return 'bg-purple-100 text-purple-800';
    case 'PENDING':
    case 'READY':
      return 'bg-yellow-100 text-yellow-800';
    default:
      return 'bg-blue-100 text-blue-800';
  }
};

const EMPTY_ADDRESS: ShippingAddressRequest = {
  recipientName: '',
  phone: '',
  postalCode: '',
  address1: '',
  address2: '',
  deliveryMemo: '',
};

/* ─────────────────────────────────────────
   배송지 입력 폼 (생성 · 변경 공용)
───────────────────────────────────────── */
const AddressForm: React.FC<{
  initial?: ShippingAddressRequest;
  submitLabel: string;
  busy: boolean;
  onSubmit: (address: ShippingAddressRequest) => void;
  onCancel: () => void;
}> = ({ initial, submitLabel, busy, onSubmit, onCancel }) => {
  const [form, setForm] = useState<ShippingAddressRequest>(initial ?? EMPTY_ADDRESS);

  const set = (key: keyof ShippingAddressRequest) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((prev) => ({ ...prev, [key]: e.target.value }));

  // 서버가 @NotBlank 로 막는 4개 필드는 버튼 단계에서 먼저 거른다.
  const valid =
    form.recipientName.trim() !== '' &&
    form.phone.trim() !== '' &&
    form.postalCode.trim() !== '' &&
    form.address1.trim() !== '';

  return (
    <div className="mt-3 grid grid-cols-2 gap-2">
      <input aria-label="받는 분" placeholder="받는 분" value={form.recipientName}
        onChange={set('recipientName')} className="border border-gray-300 rounded px-2 py-1.5 text-sm" />
      <input aria-label="연락처" placeholder="연락처" value={form.phone}
        onChange={set('phone')} className="border border-gray-300 rounded px-2 py-1.5 text-sm" />
      <input aria-label="우편번호" placeholder="우편번호" value={form.postalCode}
        onChange={set('postalCode')} className="border border-gray-300 rounded px-2 py-1.5 text-sm" />
      <input aria-label="주소" placeholder="주소" value={form.address1}
        onChange={set('address1')} className="border border-gray-300 rounded px-2 py-1.5 text-sm" />
      <input aria-label="상세주소" placeholder="상세주소 (선택)" value={form.address2 ?? ''}
        onChange={set('address2')} className="border border-gray-300 rounded px-2 py-1.5 text-sm" />
      <input aria-label="요청사항" placeholder="요청사항 (선택)" value={form.deliveryMemo ?? ''}
        onChange={set('deliveryMemo')} className="border border-gray-300 rounded px-2 py-1.5 text-sm" />
      <div className="col-span-2 flex gap-2">
        <button type="button" disabled={!valid || busy} onClick={() => onSubmit(form)}
          className="px-3 py-1.5 text-sm font-semibold rounded bg-blue-600 text-white disabled:opacity-40">
          {submitLabel}
        </button>
        <button type="button" onClick={onCancel}
          className="px-3 py-1.5 text-sm rounded border border-gray-300 text-gray-600">
          취소
        </button>
      </div>
    </div>
  );
};

/* ─────────────────────────────────────────
   주문 1건의 배송 행
───────────────────────────────────────── */
const ShipmentRow: React.FC<{
  order: OrderResponse;
  shipment: ShipmentState | undefined;
  onChanged: (orderId: number, shipment: ShipmentState) => void;
}> = ({ order, shipment, onChanged }) => {
  const { showToast } = useToast();
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState<'idle' | 'create' | 'address' | 'ship'>('idle');
  const [carrier, setCarrier] = useState('');
  const [trackingNumber, setTrackingNumber] = useState('');

  /** 서버 호출을 감싸 실패를 토스트로 드러낸다 — 실패를 삼키면 운영자가 눌렀는지조차 알 수 없다. */
  const run = async (action: () => Promise<Shipment>, successMsg: string) => {
    setBusy(true);
    try {
      const updated = await action();
      onChanged(order.id, updated);
      setMode('idle');
      showToast(successMsg, 'success');
    } catch (err) {
      showToast(errorDetail(err, '처리에 실패했습니다.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  const doAction = (action: 'ship' | 'in-transit' | 'delivered' | 'returned') => {
    if (action === 'ship') {
      setMode('ship');
      return;
    }
    const fn =
      action === 'in-transit'
        ? shippingApi.markInTransit
        : action === 'delivered'
          ? shippingApi.markDelivered
          : shippingApi.markReturned;
    void run(() => fn(order.id), `${ACTION_LABEL[action]} 완료`);
  };

  return (
    <div className="bg-white rounded-xl border border-gray-200 p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-bold text-gray-900">주문 #{order.id}</p>
          <p className="text-xs text-gray-400 mt-0.5">
            사용자 #{order.userId} · 주문상태 {order.status}
          </p>
        </div>
        {shipment && shipment !== 'none' && (
          <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${statusClass(shipment.status)}`}>
            {SHIPPING_STATUS_LABEL[shipment.status]}
          </span>
        )}
      </div>

      {shipment === undefined && <p className="text-xs text-gray-400 mt-2">배송 정보 확인 중...</p>}

      {/* 배송 미생성 */}
      {shipment === 'none' && (
        <div className="mt-2">
          <p className="text-xs text-gray-400">배송이 생성되지 않았습니다.</p>
          {mode === 'create' ? (
            <AddressForm
              submitLabel="배송 생성"
              busy={busy}
              onSubmit={(address) => run(() => shippingApi.create(order.id, address), '배송을 생성했습니다.')}
              onCancel={() => setMode('idle')}
            />
          ) : (
            <button onClick={() => setMode('create')}
              className="mt-2 px-3 py-1.5 text-sm font-semibold rounded bg-gray-900 text-white">
              배송 생성
            </button>
          )}
        </div>
      )}

      {/* 배송 존재 */}
      {shipment && shipment !== 'none' && (
        <div className="mt-2">
          <p className="text-xs text-gray-600">
            {shipment.recipientName} · {shipment.phone} · [{shipment.postalCode}] {shipment.address1}
            {shipment.address2 ? ` ${shipment.address2}` : ''}
          </p>
          {shipment.trackingNumber && (
            <p className="text-xs text-gray-500 mt-0.5 font-mono">
              {shipment.carrier} {shipment.trackingNumber}
            </p>
          )}

          {mode === 'ship' ? (
            <div className="mt-3 flex flex-wrap gap-2">
              <input aria-label="택배사" placeholder="택배사" value={carrier}
                onChange={(e) => setCarrier(e.target.value)}
                className="border border-gray-300 rounded px-2 py-1.5 text-sm" />
              <input aria-label="운송장 번호" placeholder="운송장 번호" value={trackingNumber}
                onChange={(e) => setTrackingNumber(e.target.value)}
                className="border border-gray-300 rounded px-2 py-1.5 text-sm" />
              <button
                disabled={busy || carrier.trim() === '' || trackingNumber.trim() === ''}
                onClick={() =>
                  run(
                    () => shippingApi.ship(order.id, { carrier: carrier.trim(), trackingNumber: trackingNumber.trim() }),
                    '출고 처리했습니다.'
                  )
                }
                className="px-3 py-1.5 text-sm font-semibold rounded bg-blue-600 text-white disabled:opacity-40">
                출고
              </button>
              <button onClick={() => setMode('idle')}
                className="px-3 py-1.5 text-sm rounded border border-gray-300 text-gray-600">
                취소
              </button>
            </div>
          ) : mode === 'address' ? (
            <AddressForm
              initial={{
                recipientName: shipment.recipientName,
                phone: shipment.phone,
                postalCode: shipment.postalCode,
                address1: shipment.address1,
                address2: shipment.address2 ?? '',
                deliveryMemo: shipment.deliveryMemo ?? '',
              }}
              submitLabel="배송지 변경"
              busy={busy}
              onSubmit={(address) =>
                run(() => shippingApi.changeAddress(order.id, address), '배송지를 변경했습니다.')
              }
              onCancel={() => setMode('idle')}
            />
          ) : (
            <div className="mt-3 flex flex-wrap gap-2">
              {/* 가능한 전이만 노출한다 — 판정의 정본은 서버 도메인이고, 여기는 그 사본이다 */}
              {nextShippingActions(shipment.status).map((action) => (
                <button key={action} disabled={busy} onClick={() => doAction(action)}
                  className="px-3 py-1.5 text-sm font-semibold rounded bg-gray-900 text-white disabled:opacity-40">
                  {ACTION_LABEL[action]}
                </button>
              ))}
              {/* 배송지 변경은 서버가 PENDING 에서만 허용한다 */}
              {shipment.status === 'PENDING' && (
                <button disabled={busy} onClick={() => setMode('address')}
                  className="px-3 py-1.5 text-sm rounded border border-gray-300 text-gray-700 disabled:opacity-40">
                  배송지 변경
                </button>
              )}
              {nextShippingActions(shipment.status).length === 0 && shipment.status === 'RETURNED' && (
                <p className="text-xs text-gray-400">종료된 배송입니다.</p>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

/* ─────────────────────────────────────────
   송장 일괄 업로드 (CSV)
───────────────────────────────────────── */

/**
 * 수백 행이 한 번에 출고 처리되는 작업이라 <b>미리보기가 먼저</b>다.
 *
 * <p>서버도 {@code dryRun} 기본값이 true 지만, 화면은 한 걸음 더 간다 — 미리보기를 통과한
 * <b>바로 그 파일</b>에 대해서만 반영 버튼이 열린다. 파일을 바꾸면 미리보기 결과를 버리고
 * 다시 받게 한다. 그러지 않으면 "A 를 미리보고 B 를 반영"이 가능해지는데, 그 실수는 결과가
 * 나온 뒤에야 드러난다.
 */
const TrackingUploadPanel: React.FC<{ onApplied: () => void }> = ({ onApplied }) => {
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<BulkTrackingResult | null>(null);
  const [applied, setApplied] = useState<BulkTrackingResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { showToast } = useToast();

  const pick = (e: React.ChangeEvent<HTMLInputElement>) => {
    // 파일이 바뀌면 이전 판정은 이 파일의 것이 아니다 — 남겨 두면 남의 결과로 반영하게 된다.
    setFile(e.target.files?.[0] ?? null);
    setPreview(null);
    setApplied(null);
    setError(null);
  };

  const run = async (dryRun: boolean) => {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const result = await shippingApi.uploadTracking(file, dryRun);
      if (dryRun) {
        setPreview(result);
      } else {
        setApplied(result);
        setPreview(null);
        showToast(`${result.applied}건 출고 처리했습니다.`, 'success');
        onApplied();
      }
    } catch (err) {
      setError(errorDetail(err, '송장 파일을 처리하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const result = applied ?? preview;

  return (
    <section className="rounded-xl border border-gray-200 bg-white p-4 space-y-3"
      data-testid="tracking-upload">
      <div>
        <h2 className="font-semibold text-gray-900">송장 일괄 업로드</h2>
        <p className="text-sm text-gray-500 mt-1">
          <code>order_id,carrier,tracking_number</code> 형식의 CSV 를 올립니다. 먼저 미리보기로
          행별 판정을 확인한 뒤 반영하세요 — 반영은 되돌릴 수 없습니다.
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <input type="file" accept=".csv,text/csv" onChange={pick}
          aria-label="송장 CSV 파일" className="text-sm" />
        <button type="button" onClick={() => void run(true)} disabled={!file || busy}
          className="px-3 py-2 text-sm font-semibold rounded border border-gray-300 bg-white text-gray-700 disabled:opacity-50">
          {busy ? '처리 중…' : '미리보기'}
        </button>
        {/* 미리보기를 통과하지 않은 파일에는 반영 버튼이 열리지 않는다. */}
        <button type="button" onClick={() => void run(false)}
          disabled={!file || busy || preview === null || preview.applied === 0}
          className="px-3 py-2 text-sm font-semibold rounded bg-blue-600 text-white disabled:opacity-50">
          {preview ? `${preview.applied}건 반영` : '반영'}
        </button>
      </div>

      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

      {result && (
        <div className="space-y-2" data-testid="tracking-upload-result">
          <p className="text-sm text-gray-700">
            {result.dryRun ? '미리보기 — 아직 아무것도 바뀌지 않았습니다. ' : '반영 완료. '}
            성공 {result.applied}건 · 실패 {result.failed}건
          </p>
          {result.lines.some((line) => !line.applied) && (
            <ul className="text-sm text-red-700 space-y-1">
              {result.lines.filter((line) => !line.applied).map((line, idx) => (
                <li key={`${line.orderId ?? 'row'}-${idx}`}>
                  주문 {line.orderId ?? '?'} — {line.reason}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </section>
  );
};

/* ─────────────────────────────────────────
   배송 관리 페이지
───────────────────────────────────────── */
const ShippingAdminPage: React.FC = () => {
  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [shipments, setShipments] = useState<Map<number, ShipmentState>>(new Map());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [onlyUnshipped, setOnlyUnshipped] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const orderList = await adminApi.getAllOrders();
      const sorted = [...orderList].sort((a, b) => b.id - a.id);
      setOrders(sorted);

      // 배송은 주문마다 따로 읽어야 한다(목록 API 가 없다). 한 건 실패가 화면 전체를
      // 막지 않도록 allSettled 로 모으고, 404 는 '배송 없음'이라는 정상 결과로 기록한다.
      const results = await Promise.allSettled(
        sorted.map((order) => shippingApi.get(order.id))
      );
      const next = new Map<number, ShipmentState>();
      results.forEach((result, idx) => {
        const orderId = sorted[idx].id;
        if (result.status === 'fulfilled') {
          next.set(orderId, result.value);
        } else if (apiErrorStatus(result.reason) === 404) {
          next.set(orderId, 'none');
        }
        // 그 외 실패는 undefined 로 남겨 "확인 중"이 아니라 값 없음으로 둔다
      });
      setShipments(next);
    } catch (err) {
      setError(errorDetail(err, '주문 목록을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const handleChanged = (orderId: number, shipment: ShipmentState) => {
    setShipments((prev) => new Map(prev).set(orderId, shipment));
  };

  const visible = onlyUnshipped
    ? orders.filter((o) => {
        const s = shipments.get(o.id);
        // 'none'(배송 미생성)과 PENDING/READY(출고 전)가 곧 "운영자가 손댈 차례"인 주문들이다.
        if (s === undefined) return false;
        if (s === 'none') return true;
        return s.status === 'PENDING' || s.status === 'READY';
      })
    : orders;

  // 전체 페이지 래퍼는 두지 않는다 — 이 화면은 SideNavLayout(배송 그룹 사이드바) 안에서 그려진다.
  return (
    <div className="space-y-6">
      <div>
        <div className="flex items-center justify-between mb-5">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">배송 관리</h1>
            <p className="text-sm text-gray-500 mt-1">
              주문별 배송 생성 · 출고 · 상태 전이. 전이 규칙은 서버 도메인이 강제합니다.
            </p>
          </div>
          <button onClick={() => void load()}
            className="px-3 py-2 text-sm font-semibold rounded border border-gray-300 text-gray-700 bg-white">
            새로고침
          </button>
        </div>

        <div className="mb-4">
          <TrackingUploadPanel onApplied={() => void load()} />
        </div>

        <label className="flex items-center gap-2 mb-4 text-sm text-gray-700">
          <input type="checkbox" checked={onlyUnshipped}
            onChange={(e) => setOnlyUnshipped(e.target.checked)} />
          출고 전 주문만 보기
        </label>

        {loading ? (
          <Spinner size="md" message="배송 정보 불러오는 중..." />
        ) : error ? (
          <p className="text-center text-red-600 py-8">{error}</p>
        ) : visible.length === 0 ? (
          <div className="text-center py-16 text-gray-400 bg-white rounded-xl border border-gray-200">
            <p className="text-sm">표시할 주문이 없습니다.</p>
          </div>
        ) : (
          <div className="space-y-3">
            {visible.map((order) => (
              <ShipmentRow key={order.id} order={order}
                shipment={shipments.get(order.id)} onChanged={handleChanged} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default ShippingAdminPage;
