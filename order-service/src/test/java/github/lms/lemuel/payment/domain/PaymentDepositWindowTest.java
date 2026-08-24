package github.lms.lemuel.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 입금 대기 판정 — "이 결제는 돈이 아직 안 들어온 상태인가".
 *
 * <p>이 판정이 세 가지를 좌우한다: 결제를 즉시 캡처할지, 포인트를 차감할지 선점할지,
 * 미입금 만료 배치가 집어갈지.
 *
 * <p><b>왜 라벨로 판정할 수 없나</b> — 텐더 결제의 {@code paymentMethod} 는 가장 큰 텐더에서 만든
 * 표시값이다(예: 카드 90,000 + 가상계좌 10,000 → {@code "SPLIT:CARD"}). 이 문자열만 보면 가상계좌가
 * 섞인 결제를 놓친다. 판정의 진실원은 <b>텐더 목록</b>이어야 한다.
 */
class PaymentDepositWindowTest {

    private static final Duration TTL = Duration.ofHours(48);
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 8, 20, 10, 0);

    private static PaymentTender tender(TenderType type, String amount, int seq) {
        return PaymentTender.newTender(type, new BigDecimal(amount), seq);
    }

    @Nested
    @DisplayName("수단별 입금 대기 여부")
    class ByTenderType {

        @Test
        @DisplayName("가상계좌·무통장만 입금을 기다린다")
        void depositMethods() {
            assertThat(TenderType.VIRTUAL_ACCOUNT.awaitsDeposit()).isTrue();
            assertThat(TenderType.BANK_TRANSFER.awaitsDeposit()).isTrue();
        }

        /** 카드·간편결제는 PG 가 즉시 성공/실패를 확정하므로 "입금 대기"가 없다. */
        @Test
        @DisplayName("나머지 수단은 즉시 확정 — 내부 잔액도 기다릴 것이 없다")
        void immediateMethods() {
            for (TenderType t : new TenderType[]{TenderType.CARD, TenderType.KAKAO_PAY,
                    TenderType.NAVER_PAY, TenderType.PAYCO, TenderType.SAMSUNG_PAY,
                    TenderType.POINT, TenderType.GIFT_CARD}) {
                assertThat(t.awaitsDeposit()).as(t.name()).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("결제 단위 판정")
    class ByPayment {

        @Test
        @DisplayName("텐더 없는 일반 결제는 수단 문자열로 판정한다")
        void legacySinglePayment() {
            assertThat(PaymentDomain.create(1L, new BigDecimal("10000"), "VIRTUAL_ACCOUNT")
                    .awaitsDeposit()).isTrue();
            assertThat(PaymentDomain.create(1L, new BigDecimal("10000"), "CARD")
                    .awaitsDeposit()).isFalse();
            // 미상 수단은 판정 불가 → 기다리지 않는 것으로 본다(오탐이 미탐보다 비싸다).
            assertThat(PaymentDomain.create(1L, new BigDecimal("10000"), "WHAT_IS_THIS")
                    .awaitsDeposit()).isFalse();
        }

        /**
         * 이 한 건이 라벨 판정의 사각이다 — 라벨은 {@code "SPLIT:CARD"} 지만 가상계좌 텐더가
         * 섞여 있어 실제로는 입금을 기다린다.
         */
        @Test
        @DisplayName("텐더 하나라도 입금 대기면 결제 전체가 입금 대기 — 라벨이 카드여도 그렇다")
        void splitWithDepositTender() {
            PaymentDomain payment = PaymentDomain.createWithTenders(1L, List.of(
                    tender(TenderType.CARD, "90000", 1),
                    tender(TenderType.VIRTUAL_ACCOUNT, "10000", 2)), "SPLIT:CARD");

            assertThat(payment.awaitsDeposit()).isTrue();
        }

        @Test
        @DisplayName("즉시 확정 수단만 모인 텐더 결제는 기다리지 않는다")
        void splitWithoutDepositTender() {
            PaymentDomain payment = PaymentDomain.createWithTenders(1L, List.of(
                    tender(TenderType.CARD, "90000", 1),
                    tender(TenderType.POINT, "10000", 2)), "SPLIT:CARD");

            assertThat(payment.awaitsDeposit()).isFalse();
        }

        @Test
        @DisplayName("포인트 전액 결제는 기다리지 않는다 — 즉시 확정")
        void pointOnlyDoesNotWait() {
            PaymentDomain payment = PaymentDomain.createWithTenders(1L,
                    List.of(tender(TenderType.POINT, "10000", 1)), "POINT");

            assertThat(payment.awaitsDeposit()).isFalse();
        }
    }

    @Nested
    @DisplayName("만료 판정 — 결제 기준")
    class ExpiryByPayment {

        /**
         * 생성 시각을 고정해야 하므로 {@code rehydrate} 로 만든다 — 도메인에 테스트 전용
         * setter 를 뚫지 않는다(저장된 결제를 읽어 온 상태와 같은 경로다).
         */
        private static PaymentDomain pending(LocalDateTime createdAt, PaymentTender... tenders) {
            PaymentDomain payment = PaymentDomain.rehydrate(
                    1L, 1L, new BigDecimal("100000"), BigDecimal.ZERO, PaymentStatus.READY,
                    "SPLIT:CARD", null, null, createdAt, createdAt);
            payment.replaceTenders(List.of(tenders));
            return payment;
        }

        private static PaymentDomain vaSplit() {
            return pending(CREATED,
                    tender(TenderType.CARD, "90000", 1),
                    tender(TenderType.VIRTUAL_ACCOUNT, "10000", 2));
        }

        /**
         * 문자열 기반 판정({@code isExpired(paymentMethod, ...)})은 {@code "SPLIT:CARD"} 를 파싱하지
         * 못해 이 결제를 <b>영원히 만료 후보에서 뺀다</b> — 재고를 붙잡은 채 잔류한다.
         */
        @Test
        @DisplayName("라벨로는 놓치던 분할 가상계좌 결제를 결제 기준 판정은 집는다")
        void catchesWhatLabelMisses() {
            PaymentDomain payment = vaSplit();
            LocalDateTime past = CREATED.plus(TTL).plusMinutes(1);

            assertThat(PaymentExpiryPolicy.isExpired(payment.getPaymentMethod(),
                    payment.getCreatedAt(), TTL, past)).isFalse();   // 기존 사각
            assertThat(PaymentExpiryPolicy.isExpired(payment, TTL, past)).isTrue();
        }

        @Test
        @DisplayName("기한 정각은 아직 만료가 아니다")
        void deadlineItselfIsNotExpired() {
            assertThat(PaymentExpiryPolicy.isExpired(vaSplit(), TTL, CREATED.plus(TTL))).isFalse();
        }

        @Test
        @DisplayName("입금을 기다리지 않는 결제는 기한이 지나도 만료 대상이 아니다")
        void nonDepositNeverExpires() {
            PaymentDomain card = pending(CREATED,
                    tender(TenderType.CARD, "90000", 1),
                    tender(TenderType.POINT, "10000", 2));

            assertThat(PaymentExpiryPolicy.isExpired(card, TTL, CREATED.plusYears(1))).isFalse();
        }

        @Test
        @DisplayName("null 결제는 만료 대상이 아니다 — 모르면 건드리지 않는다")
        void nullPaymentIsNotExpired() {
            assertThat(PaymentExpiryPolicy.isExpired((PaymentDomain) null, TTL, CREATED)).isFalse();
        }
    }
}
