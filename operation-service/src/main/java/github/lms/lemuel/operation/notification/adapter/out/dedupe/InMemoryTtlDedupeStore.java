package github.lms.lemuel.operation.notification.adapter.out.dedupe;

import github.lms.lemuel.operation.notification.application.port.out.DedupeStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@link DedupeStore} 포트의 인메모리 TTL 구현 — 아웃바운드 어댑터라 애플리케이션 계층은
 * 구현을 모른다.
 *
 * <p>단일 인스턴스 MVP 기준으로 충분하다. 재시작하면 사라지고 레플리카끼리 공유되지 않는다
 * (내구 멱등이 필요하면 Redis/DB 어댑터로 교체 — 그 지점이 이 클래스 하나로 국한된다).
 */
public class InMemoryTtlDedupeStore implements DedupeStore {

    /** 이 크기 아래에서는 만료 스윕을 돌리지 않는다 — 매 호출 전수 스캔을 피하는 값싼 가드. */
    private static final int SWEEP_THRESHOLD = 1024;

    private final Duration ttl;
    private final Supplier<Instant> clock;
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    public InMemoryTtlDedupeStore() {
        this(Duration.ofMinutes(30), Instant::now);
    }

    public InMemoryTtlDedupeStore(Duration ttl, Supplier<Instant> clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public boolean markIfFirst(String id) {
        Instant now = clock.get();
        evictExpired(now);
        // putIfAbsent 가 null 을 돌려주는 것은 키가 없었을 때뿐 = 첫 목격.
        Instant prior = seen.putIfAbsent(id, now.plus(ttl));
        if (prior != null && prior.isAfter(now)) {
            return false; // 아직 유효한 기존 항목 → 중복
        }
        if (prior != null) {
            // 만료된 항목이 남아 있었다 — 갱신하고 첫 목격으로 취급한다.
            seen.put(id, now.plus(ttl));
        }
        return true;
    }

    private void evictExpired(Instant now) {
        if (seen.size() < SWEEP_THRESHOLD) {
            return;
        }
        seen.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }
}
