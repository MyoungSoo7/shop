package github.lms.lemuel.payment.domain;

import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 미입금 결제 만료 정책 — 순수 도메인 규칙.
 *
 * <p>가상계좌·무통장 입금은 PG 승인이 즉시 오지 않고 구매자의 입금을 기다린다. 입금이 끝내 오지 않으면
 * 결제는 READY 로, 주문은 CREATED 로 영구 잔류하며 재고를 붙잡는다. 이 정책이 "언제부터 만료로 볼지"를 정한다.
 */
class PaymentExpiryPolicyTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 8, 6, 10, 0, 0);
    private static final Duration TTL = Duration.ofHours(48);

    @Test @DisplayName("입금 대기형 수단(가상계좌·무통장)만 만료 대상")
    void depositMethodsOnly() {
        assertThat(PaymentExpiryPolicy.isDepositMethod("VIRTUAL_ACCOUNT")).isTrue();
        assertThat(PaymentExpiryPolicy.isDepositMethod("BANK_TRANSFER")).isTrue();

        // 카드·간편결제는 PG 가 즉시 성공/실패를 확정하므로 "입금 대기"가 존재하지 않는다.
        assertThat(PaymentExpiryPolicy.isDepositMethod("CARD")).isFalse();
        assertThat(PaymentExpiryPolicy.isDepositMethod("KAKAO_PAY")).isFalse();
        assertThat(PaymentExpiryPolicy.isDepositMethod("POINT")).isFalse();
    }

    @Test @DisplayName("수단 문자열은 대소문자·공백에 관대하고, 미상값은 만료 대상이 아니다")
    void methodParsingIsLenient() {
        assertThat(PaymentExpiryPolicy.isDepositMethod("virtual_account")).isTrue();
        assertThat(PaymentExpiryPolicy.isDepositMethod("  Bank_Transfer  ")).isTrue();

        // 알 수 없는 값을 만료 대상으로 오인하면 정상 결제를 취소한다 — 모르면 만료시키지 않는다.
        assertThat(PaymentExpiryPolicy.isDepositMethod("SOMETHING_NEW")).isFalse();
        assertThat(PaymentExpiryPolicy.isDepositMethod(null)).isFalse();
        assertThat(PaymentExpiryPolicy.isDepositMethod("")).isFalse();
    }

    @Test @DisplayName("입금 기한 = 생성시각 + TTL")
    void deadlineIsCreatedPlusTtl() {
        assertThat(PaymentExpiryPolicy.deadline(CREATED, TTL))
                .isEqualTo(LocalDateTime.of(2026, 8, 8, 10, 0, 0));
    }

    @Test @DisplayName("기한 정각은 아직 만료가 아니다(경계값) — 기한을 지나야 만료")
    void deadlineBoundaryIsNotExpiredYet() {
        LocalDateTime deadline = PaymentExpiryPolicy.deadline(CREATED, TTL);

        assertThat(PaymentExpiryPolicy.isExpired("VIRTUAL_ACCOUNT", CREATED, TTL, deadline.minusNanos(1))).isFalse();
        assertThat(PaymentExpiryPolicy.isExpired("VIRTUAL_ACCOUNT", CREATED, TTL, deadline)).isFalse();
        assertThat(PaymentExpiryPolicy.isExpired("VIRTUAL_ACCOUNT", CREATED, TTL, deadline.plusNanos(1))).isTrue();
    }

    @Test @DisplayName("기한을 넘겨도 입금 대기형 수단이 아니면 만료되지 않는다")
    void nonDepositMethodNeverExpires() {
        LocalDateTime longAfter = CREATED.plusDays(365);

        assertThat(PaymentExpiryPolicy.isExpired("CARD", CREATED, TTL, longAfter)).isFalse();
        assertThat(PaymentExpiryPolicy.isExpired(null, CREATED, TTL, longAfter)).isFalse();
    }

    @Test @DisplayName("TTL 은 양수여야 한다 — 0·음수는 전량 즉시 만료를 뜻해 사고가 된다")
    void ttlMustBePositive() {
        // 타입 도메인 예외로 던진다(OO 게이트: 도메인에서 generic IllegalArgumentException 금지).
        assertThatThrownBy(() -> PaymentExpiryPolicy.deadline(CREATED, Duration.ZERO))
                .isInstanceOf(PaymentInvariantViolationException.class);
        assertThatThrownBy(() -> PaymentExpiryPolicy.deadline(CREATED, Duration.ofHours(-1)))
                .isInstanceOf(PaymentInvariantViolationException.class);
    }

    @Test @DisplayName("생성시각이 없으면 만료 판정 불가 — 만료시키지 않는다")
    void missingCreatedAtIsNotExpired() {
        assertThat(PaymentExpiryPolicy.isExpired("VIRTUAL_ACCOUNT", null, TTL, CREATED.plusDays(10))).isFalse();
    }
}
