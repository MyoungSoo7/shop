package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InsufficientPointException;
import github.lms.lemuel.point.domain.exception.InvalidPointAmountException;
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PointLot 단위 테스트.
 *
 * <p>로트는 포인트가 예치금과 갈라지는 지점이다 — 적립 1건마다 유효기간과 출처가 다르므로
 * 소비·소멸·복원이 전부 로트 단위로 일어난다.
 */
class PointLotTest {

    private static final Long ACCOUNT_ID = 7L;
    private static final OffsetDateTime GRANTED_AT =
            OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime EXPIRES_AT = GRANTED_AT.plusDays(365);

    private PointLot newLot(String amount) {
        return PointLot.issue(ACCOUNT_ID, PointLotOrigin.ORDER_EARN, new BigDecimal(amount),
                GRANTED_AT, EXPIRES_AT, "ORDER", "1001");
    }

    @Nested
    @DisplayName("issue — 발급")
    class IssueTests {

        @Test
        @DisplayName("발급 직후 remaining = original 이고 ACTIVE 다")
        void issue_startsFull() {
            PointLot lot = newLot("1000");

            assertThat(lot.getOriginalAmount()).isEqualByComparingTo(new BigDecimal("1000"));
            assertThat(lot.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("1000"));
            assertThat(lot.getStatus()).isEqualTo(PointLotStatus.ACTIVE);
            assertThat(lot.getOrigin()).isEqualTo(PointLotOrigin.ORDER_EARN);
        }

        @Test
        @DisplayName("만료일이 적립일보다 이르면 거절한다")
        void issue_rejectsExpiryBeforeGrant() {
            assertThatThrownBy(() -> PointLot.issue(ACCOUNT_ID, PointLotOrigin.ORDER_EARN,
                    new BigDecimal("1000"), GRANTED_AT, GRANTED_AT.minusDays(1), "ORDER", "1001"))
                    .isInstanceOf(InvalidPointStateException.class);
        }

        @Test
        @DisplayName("무기한 로트(만료일 없음)를 허용한다 — 수기 지급 등")
        void issue_allowsNoExpiry() {
            PointLot lot = PointLot.issue(ACCOUNT_ID, PointLotOrigin.MANUAL_GRANT,
                    new BigDecimal("500"), GRANTED_AT, null, "MANUAL", "op-1");

            assertThat(lot.getExpiresAt()).isNull();
            assertThat(lot.isExpiredAt(GRANTED_AT.plusYears(100))).isFalse();
        }

        @Test
        @DisplayName("0원·소수 로트는 거절한다")
        void issue_rejectsInvalidAmount() {
            assertThatThrownBy(() -> PointLot.issue(ACCOUNT_ID, PointLotOrigin.ORDER_EARN,
                    BigDecimal.ZERO, GRANTED_AT, EXPIRES_AT, "ORDER", "1001"))
                    .isInstanceOf(InvalidPointAmountException.class);
            assertThatThrownBy(() -> PointLot.issue(ACCOUNT_ID, PointLotOrigin.ORDER_EARN,
                    new BigDecimal("10.5"), GRANTED_AT, EXPIRES_AT, "ORDER", "1001"))
                    .isInstanceOf(InvalidPointAmountException.class);
        }
    }

    @Nested
    @DisplayName("consume — 소비")
    class ConsumeTests {

        @Test
        @DisplayName("부분 소비하면 remaining 이 줄고 ACTIVE 를 유지한다")
        void consume_partial() {
            PointLot lot = newLot("1000");

            lot.consume(new BigDecimal("300"));

            assertThat(lot.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("700"));
            assertThat(lot.getStatus()).isEqualTo(PointLotStatus.ACTIVE);
        }

        @Test
        @DisplayName("전액 소비하면 EXHAUSTED 로 전이한다 (경계값)")
        void consume_exactBecomesExhausted() {
            PointLot lot = newLot("1000");

            lot.consume(new BigDecimal("1000"));

            assertThat(lot.getRemainingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(lot.getStatus()).isEqualTo(PointLotStatus.EXHAUSTED);
        }

        @Test
        @DisplayName("잔량보다 1원이라도 많으면 거절한다 (경계값)")
        void consume_rejectsOverRemaining() {
            PointLot lot = newLot("1000");

            assertThatThrownBy(() -> lot.consume(new BigDecimal("1001")))
                    .isInstanceOf(InsufficientPointException.class);
            assertThat(lot.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("1000"));
        }

        @Test
        @DisplayName("소진된 로트는 다시 소비할 수 없다 — 종단 상태")
        void consume_rejectedWhenExhausted() {
            PointLot lot = newLot("1000");
            lot.consume(new BigDecimal("1000"));

            assertThatThrownBy(() -> lot.consume(BigDecimal.ONE))
                    .isInstanceOf(InvalidPointStateException.class);
        }
    }

    @Nested
    @DisplayName("restoreConsumed — 환불 복원")
    class RestoreTests {

        @Test
        @DisplayName("부분 소비한 로트에 되돌리면 remaining 이 복구된다")
        void restore_partiallyConsumed() {
            PointLot lot = newLot("1000");
            lot.consume(new BigDecimal("300"));

            lot.restoreConsumed(new BigDecimal("300"));

            assertThat(lot.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("1000"));
            assertThat(lot.getStatus()).isEqualTo(PointLotStatus.ACTIVE);
        }

        @Test
        @DisplayName("소진된 로트도 복원하면 ACTIVE 로 되살아난다 — 원래 만료일이 보존된다")
        void restore_revivesExhaustedLot() {
            PointLot lot = newLot("1000");
            lot.consume(new BigDecimal("1000"));

            lot.restoreConsumed(new BigDecimal("400"));

            assertThat(lot.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("400"));
            assertThat(lot.getStatus()).isEqualTo(PointLotStatus.ACTIVE);
            assertThat(lot.getExpiresAt()).isEqualTo(EXPIRES_AT);
        }

        @Test
        @DisplayName("원 발급액을 넘겨 복원할 수 없다 — 없던 포인트가 생긴다")
        void restore_rejectsOverOriginal() {
            PointLot lot = newLot("1000");
            lot.consume(new BigDecimal("300"));

            assertThatThrownBy(() -> lot.restoreConsumed(new BigDecimal("400")))
                    .isInstanceOf(InvalidPointStateException.class);
        }

        @Test
        @DisplayName("소멸된 로트에는 복원할 수 없다 — 새 로트를 발급해야 한다")
        void restore_rejectedWhenExpired() {
            PointLot lot = newLot("1000");
            lot.consume(new BigDecimal("500"));
            lot.expire(EXPIRES_AT.plusDays(1));

            assertThatThrownBy(() -> lot.restoreConsumed(new BigDecimal("500")))
                    .isInstanceOf(InvalidPointStateException.class);
        }
    }

    @Nested
    @DisplayName("expire · revoke — 소멸과 취소")
    class TerminalTests {

        @Test
        @DisplayName("소멸하면 남은 잔량을 반환하고 EXPIRED 로 닫힌다")
        void expire_returnsRemaining() {
            PointLot lot = newLot("1000");
            lot.consume(new BigDecimal("300"));

            BigDecimal forfeited = lot.expire(EXPIRES_AT.plusSeconds(1));

            assertThat(forfeited).isEqualByComparingTo(new BigDecimal("700"));
            assertThat(lot.getRemainingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(lot.getStatus()).isEqualTo(PointLotStatus.EXPIRED);
        }

        @Test
        @DisplayName("만료일 전에는 소멸시킬 수 없다 — 배치 버그를 도메인이 막는다")
        void expire_rejectedBeforeExpiry() {
            PointLot lot = newLot("1000");

            assertThatThrownBy(() -> lot.expire(EXPIRES_AT.minusSeconds(1)))
                    .isInstanceOf(InvalidPointStateException.class);
        }

        @Test
        @DisplayName("적립 취소는 남은 잔량을 반환하고 REVOKED 로 닫힌다")
        void revoke_returnsRemaining() {
            PointLot lot = newLot("1000");

            BigDecimal revoked = lot.revoke();

            assertThat(revoked).isEqualByComparingTo(new BigDecimal("1000"));
            assertThat(lot.getStatus()).isEqualTo(PointLotStatus.REVOKED);
        }

        @Test
        @DisplayName("이미 닫힌 로트는 다시 닫을 수 없다")
        void terminalLotCannotBeClosedAgain() {
            PointLot lot = newLot("1000");
            lot.revoke();

            assertThatThrownBy(() -> lot.expire(EXPIRES_AT.plusDays(1)))
                    .isInstanceOf(InvalidPointStateException.class);
            assertThatThrownBy(lot::revoke)
                    .isInstanceOf(InvalidPointStateException.class);
        }
    }

    @Nested
    @DisplayName("isExpiredAt — 만료 판정")
    class ExpiryPredicateTests {

        @Test
        @DisplayName("만료 시각 정각은 아직 만료가 아니다 (반열림 경계)")
        void notExpiredAtExactBoundary() {
            PointLot lot = newLot("1000");

            assertThat(lot.isExpiredAt(EXPIRES_AT)).isFalse();
            assertThat(lot.isExpiredAt(EXPIRES_AT.plusNanos(1_000_000))).isTrue();
        }

        @Test
        @DisplayName("무기한 로트는 언제나 만료가 아니다")
        void neverExpiresWithoutExpiry() {
            PointLot lot = PointLot.issue(ACCOUNT_ID, PointLotOrigin.MANUAL_GRANT,
                    new BigDecimal("500"), GRANTED_AT, null, "MANUAL", "op-1");

            assertThatCode(() -> assertThat(lot.isExpiredAt(GRANTED_AT.plusYears(50))).isFalse())
                    .doesNotThrowAnyException();
        }
    }
}
