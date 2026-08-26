import React, { useState } from 'react';
import { CONSENT_TYPE_LABEL, OrderPrivacyConsent, privacyConsentApi } from '@/api/privacyConsent';

interface OrderPrivacyConsentPanelProps {
  orderId: number;
}

const formatAgreedAt = (value: string): string => {
  const at = new Date(value);
  return Number.isNaN(at.getTime()) ? value : at.toLocaleString('ko-KR');
};

/**
 * 고객이 <b>자기 주문에서 무엇에 동의했는지</b> 확인하는 자리.
 *
 * <p><b>왜 필요한가.</b> 개인정보 보호법이 정보주체에게 주는 열람 요구권은 "동의한 내역을 확인할
 * 수 있어야 한다"를 포함한다. 동의를 받아 기록만 하고 보여 줄 수 없으면 절반만 한 것이고, 그때
 * 고객이 확인할 방법은 고객센터에 전화하는 것뿐이다 — 배송지 변경이 그랬던 것과 같은 형태다.
 *
 * <p>거절한 선택 항목도 함께 보여 준다. "물었고 거절했다"와 "묻지 않았다"는 다른 사실이고,
 * 나중에 광고 발송의 근거를 따질 때 필요한 것이 정확히 그 구분이다.
 *
 * <p>조회는 <b>펼칠 때</b> 한 번만 한다. 주문 목록이 카드마다 미리 읽으면 화면 한 장에 주문
 * 수만큼 요청이 나간다.
 */
const OrderPrivacyConsentPanel: React.FC<OrderPrivacyConsentPanelProps> = ({ orderId }) => {
  const [expanded, setExpanded] = useState(false);
  const [consents, setConsents] = useState<OrderPrivacyConsent[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  const toggle = async () => {
    if (expanded) { setExpanded(false); return; }
    setExpanded(true);
    if (consents || busy) return;
    setBusy(true);
    setFailed(false);
    try {
      setConsents(await privacyConsentApi.ofOrder(orderId));
    } catch {
      setFailed(true);
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
        개인정보 동의 내역 {expanded ? '▲' : '▼'}
      </button>

      {expanded && (
        <div className="mt-2 text-xs text-gray-600 space-y-2">
          {busy && <p className="text-gray-400">불러오는 중…</p>}
          {failed && <p className="text-gray-500">동의 내역을 불러오지 못했습니다.</p>}

          {consents && consents.length === 0 && !busy && (
            // 동의 기능이 생기기 전의 주문에는 이력이 없다. "동의하지 않았다"가 아니라
            // "그때는 받지 않았다"이므로 그렇게 적는다 — 없는 거절을 지어내지 않는다.
            <p className="text-gray-500">이 주문에는 기록된 동의 내역이 없습니다.</p>
          )}

          {consents?.map((c) => (
            <div key={`${c.termsCode}-${c.termsVersion}`} className="border border-gray-200 rounded p-2 space-y-1">
              <div className="flex items-center gap-2">
                <span className={c.agreed ? 'text-blue-600 font-semibold' : 'text-gray-400 font-semibold'}>
                  {c.agreed ? '동의함' : '동의 안 함'}
                </span>
                <span className="text-gray-800">
                  {CONSENT_TYPE_LABEL[c.consentType] ?? c.consentType}
                </span>
                <span className="text-gray-400">
                  {c.termsCode} v{c.termsVersion}
                </span>
              </div>
              {c.recipient && <p>제공받는 자: {c.recipient}</p>}
              <p>목적: {c.purpose}</p>
              <p>항목: {c.providedItems}</p>
              <p>보유·이용: {c.retention}</p>
              <p className="text-gray-400">동의 시각: {formatAgreedAt(c.agreedAt)}</p>
              {!c.bodyUnchanged && (
                // 버전을 올리지 않고 문장을 고쳤다는 뜻이다. 감추면 그 사실을 아무도 모르게 된다.
                <p className="text-amber-700">
                  이 문안은 동의 이후 내용이 변경되었습니다. 위 항목은 동의 당시 기록입니다.
                </p>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default OrderPrivacyConsentPanel;
