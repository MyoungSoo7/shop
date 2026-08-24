package github.lms.lemuel.common.config.kafka;

import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link TopicAdmin} 의 Kafka {@code AdminClient} 어댑터.
 *
 * <p>파티션 증설·토픽 삭제 경로는 구현하지 않는다 — 포트에 없기 때문이다(설계 의도는 {@link TopicAdmin}).
 */
public class KafkaClientTopicAdmin implements TopicAdmin {

    private static final Logger log = LoggerFactory.getLogger(KafkaClientTopicAdmin.class);
    private static final long TIMEOUT_SEC = 10;

    private final Supplier<Admin> adminFactory;

    public KafkaClientTopicAdmin(KafkaAdmin kafkaAdmin) {
        this(() -> Admin.create(kafkaAdmin.getConfigurationProperties()));
    }

    /** 테스트 진입점 — {@code Admin.create} 가 정적 팩토리라 주입 지점을 따로 연다. */
    KafkaClientTopicAdmin(Supplier<Admin> adminFactory) {
        this.adminFactory = adminFactory;
    }

    @Override
    public Map<String, TopicState> describe(Set<String> names) {
        Map<String, TopicState> found = new LinkedHashMap<>();
        if (names.isEmpty()) return found;

        try (Admin admin = adminFactory.get()) {
            Map<String, KafkaFuture<TopicDescription>> futures = admin.describeTopics(names).topicNameValues();
            Map<String, TopicDescription> described = new LinkedHashMap<>();
            for (Map.Entry<String, KafkaFuture<TopicDescription>> entry : futures.entrySet()) {
                describeOne(entry.getKey(), entry.getValue())
                        .ifPresent(d -> described.put(entry.getKey(), d));
            }
            if (described.isEmpty()) return found;

            Map<String, Config> configs = describeConfigs(admin, described.keySet());
            for (Map.Entry<String, TopicDescription> entry : described.entrySet()) {
                TopicDescription d = entry.getValue();
                found.put(entry.getKey(), toState(d, configs.get(entry.getKey())));
            }
        }
        return found;
    }

    private static TopicState toState(TopicDescription description, Config config) {
        int partitions = description.partitions().size();
        int replicas = description.partitions().isEmpty()
                ? 0 : description.partitions().get(0).replicas().size();
        ConfigEntry retention = config == null ? null : config.get(TopicConfig.RETENTION_MS_CONFIG);
        long ms = retention == null || retention.value() == null ? 0L : parseLong(retention.value());
        // DYNAMIC_TOPIC_CONFIG = 토픽에 명시된 값. 그 외(DEFAULT_CONFIG 등)는 클러스터 기본값 상속이라
        // 지금 값이 같더라도 log_retention_ms 가 바뀌면 조용히 따라 바뀐다.
        boolean pinned = retention != null
                && retention.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG;
        return new TopicState(partitions, replicas, (int) Duration.ofMillis(ms).toDays(), pinned);
    }

    private static long parseLong(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L; // -1(무제한) 등 해석 불가값은 0일로 취급 → 프로비저너가 카탈로그 값으로 고정한다
        }
    }

    private Map<String, Config> describeConfigs(Admin admin, Set<String> names) {
        List<ConfigResource> resources = names.stream()
                .map(n -> new ConfigResource(ConfigResource.Type.TOPIC, n)).toList();
        try {
            Map<ConfigResource, Config> raw =
                    admin.describeConfigs(resources).all().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            Map<String, Config> byName = new LinkedHashMap<>();
            raw.forEach((res, cfg) -> byName.put(res.name(), cfg));
            return byName;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvalidTopicCatalogException("토픽 설정 조회 중 인터럽트", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new InvalidTopicCatalogException("토픽 설정 조회 실패", e);
        }
    }

    @Override
    public void alterRetention(String name, int retentionDays) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, name);
        AlterConfigOp op = new AlterConfigOp(
                new ConfigEntry(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(Duration.ofDays(retentionDays).toMillis())),
                AlterConfigOp.OpType.SET);
        try (Admin admin = adminFactory.get()) {
            admin.incrementalAlterConfigs(Map.of(resource, List.of(op)))
                    .all().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            log.info("Kafka 토픽 보존기간 고정: {} = {}d", name, retentionDays);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvalidTopicCatalogException("보존기간 변경 중 인터럽트: " + name, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new InvalidTopicCatalogException("보존기간 변경 실패: " + name, e);
        }
    }

    /** 없는 토픽은 빈 값 — "파티션 수 0" 같은 애매한 상태로 만들지 않는다. */
    private java.util.Optional<TopicDescription> describeOne(String name, KafkaFuture<TopicDescription> future) {
        try {
            return java.util.Optional.of(future.get(TIMEOUT_SEC, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvalidTopicCatalogException("토픽 조회 중 인터럽트: " + name, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                return java.util.Optional.empty();
            }
            throw new InvalidTopicCatalogException("토픽 조회 실패: " + name, e);
        } catch (TimeoutException e) {
            throw new InvalidTopicCatalogException("토픽 조회 타임아웃: " + name, e);
        }
    }

    @Override
    public void create(TopicCatalog.Spec spec) {
        String name = spec.name();
        NewTopic topic = new NewTopic(name, spec.partitions(), (short) spec.replicas())
                .configs(Map.of(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(Duration.ofDays(spec.retentionDays()).toMillis())));

        try (Admin admin = adminFactory.get()) {
            admin.createTopics(List.of(topic)).all().get(TIMEOUT_SEC, TimeUnit.SECONDS);
            log.info("Kafka 토픽 생성: {} (partitions={}, replicas={}, retention={}d)",
                    name, spec.partitions(), spec.replicas(), spec.retentionDays());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvalidTopicCatalogException("토픽 생성 중 인터럽트: " + name, e);
        } catch (ExecutionException e) {
            // 다중 인스턴스가 동시에 기동하면 한쪽만 이기고 나머지는 이 예외를 본다 — 결과는 같으므로 통과.
            if (e.getCause() instanceof TopicExistsException) {
                log.debug("토픽이 이미 있다(동시 기동): {}", name);
                return;
            }
            throw new InvalidTopicCatalogException("토픽 생성 실패: " + name, e);
        } catch (TimeoutException e) {
            throw new InvalidTopicCatalogException("토픽 생성 타임아웃: " + name, e);
        }
    }
}
