package github.lms.lemuel.point.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.giftcard.domain.exception.GiftCardInvariantViolationException;
import github.lms.lemuel.giftcard.domain.exception.InsufficientGiftCardBalanceException;
import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardAmountException;
import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원장 예외 → HTTP 상태 매핑 회귀 테스트.
 *
 * <p><b>실기동에서 잡힌 결함의 재발 방지다.</b> 처음에는 이 예외들이 {@code RuntimeException} 이라
 * 전역 핸들러가 잡지 못했고, "이미 등록된 코드" 같은 정상 거절이 <b>500</b> 으로 나갔다.
 * 500 은 화면에 서버 문구를 전달하지 않으므로, 코드 존재를 흘리지 않으려고 서버에서 통일해 둔
 * 거절 문구가 사용자에게 닿지도 않았다.
 *
 * <p>불변식 위반만은 여전히 매핑하지 않는다 — 그건 사용자 입력으로 도달할 수 없는 로직 버그라
 * 500 이 정확한 답이다.
 */
class LedgerExceptionStatusTest {

    private static final BigDecimal ONE = BigDecimal.ONE;

    @Test
    @DisplayName("잔액 부족은 422 — 요청 형식은 옳고 상태가 허락하지 않을 뿐이다")
    void insufficientMapsTo422() {
        assertThat(new InsufficientPointException("부족", ONE, ONE).getErrorCode().status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(new InsufficientGiftCardBalanceException("부족", ONE, ONE).getErrorCode().status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("금액 규약 위반은 400")
    void invalidAmountMapsTo400() {
        assertThat(new InvalidPointAmountException("소수", "grant", ONE).getErrorCode().status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new InvalidGiftCardAmountException("소수", "issue", ONE).getErrorCode().status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("상태 위반은 400 — 기프트카드 등록 거절이 여기로 떨어진다")
    void invalidStateMapsTo400() {
        assertThat(new InvalidPointStateException("정지", "SUSPENDED", "use").getErrorCode().status())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        InvalidGiftCardStateException rejected =
                new InvalidGiftCardStateException("사용할 수 없는 기프트카드 코드입니다", "UNKNOWN", "register");
        assertThat(rejected.getErrorCode()).isEqualTo(ErrorCode.GIFT_CARD_INVALID_STATE);
        assertThat(rejected.getErrorCode().status()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 서버가 정한 거절 문구가 그대로 응답에 실려야 화면이 추측 문구를 지어내지 않는다.
        assertThat(rejected.getMessage()).isEqualTo("사용할 수 없는 기프트카드 코드입니다");
    }

    @Test
    @DisplayName("불변식 위반은 BusinessException 이 아니다 — 로직 버그라 500 이 옳다")
    void invariantViolationStaysUnmapped() {
        assertThat(new PointInvariantViolationException("깨짐")).isNotInstanceOf(BusinessException.class);
        assertThat(new GiftCardInvariantViolationException("깨짐")).isNotInstanceOf(BusinessException.class);
    }
}
