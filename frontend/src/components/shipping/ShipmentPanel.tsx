import React, { useEffect, useState } from 'react';
import { shippingApi, Shipment, ShippingStatus, SHIPPING_STATUS_LABEL } from '@/api/shipping';
import { apiErrorStatus } from '@/lib/apiError';

/** 사용자에게 보여줄 진행 단계. RETURNED 는 정상 흐름 밖이라 별도 처리한다. */
const TIMELINE: ShippingStatus[] = ['PENDING', 'SHIPPED', 'IN_TRANSIT', 'DELIVERED'];

/** READY 는 PENDING 과 같은 칸에 둔다 — 사용자에게 '출고 대기'와 '준비 전'은 같은 국면이다. */
const timelineIndex = (status: ShippingStatus): number => {
  if (status === 'READY') return 0;
  if (status === 'RETURNED') return TIMELINE.length - 1;
  return TIMELINE.indexOf(status);
};

interface ShipmentPanelProps {
  orderId: number;
  /** 부모가 이미 배송을 들고 있으면 재조회하지 않는다(관리자 목록). */
  shipment?: Shipment | null;
}

/**
 * 주문 1건의 배송 상태 표시 — 읽기 전용.
 *
 * <p>배송이 없는 주문은 정상이다(결제 후 생성 전). 404 를 오류로 표시하면 사용자가
 * 장애로 오해하므로 "아직 배송 정보가 없습니다"로 구분해 보여준다.
 */
const ShipmentPanel: React.FC<ShipmentPanelProps> = ({ orderId, shipment: provided }) => {
  const [shipment, setShipment] = useState<Shipment | null>(provided ?? null);
  const [loading, setLoading] = useState(provided === undefined);
  const [notFound, setNotFound] = useState(false);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (provided !== undefined) {
      setShipment(provided);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    shippingApi
      .get(orderId)
      .then((s) => {
        if (!cancelled) setShipment(s);
      })
      .catch((err) => {
        if (cancelled) return;
        // 404 = 배송 미생성(정상), 그 외 = 조회 실패
        if (apiErrorStatus(err) === 404) setNotFound(true);
        else setError(true);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [orderId, provided]);

  if (loading) {
    return <p className="text-xs text-gray-400">배송 정보 확인 중...</p>;
  }
  if (notFound) {
    return <p className="text-xs text-gray-400">아직 배송 정보가 없습니다.</p>;
  }
  if (error || !shipment) {
    return <p className="text-xs text-red-500">배송 정보를 불러오지 못했습니다.</p>;
  }

  const current = timelineIndex(shipment.status);
  const returned = shipment.status === 'RETURNED';

  return (
    <div className="rounded-lg bg-gray-50 border border-gray-200 p-3">
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs font-semibold text-gray-700">배송</span>
        <span
          className={`px-2 py-0.5 rounded-full text-xs font-semibold ${
            returned
              ? 'bg-purple-100 text-purple-800'
              : shipment.status === 'DELIVERED'
                ? 'bg-green-100 text-green-800'
                : 'bg-blue-100 text-blue-800'
          }`}
        >
          {SHIPPING_STATUS_LABEL[shipment.status]}
        </span>
      </div>

      {/* 진행 단계 */}
      <ol className="flex items-center gap-1 mb-2" aria-label="배송 진행 단계">
        {TIMELINE.map((step, idx) => (
          <li key={step} className="flex-1">
            <div
              className={`h-1.5 rounded-full ${
                returned ? 'bg-purple-300' : idx <= current ? 'bg-blue-500' : 'bg-gray-200'
              }`}
            />
            <span
              className={`block mt-1 text-[10px] ${
                idx <= current ? 'text-gray-700 font-medium' : 'text-gray-400'
              }`}
            >
              {SHIPPING_STATUS_LABEL[step]}
            </span>
          </li>
        ))}
      </ol>

      <dl className="grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
        <div className="col-span-2">
          <dt className="inline text-gray-400">받는 분 </dt>
          <dd className="inline text-gray-800">
            {shipment.recipientName} · {shipment.phone}
          </dd>
        </div>
        <div className="col-span-2">
          <dt className="inline text-gray-400">주소 </dt>
          <dd className="inline text-gray-800">
            [{shipment.postalCode}] {shipment.address1}
            {shipment.address2 ? ` ${shipment.address2}` : ''}
          </dd>
        </div>
        {shipment.trackingNumber && (
          <div className="col-span-2">
            <dt className="inline text-gray-400">운송장 </dt>
            <dd className="inline text-gray-800 font-mono">
              {shipment.carrier} {shipment.trackingNumber}
            </dd>
          </div>
        )}
        {shipment.deliveryMemo && (
          <div className="col-span-2">
            <dt className="inline text-gray-400">요청사항 </dt>
            <dd className="inline text-gray-600">{shipment.deliveryMemo}</dd>
          </div>
        )}
      </dl>
    </div>
  );
};

export default ShipmentPanel;
