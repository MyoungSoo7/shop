package github.lms.lemuel.operation.notification.application.port.out;

import github.lms.lemuel.operation.notification.domain.StreamEvent;

/**
 * 구독자 1명에게 이벤트를 전달한다. 구현체는 죽은 클라이언트(닫힌 탭·끊긴 프록시)를 알리려고
 * 예외를 던질 수 있으며, 스트림은 그 구독자를 떨궈낸다.
 */
@FunctionalInterface
public interface StreamListener {
    void onEvent(StreamEvent event);
}
