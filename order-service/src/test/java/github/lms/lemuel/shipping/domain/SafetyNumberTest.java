package github.lms.lemuel.shipping.domain;

import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 안심번호(수취인 가상번호) 풀.
 *
 * <p>배송 과정에서 기사·판매자에게 수취인 실번호를 그대로 넘기지 않기 위한 장치다. 번호는 유한한
 * 풀이라 배정과 회수가 정확해야 한다 — 회수되지 않으면 풀이 마르고, 만료 전에 회수되면 배송 중인
 * 주문의 연락 수단이 끊긴다.
 */
@DisplayName("SafetyNumber — 가상번호 배정·회수")
class SafetyNumberTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 20, 10, 0, 0, 0, ZoneOffset.UTC);

    private static SafetyNumber available() {
        return SafetyNumber.rehydrate(1L, "050-1234-0001", SafetyNumberStatus.AVAILABLE, null, null, null);
    }

    @Test
    @DisplayName("배정하면 주문·만료시각이 붙고 상태가 ASSIGNED 로 바뀐다")
    void assignSetsOrderAndExpiry() {
        SafetyNumber number = available();

        number.assignTo(77L, NOW, 7);

        assertThat(number.getStatus()).isEqualTo(SafetyNumberStatus.ASSIGNED);
        assertThat(number.getOrderId()).isEqualTo(77L);
        assertThat(number.getExpiresAt()).isEqualTo(NOW.plusDays(7));
    }

    @Test
    @DisplayName("이미 배정된 번호는 다시 배정할 수 없다 — 두 주문이 같은 번호를 쓰면 통화가 뒤섞인다")
    void doubleAssignRejected() {
        SafetyNumber number = available();
        number.assignTo(77L, NOW, 7);

        assertThatThrownBy(() -> number.assignTo(88L, NOW, 7))
                .isInstanceOf(ShipmentInvariantViolationException.class);
    }

    @Test
    @DisplayName("회수하면 풀로 돌아가고 주문 연결이 끊긴다")
    void releaseReturnsToPool() {
        SafetyNumber number = available();
        number.assignTo(77L, NOW, 7);

        number.release();

        assertThat(number.getStatus()).isEqualTo(SafetyNumberStatus.AVAILABLE);
        assertThat(number.getOrderId()).isNull();
        assertThat(number.getExpiresAt()).isNull();
    }

    @Test
    @DisplayName("배정되지 않은 번호는 회수 대상이 아니다")
    void releaseUnassignedRejected() {
        assertThatThrownBy(() -> available().release())
                .isInstanceOf(ShipmentInvariantViolationException.class);
    }

    @Test
    @DisplayName("만료 판정은 시각 비교 — 만료 시각 정각은 아직 유효하다")
    void expiryBoundary() {
        SafetyNumber number = available();
        number.assignTo(77L, NOW, 7);

        assertThat(number.isExpiredAt(NOW.plusDays(7))).isFalse();
        assertThat(number.isExpiredAt(NOW.plusDays(7).plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("배정되지 않은 번호는 만료되지 않는다")
    void availableNeverExpires() {
        assertThat(available().isExpiredAt(NOW.plusYears(10))).isFalse();
    }

    @Test
    @DisplayName("불변식 — 주문·기준시각 누락, 0 이하 유효일은 거절")
    void assignInvariants() {
        assertThatThrownBy(() -> available().assignTo(null, NOW, 7))
                .isInstanceOf(ShipmentInvariantViolationException.class);
        assertThatThrownBy(() -> available().assignTo(1L, null, 7))
                .isInstanceOf(ShipmentInvariantViolationException.class);
        assertThatThrownBy(() -> available().assignTo(1L, NOW, 0))
                .isInstanceOf(ShipmentInvariantViolationException.class);
    }

    @Test
    @DisplayName("번호 형식 — 비어 있으면 풀에 들어올 수 없다")
    void numberRequired() {
        assertThatThrownBy(() -> SafetyNumber.ofPool("  "))
                .isInstanceOf(ShipmentInvariantViolationException.class);
        assertThat(SafetyNumber.ofPool("050-1234-0002").getStatus())
                .isEqualTo(SafetyNumberStatus.AVAILABLE);
    }

    @Test
    @DisplayName("실번호 마스킹 — 가상번호가 있으면 그것만 노출한다")
    void maskRealNumber() {
        SafetyNumber number = available();
        number.assignTo(77L, NOW, 7);

        assertThat(number.getVirtualNumber()).isEqualTo("050-1234-0001");
    }
}
