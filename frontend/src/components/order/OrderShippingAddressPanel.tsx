import React, { useState } from 'react';
import { shippingApi, type Shipment, type ShippingAddressRequest } from '@/api/shipping';
import { errorDetail } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';

interface OrderShippingAddressPanelProps {
  orderId: number;
}

/**
 * 고객이 <b>자기 주문의 배송지</b>를 확인하고 고치는 자리.
 *
 * <p>서버의 {@code PATCH /orders/{id}/shipment/address} 는 처음부터 {@code authenticated()} 였고
 * 소유권도 컨트롤러가 본다. 그런데 이걸 부르는 화면은 운영자 콘솔뿐이었다 — 주소를 잘못 적은
 * 고객은 <b>전화를 거는 것 말고는 방법이 없었다.</b> 엔드포인트가 있는데 진입점이 없는 상태였다.
 *
 * <p>바꿀 수 있는 창은 출고 전(PENDING)뿐이다. 서버 도메인이 그렇게 막고 있으므로 여기서는
 * 버튼을 감추기만 한다 — 판정의 정본은 여전히 서버이고, 화면은 헛걸음을 줄일 뿐이다.
 * 출고된 뒤에는 왜 못 바꾸는지를 대신 적는다. 버튼만 사라지면 고객은 화면이 고장난 줄 안다.
 *
 * <p>조회는 <b>펼칠 때</b> 한 번만 한다. 주문 목록이 카드마다 배송을 미리 읽으면 화면 한 장에
 * 주문 수만큼 요청이 나가고, 그중 상당수는 배송이 생성되기 전이라 404 다.
 */
const OrderShippingAddressPanel: React.FC<OrderShippingAddressPanelProps> = ({ orderId }) => {
  const { showToast } = useToast();
  const [expanded, setExpanded] = useState(false);
  const [shipment, setShipment] = useState<Shipment | null>(null);
  const [absent, setAbsent] = useState(false);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState<ShippingAddressRequest>({
    recipientName: '', phone: '', postalCode: '', address1: '', address2: '', deliveryMemo: '',
  });

  const toggle = async () => {
    if (expanded) { setExpanded(false); return; }
    setExpanded(true);
    if (shipment || absent) return;
    setBusy(true);
    try {
      setShipment(await shippingApi.get(orderId));
    } catch {
      // 배송 생성 전에는 404 다 — 오류가 아니라 "아직 없음"이다.
      setAbsent(true);
    } finally {
      setBusy(false);
    }
  };

  const startEdit = () => {
    if (!shipment) return;
    setForm({
      recipientName: shipment.recipientName,
      phone: shipment.phone,
      postalCode: shipment.postalCode,
      address1: shipment.address1,
      address2: shipment.address2 ?? '',
      deliveryMemo: shipment.deliveryMemo ?? '',
    });
    setEditing(true);
  };

  const save = async () => {
    setBusy(true);
    try {
      setShipment(await shippingApi.changeAddress(orderId, form));
      setEditing(false);
      showToast('배송지를 변경했습니다.', 'success');
    } catch (err) {
      showToast(errorDetail(err, '배송지 변경에 실패했습니다.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  const filled =
    form.recipientName.trim() !== '' && form.phone.trim() !== ''
    && form.postalCode.trim() !== '' && form.address1.trim() !== '';

  return (
    <div className="mt-3 pt-3 border-t border-gray-100">
      <button
        type="button"
        onClick={() => void toggle()}
        className="text-xs font-medium text-gray-700 hover:text-gray-900"
      >
        배송지 확인 · 변경 {expanded ? '▲' : '▼'}
      </button>

      {expanded && (
        <div className="mt-2 text-xs text-gray-600 space-y-1.5">
          {busy && !shipment && <p className="text-gray-400">불러오는 중…</p>}
          {absent && <p className="text-gray-500">아직 배송 정보가 만들어지지 않았습니다.</p>}

          {shipment && !editing && (
            <>
              <p>
                {shipment.recipientName} · {shipment.phone}
              </p>
              <p>
                ({shipment.postalCode}) {shipment.address1} {shipment.address2 ?? ''}
              </p>
              {shipment.deliveryMemo && <p className="text-gray-500">요청사항: {shipment.deliveryMemo}</p>}
              {shipment.status === 'PENDING' ? (
                <button
                  type="button"
                  onClick={startEdit}
                  className="px-3 py-1 text-xs font-medium rounded border border-gray-300 text-gray-700"
                >
                  배송지 변경
                </button>
              ) : (
                <p className="text-gray-400">
                  이미 출고되어 배송지를 바꿀 수 없습니다. 변경이 필요하면 반품 후 다시 주문해 주세요.
                </p>
              )}
            </>
          )}

          {shipment && editing && (
            <div className="space-y-1.5">
              <div className="flex flex-wrap gap-1.5">
                <input
                  aria-label="받는 분"
                  value={form.recipientName}
                  onChange={(e) => setForm({ ...form, recipientName: e.target.value })}
                  placeholder="받는 분"
                  className="border border-gray-300 rounded px-2 py-1 text-xs w-24"
                />
                <input
                  aria-label="연락처"
                  value={form.phone}
                  onChange={(e) => setForm({ ...form, phone: e.target.value })}
                  placeholder="연락처"
                  className="border border-gray-300 rounded px-2 py-1 text-xs w-32"
                />
                <input
                  aria-label="우편번호"
                  value={form.postalCode}
                  onChange={(e) => setForm({ ...form, postalCode: e.target.value })}
                  placeholder="우편번호"
                  className="border border-gray-300 rounded px-2 py-1 text-xs w-24"
                />
              </div>
              <input
                aria-label="주소"
                value={form.address1}
                onChange={(e) => setForm({ ...form, address1: e.target.value })}
                placeholder="주소"
                className="w-full border border-gray-300 rounded px-2 py-1 text-xs"
              />
              <input
                aria-label="상세 주소"
                value={form.address2 ?? ''}
                onChange={(e) => setForm({ ...form, address2: e.target.value })}
                placeholder="상세 주소"
                className="w-full border border-gray-300 rounded px-2 py-1 text-xs"
              />
              <input
                aria-label="배송 요청사항"
                value={form.deliveryMemo ?? ''}
                onChange={(e) => setForm({ ...form, deliveryMemo: e.target.value })}
                placeholder="배송 요청사항 (선택)"
                className="w-full border border-gray-300 rounded px-2 py-1 text-xs"
              />
              <div className="flex gap-2">
                <button
                  type="button"
                  disabled={busy || !filled}
                  onClick={() => void save()}
                  className="px-3 py-1 text-xs font-semibold rounded bg-blue-600 text-white disabled:opacity-40"
                >
                  변경 저장
                </button>
                <button
                  type="button"
                  onClick={() => setEditing(false)}
                  className="px-3 py-1 text-xs rounded border border-gray-300 text-gray-600"
                >
                  닫기
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default OrderShippingAddressPanel;
