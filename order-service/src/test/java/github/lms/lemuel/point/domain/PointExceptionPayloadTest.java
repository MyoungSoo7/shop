package github.lms.lemuel.point.domain;

import github.lms.lemuel.point.domain.exception.InsufficientPointException;
import github.lms.lemuel.point.domain.exception.InvalidPointAmountException;
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 포인트 예외가 <b>실어 나르는 정보</b>를 검증한다.
 *
 * <p>메시지 문자열만으로는 API 응답을 만들 수 없다 — 잔액 부족을 422 로 번역하면서 "얼마를
 * 요청했고 얼마가 있었는지"를 응답에 담으려면 예외가 그 값을 들고 있어야 한다. 여기서 그 계약을
 * 고정한다.
 */
class PointExceptionPayloadTest {

    @Test
    @DisplayName("잔액 부족 예외는 요청액과 가용액을 함께 들고 온다")
    void insufficientCarriesRequestedAndAvailable() {
        PointAccount account = PointAccount.open(42L);
        account.grant(new BigDecimal("1000"));

        assertThatThrownBy(() -> account.use(new BigDecimal("1500")))
                .isInstanceOfSatisfying(InsufficientPointException.class, exception -> {
                    assertThat(exception.getRequested()).isEqualByComparingTo(new BigDecimal("1500"));
                    assertThat(exception.getAvailable()).isEqualByComparingTo(new BigDecimal("1000"));
                });
    }

    @Test
    @DisplayName("금액 규약 위반 예외는 어떤 연산에서 어떤 값이 거절됐는지 들고 온다")
    void invalidAmountCarriesOperationAndValue() {
        PointAccount account = PointAccount.open(42L);

        assertThatThrownBy(() -> account.grant(new BigDecimal("10.5")))
                .isInstanceOfSatisfying(InvalidPointAmountException.class, exception -> {
                    assertThat(exception.getOperation()).isEqualTo("grant");
                    assertThat(exception.getAmount()).isEqualByComparingTo(new BigDecimal("10.5"));
                });
    }

    @Test
    @DisplayName("상태 위반 예외는 현재 상태와 시도한 연산을 들고 온다")
    void invalidStateCarriesStateAndOperation() {
        PointAccount account = PointAccount.open(42L);
        account.suspend();

        assertThatThrownBy(() -> account.use(new BigDecimal("100")))
                .isInstanceOfSatisfying(InvalidPointStateException.class, exception -> {
                    assertThat(exception.getCurrentState()).isEqualTo(PointAccountStatus.SUSPENDED.name());
                    assertThat(exception.getAttemptedOperation()).isEqualTo("use");
                });
    }

    @Test
    @DisplayName("로트 상태 위반도 같은 계약을 따른다 — 종단 로트 재소비")
    void lotStateExceptionCarriesContext() {
        PointLot lot = PointLot.issue(7L, PointLotOrigin.ORDER_EARN, new BigDecimal("500"),
                java.time.OffsetDateTime.parse("2026-08-01T00:00:00Z"), null, "ORDER", "1");
        lot.revoke();

        assertThatThrownBy(() -> lot.consume(new BigDecimal("100")))
                .isInstanceOfSatisfying(InvalidPointStateException.class, exception -> {
                    assertThat(exception.getCurrentState()).isEqualTo(PointLotStatus.REVOKED.name());
                    assertThat(exception.getAttemptedOperation()).isEqualTo("consume");
                });
    }
}
