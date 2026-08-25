package github.lms.lemuel.operation.notification.application.port.in;

import github.lms.lemuel.operation.notification.application.DispatchRecord;
import github.lms.lemuel.operation.notification.application.DispatchResult;

import java.util.List;
import java.util.Optional;

/**
 * 발송 이력 조회·재발송 유즈케이스 — 운영자가 "그 알림 갔나? 안 갔으면 다시 보내라" 를 하는 경로.
 *
 * <p>발송 자체({@link DispatchNotificationUseCase})와 나눈 이유: 저쪽은 이벤트가 부르는 자동 경로고
 * 이쪽은 <b>사람이 부르는</b> 운영 경로다. 인증 요구도 다르다(이쪽만 ADMIN).
 */
public interface NotificationHistoryUseCase {

    /** 최신순 목록 + 총계. 필터는 {@code null} 이면 걸지 않는다. */
    Page list(String status, String recipient, int limit, int offset);

    /** 단건 상세 — 채널별 결과 포함. 없으면 빈 Optional. */
    Optional<DispatchRecord> detail(long id);

    /**
     * 원본 발송을 그대로 다시 보낸다.
     *
     * @param idempotencyKey 같은 키로 두 번 부르면 두 번째는 중복으로 스킵된다. {@code null} 이면
     *                       매번 새 발송이 된다 — <b>운영 콘솔은 키를 넘겨야</b> 더블클릭이 두 번 나가지 않는다.
     * @return 원본이 없으면 빈 Optional.
     */
    Optional<Resent> resend(long id, String idempotencyKey);

    /** 목록 한 페이지. 총계를 같이 주는 이유는 페이지네이션 UI 가 그것 없이는 못 그리기 때문이다. */
    record Page(List<DispatchRecord> items, long total, int limit, int offset) {
        public Page {
            items = List.copyOf(items);
        }
    }

    /** 재발송 결과. {@code eventId} 는 새로 생긴 발송의 멱등 키다. */
    record Resent(long originalId, String eventId, DispatchResult result) {
    }
}
