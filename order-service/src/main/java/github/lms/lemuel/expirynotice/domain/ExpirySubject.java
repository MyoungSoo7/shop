package github.lms.lemuel.expirynotice.domain;

/**
 * 만료 예고 대상 — 사용자가 <b>가만히 있으면 돈을 잃는</b> 것들만 여기 들어온다.
 *
 * <p>쿠폰이 빠진 것은 누락이 아니다. 쿠폰은 소멸 배치 없이 사용 시점에 만료를 확인하는 lazy 방식이고
 * ({@code Coupon.java} 의 {@code now.isAfter(expiresAt)}), 그 판단은 옳다. 다만 <i>예고</i>는
 * lazy 로 할 수 없다 — 사용자가 오지 않는 것이 문제의 전부이기 때문이다. 쿠폰 예고를 붙이려면
 * 여기 상수 하나와 조회 쿼리 하나만 늘리면 되도록 열어 둔다.
 */
public enum ExpirySubject {

    /** 포인트 로트. 받는 사람은 계정 주인이다. */
    POINT_LOT,

    /**
     * 기프트카드.
     *
     * <p><b>등록된 카드만 통보할 수 있다.</b> {@code gift_cards.owner_user_id} 는 REGISTERED 전까지
     * NULL 이고(스키마 제약이 그렇게 강제한다), 미등록 카드는 누구의 것인지 시스템이 모른다.
     * 발행 시 수령자를 아는 것은 발행 요청자뿐이라, 미등록 카드 예고는 이 배치가 아니라
     * 발행 채널이 풀어야 하는 문제다.
     */
    GIFT_CARD,

    /**
     * 선물 수령권.
     *
     * <p>받는 사람이 회원이 아닐 수 있어({@code recipient_phone} 만 있다) {@code user_id} 는
     * <b>보낸 사람</b>으로 채운다. 아직 안 찾아간 선물이 사라지기 전에 손을 쓸 수 있는 사람이
     * 보낸 사람이기 때문이다. 수령자 본인에게 가는 문자는 발송 채널이 전화번호로 처리한다.
     */
    GIFT_CLAIM
}
