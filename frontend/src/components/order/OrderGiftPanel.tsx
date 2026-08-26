import React, { useState } from 'react';
import {
  GIFT_CLAIM_STATUS_LABEL,
  giftApi,
  isOpenGiftClaim,
  type GiftStatusResponse,
} from '@/api/gift';
import { errorDetail } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';

interface OrderGiftPanelProps {
  orderId: number;
}

/**
 * 보낸 선물이 어디까지 갔는지 — <b>보내는 사람</b>이 보는 자리.
 *
 * <p><b>왜 필요한가.</b> 선물 주문은 결제가 끝나도 배송지가 없다. 받는 사람이 링크로 들어와
 * 넣어 줘야 비로소 출고된다. 그래서 보낸 사람 입장에서는 <b>돈은 나갔는데 아무 일도 안 일어나는
 * 구간</b>이 며칠씩 존재한다. 이 자리가 없으면 그 침묵을 해석할 방법이 없어 "결제가 안 됐나"
 * 하고 다시 주문하거나 고객센터로 전화한다.
 *
 * <p><b>재발송은 새 링크다.</b> 평문 토큰은 발급 순간에만 존재하므로 같은 링크를 다시 보내는 것은
 * 애초에 불가능하고, 재발송을 누르는 가장 흔한 이유가 "번호를 잘못 적었다"라 옛 링크를 살려 두는
 * 편이 오히려 위험하다. 잘못 간 링크는 그 자리에서 죽어야 한다.
 *
 * <p><b>회수는 환불이 아니다.</b> 링크만 닫는다. 결제를 되돌리는 길은 반품·취소 신청이고 그건
 * 바로 위 {@code OrderRequestActions} 의 몫이다. 두 개를 한 버튼에 합치면 "링크 잘못 보냈다"와
 * "안 살래"가 구별되지 않는다.
 *
 * <p>펼칠 때 한 번만 조회한다. 주문 카드마다 미리 읽으면 선물이 아닌 주문에서 404 가 쏟아진다 —
 * 목록의 대부분은 선물이 아니다.
 */
const OrderGiftPanel: React.FC<OrderGiftPanelProps> = ({ orderId }) => {
  const { showToast } = useToast();
  const [expanded, setExpanded] = useState(false);
  const [gift, setGift] = useState<GiftStatusResponse | null>(null);
  const [absent, setAbsent] = useState(false);
  const [busy, setBusy] = useState(false);

  const toggle = async () => {
    if (expanded) { setExpanded(false); return; }
    setExpanded(true);
    if (gift || absent) return;
    setBusy(true);
    try {
      setGift(await giftApi.status(orderId));
    } catch {
      // 선물이 아닌 주문은 404 다 — 오류가 아니라 "이 주문은 선물이 아님"이다.
      setAbsent(true);
    } finally {
      setBusy(false);
    }
  };

  const resend = async () => {
    setBusy(true);
    try {
      const { linkDelivered } = await giftApi.resend(orderId);
      setGift(await giftApi.status(orderId));
      showToast(
        linkDelivered
          ? '새 링크를 다시 보냈습니다. 이전 링크는 무효가 됩니다.'
          : '링크를 보내지 못했습니다. 번호를 확인하고 다시 시도해 주세요.',
        linkDelivered ? 'success' : 'error',
      );
    } catch (err) {
      showToast(errorDetail(err, '재발송에 실패했습니다.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    setBusy(true);
    try {
      setGift(await giftApi.cancel(orderId));
      showToast('링크를 회수했습니다. 결제 취소는 취소·환불 신청에서 해 주세요.', 'success');
    } catch (err) {
      showToast(errorDetail(err, '링크 회수에 실패했습니다.'), 'error');
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
        선물 진행 상황 {expanded ? '▲' : '▼'}
      </button>

      {expanded && (
        <div className="mt-2 text-xs text-gray-600 space-y-1.5">
          {busy && !gift && <p className="text-gray-400">불러오는 중…</p>}
          {absent && <p className="text-gray-500">선물로 보낸 주문이 아닙니다.</p>}

          {gift && (
            <>
              <p>
                {gift.recipientName} · {gift.maskedRecipientPhone}
              </p>
              <p>
                상태: <span className="font-medium text-gray-800">
                  {GIFT_CLAIM_STATUS_LABEL[gift.status]}
                </span>
              </p>
              {/* 만료 시각은 아직 열려 있을 때만 뜻이 있다. 이미 받아 간 선물 옆의 날짜는 오해를 부른다. */}
              {isOpenGiftClaim(gift.status) && (
                <p className="text-gray-500">
                  {new Date(gift.expiresAt).toLocaleDateString('ko-KR')} 까지 받지 않으면 만료됩니다
                </p>
              )}
              {gift.claimedAt && (
                <p className="text-gray-500">
                  배송지 입력 완료 · {new Date(gift.claimedAt).toLocaleString('ko-KR')}
                </p>
              )}
              {/* 받는 사람이 낸 주소는 보내는 사람에게 보여 주지 않는다 — 서버 응답에 애초에 없다.
                  주소를 감추는 것이 이 기능의 이유 자체다. */}

              {isOpenGiftClaim(gift.status) && (
                <div className="flex gap-1.5 pt-1">
                  <button
                    type="button"
                    onClick={() => void resend()}
                    disabled={busy}
                    className="px-3 py-1 text-xs font-medium rounded border border-gray-300 text-gray-700 disabled:opacity-50"
                  >
                    링크 다시 보내기
                  </button>
                  <button
                    type="button"
                    onClick={() => void cancel()}
                    disabled={busy}
                    className="px-3 py-1 text-xs font-medium rounded border border-gray-300 text-gray-700 disabled:opacity-50"
                  >
                    링크 회수
                  </button>
                </div>
              )}
              {gift.status === 'EXPIRED' && (
                <p className="text-gray-500">
                  기간이 지나 링크가 닫혔습니다. 다시 보내려면 새로 주문해 주세요.
                </p>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
};

export default OrderGiftPanel;
