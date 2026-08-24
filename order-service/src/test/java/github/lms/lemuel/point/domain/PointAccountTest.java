package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InsufficientPointException;
import github.lms.lemuel.point.domain.exception.InvalidPointAmountException;
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import github.lms.lemuel.point.domain.exception.PointInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PointAccount 잔고 불변식 단위 테스트.
 *
 * <p>검증 대상:
 * <ul>
 *   <li>total = available + locked (항등식), 3필드 음수 금지
 *   <li>적립(grant)·사용(use)·복원(restore)·소멸(expire) 정상 경로
 *   <li>잔액 부족 거절, 소수 포인트 거절, 상태별 허용/거절
 *   <li>계정 상태머신 — SUSPENDED 는 사용만 막고 적립은 허용, CLOSED 는 잔액 0 에서만
 * </ul>
 */
class PointAccountTest {

    private static final Long USER_ID = 42L;

    private PointAccount newAccount() {
        return PointAccount.open(USER_ID);
    }

    private static void assertInvariant(PointAccount account) {
        assertThat(account.getAvailable()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(account.getLocked()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(account.getTotal()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(account.getTotal())
                .isEqualByComparingTo(account.getAvailable().add(account.getLocked()));
    }

    @Test
    @DisplayName("계정 개설 시 잔고 3필드가 0 이고 ACTIVE 다")
    void open_startsEmptyAndActive() {
        PointAccount account = newAccount();

        assertThat(account.getUserId()).isEqualTo(USER_ID);
        assertThat(account.getAvailable()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getLocked()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getStatus()).isEqualTo(PointAccountStatus.ACTIVE);
        assertInvariant(account);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // grant — 적립·충전
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("grant — 적립")
    class GrantTests {

        @Test
        @DisplayName("적립하면 available 과 total 이 같이 증가하고 locked 는 불변")
        void grant_increasesAvailable() {
            PointAccount account = newAccount();

            account.grant(new BigDecimal("5000"));

            assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("5000"));
            assertThat(account.getLocked()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(account.getTotal()).isEqualByComparingTo(new BigDecimal("5000"));
            assertInvariant(account);
        }

        @Test
        @DisplayName("1원 적립도 정상 처리된다 (경계값)")
        void grant_minimumUnit() {
            PointAccount account = newAccount();

            account.grant(BigDecimal.ONE);

            assertThat(account.getAvailable()).isEqualByComparingTo(BigDecimal.ONE);
            assertInvariant(account);
        }

        @Test
        @DisplayName("소수 포인트는 거절한다 — 1원 단위 정수만 유통")
        void grant_rejectsFractionalAmount() {
            PointAccount account = newAccount();

            assertThatThrownBy(() -> account.grant(new BigDecimal("100.5")))
                    .isInstanceOf(InvalidPointAmountException.class);
        }

        @Test
        @DisplayName("소수점이 있어도 값이 정수면 허용한다 — 100.00 은 100")
        void grant_acceptsIntegralValueWithScale() {
            PointAccount account = newAccount();

            account.grant(new BigDecimal("100.00"));

            assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("100"));
            assertInvariant(account);
        }

        @Test
        @DisplayName("0원·음수 적립은 거절한다")
        void grant_rejectsNonPositive() {
            PointAccount account = newAccount();

            assertThatThrownBy(() -> account.grant(BigDecimal.ZERO))
                    .isInstanceOf(InvalidPointAmountException.class);
            assertThatThrownBy(() -> account.grant(new BigDecimal("-100")))
                    .isInstanceOf(InvalidPointAmountException.class);
        }

        @Test
        @DisplayName("정지 계정도 적립은 받는다 — 조사 중이라고 정상 주문 적립을 뺏지 않는다")
        void grant_allowedWhileSuspended() {
            PointAccount account = newAccount();
            account.suspend();

            assertThatCode(() -> account.grant(new BigDecimal("1000"))).doesNotThrowAnyException();
            assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("1000"));
        }

        @Test
        @DisplayName("해지 계정에는 적립할 수 없다")
        void grant_rejectedWhenClosed() {
            PointAccount account = newAccount();
            account.close();

            assertThatThrownBy(() -> account.grant(new BigDecimal("1000")))
                    .isInstanceOf(InvalidPointStateException.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // use — 사용(결제 차감)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("use — 사용")
    class UseTests {

        @Test
        @DisplayName("사용하면 available 과 total 이 같이 감소한다")
        void use_decreasesAvailable() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("5000"));

            account.use(new BigDecimal("3000"));

            assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("2000"));
            assertThat(account.getTotal()).isEqualByComparingTo(new BigDecimal("2000"));
            assertInvariant(account);
        }

        @Test
        @DisplayName("잔액 전액 사용이 가능하다 (경계값)")
        void use_exactBalance() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("5000"));

            account.use(new BigDecimal("5000"));

            assertThat(account.getAvailable()).isEqualByComparingTo(BigDecimal.ZERO);
            assertInvariant(account);
        }

        @Test
        @DisplayName("잔액보다 1원이라도 많으면 거절한다 (경계값)")
        void use_rejectsOverBalance() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("5000"));

            assertThatThrownBy(() -> account.use(new BigDecimal("5001")))
                    .isInstanceOf(InsufficientPointException.class);
            assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("5000"));
        }

        @Test
        @DisplayName("정지 계정은 사용할 수 없다")
        void use_rejectedWhenSuspended() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("5000"));
            account.suspend();

            assertThatThrownBy(() -> account.use(new BigDecimal("1000")))
                    .isInstanceOf(InvalidPointStateException.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // restore / expire
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("restore · expire")
    class RestoreExpireTests {

        @Test
        @DisplayName("환불 복원은 available 을 되돌린다")
        void restore_increasesAvailable() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("5000"));
            account.use(new BigDecimal("3000"));

            account.restore(new BigDecimal("3000"));

            assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("5000"));
            assertInvariant(account);
        }

        @Test
        @DisplayName("정지 계정도 복원은 받는다 — 고객 돈을 돌려주는 경로다")
        void restore_allowedWhileSuspended() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("5000"));
            account.use(new BigDecimal("3000"));
            account.suspend();

            assertThatCode(() -> account.restore(new BigDecimal("3000"))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("소멸은 available 을 차감한다")
        void expire_decreasesAvailable() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("5000"));

            account.expire(new BigDecimal("2000"));

            assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("3000"));
            assertInvariant(account);
        }

        @Test
        @DisplayName("적립 취소는 available 을 차감한다 — 주문이 환불되면 그 적립분을 회수한다")
        void revoke_decreasesAvailable() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("5000"));

            account.revoke(new BigDecimal("1500"));

            assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("3500"));
            assertInvariant(account);
        }

        @Test
        @DisplayName("잔액을 넘는 회수는 불변식 위반이다 — 회수액은 로트에서 계산돼 넘어온다")
        void revoke_overBalanceIsInvariantViolation() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("1000"));

            assertThatThrownBy(() -> account.revoke(new BigDecimal("2000")))
                    .isInstanceOf(PointInvariantViolationException.class);
        }

        @Test
        @DisplayName("잔액을 넘는 소멸은 불변식 위반이다 — 사용자 입력이 아니라 로직 버그")
        void expire_overBalanceIsInvariantViolation() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("1000"));

            assertThatThrownBy(() -> account.expire(new BigDecimal("2000")))
                    .isInstanceOf(PointInvariantViolationException.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 상태머신
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("상태 전이")
    class StatusTests {

        @Test
        @DisplayName("ACTIVE → SUSPENDED → ACTIVE 왕복이 가능하다")
        void suspendAndActivate() {
            PointAccount account = newAccount();

            account.suspend();
            assertThat(account.getStatus()).isEqualTo(PointAccountStatus.SUSPENDED);

            account.activate();
            assertThat(account.getStatus()).isEqualTo(PointAccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("잔액이 남은 계정은 해지할 수 없다")
        void close_rejectedWithRemainingBalance() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("100"));

            assertThatThrownBy(account::close)
                    .isInstanceOf(InvalidPointStateException.class);
            assertThat(account.getStatus()).isEqualTo(PointAccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("해지 계정은 다시 활성화할 수 없다 — 종단 상태")
        void close_isTerminal() {
            PointAccount account = newAccount();
            account.close();

            assertThatThrownBy(account::activate)
                    .isInstanceOf(InvalidPointStateException.class);
        }

        @Test
        @DisplayName("동일 상태 재적용은 멱등 no-op 이다")
        void sameStateTransitionIsIdempotent() {
            PointAccount account = newAccount();

            assertThatCode(account::activate).doesNotThrowAnyException();
            account.suspend();
            assertThatCode(account::suspend).doesNotThrowAnyException();
            assertThat(account.getStatus()).isEqualTo(PointAccountStatus.SUSPENDED);
        }
    }

    /**
     * 수기 차감 — 운영자가 오지급을 회수하는 경로.
     *
     * <p>이미 있는 감액 셋과 구별되는 점이 이 묶음의 전부다:
     * <ul>
     *   <li>{@code use} 와 달리 <b>정지 계정에서도</b> 된다 — 잘못 준 돈은 계정을 정지시켜 두고 거둬들인다.
     *   <li>{@code revoke}/{@code expire} 와 달리 잔액 초과는 <b>불변식 위반(500)이 아니라 입력 오류(400)</b>다.
     *       운영자가 타이핑한 숫자이므로, 장부가 깨졌다는 신호로 올려서는 안 된다.
     * </ul>
     */
    @Nested
    @DisplayName("수기 차감")
    class ManualDeduct {

        @Test
        @DisplayName("가용 잔고에서 빼고 total 도 함께 줄인다")
        void deductsFromAvailable() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("1000"));

            account.deduct(new BigDecimal("300"));

            assertThat(account.getAvailable()).isEqualByComparingTo("700");
            assertThat(account.getTotal()).isEqualByComparingTo("700");
            assertInvariant(account);
        }

        @Test
        @DisplayName("전액도 뺄 수 있다 — 잔액 0 은 정상 착지다")
        void deductsAll() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("500"));

            account.deduct(new BigDecimal("500"));

            assertThat(account.getAvailable()).isEqualByComparingTo("0");
            assertInvariant(account);
        }

        @Test
        @DisplayName("잔액을 넘으면 입력 오류로 거절한다 — 불변식 위반이 아니다")
        void rejectsOverBalanceAsBusinessError() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("100"));

            assertThatThrownBy(() -> account.deduct(new BigDecimal("101")))
                    .isInstanceOf(InsufficientPointException.class)
                    .isNotInstanceOf(PointInvariantViolationException.class);
        }

        @Test
        @DisplayName("정지 계정에서도 차감된다 — 부정 적립은 계정을 잠근 뒤 거둬들인다")
        void allowedOnSuspendedAccount() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("1000"));
            account.suspend();

            assertThatCode(() -> account.deduct(new BigDecimal("400"))).doesNotThrowAnyException();
            assertThat(account.getAvailable()).isEqualByComparingTo("600");
        }

        @Test
        @DisplayName("해지 계정에서는 차감할 수 없다 — 잔액 0 이라 뺄 것이 없다")
        void rejectedOnClosedAccount() {
            PointAccount account = newAccount();
            account.close();

            assertThatThrownBy(() -> account.deduct(new BigDecimal("1")))
                    .isInstanceOf(InvalidPointStateException.class);
        }

        @Test
        @DisplayName("0 이하·소수 포인트는 거절한다")
        void rejectsNonPointAmounts() {
            PointAccount account = newAccount();
            account.grant(new BigDecimal("1000"));

            assertThatThrownBy(() -> account.deduct(BigDecimal.ZERO))
                    .isInstanceOf(InvalidPointAmountException.class);
            assertThatThrownBy(() -> account.deduct(new BigDecimal("10.5")))
                    .isInstanceOf(InvalidPointAmountException.class);
        }
    }

    @Test
    @DisplayName("rehydrate 로 복원한 계정도 불변식을 검증한다")
    void rehydrate_validatesInvariant() {
        assertThatThrownBy(() -> PointAccount.rehydrate(
                1L, USER_ID,
                new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("999"),
                PointAccountStatus.ACTIVE, 0L, null, null))
                .isInstanceOf(PointInvariantViolationException.class);
    }
}
