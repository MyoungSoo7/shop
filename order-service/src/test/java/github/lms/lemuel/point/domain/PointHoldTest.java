package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InvalidPointAmountException;
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 포인트 선점(hold) — 입금 대기 결제가 붙잡아 두는 잔고 조각.
 *
 * <p>가상계좌는 입금 전까지 결제가 확정되지 않는다. 그 사이 포인트를 <b>차감하지 않으면</b> 같은
 * 포인트를 다른 주문에 또 쓸 수 있고, <b>차감해 버리면</b> 미입금 취소마다 복원 경로가 필요하다.
 * 선점은 그 사이를 메운다 — 가용에서 빼서 잠그되, 총액은 건드리지 않는다.
 */
class PointHoldTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 21, 10, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime LATER = NOW.plusHours(3);

    private static PointHold active() {
        return PointHold.place(7L, new BigDecimal("5000"), "PAYMENT_TENDER", "42", NOW);
    }

    @Nested
    @DisplayName("생성")
    class Place {

        @Test
        @DisplayName("선점은 ACTIVE 로 시작하고 해소 시각은 비어 있다")
        void startsActive() {
            PointHold hold = active();

            assertThat(hold.getStatus()).isEqualTo(PointHoldStatus.ACTIVE);
            assertThat(hold.isActive()).isTrue();
            assertThat(hold.getAmount()).isEqualByComparingTo("5000");
            assertThat(hold.getResolvedAt()).isNull();
        }

        @Test
        @DisplayName("금액은 양수 1원 단위 정수여야 한다")
        void amountMustBeWholePositive() {
            assertThatThrownBy(() -> PointHold.place(7L, BigDecimal.ZERO, "T", "1", NOW))
                    .isInstanceOf(InvalidPointAmountException.class);
            assertThatThrownBy(() -> PointHold.place(7L, new BigDecimal("-100"), "T", "1", NOW))
                    .isInstanceOf(InvalidPointAmountException.class);
            assertThatThrownBy(() -> PointHold.place(7L, new BigDecimal("100.5"), "T", "1", NOW))
                    .isInstanceOf(InvalidPointAmountException.class);
        }

        /**
         * 참조가 없으면 이 선점이 <b>어느 결제 것인지</b> 알 수 없다. 그러면 입금이 와도 무엇을
         * 확정할지, 기한이 지나도 무엇을 풀지 짚을 수 없어 잔고가 영영 잠긴다.
         */
        @Test
        @DisplayName("참조(무엇을 위한 선점인가)가 없으면 거부한다")
        void referenceRequired() {
            assertThatThrownBy(() -> PointHold.place(7L, BigDecimal.TEN, null, "1", NOW))
                    .isInstanceOf(InvalidPointStateException.class);
            assertThatThrownBy(() -> PointHold.place(7L, BigDecimal.TEN, "T", "  ", NOW))
                    .isInstanceOf(InvalidPointStateException.class);
            assertThatThrownBy(() -> PointHold.place(null, BigDecimal.TEN, "T", "1", NOW))
                    .isInstanceOf(InvalidPointStateException.class);
        }
    }

    @Nested
    @DisplayName("해소 전이")
    class Resolve {

        @Test
        @DisplayName("입금 확인 → CAPTURED, 해소 시각 기록")
        void capture() {
            PointHold hold = active();

            hold.capture(LATER);

            assertThat(hold.getStatus()).isEqualTo(PointHoldStatus.CAPTURED);
            assertThat(hold.isActive()).isFalse();
            assertThat(hold.getResolvedAt()).isEqualTo(LATER);
        }

        @Test
        @DisplayName("주문 취소 → RELEASED")
        void release() {
            PointHold hold = active();

            hold.release(LATER);

            assertThat(hold.getStatus()).isEqualTo(PointHoldStatus.RELEASED);
            assertThat(hold.getResolvedAt()).isEqualTo(LATER);
        }

        @Test
        @DisplayName("입금 기한 경과 → EXPIRED (잔고 효과는 RELEASED 와 같지만 사유가 다르다)")
        void expire() {
            PointHold hold = active();

            hold.expire(LATER);

            assertThat(hold.getStatus()).isEqualTo(PointHoldStatus.EXPIRED);
        }

        /**
         * 재시도 경로가 여럿이다(배치 재실행·수동 조작). 같은 결론을 다시 적용하는 것은 사고가
         * 아니므로 조용히 통과시키되, <b>해소 시각은 최초를 보존</b>한다 — 언제 풀렸는지가 흔들리면
         * 입금 vs 만료 경합을 사후에 재구성할 수 없다.
         */
        @Test
        @DisplayName("같은 상태 재적용은 멱등 no-op — 최초 해소 시각을 보존한다")
        void sameStateIsIdempotent() {
            PointHold hold = active();
            hold.capture(LATER);

            hold.capture(LATER.plusHours(1));

            assertThat(hold.getStatus()).isEqualTo(PointHoldStatus.CAPTURED);
            assertThat(hold.getResolvedAt()).isEqualTo(LATER);
        }

        /**
         * 이것이 입금 vs 만료 경합의 최종 방어선이다. 배치가 만료시킨 선점을 뒤늦은 입금이
         * 확정해 버리면, 이미 가용으로 돌아간 포인트를 한 번 더 쓰는 것이 된다.
         */
        @Test
        @DisplayName("종단 상태끼리는 전이하지 않는다 — 만료된 선점을 뒤늦은 입금이 확정할 수 없다")
        void terminalCannotCrossTransition() {
            PointHold expired = active();
            expired.expire(LATER);

            assertThatThrownBy(() -> expired.capture(LATER.plusHours(1)))
                    .isInstanceOf(InvalidPointStateException.class);

            PointHold captured = active();
            captured.capture(LATER);
            assertThatThrownBy(() -> captured.release(LATER.plusHours(1)))
                    .isInstanceOf(InvalidPointStateException.class);
        }
    }

    @Nested
    @DisplayName("상태 규약")
    class StatusRules {

        @Test
        @DisplayName("ACTIVE 만 잔고를 붙잡고, 나머지는 종단")
        void holdsBalanceOnlyWhenActive() {
            assertThat(PointHoldStatus.ACTIVE.holdsBalance()).isTrue();
            assertThat(PointHoldStatus.ACTIVE.isTerminal()).isFalse();
            for (PointHoldStatus s : new PointHoldStatus[]{
                    PointHoldStatus.CAPTURED, PointHoldStatus.RELEASED, PointHoldStatus.EXPIRED}) {
                assertThat(s.holdsBalance()).isFalse();
                assertThat(s.isTerminal()).isTrue();
                assertThat(s.canTransitionTo(PointHoldStatus.CAPTURED)).isFalse();
            }
        }

        @Test
        @DisplayName("ACTIVE → 종단 셋만 허용, ACTIVE → ACTIVE 는 전이가 아니다")
        void activeTransitions() {
            assertThat(PointHoldStatus.ACTIVE.canTransitionTo(PointHoldStatus.CAPTURED)).isTrue();
            assertThat(PointHoldStatus.ACTIVE.canTransitionTo(PointHoldStatus.RELEASED)).isTrue();
            assertThat(PointHoldStatus.ACTIVE.canTransitionTo(PointHoldStatus.EXPIRED)).isTrue();
            assertThat(PointHoldStatus.ACTIVE.canTransitionTo(PointHoldStatus.ACTIVE)).isFalse();
        }
    }
}
