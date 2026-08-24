package github.lms.lemuel.common.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * (정책, 키) 쌍별 Bucket 보관소.
 *
 * <p>버킷은 최초 요청 시 lazy 생성되고 in-memory 로 유지된다 (단일 노드 가정).
 * 장시간 미사용 버킷 GC 는 Bucket4j 가 refill 기반으로 관리.
 */
@Component
public class RateLimitRegistry {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public Bucket resolve(RateLimitPolicy policy, String key) {
        String composite = policy.name() + "|" + key;
        return buckets.computeIfAbsent(composite, k -> buildBucket(policy));
    }

    private Bucket buildBucket(RateLimitPolicy policy) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(policy.capacity())
                .refillGreedy(policy.capacity(), policy.window())
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    public int size() {
        return buckets.size();
    }

    /** 테스트 편의용 — 버킷 전체 초기화. */
    public void reset() {
        buckets.clear();
    }
}
