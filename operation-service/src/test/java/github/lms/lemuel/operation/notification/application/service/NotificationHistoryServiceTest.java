package github.lms.lemuel.operation.notification.application.service;

import github.lms.lemuel.operation.notification.adapter.out.dedupe.InMemoryTtlDedupeStore;
import github.lms.lemuel.operation.notification.application.DispatchRecord;
import github.lms.lemuel.operation.notification.application.port.in.NotificationHistoryUseCase;
import github.lms.lemuel.operation.notification.application.port.out.NotificationChannel;
import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 재발송 규칙 — <b>과거를 고치지 않고 새로 보낸다</b>.
 */
class NotificationHistoryServiceTest {

    /** 받은 알림을 그대로 모아 두는 채널 — 무엇이 다시 나갔는지 내용으로 확인한다. */
    private static final class RecordingChannel implements NotificationChannel {
        private final List<Notification> sent = new ArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public synchronized void send(Notification notification) {
            sent.add(notification);
        }

        synchronized List<Notification> sent() {
            return List.copyOf(sent);
        }
    }

    private FakeNotificationJournal journal;
    private RecordingChannel channel;
    private NotificationDispatcher dispatcher;
    private NotificationHistoryUseCase history;

    @BeforeEach
    void setUp() {
        journal = new FakeNotificationJournal();
        channel = new RecordingChannel();
        dispatcher = new NotificationDispatcher(List.of(channel),
                new InMemoryTtlDedupeStore(Duration.ofMinutes(30), Instant::now), journal);
        history = new NotificationHistoryService(journal, journal, dispatcher);
    }

    private long dispatchOriginal() {
        dispatcher.dispatch(new Notification(NotificationType.SETTLEMENT_CONFIRMED,
                "ops@lemuel.co.kr", "정산 확정", "본문", "evt-original"));
        return journal.findRecent(null, "ops@lemuel.co.kr", 10, 0).get(0).id();
    }

    @Test
    @DisplayName("재발송은 원본 내용을 그대로 다시 보낸다")
    void resendRepeatsOriginalContent() {
        long id = dispatchOriginal();

        Optional<NotificationHistoryUseCase.Resent> resent = history.resend(id, null);

        assertTrue(resent.isPresent());
        assertEquals(2, channel.sent().size());
        Notification again = channel.sent().get(1);
        assertEquals("ops@lemuel.co.kr", again.recipient());
        assertEquals("정산 확정", again.subject());
        assertEquals("본문", again.body());
        assertEquals(NotificationType.SETTLEMENT_CONFIRMED, again.type());
    }

    @Test
    @DisplayName("재발송은 원본 행을 고치지 않는다 — 실패했다는 사실이 남아야 사고 조사가 된다")
    void resendLeavesOriginalUntouched() {
        long id = dispatchOriginal();
        DispatchRecord before = journal.findById(id).orElseThrow();

        history.resend(id, "key-1");

        DispatchRecord after = journal.findById(id).orElseThrow();
        assertEquals(before.status(), after.status());
        assertEquals(before.eventId(), after.eventId());
    }

    @Test
    @DisplayName("새 행은 원본을 가리킨다 — 계보로 둘을 잇는다")
    void resendLinksLineage() {
        long id = dispatchOriginal();

        NotificationHistoryUseCase.Resent resent = history.resend(id, "key-1").orElseThrow();

        DispatchRecord created = journal.findById(
                journal.findRecent(null, null, 10, 0).stream()
                        .filter(r -> resent.eventId().equals(r.eventId()))
                        .findFirst().orElseThrow().id()).orElseThrow();
        assertEquals(id, created.resentFromId());
    }

    @Test
    @DisplayName("같은 idempotencyKey 로 두 번 눌러도 한 번만 나간다 — 콘솔 더블클릭 방어")
    void sameIdempotencyKeySendsOnce() {
        long id = dispatchOriginal();

        NotificationHistoryUseCase.Resent first = history.resend(id, "key-1").orElseThrow();
        NotificationHistoryUseCase.Resent second = history.resend(id, "key-1").orElseThrow();

        assertEquals(first.eventId(), second.eventId());
        assertFalse(first.result().deduped());
        assertTrue(second.result().deduped());
        assertEquals(2, channel.sent().size(), "원본 1건 + 재발송 1건");
    }

    @Test
    @DisplayName("키를 안 주면 매번 새 발송이다 — 의도적으로 두 번 보내는 경로가 막히지 않게")
    void withoutKeyEachResendIsNew() {
        long id = dispatchOriginal();

        NotificationHistoryUseCase.Resent first = history.resend(id, null).orElseThrow();
        NotificationHistoryUseCase.Resent second = history.resend(id, null).orElseThrow();

        assertNotEquals(first.eventId(), second.eventId());
        assertEquals(3, channel.sent().size());
    }

    @Test
    @DisplayName("없는 항목의 재발송은 빈 결과다 — 컨트롤러가 404 로 바꾼다")
    void resendOfMissingRecordIsEmpty() {
        assertTrue(history.resend(9_999L, null).isEmpty());
        assertTrue(history.detail(9_999L).isEmpty());
    }

    @Test
    @DisplayName("limit 이 0 이하면 기본값으로 되돌린다 — 빈 페이지를 돌려주지 않는다")
    void nonPositiveLimitFallsBackToDefault() {
        dispatchOriginal();

        NotificationHistoryUseCase.Page page = history.list(null, null, 0, -5);

        assertEquals(50, page.limit());
        assertEquals(0, page.offset());
        assertEquals(1, page.total());
        assertEquals(1, page.items().size());
    }
}
