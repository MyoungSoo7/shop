package github.lms.lemuel.operation.notification.application.port.in;

import github.lms.lemuel.operation.notification.application.DispatchResult;
import github.lms.lemuel.operation.notification.domain.Notification;

/**
 * 인바운드 유스케이스 포트. web·kafka 어댑터는 구현체가 아니라 이 인터페이스에 의존해
 * 의존 방향(어댑터 → 애플리케이션 포트)을 지킨다.
 */
public interface DispatchNotificationUseCase {

    /** 활성 채널 전체로 팬아웃한다. eventId 단위로 멱등. */
    DispatchResult dispatch(Notification notification);
}
