package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.GiftClaim;
import github.lms.lemuel.order.domain.Order;

import java.util.List;

/**
 * 선물 보내기 — 보내는 사람 쪽 진입점.
 *
 * <p>일반 주문과 <b>같은 생성 경로</b>({@link CreateMultiItemOrderUseCase})를 쓴다. 다른 것은
 * 배송지를 넘기지 않는다는 것 하나뿐이다. 선물 전용 주문 생성을 따로 만들면 쿠폰·재고·멱등·
 * 금액 계산이 두 벌이 되고, 둘 중 하나만 고쳐지는 날이 반드시 온다.
 */
public interface SendGiftUseCase {

    /**
     * 선물 주문을 만들고 받는 사람에게 링크를 보낸다.
     *
     * @param idempotencyKey 없으면 {@code null} — 일반 주문과 같은 규칙으로 중복 제출을 막는다
     */
    SentGift send(SendCommand command, String idempotencyKey);

    /**
     * 링크를 다시 보낸다 — <b>새 토큰으로</b>. 옛 링크는 죽는다.
     *
     * <p>평문 토큰은 발급 순간에만 존재하므로(저장소엔 해시뿐) 같은 링크를 다시 보내는 것은
     * 애초에 불가능하다. 재발송을 누르는 흔한 이유가 "번호를 잘못 적었다"이기도 해서, 옛 링크를
     * 살려 두는 편이 오히려 위험하다.
     *
     * @return 발송에 성공했는지. 실패해도 새 토큰은 이미 발급된 상태다
     */
    boolean resendLink(Long orderId);

    /** 보낸 사람이 보는 진행 상황. */
    GiftClaim getByOrderId(Long orderId);

    /**
     * 링크를 거둬들인다. <b>주문은 취소하지 않는다</b> — 결제 취소는 반품·취소 신청 경로의 일이고,
     * 여기서 겹쳐 하면 환불 규칙이 두 자리에 생긴다. 이건 "이 링크를 더는 쓸 수 없게 한다"까지다.
     */
    GiftClaim cancel(Long orderId);

    /**
     * @param message 받는 사람에게 보이는 한 줄. 없으면 {@code null}
     */
    record SendCommand(Long senderUserId,
                       List<CreateMultiItemOrderUseCase.Line> lines,
                       String couponCode,
                       String recipientName,
                       String recipientPhone,
                       String message) {
    }

    /**
     * @param claimToken    링크 평문 토큰. <b>이 반환값이 평문이 존재하는 유일한 자리</b>다 —
     *                      저장소에는 해시만 남고, 여기서 못 쓰면 다시 만들 수 없다.
     * @param linkDelivered 안내 발송에 성공했는지. <b>false 여도 주문은 성립한다</b> — 결제된 주문을
     *                      문자 한 통 때문에 무를 수는 없다. 대신 보낸 사람 화면이 이 값을 보고
     *                      재발송을 권해야 한다. 조용히 성공한 척하면 아무도 모르는 채로 끝난다
     */
    record SentGift(Order order, GiftClaim claim, String claimToken, boolean linkDelivered) {
    }
}
