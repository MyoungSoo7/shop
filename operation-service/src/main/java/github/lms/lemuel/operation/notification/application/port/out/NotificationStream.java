package github.lms.lemuel.operation.notification.application.port.out;

import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.StreamEvent;

/**
 * 아웃바운드 포트: 서버→클라이언트 푸시 스트림("알림 허브").
 *
 * <p>발행과 구독을 일부러 한 포트에 둔다 — 같은 팬아웃의 양 끝이고, 나누면 한쪽만 충족하는
 * 구현(아무도 구독할 수 없는 발행자)이 성립해 버린다.
 */
public interface NotificationStream {

    /**
     * 알림을 스트림에 기록하고, 그 수신자를 듣고 있는 모든 구독자에게 밀어 넣는다.
     * 부여된 시퀀스가 담긴 이벤트를 돌려준다.
     */
    StreamEvent publish(Notification notification);

    /**
     * 주어진 {@code recipients}(클라이언트가 받을 자격이 있는 신원 — 요청 파라미터가 아니라
     * JWT 에서만 파생된다)에 대한 리스너를 등록한다.
     *
     * @param lastEventId 클라이언트의 재개 지점. {@code null} 이면 라이브만. 그 외에는 이 시퀀스보다
     *                    큰 보존 이벤트를 순서대로 먼저 재생한 뒤 라이브로 넘어간다. 보존 창은
     *                    유한하므로, 오래 자리를 비웠으면 놓친 전부가 아니라 창 안의 것만 돌아온다.
     */
    StreamSubscription subscribe(java.util.Set<String> recipients, Long lastEventId, StreamListener listener);

    /** 살아 있는 구독자 수 — 메트릭·테스트용. */
    int subscriberCount();
}
