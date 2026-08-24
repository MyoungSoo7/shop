package github.lms.lemuel.common.config.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TopicProvisioningInitializerTest {

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private static final TopicCatalog CATALOG = TopicCatalog.of(List.of(
            new TopicCatalog.Topic("lemuel.payment.captured", "order-service", "paymentId", 3, 1, 7)));

    /** 브로커 상태를 흉내내는 최소 구현. */
    private static final class StubAdmin implements TopicAdmin {
        private final Map<String, TopicAdmin.TopicState> existing;
        private final List<String> created = new java.util.ArrayList<>();

        StubAdmin(Map<String, TopicAdmin.TopicState> existing) {
            this.existing = new LinkedHashMap<>(existing);
        }

        @Override
        public Map<String, TopicState> describe(Set<String> names) {
            Map<String, TopicState> found = new LinkedHashMap<>();
            names.forEach(n -> {
                if (existing.containsKey(n)) found.put(n, existing.get(n));
            });
            return found;
        }

        @Override
        public void create(TopicCatalog.Spec spec) {
            created.add(spec.name());
        }

        @Override
        public void alterRetention(String name, int retentionDays) {
            // 이 테스트의 관심사가 아니다 — 프로비저너 단위 테스트가 검증한다.
        }
    }

    private TopicProvisioningInitializer initializer(TopicAdmin admin, String module) {
        return new TopicProvisioningInitializer(
                CATALOG, new TopicProvisioner(admin), module, meterRegistry);
    }

    @Test
    @DisplayName("소유 모듈이 지정되면 없는 토픽을 만든다")
    void provisionsOwnedTopics() {
        StubAdmin admin = new StubAdmin(Map.of());

        initializer(admin, "order-service").afterSingletonsInstantiated();

        assertThat(admin.created).contains("lemuel.payment.captured", "lemuel.payment.captured.DLT");
    }

    @Test
    @DisplayName("owner 가 비면 건너뛴다 — 컨슈머 전용 서비스는 토픽을 만들지 않는다")
    void skipsWhenModuleIsBlank() {
        StubAdmin admin = new StubAdmin(Map.of());

        assertThat(initializer(admin, "  ").provisionQuietly()).isZero();
        assertThat(admin.created).isEmpty();
    }

    @Test
    @DisplayName("드리프트를 게이지로 노출한다 — WARN 로그만 남기면 아무도 읽지 않는다")
    void publishesDriftGauge() {
        StubAdmin admin = new StubAdmin(Map.of(
                "lemuel.payment.captured", new TopicAdmin.TopicState(1, 1, 7, true),
                "lemuel.payment.captured.DLT", new TopicAdmin.TopicState(1, 1, 30, true)));

        initializer(admin, "order-service").afterSingletonsInstantiated();

        assertThat(meterRegistry.get(TopicProvisioningInitializer.DRIFT_GAUGE).gauge().value())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("브로커 오류는 삼키고 기동을 막지 않는다")
    void survivesBrokerFailure() {
        TopicAdmin failing = new TopicAdmin() {
            @Override
            public Map<String, TopicState> describe(Set<String> names) {
                throw new InvalidTopicCatalogException("broker down");
            }

            @Override
            public void create(TopicCatalog.Spec spec) {
                throw new IllegalStateException("unreachable");
            }

            @Override
            public void alterRetention(String name, int retentionDays) {
                throw new IllegalStateException("unreachable");
            }
        };

        assertThat(initializer(failing, "order-service").provisionQuietly()).isEqualTo(-1);
    }
}
