package github.lms.lemuel.operation.notification.application.service;

import github.lms.lemuel.operation.notification.adapter.out.dedupe.InMemoryTtlDedupeStore;
import github.lms.lemuel.operation.notification.application.DispatchResult;
import github.lms.lemuel.operation.notification.application.port.out.DedupeStore;
import github.lms.lemuel.operation.notification.application.port.out.NotificationChannel;
import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 디스패처와 저널의 배선 — <b>L1 이 못 막는 것을 L2 가 막는가</b>.
 *
 * <p>레플리카 2개를 흉내내는 방법: 디스패처 인스턴스를 둘 만들고 <b>DedupeStore 는 각자</b>,
 * 저널은 <b>공유</b>시킨다. 실제 배포에서 인메모리 dedupe 는 파드마다 따로 있고 DB 는 하나라
 * 이 조립이 곧 프로덕션의 모양이다.
 */
class NotificationDispatcherJournalTest {

    private static final Notification EVENT =
            new Notification(NotificationType.SETTLEMENT_CONFIRMED, "ops@lemuel.co.kr", "정산", "본문", "evt-1");

    /** 호출 횟수만 세는 채널. */
    private static final class CountingChannel implements NotificationChannel {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void send(Notification notification) {
            calls.incrementAndGet();
        }
    }

    private static DedupeStore freshDedupe() {
        return new InMemoryTtlDedupeStore(Duration.ofMinutes(30), Instant::now);
    }

    @Test
    @DisplayName("레플리카가 둘이어도 같은 eventId 는 한 번만 나간다 — L1 을 통과해도 L2 가 막는다")
    void secondReplicaIsStoppedByJournal() {
        FakeNotificationJournal sharedJournal = new FakeNotificationJournal();
        CountingChannel channel = new CountingChannel();
        // dedupe 는 파드마다 따로 — 그래서 L1 은 두 번째 발송을 못 막는다.
        NotificationDispatcher replicaA =
                new NotificationDispatcher(List.of(channel), freshDedupe(), sharedJournal);
        NotificationDispatcher replicaB =
                new NotificationDispatcher(List.of(channel), freshDedupe(), sharedJournal);

        DispatchResult first = replicaA.dispatch(EVENT);
        DispatchResult second = replicaB.dispatch(EVENT);

        assertFalse(first.deduped());
        assertTrue(second.deduped(), "두 번째 레플리카는 저널에서 중복으로 판정돼야 한다");
        assertEquals(1, channel.calls.get(), "채널은 정확히 한 번만 호출돼야 한다");

        replicaA.close();
        replicaB.close();
    }

    @Test
    @DisplayName("L1 에서 걸리면 저널까지 가지 않는다 — 재전달마다 DB 를 때리지 않는다")
    void l1SkipDoesNotTouchJournal() {
        FakeNotificationJournal journal = new FakeNotificationJournal();
        DedupeStore shared = freshDedupe();
        try (NotificationDispatcher dispatcher =
                     new NotificationDispatcher(List.of(new CountingChannel()), shared, journal)) {

            dispatcher.dispatch(EVENT);
            dispatcher.dispatch(EVENT);

            assertEquals(1, journal.beganEventIds().size(), "같은 프로세스의 재전달은 L1 에서 끝나야 한다");
        }
    }

    @Test
    @DisplayName("저장소가 죽어도 발송은 계속된다 (fail-open) — 저널 없던 시절보다 나빠지지 않게")
    void storageFailureDoesNotBlockDispatch() {
        FakeNotificationJournal journal = new FakeNotificationJournal();
        journal.breakStorage();
        CountingChannel channel = new CountingChannel();
        try (NotificationDispatcher dispatcher =
                     new NotificationDispatcher(List.of(channel), freshDedupe(), journal)) {

            DispatchResult result = dispatcher.dispatch(EVENT);

            assertFalse(result.deduped());
            assertTrue(result.allSucceeded());
            assertEquals(1, channel.calls.get());
        }
    }

    @Test
    @DisplayName("저널이 없는 조립(NOOP)은 기존 동작 그대로다 — 2인자 생성자 호출부가 안 깨진다")
    void noJournalKeepsLegacyBehaviour() {
        CountingChannel channel = new CountingChannel();
        try (NotificationDispatcher dispatcher =
                     new NotificationDispatcher(List.of(channel), freshDedupe())) {

            assertTrue(dispatcher.dispatch(EVENT).allSucceeded());
            assertEquals(1, channel.calls.get());
        }
    }

    @Test
    @DisplayName("eventId 가 없는 발송은 저널도 막지 않는다 — 수기 발송은 반복이 의도다")
    void manualDispatchIsNeverDeduped() {
        FakeNotificationJournal journal = new FakeNotificationJournal();
        CountingChannel channel = new CountingChannel();
        Notification manual =
                new Notification(NotificationType.GENERIC, "ops@lemuel.co.kr", "수기", "본문", null);
        try (NotificationDispatcher dispatcher =
                     new NotificationDispatcher(List.of(channel), freshDedupe(), journal)) {

            dispatcher.dispatch(manual);
            dispatcher.dispatch(manual);

            assertEquals(2, channel.calls.get());
        }
    }
}
