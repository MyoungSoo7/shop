package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.GiftClaim;

/**
 * 받는 사람에게 가는 문자·알림톡 — 선물 링크와 인증번호.
 *
 * <p>주문 통지({@link SendOrderNotificationPort})와 <b>수신자가 다르다</b>. 그쪽은 주문한 사람에게
 * 가는 진행 안내이고, 이쪽은 아직 이 가게와 아무 관계도 없는 사람에게 처음 가는 연락이다.
 * 한 포트에 묶으면 "주문 통지 채널을 껐더니 선물 링크도 안 나갔다"가 된다.
 *
 * <p>구현은 셋 중 하나가 뜬다 — 개발용 로그({@code !prod}), 알림톡 연동(운영·ON),
 * 그리고 연동이 없는 운영에서 <b>명시적으로 실패</b>하는 자리 채우기. 마지막이 중요하다.
 * 조용히 성공하면 결제는 끝났는데 링크는 아무 데도 안 간 선물이 쌓이고, 그건 받는 사람도
 * 보낸 사람도 모른 채 유효기간이 지난다.
 */
public interface SendGiftMessagePort {

    /**
     * 선물이 도착했다는 첫 연락. 링크 평문은 <b>이 순간에만</b> 존재한다.
     *
     * @param claimUrl 받는 사람이 열 주소(평문 토큰이 들어 있다). 저장하지 않는다.
     */
    void sendGiftLink(GiftClaim claim, String claimUrl);

    /** 본인확인 인증번호. 평문 6자리는 여기까지만 흐르고 저장되지 않는다. */
    void sendVerificationCode(GiftClaim claim, String code);
}
