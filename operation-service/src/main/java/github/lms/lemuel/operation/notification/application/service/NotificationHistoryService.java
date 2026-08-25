package github.lms.lemuel.operation.notification.application.service;

import github.lms.lemuel.operation.notification.application.DispatchRecord;
import github.lms.lemuel.operation.notification.application.DispatchResult;
import github.lms.lemuel.operation.notification.application.port.in.DispatchNotificationUseCase;
import github.lms.lemuel.operation.notification.application.port.in.NotificationHistoryUseCase;
import github.lms.lemuel.operation.notification.application.port.out.NotificationJournal;
import github.lms.lemuel.operation.notification.application.port.out.NotificationJournalQuery;
import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.NotificationType;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 이력 조회와 재발송. 프레임워크 의존이 없어(어노테이션 0개) 어댑터 없이 단위 테스트된다.
 *
 * <p>재발송은 <b>새 발송</b>이지 과거 행의 상태 변경이 아니다. 과거 행을 되살리면 "그때 실패했다"는
 * 사실이 사라지고, 사고 조사 때 가장 필요한 정보가 지워진다. 대신 새 행을 만들고
 * {@code resent_from_id} 로 원본을 가리켜 <b>둘 다</b> 남긴다.
 */
public class NotificationHistoryService implements NotificationHistoryUseCase {

    private static final int DEFAULT_LIMIT = 50;

    private final NotificationJournalQuery query;
    private final NotificationJournal journal;
    private final DispatchNotificationUseCase dispatcher;

    public NotificationHistoryService(NotificationJournalQuery query,
                                      NotificationJournal journal,
                                      DispatchNotificationUseCase dispatcher) {
        this.query = query;
        this.journal = journal;
        this.dispatcher = dispatcher;
    }

    @Override
    public Page list(String status, String recipient, int limit, int offset) {
        int effectiveLimit = limit <= 0 ? DEFAULT_LIMIT : limit;
        int effectiveOffset = Math.max(offset, 0);
        return new Page(
                query.findRecent(status, recipient, effectiveLimit, effectiveOffset),
                query.count(status, recipient),
                effectiveLimit,
                effectiveOffset);
    }

    @Override
    public Optional<DispatchRecord> detail(long id) {
        return query.findById(id);
    }

    @Override
    public Optional<Resent> resend(long id, String idempotencyKey) {
        Optional<DispatchRecord> original = query.findById(id);
        if (original.isEmpty()) {
            return Optional.empty();
        }
        DispatchRecord record = original.get();
        String eventId = resendEventId(id, idempotencyKey);

        Notification notification = new Notification(
                parseType(record.type()),
                record.recipient(),
                record.subject(),
                record.body(),
                eventId);

        DispatchResult result = dispatcher.dispatch(notification);
        // 발송 뒤에 붙인다 — 그 전에는 저널 행이 없다. 중복 스킵이었다면 이미 붙어 있는 행이라 무해하다.
        journal.linkResend(eventId, id);
        return Optional.of(new Resent(id, eventId, result));
    }

    /**
     * 재발송 멱등 키. 원본 id 를 접두사로 넣어 <b>키를 눈으로 보고</b> 계보를 알 수 있게 한다
     * (로그·DLQ 에서 저널 조회 없이 추적 가능). 키가 없으면 UUID 라 중복 판정에 걸리지 않는다.
     */
    private static String resendEventId(long id, String idempotencyKey) {
        String suffix = (idempotencyKey == null || idempotencyKey.isBlank())
                ? UUID.randomUUID().toString()
                : idempotencyKey.trim();
        return "resend:%d:%s".formatted(id, suffix);
    }

    /**
     * 저장된 타입 문자열을 도메인 타입으로. 모르는 값은 GENERIC 으로 흡수한다 —
     * enum 상수가 지워진 과거 행 때문에 재발송이 막히면 안 된다.
     */
    private static NotificationType parseType(String raw) {
        if (raw == null) {
            return NotificationType.GENERIC;
        }
        try {
            return NotificationType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NotificationType.GENERIC;
        }
    }
}
