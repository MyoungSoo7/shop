package github.lms.lemuel.payment.domain;

import github.lms.lemuel.payment.domain.exception.CashReceiptNotAllowedException;
import github.lms.lemuel.payment.domain.exception.InvalidCashReceiptStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 현금영수증 도메인 — 발급 대상 판정 · 금액 분해 · 상태머신.
 */
@DisplayName("CashReceipt — 발급 대상 · 부가세 분해 · 상태 전이")
class CashReceiptTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 21, 12, 0);

    private static CashReceiptIdentifier mobile() {
        return CashReceiptIdentifier.of(CashReceiptIdentifier.Type.MOBILE, "01012345678");
    }

    private static CashReceiptIdentifier business() {
        return CashReceiptIdentifier.of(CashReceiptIdentifier.Type.BUSINESS_NUMBER, "2208162517");
    }

    private static CashReceipt requested(BigDecimal amount) {
        return CashReceipt.request(1L, 10L, 100L, "BANK_TRANSFER", amount,
                CashReceiptPurpose.INCOME_DEDUCTION, mobile(), T0);
    }

    @Nested
    @DisplayName("발급 대상 수단")
    class CashTender {

        @ParameterizedTest(name = "대상: {0}")
        @ValueSource(strings = {"BANK_TRANSFER", "VIRTUAL_ACCOUNT", "bank_transfer"})
        void cashTendersAreEligible(String method) {
            assertThat(CashReceipt.isCashTender(method)).isTrue();
        }

        @ParameterizedTest(name = "비대상: {0}")
        @ValueSource(strings = {"CARD", "KAKAO_PAY", "POINT", "GIFT_CARD"})
        @DisplayName("카드·간편결제는 카드사 전표로 이미 신고돼 이중 공제가 된다")
        void cardTendersAreNot(String method) {
            assertThat(CashReceipt.isCashTender(method)).isFalse();
        }

        @Test
        @DisplayName("미상 수단은 발급하지 않는다 — 모르는 것을 현금으로 가정하면 이중 발급이 난다")
        void unknownMethodIsNotEligible() {
            assertThat(CashReceipt.isCashTender("SOMETHING_NEW")).isFalse();
            assertThat(CashReceipt.isCashTender(null)).isFalse();
            assertThat(CashReceipt.isCashTender("")).isFalse();
        }

        @Test
        void requestRejectsCardPayment() {
            assertThatThrownBy(() -> CashReceipt.request(1L, 10L, 100L, "CARD",
                    new BigDecimal("11000"), CashReceiptPurpose.INCOME_DEDUCTION, mobile(), T0))
                    .isInstanceOf(CashReceiptNotAllowedException.class);
        }
    }

    @Nested
    @DisplayName("용도 ↔ 식별번호 조합")
    class PurposeMatch {

        @Test
        @DisplayName("소득공제에 사업자등록번호는 쓸 수 없다")
        void incomeDeductionRejectsBusinessNumber() {
            assertThatThrownBy(() -> CashReceipt.request(1L, 10L, 100L, "BANK_TRANSFER",
                    new BigDecimal("11000"), CashReceiptPurpose.INCOME_DEDUCTION, business(), T0))
                    .isInstanceOf(CashReceiptNotAllowedException.class);
        }

        @Test
        @DisplayName("지출증빙에 휴대폰번호는 쓸 수 없다")
        void expenseProofRejectsMobile() {
            assertThatThrownBy(() -> CashReceipt.request(1L, 10L, 100L, "BANK_TRANSFER",
                    new BigDecimal("11000"), CashReceiptPurpose.EXPENSE_PROOF, mobile(), T0))
                    .isInstanceOf(CashReceiptNotAllowedException.class);
        }

        @Test
        void expenseProofAcceptsBusinessNumber() {
            CashReceipt receipt = CashReceipt.request(1L, 10L, 100L, "VIRTUAL_ACCOUNT",
                    new BigDecimal("11000"), CashReceiptPurpose.EXPENSE_PROOF, business(), T0);

            assertThat(receipt.getPurpose()).isEqualTo(CashReceiptPurpose.EXPENSE_PROOF);
        }
    }

    @Nested
    @DisplayName("공급가액 · 부가세 분해")
    class VatSplit {

        @ParameterizedTest(name = "총액 {0} → 공급가액 {1} + 부가세 {2}")
        @CsvSource({
                "11000, 10000, 1000",
                "10000,  9091,  909",   // 909.09... → 909 (HALF_UP)
                "1,         1,    0",   // 0.0909... → 0
                "5,         5,    0",   // 0.4545... → 0
                "6,         5,    1",   // 0.5454... → 1 (반올림 경계)
                "100000, 90909, 9091"
        })
        @DisplayName("분해 결과는 언제나 총액으로 되돌아간다 — 합이 어긋나면 세금 서류가 깨진다")
        void splitAlwaysSumsBackToTotal(String total, String supply, String vat) {
            CashReceipt receipt = requested(new BigDecimal(total));

            assertThat(receipt.getSupplyAmount()).isEqualByComparingTo(supply);
            assertThat(receipt.getVatAmount()).isEqualByComparingTo(vat);
            assertThat(receipt.getSupplyAmount().add(receipt.getVatAmount()))
                    .isEqualByComparingTo(total);
        }

        @Test
        @DisplayName("0 원·음수 결제는 발급 대상이 아니다")
        void rejectsNonPositive() {
            assertThatThrownBy(() -> requested(BigDecimal.ZERO))
                    .isInstanceOf(CashReceiptNotAllowedException.class);
            assertThatThrownBy(() -> requested(new BigDecimal("-1")))
                    .isInstanceOf(CashReceiptNotAllowedException.class);
        }
    }

    @Nested
    @DisplayName("상태 전이")
    class Transitions {

        @Test
        void requestedToIssued() {
            CashReceipt receipt = requested(new BigDecimal("11000"));

            receipt.markIssued("APV-1", T0.plusSeconds(1));

            assertThat(receipt.getStatus()).isEqualTo(CashReceiptStatus.ISSUED);
            assertThat(receipt.getApprovalNumber()).isEqualTo("APV-1");
            assertThat(receipt.getIssuedAt()).isEqualTo(T0.plusSeconds(1));
        }

        @Test
        @DisplayName("승인번호 없이 발급 확정은 불가 — 취소할 때 가리킬 대상이 없다")
        void issueRequiresApprovalNumber() {
            CashReceipt receipt = requested(new BigDecimal("11000"));

            assertThatThrownBy(() -> receipt.markIssued("  ", T0))
                    .isInstanceOf(InvalidCashReceiptStateException.class);
        }

        @Test
        @DisplayName("발급 실패는 자리를 비운다 — 재신청이 영영 막히면 고객이 공제를 잃는다")
        void failedFreesTheSlot() {
            CashReceipt receipt = requested(new BigDecimal("11000"));

            receipt.markFailed("국세청 응답 없음", T0);

            assertThat(receipt.getStatus()).isEqualTo(CashReceiptStatus.FAILED);
            assertThat(receipt.isActive()).isFalse();
        }

        @Test
        void issuedToCanceled() {
            CashReceipt receipt = requested(new BigDecimal("11000"));
            receipt.markIssued("APV-1", T0);

            receipt.requestCancel("전액 환불", T0.plusMinutes(1));
            receipt.markCanceled(T0.plusMinutes(2));

            assertThat(receipt.getStatus()).isEqualTo(CashReceiptStatus.CANCELED);
            assertThat(receipt.isActive()).isFalse();
        }

        @Test
        @DisplayName("취소 실패는 ISSUED 로 되돌아온다 — CANCELED 로 두면 국세청과 어긋난 채 종단에 박힌다")
        void cancelFailureRevertsToIssued() {
            CashReceipt receipt = requested(new BigDecimal("11000"));
            receipt.markIssued("APV-1", T0);
            receipt.requestCancel("전액 환불", T0.plusMinutes(1));

            receipt.revertCancel("국세청 취소 거부", T0.plusMinutes(2));

            assertThat(receipt.getStatus()).isEqualTo(CashReceiptStatus.ISSUED);
            assertThat(receipt.getStatus().cancellable()).isTrue(); // 다시 시도할 수 있다
            assertThat(receipt.getCancelReason()).isNull();
        }

        @Test
        @DisplayName("발급되지 않은 건은 취소할 수 없다")
        void cannotCancelWhatWasNotIssued() {
            CashReceipt receipt = requested(new BigDecimal("11000"));

            assertThatThrownBy(() -> receipt.requestCancel("환불", T0))
                    .isInstanceOf(InvalidCashReceiptStateException.class);
        }

        @Test
        @DisplayName("종단(CANCELED)에서는 어떤 전이도 열리지 않는다")
        void canceledIsTerminal() {
            CashReceipt receipt = requested(new BigDecimal("11000"));
            receipt.markIssued("APV-1", T0);
            receipt.requestCancel("환불", T0);
            receipt.markCanceled(T0);

            assertThatThrownBy(() -> receipt.requestCancel("또 환불", T0))
                    .isInstanceOf(InvalidCashReceiptStateException.class);
        }

        @Test
        @DisplayName("같은 상태 재적용은 멱등 no-op — 재시도 경로가 여럿이다")
        void sameStateIsNoOp() {
            CashReceipt receipt = requested(new BigDecimal("11000"));
            receipt.markIssued("APV-1", T0);

            receipt.markIssued("APV-1", T0.plusMinutes(5));

            assertThat(receipt.getStatus()).isEqualTo(CashReceiptStatus.ISSUED);
        }

        @Test
        @DisplayName("REQUESTED 는 자리를 차지한다 — 응답 대기 중 재신청이 이중 발급이 되지 않게")
        void requestedOccupiesSlot() {
            assertThat(requested(new BigDecimal("11000")).isActive()).isTrue();
        }
    }
}
