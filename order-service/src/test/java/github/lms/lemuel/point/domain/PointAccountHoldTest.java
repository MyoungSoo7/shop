package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InsufficientPointException;
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import github.lms.lemuel.point.domain.exception.PointInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 포인트 계정의 선점(hold) 잔고 이동 — Phase 2 에서 {@code locked} 가 처음으로 0 이 아니게 된다.
 *
 * <p>세 연산의 잔고 효과가 서로 다르다는 것이 이 테스트의 전부다:
 *
 * <table>
 *   <caption>선점 연산별 잔고 효과</caption>
 *   <tr><th>연산</th><th>available</th><th>locked</th><th>total</th></tr>
 *   <tr><td>hold</td><td>−X</td><td>+X</td><td>불변</td></tr>
 *   <tr><td>captureHold</td><td>불변</td><td>−X</td><td>−X</td></tr>
 *   <tr><td>releaseHold</td><td>+X</td><td>−X</td><td>불변</td></tr>
 * </table>
 *
 * <p>선점은 <b>로트를 건드리지 않는다</b>. 실제 소비(로트 차감)는 확정 시점에 일어나므로,
 * 4번째 불변식 {@code available == Σ ACTIVE lot.remaining − locked} 가 세 연산 모두에서 성립한다.
 */
class PointAccountHoldTest {

    private PointAccount account;

    @BeforeEach
    void setUp() {
        account = PointAccount.open(7L);
        account.grant(new BigDecimal("10000"));
    }

    @Nested
    @DisplayName("선점")
    class Hold {

        @Test
        @DisplayName("가용에서 빼서 잠근다 — 총액은 그대로다(아직 쓴 것이 아니다)")
        void movesAvailableToLocked() {
            account.hold(new BigDecimal("3000"));

            assertThat(account.getAvailable()).isEqualByComparingTo("7000");
            assertThat(account.getLocked()).isEqualByComparingTo("3000");
            assertThat(account.getTotal()).isEqualByComparingTo("10000");
        }

        @Test
        @DisplayName("가용을 넘는 선점은 거절 — 사용자 입력 오류다")
        void rejectsOverAvailable() {
            assertThatThrownBy(() -> account.hold(new BigDecimal("10001")))
                    .isInstanceOf(InsufficientPointException.class);

            assertThat(account.getAvailable()).isEqualByComparingTo("10000");
            assertThat(account.getLocked()).isEqualByComparingTo("0");
        }

        /**
         * 이미 잠근 몫은 다른 주문이 또 잠글 수 없다 — 이 한 줄이 Phase 2 의 존재 이유다
         * (같은 포인트를 두 주문이 함께 쓰는 것을 막는다).
         */
        @Test
        @DisplayName("이미 잠근 몫은 다시 잠글 수 없다 — 같은 포인트의 이중 사용 차단")
        void lockedIsNotAvailableAgain() {
            account.hold(new BigDecimal("6000"));

            assertThatThrownBy(() -> account.hold(new BigDecimal("5000")))
                    .isInstanceOf(InsufficientPointException.class);

            account.hold(new BigDecimal("4000"));
            assertThat(account.getAvailable()).isEqualByComparingTo("0");
            assertThat(account.getLocked()).isEqualByComparingTo("10000");
            assertThat(account.getTotal()).isEqualByComparingTo("10000");
        }

        @Test
        @DisplayName("사용할 수 없는 계정 상태에서는 선점도 못 한다")
        void rejectsWhenNotUsable() {
            account.suspend();

            assertThatThrownBy(() -> account.hold(new BigDecimal("1000")))
                    .isInstanceOf(InvalidPointStateException.class);
        }
    }

    @Nested
    @DisplayName("확정")
    class Capture {

        @Test
        @DisplayName("잠근 몫을 실제로 쓴다 — 가용은 그대로, 총액이 준다")
        void spendsLocked() {
            account.hold(new BigDecimal("3000"));

            account.captureHold(new BigDecimal("3000"));

            assertThat(account.getAvailable()).isEqualByComparingTo("7000");
            assertThat(account.getLocked()).isEqualByComparingTo("0");
            assertThat(account.getTotal()).isEqualByComparingTo("7000");
        }

        @Test
        @DisplayName("부분 확정도 성립한다")
        void partialCapture() {
            account.hold(new BigDecimal("3000"));

            account.captureHold(new BigDecimal("1000"));

            assertThat(account.getLocked()).isEqualByComparingTo("2000");
            assertThat(account.getTotal()).isEqualByComparingTo("9000");
        }

        /**
         * 확정 금액은 <b>선점 레코드에서</b> 온다(사용자가 타이핑한 숫자가 아니다). 잠근 것보다 큰
         * 값이 넘어왔다면 선점과 잔고가 어긋난 것이므로 입력 오류가 아니라 불변식 위반이다.
         */
        @Test
        @DisplayName("잠근 것보다 크게 확정하려 하면 불변식 위반 — 입력 오류가 아니라 장부 손상 신호")
        void overCaptureIsInvariantViolation() {
            account.hold(new BigDecimal("3000"));

            assertThatThrownBy(() -> account.captureHold(new BigDecimal("3001")))
                    .isInstanceOf(PointInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("해제")
    class Release {

        @Test
        @DisplayName("잠근 몫을 가용으로 되돌린다 — 총액은 그대로다")
        void returnsToAvailable() {
            account.hold(new BigDecimal("3000"));

            account.releaseHold(new BigDecimal("3000"));

            assertThat(account.getAvailable()).isEqualByComparingTo("10000");
            assertThat(account.getLocked()).isEqualByComparingTo("0");
            assertThat(account.getTotal()).isEqualByComparingTo("10000");
        }

        @Test
        @DisplayName("잠근 것보다 크게 해제하려 하면 불변식 위반")
        void overReleaseIsInvariantViolation() {
            account.hold(new BigDecimal("3000"));

            assertThatThrownBy(() -> account.releaseHold(new BigDecimal("3001")))
                    .isInstanceOf(PointInvariantViolationException.class);
        }

        /**
         * 해제는 고객 재산을 돌려주는 방향이라 정지 계정에서도 막지 않는다 — 조사 중이라고
         * 남의 포인트를 계속 잠가 둘 이유가 없다({@code restore} 와 같은 판단).
         */
        @Test
        @DisplayName("정지 계정에서도 해제는 된다 — 잠가 둔 채로 두는 것이 더 나쁘다")
        void allowedWhenSuspended() {
            account.hold(new BigDecimal("3000"));
            account.suspend();

            account.releaseHold(new BigDecimal("3000"));

            assertThat(account.getAvailable()).isEqualByComparingTo("10000");
            assertThat(account.getLocked()).isEqualByComparingTo("0");
        }
    }

    @Test
    @DisplayName("선점 → 해제 왕복은 원상복구 — 잔고가 흔적을 남기지 않는다")
    void holdThenReleaseIsRoundTrip() {
        account.hold(new BigDecimal("2500"));
        account.releaseHold(new BigDecimal("2500"));

        assertThat(account.getAvailable()).isEqualByComparingTo("10000");
        assertThat(account.getLocked()).isEqualByComparingTo("0");
        assertThat(account.getTotal()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("선점 → 확정은 곧바로 쓴 것과 잔고 결과가 같다")
    void holdThenCaptureEqualsDirectUse() {
        PointAccount direct = PointAccount.open(8L);
        direct.grant(new BigDecimal("10000"));
        direct.use(new BigDecimal("2500"));

        account.hold(new BigDecimal("2500"));
        account.captureHold(new BigDecimal("2500"));

        assertThat(account.getAvailable()).isEqualByComparingTo(direct.getAvailable());
        assertThat(account.getLocked()).isEqualByComparingTo(direct.getLocked());
        assertThat(account.getTotal()).isEqualByComparingTo(direct.getTotal());
    }
}
