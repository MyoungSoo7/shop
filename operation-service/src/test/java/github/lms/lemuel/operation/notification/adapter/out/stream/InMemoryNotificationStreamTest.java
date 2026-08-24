package github.lms.lemuel.operation.notification.adapter.out.stream;

import github.lms.lemuel.operation.notification.application.port.out.StreamListener;
import github.lms.lemuel.operation.notification.application.port.out.StreamSubscription;
import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.NotificationType;
import github.lms.lemuel.operation.notification.domain.StreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 푸시 허브의 계약: 수신자별 라우팅·순서·그리고 존재 이유인 <b>끊긴 클라이언트가 놓친 것 재생</b>.
 *
 * <p>이관 전(Kotlin) 케이스를 1:1 로 보존한다 — 재생 창·재진입 순서·동시 발행 유일성처럼
 * 눈으로는 못 보는 규칙이라, 하나라도 빠지면 이관 후 조용히 깨진다.
 */
class InMemoryNotificationStreamTest {

    private static Notification notification(String recipient) {
        return notification(recipient, "s");
    }

    private static Notification notification(String recipient, String subject) {
        return new Notification(NotificationType.GENERIC, recipient, subject, "body", null);
    }

    /** 전달된 것을 전달 순서대로 기록한다. */
    private static class Recorder implements StreamListener {
        final List<StreamEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(StreamEvent event) {
            events.add(event);
        }

        List<Long> seqs() {
            return events.stream().map(StreamEvent::seq).toList();
        }

        List<String> subjects() {
            return events.stream().map(e -> e.notification().subject()).toList();
        }
    }

    @Test
    @DisplayName("publish 는 1부터 단조증가하는 시퀀스를 부여한다")
    void publishAssignsMonotonicSequenceStartingAtOne() {
        InMemoryNotificationStream stream = new InMemoryNotificationStream();

        StreamEvent first = stream.publish(notification("seller-1"));
        StreamEvent second = stream.publish(notification("seller-2"));
        StreamEvent third = stream.publish(notification("seller-1"));

        // 시퀀스는 수신자별이 아니라 전역이다: 한 구독자가 여러 신원을 동시에 들어도
        // 재개에 쓸 정렬된 id 축은 하나여야 한다.
        assertEquals(List.of(1L, 2L, 3L), List.of(first.seq(), second.seq(), third.seq()));
    }

    @Test
    @DisplayName("라이브 구독자는 자기 앞으로 온 알림만 받는다")
    void liveSubscriberReceivesOnlyItsOwnNotifications() {
        InMemoryNotificationStream stream = new InMemoryNotificationStream();
        Recorder mine = new Recorder();
        stream.subscribe(Set.of("seller-1"), null, mine);

        stream.publish(notification("seller-1", "mine"));
        stream.publish(notification("seller-2", "not-mine"));
        stream.publish(notification("seller-1", "mine-again"));

        assertEquals(List.of("mine", "mine-again"), mine.subjects());
    }

    @Test
    @DisplayName("lastEventId 없이 구독하면 라이브만 받는다")
    void subscribingWithoutLastEventIdDeliversLiveOnly() {
        InMemoryNotificationStream stream = new InMemoryNotificationStream();
        stream.publish(notification("seller-1", "before"));

        Recorder late = new Recorder();
        stream.subscribe(Set.of("seller-1"), null, late);
        stream.publish(notification("seller-1", "after"));

        assertEquals(List.of("after"), late.subjects());
    }

    @Test
    @DisplayName("lastEventId 로 재접속하면 놓친 것만 정확히 재생한다")
    void reconnectWithLastEventIdReplaysExactlyWhatWasMissed() {
        InMemoryNotificationStream stream = new InMemoryNotificationStream();
        StreamEvent first = stream.publish(notification("seller-1", "seen"));
        stream.publish(notification("seller-1", "missed-1"));
        stream.publish(notification("seller-2", "someone-else"));
        stream.publish(notification("seller-1", "missed-2"));

        Recorder resumed = new Recorder();
        stream.subscribe(Set.of("seller-1"), first.seq(), resumed);

        assertEquals(List.of("missed-1", "missed-2"), resumed.subjects());
    }

    @Test
    @DisplayName("재생은 보존 버퍼 크기로 제한된다")
    void replayIsBoundedByRetainedBufferSize() {
        InMemoryNotificationStream stream = new InMemoryNotificationStream(2, 10_000, 200);
        for (int i = 0; i < 5; i++) {
            stream.publish(notification("seller-1", "n" + i));
        }

        Recorder resumed = new Recorder();
        stream.subscribe(Set.of("seller-1"), 0L, resumed);

        // 최신 둘만 살아남는다 — 클라이언트는 "최신 상태입니다"라는 거짓말 대신 id 공백을 본다.
        assertEquals(List.of("n3", "n4"), resumed.subjects());
    }

    @Test
    @DisplayName("여러 신원을 가진 구독자도 정렬된 하나의 스트림을 받는다")
    void subscriberWithSeveralIdentitiesGetsOneOrderedStream() {
        InMemoryNotificationStream stream = new InMemoryNotificationStream();
        stream.publish(notification("42", "by-user-id"));
        stream.publish(notification("seller@lemuel.co.kr", "by-email"));

        Recorder resumed = new Recorder();
        stream.subscribe(Set.of("42", "seller@lemuel.co.kr"), 0L, resumed);

        assertEquals(List.of("by-user-id", "by-email"), resumed.subjects());
        assertEquals(List.of(1L, 2L), resumed.seqs());
    }

    @Test
    @DisplayName("재생 중 발행된 이벤트는 백로그 뒤에 순서대로 전달된다")
    void eventsPublishedDuringReplayComeAfterTheBacklogInOrder() {
        InMemoryNotificationStream stream = new InMemoryNotificationStream();
        stream.publish(notification("seller-1", "backlog-1"));
        stream.publish(notification("seller-1", "backlog-2"));

        // 리스너 안에서의 재진입 발행: 라이브 이벤트가 아직 재생 중인 백로그를 앞지르면 안 된다.
        Recorder recorder = new Recorder() {
            private boolean injected = false;

            @Override
            public void onEvent(StreamEvent event) {
                super.onEvent(event);
                if (!injected) {
                    injected = true;
                    stream.publish(notification("seller-1", "live-during-replay"));
                }
            }
        };
        stream.subscribe(Set.of("seller-1"), 0L, recorder);

        assertEquals(List.of("backlog-1", "backlog-2", "live-during-replay"), recorder.subjects());
    }

    @Test
    @DisplayName("cancel 은 전달을 멈추고 구독자를 놓아준다")
    void cancelStopsDeliveryAndReleasesTheSubscriber() {
        InMemoryNotificationStream stream = new InMemoryNotificationStream();
        Recorder recorder = new Recorder();
        StreamSubscription subscription = stream.subscribe(Set.of("seller-1"), null, recorder);

        stream.publish(notification("seller-1", "before-cancel"));
        subscription.cancel();
        stream.publish(notification("seller-1", "after-cancel"));

        assertEquals(List.of("before-cancel"), recorder.subjects());
        assertEquals(0, stream.subscriberCount());
    }

    @Test
    @DisplayName("취소를 두 번 해도 무해하다")
    void cancellingTwiceIsHarmless() {
        InMemoryNotificationStream stream = new InMemoryNotificationStream();
        StreamSubscription subscription = stream.subscribe(Set.of("seller-1"), null, new Recorder());

        subscription.cancel();
        subscription.cancel();

        assertEquals(0, stream.subscriberCount());
    }

    @Test
    @DisplayName("예외를 던지는 리스너는 떨궈지고 나머지는 영향받지 않는다")
    void listenerThatThrowsIsDroppedWithoutAffectingOthers() {
        InMemoryNotificationStream stream = new InMemoryNotificationStream();
        Recorder healthy = new Recorder();
        stream.subscribe(Set.of("seller-1"), null, healthy);
        stream.subscribe(Set.of("seller-1"), null, event -> {
            throw new IllegalStateException("client gone");
        });

        stream.publish(notification("seller-1", "first"));
        stream.publish(notification("seller-1", "second"));

        assertEquals(List.of("first", "second"), healthy.subjects());
        assertEquals(1, stream.subscriberCount());
    }

    @Test
    @DisplayName("신원 없이 구독하는 것은 거부된다")
    void subscribingWithoutAnIdentityIsRefused() {
        InMemoryNotificationStream stream = new InMemoryNotificationStream();

        // 빈 신원 집합은 "아무것도 구독하지 않음"이다 — 영원히 조용히 아무것도 안 주느니 여기서 실패한다.
        assertThrows(IllegalArgumentException.class,
                () -> stream.subscribe(Set.of(), null, new Recorder()));
        assertThrows(IllegalArgumentException.class,
                () -> stream.subscribe(null, null, new Recorder()));
    }

    @Test
    @DisplayName("유휴 보존 버퍼는 수신자가 너무 많아지면 정리된다")
    void idleRetentionBuffersArePrunedWhenTooManyRecipients() {
        InMemoryNotificationStream stream = new InMemoryNotificationStream(5, 2, 200);

        // 유휴 수신자 넷, 오래된 것부터 발행.
        for (String recipient : List.of("a", "b", "c", "d")) {
            stream.publish(notification(recipient));
        }

        // 가장 식은 것은 사라졌다: "a" 로 재개하면 아무것도 재생되지 않는다.
        Recorder cold = new Recorder();
        stream.subscribe(Set.of("a"), 0L, cold);
        assertTrue(cold.events.isEmpty(), "가장 식은 버퍼는 정리됐어야 한다");

        // 가장 새 것은 남아 있다.
        Recorder warm = new Recorder();
        stream.subscribe(Set.of("d"), 0L, warm);
        assertEquals(1, warm.events.size());
    }

    @Test
    @DisplayName("살아 있는 구독자가 있는 수신자는 재생 창을 잃지 않는다")
    void recipientWithLiveSubscriberKeepsItsReplayWindow() {
        InMemoryNotificationStream stream = new InMemoryNotificationStream(5, 1, 200);
        stream.subscribe(Set.of("live"), null, new Recorder());

        stream.publish(notification("live", "kept"));
        for (String recipient : List.of("x", "y", "z")) {
            stream.publish(notification(recipient));
        }

        // "live" 로 재접속하면 여전히 재생돼야 한다 — 실제로 붙어 있는 클라이언트의 재개 보장을 깨면 안 된다.
        Recorder resumed = new Recorder();
        stream.subscribe(Set.of("live"), 0L, resumed);
        assertEquals(List.of("kept"), resumed.subjects());
    }

    @Test
    @DisplayName("메일박스가 넘치면 가장 오래된 것을 버리고 계속 흐른다")
    void overflowingMailboxDropsOldestAndKeepsFlowing() {
        InMemoryNotificationStream stream = new InMemoryNotificationStream(100, 10_000, 2);
        List<Long> delivered = new CopyOnWriteArrayList<>();
        boolean[] burst = {false};

        // 전달받는 도중에 발행하는 리스너: 새 이벤트가 흐르는 것 뒤에 줄을 서므로 메일박스가 실제로 자란다.
        StreamListener reentrant = event -> {
            delivered.add(event.seq());
            if (!burst[0]) {
                burst[0] = true;
                for (int i = 0; i < 6; i++) {
                    stream.publish(notification("seller-1", "burst" + i));
                }
            }
        };
        stream.subscribe(Set.of("seller-1"), null, reentrant);

        stream.publish(notification("seller-1", "first"));

        // 1(first) + 재진입 발행 6건이지만 메일박스는 한 번에 2건만 쥔다 —
        // 허브가 무한히 버퍼링하는 대신 클라이언트가 id 공백을 본다.
        assertTrue(delivered.size() >= 3 && delivered.size() <= 7, "예상 밖 전달 수: " + delivered);
        assertTrue(delivered.size() < 7, "아무것도 안 버려졌다 — 상한이 작동하지 않았다: " + delivered);
        assertEquals(delivered.stream().sorted().toList(), List.copyOf(delivered),
                "전달 순서는 유지돼야 한다: " + delivered);
    }

    @Test
    @DisplayName("동시 발행에도 시퀀스는 유일하다")
    void concurrentPublishesAssignUniqueSequences() throws Exception {
        InMemoryNotificationStream stream = new InMemoryNotificationStream();
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Long> seqs = new CopyOnWriteArrayList<>();

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        seqs.add(stream.publish(notification("seller-1")).seq());
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "발행자들이 끝나지 않았다");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * perThread, seqs.size());
        assertEquals(seqs.size(), Set.copyOf(seqs).size(), "시퀀스가 중복됐다");
        assertEquals(LongStream.rangeClosed(1, (long) threads * perThread).boxed().collect(Collectors.toSet()),
                Set.copyOf(seqs));
    }
}
