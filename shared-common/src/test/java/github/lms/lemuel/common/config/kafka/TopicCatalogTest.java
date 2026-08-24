package github.lms.lemuel.common.config.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 토픽 카탈로그 — 토픽 전송 속성(파티션·보존·순서키·소유자)의 단일 출처 (ADR 0035).
 *
 * <p>여기서 지키는 것은 "파티션 수는 코드 리뷰를 거친 값이어야 한다" 하나다. 키(aggregateId)로
 * 순서를 보장하는 설계에서 파티션 수가 바뀌면 {@code hash(key) % N} 이 바뀌어 같은 애그리거트의
 * 이벤트가 다른 파티션으로 흩어진다 — 순서 보장이 소급 붕괴한다. 그 값이 코드 밖(브로커 자동생성)에
 * 있으면 리뷰할 대상 자체가 없다.
 */
class TopicCatalogTest {

    private static TopicCatalog.Topic topic(String name) {
        return new TopicCatalog.Topic(name, "settlement-service", "settlementId", 3, 1, 7);
    }

    @Nested
    @DisplayName("불변식 — 생성 시점에 거부한다")
    class Invariants {

        @Test
        @DisplayName("토픽 이름이 중복되면 거부한다")
        void rejectsDuplicateNames() {
            assertThatThrownBy(() -> TopicCatalog.of(List.of(
                    topic("lemuel.settlement.confirmed"),
                    topic("lemuel.settlement.confirmed"))))
                    .isInstanceOf(InvalidTopicCatalogException.class)
                    .hasMessageContaining("lemuel.settlement.confirmed");
        }

        @Test
        @DisplayName("순서키가 비면 거부한다 — 무엇으로 순서를 보장하는지 선언하지 않은 토픽은 없다")
        void rejectsBlankOrderingKey() {
            assertThatThrownBy(() -> TopicCatalog.of(List.of(
                    new TopicCatalog.Topic("lemuel.settlement.confirmed", "settlement-service", "  ", 3, 1, 7))))
                    .isInstanceOf(InvalidTopicCatalogException.class)
                    .hasMessageContaining("orderingKey");
        }

        @Test
        @DisplayName("소유자가 비면 거부한다 — 토픽을 만드는 주체가 둘이면 파티션 수가 갈린다")
        void rejectsBlankOwner() {
            assertThatThrownBy(() -> TopicCatalog.of(List.of(
                    new TopicCatalog.Topic("lemuel.settlement.confirmed", "", "settlementId", 3, 1, 7))))
                    .isInstanceOf(InvalidTopicCatalogException.class)
                    .hasMessageContaining("owner");
        }

        @Test
        @DisplayName("파티션 수가 1 미만이면 거부한다")
        void rejectsNonPositivePartitions() {
            assertThatThrownBy(() -> TopicCatalog.of(List.of(
                    new TopicCatalog.Topic("lemuel.settlement.confirmed", "settlement-service", "settlementId", 0, 1, 7))))
                    .isInstanceOf(InvalidTopicCatalogException.class)
                    .hasMessageContaining("partitions");
        }

        @Test
        @DisplayName("lemuel. 접두사가 없으면 거부한다")
        void rejectsForeignNamespace() {
            assertThatThrownBy(() -> TopicCatalog.of(List.of(
                    new TopicCatalog.Topic("other.settlement.confirmed", "settlement-service", "settlementId", 3, 1, 7))))
                    .isInstanceOf(InvalidTopicCatalogException.class)
                    .hasMessageContaining("lemuel.");
        }

        @Test
        @DisplayName(".DLT 는 카탈로그에 직접 등록할 수 없다 — 원본에서 파생되어야 파티션이 갈리지 않는다")
        void rejectsExplicitDltEntry() {
            assertThatThrownBy(() -> TopicCatalog.of(List.of(topic("lemuel.settlement.confirmed.DLT"))))
                    .isInstanceOf(InvalidTopicCatalogException.class)
                    .hasMessageContaining("DLT");
        }

        @Test
        @DisplayName("복제본이 1 미만이면 거부한다 — 내구성도 리뷰 대상이다")
        void rejectsNonPositiveReplicas() {
            assertThatThrownBy(() -> TopicCatalog.of(List.of(
                    new TopicCatalog.Topic("lemuel.settlement.confirmed", "settlement-service", "settlementId", 3, 0, 7))))
                    .isInstanceOf(InvalidTopicCatalogException.class)
                    .hasMessageContaining("replicas");
        }

        @Test
        @DisplayName("보존기간이 1일 미만이면 거부한다")
        void rejectsNonPositiveRetention() {
            assertThatThrownBy(() -> TopicCatalog.of(List.of(
                    new TopicCatalog.Topic("lemuel.settlement.confirmed", "settlement-service", "settlementId", 3, 1, 0))))
                    .isInstanceOf(InvalidTopicCatalogException.class)
                    .hasMessageContaining("retentionDays");
        }
    }

    @Nested
    @DisplayName("JSON 로딩")
    class Loading {

        @Test
        @DisplayName("JSON 스트림에서 읽는다")
        void parsesJson() {
            String json = """
                    { "topics": [
                      { "name": "lemuel.payout.completed", "owner": "settlement-service",
                        "orderingKey": "payoutId", "partitions": 4, "replicas": 1, "retentionDays": 7 }
                    ]}""";

            TopicCatalog catalog = TopicCatalog.load(
                    new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            assertThat(catalog.find("lemuel.payout.completed").orElseThrow().partitions()).isEqualTo(4);
        }

        @Test
        @DisplayName("깨진 JSON 은 타입 예외로 올린다 — 조용히 빈 카탈로그가 되면 토픽이 통째로 안 만들어진다")
        void rejectsMalformedJson() {
            assertThatThrownBy(() -> TopicCatalog.load(
                    new java.io.ByteArrayInputStream("{ not json".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                    .isInstanceOf(InvalidTopicCatalogException.class);
        }
    }

    @Nested
    @DisplayName("DLT 파생")
    class DeadLetterTopics {

        @Test
        @DisplayName("DLT 는 원본과 같은 파티션 수를 갖는다 — 실측 사고(원본 6 vs DLT 3)의 회귀 가드")
        void dltMirrorsSourcePartitions() {
            TopicCatalog.Topic source = new TopicCatalog.Topic(
                    "lemuel.payment.captured", "order-service", "paymentId", 6, 1, 7);

            TopicCatalog.Spec dlt = source.deadLetterSpec();

            assertThat(dlt.name()).isEqualTo("lemuel.payment.captured.DLT");
            assertThat(dlt.partitions()).isEqualTo(source.partitions());
        }

        @Test
        @DisplayName("DLT 보존기간은 원본보다 길다 — 운영자가 사후 분석할 시간을 남긴다")
        void dltRetainsLongerThanSource() {
            TopicCatalog.Spec dlt = topic("lemuel.settlement.confirmed").deadLetterSpec();

            assertThat(dlt.retentionDays()).isGreaterThan(7);
        }
    }

    @Nested
    @DisplayName("조회")
    class Lookup {

        @Test
        @DisplayName("소유 서비스로 필터링한다 — 각 토픽을 만드는 주체는 프로듀서 하나뿐이다")
        void filtersByOwner() {
            TopicCatalog catalog = TopicCatalog.of(List.of(
                    new TopicCatalog.Topic("lemuel.payment.captured", "order-service", "paymentId", 3, 1, 7),
                    new TopicCatalog.Topic("lemuel.settlement.confirmed", "settlement-service", "settlementId", 3, 1, 7)));

            assertThat(catalog.ownedBy("order-service"))
                    .extracting(TopicCatalog.Topic::name)
                    .containsExactly("lemuel.payment.captured");
        }

        @Test
        @DisplayName("소유 토픽이 없는 서비스는 빈 목록을 받는다 — 컨슈머 전용 서비스는 토픽을 만들지 않는다")
        void consumerOnlyServiceOwnsNothing() {
            TopicCatalog catalog = TopicCatalog.of(List.of(topic("lemuel.settlement.confirmed")));

            assertThat(catalog.ownedBy("account-service")).isEmpty();
        }
    }

    @Nested
    @DisplayName("정본 리소스 — 실제 배포되는 카탈로그")
    class ShippedCatalog {

        private final TopicCatalog catalog = TopicCatalog.loadDefault();

        @Test
        @DisplayName("클래스패스에서 로드되고 불변식을 통과한다")
        void loadsAndValidates() {
            assertThat(catalog.all()).isNotEmpty();
        }

        @Test
        @DisplayName("빈 스캔으로 통과하지 않는다 — 토픽이 비정상적으로 적으면 파서가 깨진 것이다")
        void isNotAccidentallyEmpty() {
            assertThat(catalog.all().size())
                    .as("cross-service 토픽은 40개를 넘는다 — 이보다 적으면 리소스 로딩이나 파싱이 깨졌다")
                    .isGreaterThanOrEqualTo(40);
        }

        @Test
        @DisplayName("모든 토픽의 소유자는 실재하는 Gradle 모듈이다")
        void ownersAreRealModules() {
            assertThat(catalog.all())
                    .allSatisfy(t -> assertThat(t.owner()).endsWith("-service"));
        }

        @Test
        @DisplayName("모든 토픽이 복제본을 선언한다 — 코드 상수로 숨어 있던 값을 카탈로그로 끌어냈다")
        void everyTopicDeclaresReplicas() {
            assertThat(catalog.all()).allSatisfy(t -> assertThat(t.replicas()).isPositive());
        }

        @Test
        @DisplayName("payment.captured 는 order-service 소유다 — 발행자가 소유자라는 규칙의 대표 사례")
        void paymentCapturedIsOwnedByOrderService() {
            assertThat(catalog.find("lemuel.payment.captured"))
                    .get()
                    .extracting(TopicCatalog.Topic::owner)
                    .isEqualTo("order-service");
        }
    }
}
