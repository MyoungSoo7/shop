package github.lms.lemuel.operation.notification.adapter.out.dedupe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 멱등 게이트 — 첫 목격/재전달/TTL 만료, 그리고 동시 호출에서 정확히 1승. */
class InMemoryTtlDedupeStoreTest {

    @Test
    @DisplayName("첫 목격은 true, 재전달은 false")
    void firstSightTrueRedeliveryFalse() {
        InMemoryTtlDedupeStore store = new InMemoryTtlDedupeStore();

        assertTrue(store.markIfFirst("evt-1"));
        assertFalse(store.markIfFirst("evt-1"));
        assertFalse(store.markIfFirst("evt-1"));
    }

    @Test
    @DisplayName("서로 다른 id 는 독립적이다")
    void distinctIdsAreIndependent() {
        InMemoryTtlDedupeStore store = new InMemoryTtlDedupeStore();

        assertTrue(store.markIfFirst("a"));
        assertTrue(store.markIfFirst("b"));
    }

    @Test
    @DisplayName("TTL 이 지나면 다시 첫 목격으로 취급된다")
    void entryExpiresAfterTtlSoItIsTreatedAsFirstAgain() {
        Instant[] now = {Instant.parse("2026-01-01T00:00:00Z")};
        InMemoryTtlDedupeStore store = new InMemoryTtlDedupeStore(Duration.ofMinutes(10), () -> now[0]);

        assertTrue(store.markIfFirst("evt"));
        assertFalse(store.markIfFirst("evt"));

        now[0] = now[0].plus(Duration.ofMinutes(11)); // TTL 경과
        assertTrue(store.markIfFirst("evt"));
    }

    @Test
    @DisplayName("TTL 경계 직전은 아직 중복이다")
    void justBeforeTtlBoundaryIsStillDuplicate() {
        Instant[] now = {Instant.parse("2026-01-01T00:00:00Z")};
        InMemoryTtlDedupeStore store = new InMemoryTtlDedupeStore(Duration.ofMinutes(10), () -> now[0]);

        assertTrue(store.markIfFirst("evt"));
        now[0] = now[0].plus(Duration.ofMinutes(9).plusSeconds(59));

        assertFalse(store.markIfFirst("evt"), "TTL 안이면 여전히 중복이어야 한다");
    }

    @Test
    @DisplayName("동시에 같은 id 를 던져도 정확히 한 번만 통과한다")
    void concurrentMarkOfSameIdLetsExactlyOneThrough() throws Exception {
        InMemoryTtlDedupeStore store = new InMemoryTtlDedupeStore();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    if (store.markIfFirst("same-event")) {
                        winners.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "경쟁자들이 끝나지 않았다");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, winners.get(), "멱등 게이트를 통과한 스레드는 정확히 하나여야 한다");
    }

    @Test
    @DisplayName("스윕 임계를 넘겨도 유효 항목은 살아남는다")
    void sweepDoesNotEvictLiveEntries() {
        Instant[] now = {Instant.parse("2026-01-01T00:00:00Z")};
        InMemoryTtlDedupeStore store = new InMemoryTtlDedupeStore(Duration.ofMinutes(30), () -> now[0]);

        // 스윕 임계(1024)를 넘겨 만료 스캔이 실제로 돌게 한다.
        List<String> ids = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 1100; i++) {
            String id = "evt-" + i;
            ids.add(id);
            assertTrue(store.markIfFirst(id));
        }

        // TTL 안이므로 전부 여전히 중복으로 보여야 한다.
        Set<String> sample = Set.of(ids.getFirst(), ids.get(500), ids.getLast());
        for (String id : sample) {
            assertFalse(store.markIfFirst(id), id + " 는 TTL 안이라 중복이어야 한다");
        }
    }
}
