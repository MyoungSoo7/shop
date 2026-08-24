package github.lms.lemuel.common.config.kafka;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 토픽 프로비저닝 — 속성마다 다르게 다룬다.
 *
 * <p>파티션은 만들 때만(변경 = 키 재해시 = 순서 보장 소급 붕괴), 보존기간은 항상 맞춘다(되돌릴 수
 * 있고 키·순서와 무관), 복제본은 보고만(브로커 수 종속).
 */
class TopicProvisionerTest {

    /** 테스트용 인메모리 브로커. */
    private static final class FakeTopicAdmin implements TopicAdmin {
        private final Map<String, TopicState> existing;
        private final List<String> created = new ArrayList<>();
        private final List<String> retentionCalls = new ArrayList<>();

        FakeTopicAdmin(Map<String, TopicState> existing) {
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
            created.add(spec.name() + ":p" + spec.partitions() + ":r" + spec.replicas()
                    + ":" + spec.retentionDays() + "d");
        }

        @Override
        public void alterRetention(String name, int retentionDays) {
            retentionCalls.add(name + "=" + retentionDays + "d");
        }
    }

    private static TopicCatalog catalogOf(TopicCatalog.Topic... topics) {
        return TopicCatalog.of(List.of(topics));
    }

    private static TopicCatalog.Topic paymentCaptured(int partitions) {
        return new TopicCatalog.Topic("lemuel.payment.captured", "order-service", "paymentId",
                partitions, 1, 7);
    }

    /** 카탈로그와 완전히 일치하고 보존기간까지 고정된 상태. */
    private static TopicAdmin.TopicState settled(int partitions, int retentionDays) {
        return new TopicAdmin.TopicState(partitions, 1, retentionDays, true);
    }

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("없는 토픽은 카탈로그 스펙 그대로 만든다 — 복제본도 카탈로그가 정한다")
        void createsMissingTopic() {
            FakeTopicAdmin admin = new FakeTopicAdmin(Map.of());

            TopicProvisioner.Report report =
                    new TopicProvisioner(admin).provision(catalogOf(paymentCaptured(3)), "order-service");

            assertThat(admin.created).contains("lemuel.payment.captured:p3:r1:7d");
            assertThat(report.created()).contains("lemuel.payment.captured");
        }

        @Test
        @DisplayName("DLT 도 원본과 같은 파티션·복제본으로, 더 긴 보존기간으로 함께 만든다")
        void createsDeadLetterTopicAlongside() {
            FakeTopicAdmin admin = new FakeTopicAdmin(Map.of());

            new TopicProvisioner(admin).provision(catalogOf(paymentCaptured(6)), "order-service");

            assertThat(admin.created).contains("lemuel.payment.captured.DLT:p6:r1:30d");
        }

        @Test
        @DisplayName("소유하지 않은 토픽은 만들지 않는다")
        void ignoresTopicsOwnedByOtherServices() {
            FakeTopicAdmin admin = new FakeTopicAdmin(Map.of());

            new TopicProvisioner(admin).provision(catalogOf(paymentCaptured(3)), "settlement-service");

            assertThat(admin.created).isEmpty();
        }
    }

    @Nested
    @DisplayName("파티션 — 만들 때만, 절대 바꾸지 않는다")
    class Partitions {

        @Test
        @DisplayName("파티션이 카탈로그보다 적어도 늘리지 않고 드리프트로 보고한다")
        void neverGrowsExistingTopic() {
            FakeTopicAdmin admin = new FakeTopicAdmin(Map.of(
                    "lemuel.payment.captured", settled(1, 7),
                    "lemuel.payment.captured.DLT", settled(1, 30)));

            TopicProvisioner.Report report =
                    new TopicProvisioner(admin).provision(catalogOf(paymentCaptured(3)), "order-service");

            assertThat(admin.created).as("증설도 재생성도 없어야 한다").isEmpty();
            assertThat(report.drifted())
                    .extracting(TopicProvisioner.Drift::topic, TopicProvisioner.Drift::property,
                            TopicProvisioner.Drift::declared, TopicProvisioner.Drift::actual)
                    .contains(Tuple.tuple("lemuel.payment.captured", "partitions", 3, 1));
        }

        @Test
        @DisplayName("파티션이 카탈로그보다 많아도 드리프트로 보고한다")
        void reportsUpwardDriftToo() {
            FakeTopicAdmin admin = new FakeTopicAdmin(Map.of(
                    "lemuel.payment.captured", settled(6, 7),
                    "lemuel.payment.captured.DLT", settled(6, 30)));

            TopicProvisioner.Report report =
                    new TopicProvisioner(admin).provision(catalogOf(paymentCaptured(3)), "order-service");

            assertThat(report.drifted())
                    .extracting(TopicProvisioner.Drift::property, TopicProvisioner.Drift::actual)
                    .contains(Tuple.tuple("partitions", 6));
        }
    }

    @Nested
    @DisplayName("보존기간 — 항상 맞춘다")
    class Retention {

        @Test
        @DisplayName("보존기간이 다르면 고쳐 맞춘다 — 되돌릴 수 있고 키·순서와 무관하다")
        void alignsMismatchedRetention() {
            FakeTopicAdmin admin = new FakeTopicAdmin(Map.of(
                    "lemuel.payment.captured", settled(3, 1),
                    "lemuel.payment.captured.DLT", settled(3, 30)));

            TopicProvisioner.Report report =
                    new TopicProvisioner(admin).provision(catalogOf(paymentCaptured(3)), "order-service");

            assertThat(admin.retentionCalls).containsExactly("lemuel.payment.captured=7d");
            assertThat(report.retentionAligned()).containsExactly("lemuel.payment.captured");
        }

        @Test
        @DisplayName("값이 같아도 토픽에 고정돼 있지 않으면 고정한다 — 상속은 '지금 우연히 같은 값'일 뿐이다")
        void pinsInheritedRetentionEvenWhenValueMatches() {
            FakeTopicAdmin admin = new FakeTopicAdmin(Map.of(
                    "lemuel.payment.captured", new TopicAdmin.TopicState(3, 1, 7, false),
                    "lemuel.payment.captured.DLT", settled(3, 30)));

            TopicProvisioner.Report report =
                    new TopicProvisioner(admin).provision(catalogOf(paymentCaptured(3)), "order-service");

            assertThat(admin.retentionCalls)
                    .as("클러스터 기본값이 바뀌면 조용히 따라 바뀌는 상태를 끊어야 한다")
                    .containsExactly("lemuel.payment.captured=7d");
            assertThat(report.retentionAligned()).containsExactly("lemuel.payment.captured");
        }

        @Test
        @DisplayName("이미 고정돼 있고 값도 맞으면 건드리지 않는다")
        void leavesPinnedMatchingRetentionAlone() {
            FakeTopicAdmin admin = new FakeTopicAdmin(Map.of(
                    "lemuel.payment.captured", settled(3, 7),
                    "lemuel.payment.captured.DLT", settled(3, 30)));

            TopicProvisioner.Report report =
                    new TopicProvisioner(admin).provision(catalogOf(paymentCaptured(3)), "order-service");

            assertThat(admin.retentionCalls).isEmpty();
            assertThat(report.retentionAligned()).isEmpty();
            assertThat(report.drifted()).isEmpty();
        }
    }

    @Nested
    @DisplayName("복제본 — 보고만")
    class Replicas {

        @Test
        @DisplayName("복제본이 다르면 드리프트로 보고하고 고치지 않는다 — 파티션 재배치가 필요하다")
        void reportsReplicaDriftWithoutFixing() {
            FakeTopicAdmin admin = new FakeTopicAdmin(Map.of(
                    "lemuel.payment.captured", new TopicAdmin.TopicState(3, 3, 7, true),
                    "lemuel.payment.captured.DLT", new TopicAdmin.TopicState(3, 3, 30, true)));

            TopicProvisioner.Report report =
                    new TopicProvisioner(admin).provision(catalogOf(paymentCaptured(3)), "order-service");

            assertThat(admin.created).isEmpty();
            assertThat(report.drifted())
                    .extracting(TopicProvisioner.Drift::property, TopicProvisioner.Drift::declared,
                            TopicProvisioner.Drift::actual)
                    .contains(Tuple.tuple("replicas", 1, 3));
        }
    }
}
