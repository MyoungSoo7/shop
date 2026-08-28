package github.lms.lemuel.common.events.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 계약 픽스처 자체 무결성 검증 — 정본 샘플이 스키마와 어긋나면 계약 테스트 전체가 무의미해지므로,
 * 스키마·샘플 변경 시 이 테스트가 먼저 깨져야 한다 (ADR 0024).
 *
 * <p>아래 목록은 settlement 에서 쇼핑몰만 떼어 오면서 한 번 거짓말을 했다. 스키마·샘플 리소스는
 * 커머스 20종만 남기고 지웠는데 이 목록에는 정산·대출·카드 토픽 31개가 그대로 남아, 23개 케이스가
 * "픽스처가 없다"로 깨졌다. 목록을 고치는 것만으로는 같은 일이 또 일어나므로,
 * {@link #topicListCoversEveryShippedSchema()} 가 목록과 실제 리소스를 대조한다 —
 * 스키마를 추가하고 목록에 안 적으면 그쪽이 깨진다.</p>
 */
class EventContractFixtureTest {

    private static final String SCHEMA_DIR = "contracts/events";
    private static final String SCHEMA_SUFFIX = ".schema.json";

    /**
     * 목록을 명시적으로 두는 이유: 계약은 리뷰 대상이라 diff 에 보여야 한다. 자동 스캔으로 대체하면
     * 토픽이 통째로 사라져도 테스트는 조용히 0건을 돌고 통과한다.
     */
    private static final List<String> SHIPPED_TOPICS = List.of(
            "lemuel.education.course_published",
            "lemuel.giftcard.expired",
            "lemuel.giftcard.registered",
            "lemuel.giftcard.restored",
            "lemuel.giftcard.used",
            "lemuel.marketing.reward_requested",
            "lemuel.order.created",
            "lemuel.organization.created",
            "lemuel.organization.member_joined",
            "lemuel.organization.member_removed",
            "lemuel.organization.member_role_changed",
            "lemuel.payment.captured",
            "lemuel.payment.refunded",
            "lemuel.point.charged",
            "lemuel.point.expired",
            "lemuel.point.granted",
            "lemuel.point.restored",
            "lemuel.point.revoked",
            "lemuel.point.used",
            "lemuel.product.changed",
            "lemuel.seller.tier_changed",
            "lemuel.user.registered");

    @ParameterizedTest
    @ValueSource(strings = {
            "lemuel.education.course_published",
            "lemuel.giftcard.expired",
            "lemuel.giftcard.registered",
            "lemuel.giftcard.restored",
            "lemuel.giftcard.used",
            "lemuel.marketing.reward_requested",
            "lemuel.order.created",
            "lemuel.organization.created",
            "lemuel.organization.member_joined",
            "lemuel.organization.member_removed",
            "lemuel.organization.member_role_changed",
            "lemuel.payment.captured",
            "lemuel.payment.refunded",
            "lemuel.point.charged",
            "lemuel.point.expired",
            "lemuel.point.granted",
            "lemuel.point.restored",
            "lemuel.point.revoked",
            "lemuel.point.used",
            "lemuel.product.changed",
            "lemuel.seller.tier_changed",
            "lemuel.user.registered"
    })
    @DisplayName("모든 토픽의 정본 샘플은 자기 계약 스키마를 통과한다")
    void canonicalSamples_areValidAgainstTheirSchemas(String topic) {
        EventContractValidator.assertValid(topic, EventContractValidator.canonicalSample(topic));
    }

    @Test
    @DisplayName("위 목록은 실제로 배포되는 스키마 전부와 정확히 일치한다 — 한쪽만 지우면 여기서 깨진다")
    void topicListCoversEveryShippedSchema() {
        Set<String> onDisk = shippedSchemaTopics();

        assertThat(onDisk)
                .as("스키마 리소스를 한 건도 못 찾았다 — 대조가 무력화된 것이지 통과한 것이 아니다")
                .isNotEmpty();
        assertThat(new TreeSet<>(SHIPPED_TOPICS))
                .as("@ValueSource 목록과 %s/*%s 가 어긋난다", SCHEMA_DIR, SCHEMA_SUFFIX)
                .isEqualTo(onDisk);
    }

    /**
     * 계약 리소스는 testFixtures 산출물이라 이 모듈의 테스트 클래스패스에는 <b>jar</b> 로 올라온다.
     * 그래서 {@code file:} 만 다루면 실제 CI 에서 대조가 통째로 죽는다 — 두 경우를 다 연다.
     */
    private static Set<String> shippedSchemaTopics() {
        URL dir = EventContractFixtureTest.class.getClassLoader().getResource(SCHEMA_DIR);
        if (dir == null) {
            throw new IllegalStateException(SCHEMA_DIR + " 가 클래스패스에 없다");
        }
        try {
            URI uri = dir.toURI();
            if (!"jar".equals(uri.getScheme())) {
                return topicsIn(Path.of(uri));
            }
            try {
                try (FileSystem fs = FileSystems.newFileSystem(uri, Map.<String, Object>of())) {
                    return topicsIn(fs.getPath(SCHEMA_DIR));
                }
            } catch (FileSystemAlreadyExistsException alreadyOpen) {
                // 다른 테스트가 먼저 열어 둔 jar 파일시스템은 우리가 닫으면 안 된다.
                return topicsIn(FileSystems.getFileSystem(uri).getPath(SCHEMA_DIR));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> topicsIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(SCHEMA_SUFFIX))
                    .map(n -> n.substring(0, n.length() - SCHEMA_SUFFIX.length()))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    @DisplayName("필수 필드 삭제(paymentId 없는 captured)는 계약 위반으로 검출된다")
    void missingRequiredField_isViolation() {
        Set<String> violations = EventContractValidator.validate(
                "lemuel.payment.captured",
                "{\"orderId\":5001,\"amount\":\"45000\"}");
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("타입 변경(amount 문자열→숫자)은 계약 위반으로 검출된다")
    void typeDrift_isViolation() {
        Set<String> violations = EventContractValidator.validate(
                "lemuel.payment.captured",
                "{\"paymentId\":1001,\"orderId\":5001,\"amount\":45000}");
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("금액 필드가 하나도 없는 refunded 는 계약 위반이다 (anyOf)")
    void refundedWithoutAnyAmount_isViolation() {
        Set<String> violations = EventContractValidator.validate(
                "lemuel.payment.refunded",
                "{\"paymentId\":1001,\"orderId\":5001,\"refundId\":42}");
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("optional 필드 추가는 계약 위반이 아니다 (additionalProperties 허용 — 전방 호환)")
    void additiveField_isNotViolation() {
        Set<String> violations = EventContractValidator.validate(
                "lemuel.payment.captured",
                "{\"paymentId\":1001,\"orderId\":5001,\"amount\":\"45000\",\"newOptionalField\":\"x\"}");
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("등록되지 않은 토픽은 명확한 예외를 던진다")
    void unknownTopic_throws() {
        assertThatThrownBy(() -> EventContractValidator.validate("lemuel.unknown.topic", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lemuel.unknown.topic");
    }
}
