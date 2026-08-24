package github.lms.lemuel.payment.domain;
import github.lms.lemuel.payment.domain.exception.InvalidPaymentStateException;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 텐더 기반 결제 도메인 핵심 검증.
 *
 * <p>검증하는 것:
 * <ul>
 *   <li>tender 합계 == amount 도메인 불변식</li>
 *   <li>역순 환불 정책 (외부 PG 먼저 → 내부 잔액 마지막)</li>
 *   <li>부분 환불 시 정확한 tender 분배</li>
 *   <li>텐더 1 개(포인트·기프트카드 전액 결제)도 같은 경로를 탄다</li>
 * </ul>
 */
class SplitPaymentDomainTest {

    @Test
    @DisplayName("createWithTenders: amount 가 tender 합계로 자동 계산되어 외부 수동 지정 불가")
    void createWithTenders_autoSumsAmount() {
        List<PaymentTender> tenders = List.of(
                PaymentTender.newTender(TenderType.POINT, new BigDecimal("5000"), 1),
                PaymentTender.newTender(TenderType.GIFT_CARD, new BigDecimal("10000"), 2),
                PaymentTender.newTender(TenderType.CARD, new BigDecimal("35000"), 3)
        );

        PaymentDomain p = PaymentDomain.createWithTenders(100L, tenders, "SPLIT:CARD");

        assertThat(p.isSplit()).isTrue();
        assertThat(p.getAmount()).isEqualByComparingTo("50000");
        assertThat(p.getTenders()).hasSize(3);
    }

    /**
     * 지불수단이 하나뿐인 결제(포인트 전액·기프트카드 전액)도 텐더로 모델링한다.
     *
     * <p>텐더를 붙이지 않고 일반 결제로 만들면 원장 차감·환불 복원이 걸릴 자리가 없다 —
     * 일반 결제 경로는 {@code paymentMethod} 문자열만 알고 지불수단을 모델링하지 않기 때문이다
     * (docs/plan/point-ledger.md §6 ③).
     */
    @Test
    @DisplayName("createWithTenders: tender 1 개도 허용 — 포인트 전액 결제의 자리")
    void createWithTenders_singleTender() {
        List<PaymentTender> tenders =
                List.of(PaymentTender.newTender(TenderType.POINT, new BigDecimal("12000"), 1));

        PaymentDomain p = PaymentDomain.createWithTenders(100L, tenders, "POINT");

        assertThat(p.getAmount()).isEqualByComparingTo("12000");
        assertThat(p.getTenders()).hasSize(1);
        // 텐더가 있으면 환불도 텐더 경로를 타야 한다 — 그래야 원장이 복원된다.
        assertThat(p.isSplit()).isTrue();
    }

    @Test
    @DisplayName("createWithTenders: tender 가 없으면 거부 — 금액을 계산할 근거가 없다")
    void createWithTenders_rejectsEmpty() {
        assertThatThrownBy(() -> PaymentDomain.createWithTenders(1L, List.of(), "X"))
                .isInstanceOf(PaymentInvariantViolationException.class);
        assertThatThrownBy(() -> PaymentDomain.createWithTenders(1L, null, "X"))
                .isInstanceOf(PaymentInvariantViolationException.class);
    }

    /** 텐더 1 개짜리 결제도 환불 계획이 서야 한다 — 서지 않으면 포인트가 영영 안 돌아온다. */
    @Test
    @DisplayName("planRefundFromTenders: 텐더 1 개(포인트 전액)도 계획이 선다")
    void planRefund_singleTender() {
        List<PaymentTender> tenders = capturedTenders(tender(TenderType.POINT, "12000", 1));
        PaymentDomain p = PaymentDomain.createWithTenders(1L, tenders, "POINT");

        var plans = p.planRefundFromTenders(new BigDecimal("12000"));

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).tender().getType()).isEqualTo(TenderType.POINT);
        assertThat(plans.get(0).amount()).isEqualByComparingTo("12000");
    }

    @Test
    @DisplayName("planRefundFromTenders: 역순 환불 — CARD(3) → GIFT_CARD(2) → POINT(1) 순서")
    void planRefund_reverseOrder() {
        List<PaymentTender> tenders = capturedTenders(
                tender(TenderType.POINT, "5000", 1),
                tender(TenderType.GIFT_CARD, "10000", 2),
                tender(TenderType.CARD, "35000", 3)
        );
        PaymentDomain p = PaymentDomain.createWithTenders(1L, tenders, "X");

        // 30,000 환불 → CARD 에서만 차감 (35,000 중 30,000)
        var plans = p.planRefundFromTenders(new BigDecimal("30000"));

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).tender().getType()).isEqualTo(TenderType.CARD);
        assertThat(plans.get(0).amount()).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("planRefundFromTenders: CARD 잔여보다 큰 환불 — CARD 소진 후 GIFT_CARD 로 흘러감")
    void planRefund_overflowsToNextTender() {
        List<PaymentTender> tenders = capturedTenders(
                tender(TenderType.POINT, "5000", 1),
                tender(TenderType.GIFT_CARD, "10000", 2),
                tender(TenderType.CARD, "35000", 3)
        );
        PaymentDomain p = PaymentDomain.createWithTenders(1L, tenders, "X");

        // 40,000 환불 → CARD 35,000 + GIFT_CARD 5,000
        var plans = p.planRefundFromTenders(new BigDecimal("40000"));

        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).tender().getType()).isEqualTo(TenderType.CARD);
        assertThat(plans.get(0).amount()).isEqualByComparingTo("35000");
        assertThat(plans.get(1).tender().getType()).isEqualTo(TenderType.GIFT_CARD);
        assertThat(plans.get(1).amount()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("planRefundFromTenders: 전액 환불 — 모든 tender 가 분배에 포함")
    void planRefund_fullAmount() {
        List<PaymentTender> tenders = capturedTenders(
                tender(TenderType.POINT, "5000", 1),
                tender(TenderType.GIFT_CARD, "10000", 2),
                tender(TenderType.CARD, "35000", 3)
        );
        PaymentDomain p = PaymentDomain.createWithTenders(1L, tenders, "X");

        var plans = p.planRefundFromTenders(new BigDecimal("50000"));

        assertThat(plans).hasSize(3);
        // 역순 보장
        assertThat(plans.get(0).tender().getType()).isEqualTo(TenderType.CARD);
        assertThat(plans.get(1).tender().getType()).isEqualTo(TenderType.GIFT_CARD);
        assertThat(plans.get(2).tender().getType()).isEqualTo(TenderType.POINT);
    }

    @Test
    @DisplayName("planRefundFromTenders: 전체 환불 가능액 초과 시 IllegalArgumentException")
    void planRefund_exceedsTotal() {
        List<PaymentTender> tenders = capturedTenders(
                tender(TenderType.POINT, "5000", 1),
                tender(TenderType.CARD, "10000", 2)
        );
        PaymentDomain p = PaymentDomain.createWithTenders(1L, tenders, "X");

        assertThatThrownBy(() -> p.planRefundFromTenders(new BigDecimal("20000")))
                .isInstanceOf(PaymentInvariantViolationException.class);
    }

    @Test
    @DisplayName("validateTenderSum: tender 합계와 amount 가 다르면 즉시 IllegalStateException")
    void validateTenderSum_invariant() {
        // createWithTenders 는 자동 계산이라 위반 불가. rehydrate 시나리오를 시뮬레이트.
        List<PaymentTender> tenders = List.of(
                PaymentTender.newTender(TenderType.CARD, new BigDecimal("100"), 1),
                PaymentTender.newTender(TenderType.POINT, new BigDecimal("200"), 2)
        );
        PaymentDomain p = PaymentDomain.createWithTenders(1L, tenders, "X");
        // 정상 케이스
        p.validateTenderSum();

        // 불변식 위반 시뮬레이션
        PaymentDomain corrupted = PaymentDomain.create(1L, new BigDecimal("999"), "X");
        corrupted.replaceTenders(tenders);
        assertThatThrownBy(corrupted::validateTenderSum)
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessageContaining("tender 합계");
    }

    @Test
    @DisplayName("isSplit: tender 비어있으면 false (legacy 단일결제 호환)")
    void isSplit_legacyPayment() {
        PaymentDomain legacy = PaymentDomain.create(1L, new BigDecimal("10000"), "CARD");
        assertThat(legacy.isSplit()).isFalse();
    }

    private static PaymentTender tender(TenderType type, String amount, int seq) {
        return PaymentTender.newTender(type, new BigDecimal(amount), seq);
    }

    private static List<PaymentTender> capturedTenders(PaymentTender... tenders) {
        for (PaymentTender t : tenders) {
            t.authorize(t.getType().usesExternalPg() ? "TX-" + t.getSequence() : null);
            t.capture();
        }
        return List.of(tenders);
    }
}
