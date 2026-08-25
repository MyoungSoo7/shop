package github.lms.lemuel.order.adapter.out.notification;

/**
 * 알림톡 실제 발신 경계 — 벤더(카카오 비즈메시지 대행사) SDK 가 들어올 자리.
 *
 * <p>채널 구현이 벤더 클라이언트를 직접 들고 있지 않은 이유는 두 가지다. 하나는 대행사를 바꿔도
 * "어떤 사건에 어떤 템플릿을 보낼지"의 규칙이 안 흔들리게 하려는 것이고, 다른 하나는 <b>테스트</b>다.
 * 이 인터페이스가 없으면 발송 규칙을 검증하려고 실제 발신 계정을 붙여야 한다.
 *
 * <p>이 저장소에는 구현체가 없다. 계약·템플릿 승인이 끝난 뒤 대행사 SDK 를 감싸는 빈을 하나
 * 등록하면 켜진다. {@code app.notification.alimtalk.enabled=true} 인데 이 빈이 없으면 컨텍스트가
 * 기동에 실패하는데, 이건 의도된 것이다 — "켠 줄 알았는데 조용히 아무것도 안 나가는" 상태보다
 * 기동 실패가 낫다.
 */
public interface AlimtalkSender {

    /**
     * 승인된 템플릿으로 알림톡 한 건을 보낸다.
     *
     * @param phone        수신 번호(주문 시점 배송지 스냅샷의 번호)
     * @param templateCode 대행사에 사전 승인된 템플릿 코드
     * @param message      템플릿 변수를 채운 본문. 승인 본문과 글자 단위로 일치해야 발송된다.
     * @throws RuntimeException 전송 실패 — 격리는 {@link CompositeOrderNotificationAdapter} 가 한다.
     */
    void send(String phone, String templateCode, String message);
}
