import React, { useState } from 'react';
import {
  ShipmentTracking,
  ShipmentTrackingEvent,
  SHIPPING_STATUS_LABEL,
  shippingApi,
} from '@/api/shipping';
import { apiErrorStatus } from '@/lib/apiError';

interface ShipmentTrackingPanelProps {
  orderId: number;
}

const formatOccurredAt = (value: string): string => {
  const at = new Date(value);
  return Number.isNaN(at.getTime()) ? value : at.toLocaleString('ko-KR');
};

/**
 * 배송이 <b>언제</b> 어떻게 움직였는지 보여 주는 자리.
 *
 * <p><b>왜 필요한가.</b> 지금까지 고객이 보는 것은 상태 <i>단어 하나</i>였다. "배송 중"이라고만
 * 뜨면 언제부터 그런지, 왜 안 움직이는지 알 방법이 없고, 확인하려면 고객센터에 전화해야 했다 —
 * 운영자도 답할 근거가 없기는 마찬가지였다.
 *
 * <p><b>택배사 조회는 전제가 아니다.</b> 목록의 정본은 우리 시스템의 상태 전이다. 연동이 꺼져
 * 있어도 타임라인은 그대로 나오고, 택배사 조회가 실패하면 사유 한 줄만 덧붙는다. 실패했다고
 * 목록을 비우면 화면은 "아무 일도 없었다"로 읽히는데, 이미 출고된 주문에 대해 그건 거짓이다.
 *
 * <p>조회는 <b>펼칠 때</b> 한 번만 한다. 주문 목록이 카드마다 미리 읽으면 화면 한 장에 주문
 * 수만큼 요청이 나가고, 그중 택배사를 부르는 것들은 외부 호출까지 딸려 나간다.
 */
const ShipmentTrackingPanel: React.FC<ShipmentTrackingPanelProps> = ({ orderId }) => {
  const [expanded, setExpanded] = useState(false);
  const [tracking, setTracking] = useState<ShipmentTracking | null>(null);
  const [busy, setBusy] = useState(false);
  const [notFound, setNotFound] = useState(false);
  const [failed, setFailed] = useState(false);

  const toggle = async () => {
    if (expanded) {
      setExpanded(false);
      return;
    }
    setExpanded(true);
    if (tracking || busy) return;
    setBusy(true);
    setFailed(false);
    setNotFound(false);
    try {
      setTracking(await shippingApi.tracking(orderId));
    } catch (err) {
      // 404 는 배송 생성 전이다 — 장애가 아니라 정상 국면이므로 다르게 적는다.
      if (apiErrorStatus(err) === 404) setNotFound(true);
      else setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mt-3 pt-3 border-t border-gray-100">
      <button
        type="button"
        onClick={() => void toggle()}
        className="text-xs font-medium text-gray-700 hover:text-gray-900"
      >
        배송 추적 {expanded ? '▲' : '▼'}
      </button>

      {expanded && (
        <div className="mt-2 text-xs text-gray-600 space-y-2">
          {busy && <p className="text-gray-400">불러오는 중…</p>}
          {notFound && !busy && <p className="text-gray-400">아직 배송 정보가 없습니다.</p>}
          {failed && !busy && <p className="text-gray-500">배송 추적을 불러오지 못했습니다.</p>}

          {tracking && (
            <>
              {tracking.trackingNumber && (
                <p className="text-gray-500">
                  운송장{' '}
                  <span className="font-mono text-gray-800">
                    {tracking.carrier} {tracking.trackingNumber}
                  </span>
                </p>
              )}

              {tracking.events.length === 0 ? (
                <p className="text-gray-500">기록된 배송 이력이 없습니다.</p>
              ) : (
                <ol className="space-y-2" aria-label="배송 이력">
                  {tracking.events.map((event: ShipmentTrackingEvent, idx: number) => (
                    <li
                      key={`${event.occurredAt}-${event.source}-${idx}`}
                      className="flex gap-2 border-l-2 border-gray-200 pl-2"
                    >
                      <div className="flex-1">
                        <div className="flex items-center gap-2">
                          <span className="text-gray-800">{event.description}</span>
                          {/* 출처를 감추면 택배사가 알려준 것과 우리가 찍은 것이 구분되지 않는다.
                              같은 사건이 두 줄로 보일 때 사용자가 판단할 근거가 이것뿐이다. */}
                          {event.source === 'CARRIER' && (
                            <span className="px-1.5 py-0.5 rounded bg-gray-100 text-gray-500 text-[10px]">
                              택배사
                            </span>
                          )}
                        </div>
                        <div className="text-gray-400">
                          {formatOccurredAt(event.occurredAt)}
                          {event.location ? ` · ${event.location}` : ''}
                          {' · '}
                          {SHIPPING_STATUS_LABEL[event.status]}
                        </div>
                      </div>
                    </li>
                  ))}
                </ol>
              )}

              {/* 조회 실패는 목록을 지우지 않고 여기 한 줄로만 붙는다. */}
              {tracking.carrierNote && <p className="text-amber-700">{tracking.carrierNote}</p>}
            </>
          )}
        </div>
      )}
    </div>
  );
};

export default ShipmentTrackingPanel;
