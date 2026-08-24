package github.lms.lemuel.operation.notification.application.port.out;

import github.lms.lemuel.operation.notification.domain.Notification;

/**
 * 아웃바운드 포트. 채널은 알림 1건을 전달하는 방법을 안다.
 *
 * <p>블로킹 시그니처다 — 동시 팬아웃은 디스패처가 가상 스레드로 처리하므로 채널 구현이
 * 비동기를 알 필요가 없다(Kotlin 코루틴 시절의 {@code suspend} 를 대체한 구조).
 */
public interface NotificationChannel {

    /** 안정적인 채널 이름 — "log", "slack", "email", "sse". */
    String name();

    /** 현재 활성 여부(설정이 갖춰졌는가). */
    boolean isEnabled();

    /** 전달한다. 실패 시 예외를 던진다 — 재시도·타임아웃은 디스패처의 책임이다. */
    void send(Notification notification) throws Exception;
}
