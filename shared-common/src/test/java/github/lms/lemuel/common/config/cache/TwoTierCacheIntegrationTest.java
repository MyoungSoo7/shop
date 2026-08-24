package github.lms.lemuel.common.config.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.cache.Cache;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2-tier 캐시(L1 Caffeine + L2 Redis) 통합 검증 — 실제 Redis(Testcontainers).
 *
 * <p>가장 리스크가 큰 부분을 검증한다:
 * <ul>
 *   <li>도메인형 POJO(LocalDateTime/BigDecimal/enum/List&lt;Long&gt;)의 L2 직렬화 round-trip 충실성</li>
 *   <li>L1 miss → L2 적중 → L1 승격</li>
 *   <li>evict 가 L1·L2 모두 제거</li>
 *   <li>Pub/Sub 무효화로 타 인스턴스 L1 이 갱신됨</li>
 * </ul>
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class TwoTierCacheIntegrationTest {

    // Docker 없는 환경에서는 통째로 건너뛴다 — 저장소의 다른 Testcontainers 테스트
    // (AuditLogPersistenceAdapterIT·OutboxClaimConcurrencyIT)와 같은 가드다. 이것만 빠져 있어서
    // 도커를 안 띄운 로컬에서 :shared-common:test 가 initializationError 로 빨갛게 떴고,
    // "환경 탓인 실패"와 "진짜 실패"가 같은 색으로 보이면 게이트를 읽는 눈이 무뎌진다.
    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private RedisTemplate<String, Object> redisTemplate;
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        redisTemplate = new TwoTierCacheConfig().cacheRedisTemplate(connectionFactory);
        stringRedisTemplate = new StringRedisTemplate(connectionFactory);
        stringRedisTemplate.afterPropertiesSet();

        // 테스트 간 격리
        connectionFactory.getConnection().serverCommands().flushDb();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    private TwoTierCacheManager newManager(String originId) {
        CacheInvalidationPublisher publisher = new CacheInvalidationPublisher(stringRedisTemplate, originId);
        return new TwoTierCacheManager(
                CacheNames.ALL, redisTemplate, publisher,
                Duration.ofSeconds(60), Duration.ofSeconds(600), 500, true);
    }

    @Test
    @DisplayName("L1 비운 뒤에도 L2 에서 도메인형 객체가 충실히 역직렬화된다 (List<Long> 타입 유지 포함)")
    void l2RoundTripAfterL1Eviction() {
        TwoTierCacheManager manager = newManager("nodeA");
        TwoTierCache cache = (TwoTierCache) manager.getCache(CacheNames.PRODUCTS);

        Sample sample = sample(1L, "노트북", "1999.99", Sample.Status.ACTIVE, List.of(10L, 20L));
        cache.put(1L, sample);

        // L1 만 비워 L2 경로 강제 (key 는 문자열화되어 저장됨)
        cache.evictLocal("1");

        Cache.ValueWrapper wrapper = cache.get(1L);
        assertThat(wrapper).isNotNull();
        Sample got = (Sample) wrapper.get();
        assertThat(got).isNotNull();
        assertThat(got.getId()).isEqualTo(1L);
        assertThat(got.getName()).isEqualTo("노트북");
        assertThat(got.getPrice()).isEqualByComparingTo(new BigDecimal("1999.99"));
        assertThat(got.getStatus()).isEqualTo(Sample.Status.ACTIVE);
        assertThat(got.getCreatedAt()).isEqualTo(sample.getCreatedAt());
        // 핵심: List<Long> 요소가 Integer 로 변질되지 않아야 한다
        assertThat(got.getTagIds()).containsExactly(10L, 20L);
        assertThat(got.getTagIds().get(0)).isInstanceOf(Long.class);
    }

    @Test
    @DisplayName("List<Sample> 컬렉션 값도 round-trip 된다")
    void listValueRoundTrip() {
        TwoTierCacheManager manager = newManager("nodeA");
        TwoTierCache cache = (TwoTierCache) manager.getCache(CacheNames.PRODUCTS);

        // 실제 캐시 대상 어댑터는 Collectors.toList()(가변 ArrayList)를 반환하므로 동일하게 ArrayList 사용
        List<Sample> samples = new ArrayList<>(List.of(
                sample(1L, "A", "10.00", Sample.Status.ACTIVE, List.of(1L)),
                sample(2L, "B", "20.00", Sample.Status.INACTIVE, List.of(2L, 3L))));
        cache.put("all", samples);
        cache.evictLocal("all");

        @SuppressWarnings("unchecked")
        List<Sample> got = (List<Sample>) cache.get("all").get();
        assertThat(got).hasSize(2);
        assertThat(got.get(1).getName()).isEqualTo("B");
        assertThat(got.get(1).getTagIds()).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("evict 는 L1 과 L2 를 모두 제거한다")
    void evictRemovesFromBothTiers() {
        TwoTierCacheManager manager = newManager("nodeA");
        TwoTierCache cache = (TwoTierCache) manager.getCache(CacheNames.PRODUCTS);

        cache.put(2L, sample(2L, "X", "5.00", Sample.Status.ACTIVE, List.of()));
        cache.evict(2L);
        cache.evictLocal("2");   // 혹시 L1 에 남았다면 제거 — 순수 L2 조회 강제

        assertThat(cache.get(2L)).isNull();
    }

    @Test
    @DisplayName("Pub/Sub 무효화로 다른 인스턴스의 stale L1 이 갱신된다")
    void pubSubInvalidatesOtherNodeL1() throws Exception {
        TwoTierCacheManager managerA = newManager("nodeA");
        TwoTierCacheManager managerB = newManager("nodeB");
        TwoTierCache cacheA = (TwoTierCache) managerA.getCache(CacheNames.PRODUCTS);
        TwoTierCache cacheB = (TwoTierCache) managerB.getCache(CacheNames.PRODUCTS);

        // nodeB 가 무효화 메시지를 수신하도록 리스너 컨테이너 기동
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.afterPropertiesSet();
        container.start();
        container.addMessageListener(
                new CacheInvalidationListener(managerB, "nodeB"),
                new ChannelTopic(CacheInvalidationPublisher.CHANNEL));
        try {
            // 구독이 실제 활성화될 때까지 잠깐 대기
            waitUntil(() -> container.isRunning(), 2000);

            cacheA.put(3L, sample(3L, "v1", "1.00", Sample.Status.ACTIVE, List.of()));
            // nodeB 가 L2 에서 읽어 자기 L1 에 v1 적재
            assertThat(((Sample) cacheB.get(3L).get()).getName()).isEqualTo("v1");

            // nodeA 가 값을 갱신 → Pub/Sub 로 nodeB L1 무효화 메시지 발행
            cacheA.put(3L, sample(3L, "v2", "2.00", Sample.Status.ACTIVE, List.of()));

            // nodeB 가 무효화를 수신해 L1 을 비우고 L2 의 최신값(v2)을 다시 읽을 때까지 폴링
            boolean refreshed = waitUntil(
                    () -> "v2".equals(((Sample) cacheB.get(3L).get()).getName()), 5000);
            assertThat(refreshed)
                    .as("nodeB L1 이 Pub/Sub 무효화로 갱신되어 v2 를 읽어야 한다")
                    .isTrue();
        } finally {
            container.stop();
        }
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 조건 평가 중 일시적 예외(아직 미적재 등)는 무시하고 재시도
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static Sample sample(Long id, String name, String price, Sample.Status status, List<Long> tagIds) {
        Sample s = new Sample();
        s.id = id;
        s.name = name;
        s.price = new BigDecimal(price);
        s.status = status;
        s.tagIds = new ArrayList<>(tagIds);
        s.createdAt = LocalDateTime.of(2026, 6, 11, 10, 30, 0);
        return s;
    }

    /**
     * 캐시되는 도메인 객체(Product/EcommerceCategory)와 동일한 직렬화 특성을 가진 테스트 POJO:
     * private 필드 + public 무인자 생성자 + LocalDateTime/BigDecimal/enum/List&lt;Long&gt;.
     * 패키지가 {@code github.lms.lemuel.} 라 polymorphic 검증기를 통과한다.
     */
    static class Sample {
        enum Status { ACTIVE, INACTIVE }

        private Long id;
        private String name;
        private BigDecimal price;
        private Status status;
        private List<Long> tagIds = new ArrayList<>();
        private LocalDateTime createdAt;

        public Sample() {
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public Status getStatus() {
            return status;
        }

        public List<Long> getTagIds() {
            return tagIds;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }
    }
}
