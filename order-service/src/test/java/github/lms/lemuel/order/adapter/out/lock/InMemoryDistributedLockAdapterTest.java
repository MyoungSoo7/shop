package github.lms.lemuel.order.adapter.out.lock;

import github.lms.lemuel.order.application.port.out.LockAcquisitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryDistributedLockAdapterTest {

    private final InMemoryDistributedLockAdapter adapter = new InMemoryDistributedLockAdapter();

    @Test
    @DisplayName("락 구간의 결과를 그대로 반환한다")
    void returnsActionResult() {
        String result = adapter.executeWithLock("k", Duration.ofSeconds(1), Duration.ofSeconds(5), () -> "ok");
        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("같은 키는 상호배제 — 보유 중이면 대기 시간 초과 시 LockAcquisitionException")
    void mutualExclusionTimesOut() throws InterruptedException {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> adapter.executeWithLock("k", Duration.ofSeconds(5), Duration.ofSeconds(10), () -> {
            holding.countDown();
            await(release);
            return null;
        }));
        holder.start();
        holding.await(); // holder 가 락을 잡을 때까지 대기

        // 같은 키로 짧게 시도 → 타임아웃
        assertThatThrownBy(() ->
                adapter.executeWithLock("k", Duration.ofMillis(100), Duration.ofSeconds(10), () -> "x"))
                .isInstanceOf(LockAcquisitionException.class);

        release.countDown();
        holder.join();

        // 해제 후에는 정상 획득
        assertThat(adapter.executeWithLock("k", Duration.ofSeconds(1), Duration.ofSeconds(10), () -> "ok"))
                .isEqualTo("ok");
    }

    @Test
    @DisplayName("동시 진입은 직렬화되어 임계 구역 카운터에 경쟁이 없다")
    void serializesConcurrentAccess() throws InterruptedException {
        int threads = 20;
        int[] counter = {0};
        CountDownLatch done = new CountDownLatch(threads);
        Runnable task = () -> {
            adapter.executeWithLock("same-key", Duration.ofSeconds(10), Duration.ofSeconds(10), () -> {
                int v = counter[0];
                counter[0] = v + 1; // 직렬화 안 되면 lost update 발생
                return null;
            });
            done.countDown();
        };
        for (int i = 0; i < threads; i++) {
            new Thread(task).start();
        }
        done.await();
        assertThat(counter[0]).isEqualTo(threads);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
