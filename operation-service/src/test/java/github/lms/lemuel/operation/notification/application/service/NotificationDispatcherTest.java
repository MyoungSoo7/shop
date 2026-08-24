package github.lms.lemuel.operation.notification.application.service;

import github.lms.lemuel.operation.notification.adapter.out.dedupe.InMemoryTtlDedupeStore;
import github.lms.lemuel.operation.notification.application.ChannelResult;
import github.lms.lemuel.operation.notification.application.DispatchResult;
import github.lms.lemuel.operation.notification.application.port.out.NotificationChannel;
import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 팬아웃 코어 규칙 — 채널 격리·타임아웃·재시도·멱등.
 *
 * <p>이관 전(Kotlin/코루틴) 케이스를 1:1 로 보존하되, 원본이 "둘 다 호출됐다"까지만 봤던
 * 동시성은 <b>배리어로 실제 동시성</b>을 증명하도록 강화했다 — 순차 실행이면 배리어에서
 * 시간초과로 깨진다(코루틴 → 가상스레드 이관에서 조용히 순차가 돼도 테스트가 잡는다).
 */
class NotificationDispatcherTest {

    private static final Notification SAMPLE =
            new Notification(NotificationType.GENERIC, "x@y.z", "s", "b", null);

    /** 테스트용 채널 — mock 대신 손으로 쓴 fake 라 호출 횟수·동시성 관찰이 명시적이다. */
    private static final class FakeChannel implements NotificationChannel {
        private final String name;
        private final boolean enabled;
        private final ThrowingRunnable behaviour;
        private final AtomicInteger calls = new AtomicInteger();

        FakeChannel(String name, boolean enabled, ThrowingRunnable behaviour) {
            this.name = name;
            this.enabled = enabled;
            this.behaviour = behaviour;
        }

        static FakeChannel ok(String name) {
            return new FakeChannel(name, true, () -> { });
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void send(Notification notification) throws Exception {
            calls.incrementAndGet();
            behaviour.run();
        }

        int calls() {
            return calls.get();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Map<String, ChannelResult> byName(DispatchResult result) {
        return result.results().stream()
                .collect(Collectors.toMap(ChannelResult::channel, Function.identity()));
    }

    @Test
    @DisplayName("활성 채널이 전부 호출되고 결과가 합산된다")
    void allEnabledChannelsAreInvokedAndAggregated() throws Exception {
        FakeChannel a = FakeChannel.ok("a");
        FakeChannel b = FakeChannel.ok("b");

        try (NotificationDispatcher dispatcher =
                     new NotificationDispatcher(List.of(a, b), new InMemoryTtlDedupeStore())) {
            DispatchResult result = dispatcher.dispatch(SAMPLE);

            assertFalse(result.deduped());
            assertTrue(result.allSucceeded());
            assertEquals(2, result.results().size());
            assertEquals(1, a.calls());
            assertEquals(1, b.calls());
        }
    }

    @Test
    @DisplayName("팬아웃은 실제로 동시에 일어난다 — 순차면 배리어에서 깨진다")
    void fanOutIsGenuinelyConcurrent() throws Exception {
        // 두 채널이 서로를 기다린다. 순차 실행이면 첫 채널이 2초 뒤 BrokenBarrier 로 실패한다.
        CyclicBarrier barrier = new CyclicBarrier(2);
        ThrowingRunnable meetOther = () -> barrier.await(2, TimeUnit.SECONDS);
        FakeChannel a = new FakeChannel("a", true, meetOther);
        FakeChannel b = new FakeChannel("b", true, meetOther);

        try (NotificationDispatcher dispatcher = new NotificationDispatcher(
                List.of(a, b), new InMemoryTtlDedupeStore(), 5_000, 1, 1)) {
            DispatchResult result = dispatcher.dispatch(SAMPLE);

            assertTrue(result.allSucceeded(),
                    "동시 실행이면 두 채널이 배리어에서 만나 둘 다 성공한다: " + result.results());
        }
    }

    @Test
    @DisplayName("비활성 채널은 건너뛴다")
    void disabledChannelsAreSkipped() throws Exception {
        FakeChannel on = FakeChannel.ok("on");
        FakeChannel off = new FakeChannel("off", false, () -> { });

        try (NotificationDispatcher dispatcher =
                     new NotificationDispatcher(List.of(on, off), new InMemoryTtlDedupeStore())) {
            DispatchResult result = dispatcher.dispatch(SAMPLE);

            assertEquals(1, result.results().size());
            assertEquals("on", result.results().getFirst().channel());
            assertEquals(0, off.calls());
        }
    }

    @Test
    @DisplayName("한 채널의 실패가 다른 채널을 막지 않는다 — 격리 후 합산")
    void oneFailingChannelDoesNotBlockOthers() throws Exception {
        FakeChannel good = FakeChannel.ok("good");
        FakeChannel bad = new FakeChannel("bad", true, () -> {
            throw new IllegalStateException("boom");
        });

        // maxAttempts=1 로 재시도 백오프 없이 빠르게.
        try (NotificationDispatcher dispatcher = new NotificationDispatcher(
                List.of(good, bad), new InMemoryTtlDedupeStore(), 3_000, 1, 1)) {
            DispatchResult result = dispatcher.dispatch(SAMPLE);

            Map<String, ChannelResult> results = byName(result);
            assertInstanceOf(ChannelResult.Success.class, results.get("good"));
            ChannelResult.Failure failure = assertInstanceOf(ChannelResult.Failure.class, results.get("bad"));
            assertEquals("boom", failure.error());
            assertTrue(result.anySucceeded());
            assertFalse(result.allSucceeded());
        }
    }

    @Test
    @DisplayName("느린 채널은 타임아웃되지만 빠른 채널을 막지 않는다")
    void slowChannelTimesOutWithoutBlockingFastChannel() throws Exception {
        FakeChannel fast = FakeChannel.ok("fast");
        FakeChannel slow = new FakeChannel("slow", true, () -> Thread.sleep(10_000));

        try (NotificationDispatcher dispatcher = new NotificationDispatcher(
                List.of(fast, slow), new InMemoryTtlDedupeStore(), 100, 1, 1)) {
            DispatchResult result = dispatcher.dispatch(SAMPLE);

            Map<String, ChannelResult> results = byName(result);
            assertInstanceOf(ChannelResult.Success.class, results.get("fast"));
            ChannelResult.Failure failure = assertInstanceOf(ChannelResult.Failure.class, results.get("slow"));
            assertTrue(failure.error().contains("timeout"), "타임아웃 사유가 남아야 한다: " + failure.error());
        }
    }

    @Test
    @DisplayName("일시적 실패 뒤 두 번째 시도에서 성공한다")
    void retrySucceedsOnSecondAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FakeChannel flaky = new FakeChannel("flaky", true, () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("transient");
            }
        });

        try (NotificationDispatcher dispatcher = new NotificationDispatcher(
                List.of(flaky), new InMemoryTtlDedupeStore(), 3_000, 3, 1)) {
            DispatchResult result = dispatcher.dispatch(SAMPLE);

            ChannelResult.Success success =
                    assertInstanceOf(ChannelResult.Success.class, result.results().getFirst());
            assertEquals(2, success.attempts());
        }
    }

    @Test
    @DisplayName("재시도를 모두 소진하면 마지막 사유로 실패한다")
    void exhaustedRetriesReportLastError() throws Exception {
        FakeChannel always = new FakeChannel("always", true, () -> {
            throw new IllegalStateException("still down");
        });

        try (NotificationDispatcher dispatcher = new NotificationDispatcher(
                List.of(always), new InMemoryTtlDedupeStore(), 3_000, 3, 1)) {
            DispatchResult result = dispatcher.dispatch(SAMPLE);

            ChannelResult.Failure failure =
                    assertInstanceOf(ChannelResult.Failure.class, result.results().getFirst());
            assertEquals(3, failure.attempts());
            assertEquals("still down", failure.error());
            assertEquals(3, always.calls());
            assertFalse(result.anySucceeded());
        }
    }

    @Test
    @DisplayName("같은 eventId 는 한 번만 발송된다")
    void duplicateEventIdIsDispatchedOnce() throws Exception {
        FakeChannel channel = FakeChannel.ok("c");

        try (NotificationDispatcher dispatcher =
                     new NotificationDispatcher(List.of(channel), new InMemoryTtlDedupeStore())) {
            Notification n = SAMPLE.withEventId("evt-99");

            DispatchResult first = dispatcher.dispatch(n);
            DispatchResult second = dispatcher.dispatch(n);

            assertFalse(first.deduped());
            assertTrue(second.deduped());
            assertTrue(second.results().isEmpty());
            assertEquals(1, channel.calls(), "두 번 dispatch 해도 채널 발송은 1회");
        }
    }

    @Test
    @DisplayName("활성 채널이 하나도 없으면 빈 결과 — 중복 스킵과 구분된다")
    void noEnabledChannelsYieldsEmptyNonDedupedResult() throws Exception {
        FakeChannel off = new FakeChannel("off", false, () -> { });

        try (NotificationDispatcher dispatcher =
                     new NotificationDispatcher(List.of(off), new InMemoryTtlDedupeStore())) {
            DispatchResult result = dispatcher.dispatch(SAMPLE);

            assertFalse(result.deduped(), "설정 오류(채널 0개)는 중복 스킵이 아니다");
            assertTrue(result.results().isEmpty());
            assertFalse(result.allSucceeded(), "결과가 비면 전건 성공이 아니다");
        }
    }

    @Test
    @DisplayName("eventId 가 없으면 dedupe 하지 않는다 — 두 번 보내는 것이 의도인 경로")
    void nullEventIdIsNeverDeduped() throws Exception {
        FakeChannel channel = FakeChannel.ok("c");

        try (NotificationDispatcher dispatcher =
                     new NotificationDispatcher(List.of(channel), new InMemoryTtlDedupeStore())) {
            dispatcher.dispatch(SAMPLE);
            DispatchResult second = dispatcher.dispatch(SAMPLE);

            assertFalse(second.deduped());
            assertEquals(2, channel.calls());
        }
    }

    @Test
    @DisplayName("결과 목록은 방어 복사된다 — 호출자가 뒤에서 바꿀 수 없다")
    void resultsAreDefensivelyCopied() {
        java.util.List<ChannelResult> mutable = new java.util.ArrayList<>();
        mutable.add(new ChannelResult.Success("a", 1));
        DispatchResult result = new DispatchResult(false, mutable);

        mutable.clear();

        assertEquals(1, result.results().size(), "생성 후 원본을 비워도 결과는 유지된다");
    }

    @Test
    @DisplayName("배리어가 깨지면 실패로 보고된다 — 동시성 테스트의 자기검증")
    void brokenBarrierSurfacesAsFailure() throws Exception {
        // 짝이 없는 배리어(3자리에 1명) — 반드시 시간초과로 깨진다.
        CyclicBarrier lonely = new CyclicBarrier(3);
        FakeChannel stuck = new FakeChannel("stuck", true, () -> lonely.await(100, TimeUnit.MILLISECONDS));

        try (NotificationDispatcher dispatcher = new NotificationDispatcher(
                List.of(stuck), new InMemoryTtlDedupeStore(), 3_000, 1, 1)) {
            DispatchResult result = dispatcher.dispatch(SAMPLE);

            ChannelResult.Failure failure =
                    assertInstanceOf(ChannelResult.Failure.class, result.results().getFirst());
            assertTrue(failure.error() != null && !failure.error().isBlank(),
                    "실패 사유가 비어 있으면 안 된다");
        }
    }

    @Test
    @DisplayName("예외 메시지가 없으면 예외 타입명을 사유로 남긴다")
    void failureWithoutMessageFallsBackToExceptionType() throws Exception {
        FakeChannel silent = new FakeChannel("silent", true, () -> {
            throw new BrokenBarrierException();
        });

        try (NotificationDispatcher dispatcher = new NotificationDispatcher(
                List.of(silent), new InMemoryTtlDedupeStore(), 3_000, 1, 1)) {
            DispatchResult result = dispatcher.dispatch(SAMPLE);

            ChannelResult.Failure failure =
                    assertInstanceOf(ChannelResult.Failure.class, result.results().getFirst());
            assertEquals("BrokenBarrierException", failure.error());
        }
    }
}
