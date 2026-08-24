package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.in.CapturePaymentPort;
import github.lms.lemuel.payment.application.port.in.CreatePaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadOrderPort;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.PaymentIdempotencyPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.exception.OrderNotFoundException;
import github.lms.lemuel.payment.domain.exception.PaymentAmountMismatchException;
import github.lms.lemuel.payment.domain.exception.PaymentIdempotencyConflictException;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.payment.domain.exception.PaymentOwnershipException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TossPaymentService 단위 테스트.
 *
 * <p>PG 호출은 {@link TossConfirmApiClient}(별도 빈)에 위임한다 — 같은 클래스 안에서 자기호출하면
 * 스프링 AOP 프록시를 타지 않아 {@code @Retry}/{@code @CircuitBreaker} 가 무력화되기 때문이다
 * (김영한 스프링 고급편 §14 "프록시와 내부 호출", 대안 3 구조 변경). 따라서 여기서는
 * <b>협력 빈에 위임하는지</b>를 검증하고, HTTP 응답 처리는 {@code TossConfirmApiClientTest} 가 맡는다.
 *
 * <p>승인 전 검증(소유권·금액)과 멱등 replay 는 <b>돈이 움직이기 전</b>에 끝나야 의미가 있으므로,
 * 거절 케이스마다 {@code tossConfirmApiClient} 가 호출되지 <b>않았음</b>을 함께 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class TossPaymentServiceTest {

    private static final Long BUYER = 7L;
    private static final Long ORDER_ID = 10L;

    @Mock TossConfirmApiClient tossConfirmApiClient;
    @Mock CreatePaymentPort createPaymentPort;
    @Mock SavePaymentPort savePaymentPort;
    @Mock CapturePaymentPort capturePaymentPort;
    @Mock LoadOrderPort loadOrderPort;
    @Mock LoadPaymentPort loadPaymentPort;
    @Mock PaymentIdempotencyPort paymentIdempotencyPort;

    private TossPaymentService service;

    @BeforeEach
    void setup() {
        service = new TossPaymentService(tossConfirmApiClient, createPaymentPort,
                savePaymentPort, capturePaymentPort, loadOrderPort, loadPaymentPort,
                paymentIdempotencyPort);
    }

    private PaymentDomain readyPayment() {
        return PaymentDomain.create(1L, new BigDecimal("10000"), "TOSS_PAYMENTS");
    }

    private PaymentDomain capturedPayment(Long id, Long orderId, String amount, String txn) {
        return PaymentDomain.rehydrate(id, orderId, new BigDecimal(amount), BigDecimal.ZERO,
                PaymentStatus.CAPTURED, "TOSS_PAYMENTS", txn, null, null, null);
    }

    /** 주문 소유자 BUYER, 금액 10,000원인 정상 주문. */
    private void givenOrder(Long orderId, Long ownerId, String amount) {
        when(loadOrderPort.loadOrder(orderId))
                .thenReturn(new LoadOrderPort.OrderInfo(orderId, ownerId, new BigDecimal(amount), "CREATED"));
    }

    /** 멱등 키가 처음 보는 키인 상황(=replay 없음). */
    private void givenNoReplay() {
        lenient().when(paymentIdempotencyPort.findPaymentId(anyString())).thenReturn(Optional.empty());
    }

    private void givenPaymentPipelineSucceeds(PaymentDomain captured) {
        when(createPaymentPort.createPayment(any())).thenReturn(readyPayment());
        when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(capturePaymentPort.capturePayment(any())).thenReturn(captured);
    }

    @Test
    @DisplayName("confirmTossPayment: Toss 승인 성공 시 READY→AUTHORIZED 저장 후 capture 결과 반환")
    void confirmTossPayment_success() {
        givenOrder(ORDER_ID, BUYER, "10000");
        givenNoReplay();
        givenPaymentPipelineSucceeds(capturedPayment(1L, ORDER_ID, "10000", "TOSS:tx-1"));

        PaymentDomain result = service.confirmTossPayment(
                ORDER_ID, "pay-key-1", "toss-order-1", 10000L, BUYER, null);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        verify(savePaymentPort).save(any());
        verify(capturePaymentPort).capturePayment(any());
    }

    @Test
    @DisplayName("confirmTossPayment: PG 확인은 프록시가 걸린 별도 빈에 위임하고, 결제 생성보다 먼저 수행한다")
    void confirmTossPayment_delegatesPgCallToProxiedBeanFirst() {
        givenOrder(ORDER_ID, BUYER, "10000");
        givenNoReplay();
        givenPaymentPipelineSucceeds(capturedPayment(1L, ORDER_ID, "10000", "TOSS:tx-1"));

        service.confirmTossPayment(ORDER_ID, "pay-key-1", "toss-order-1", 10000L, BUYER, null);

        InOrder order = inOrder(tossConfirmApiClient, createPaymentPort);
        order.verify(tossConfirmApiClient).confirm("pay-key-1", "toss-order-1", 10000L);
        order.verify(createPaymentPort).createPayment(any());
    }

    @Test
    @DisplayName("confirmTossPayment: PG 확인이 실패하면 결제를 생성하지 않고 즉시 전파")
    void confirmTossPayment_pgFailureStopsFlow() {
        givenOrder(ORDER_ID, BUYER, "10000");
        givenNoReplay();
        doThrow(new IllegalStateException("Toss PG 일시 장애"))
                .when(tossConfirmApiClient).confirm(any(), any(), any());

        assertThatThrownBy(() -> service.confirmTossPayment(
                ORDER_ID, "pay-key-1", "toss-order-1", 10000L, BUYER, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Toss PG 일시 장애");

        verify(createPaymentPort, never()).createPayment(any());
        verify(capturePaymentPort, never()).capturePayment(any());
    }

    @Nested
    @DisplayName("승인 전 금액 대조 (원칙: PG 는 자기가 아는 금액끼리만 맞춰본다)")
    class AmountVerification {

        @Test
        @DisplayName("요청 금액이 주문 금액보다 적으면 PG 호출 전에 거절한다")
        void rejectsUnderpayment() {
            givenOrder(ORDER_ID, BUYER, "10000");
            givenNoReplay();

            assertThatThrownBy(() -> service.confirmTossPayment(
                    ORDER_ID, "pay-key-1", "toss-order-1", 1000L, BUYER, null))
                    .isInstanceOf(PaymentAmountMismatchException.class)
                    .hasMessageContaining("10000")
                    .hasMessageContaining("1000");

            verify(tossConfirmApiClient, never()).confirm(any(), any(), any());
            verify(createPaymentPort, never()).createPayment(any());
        }

        @Test
        @DisplayName("요청 금액이 주문 금액보다 많아도 거절한다 (과납도 대사 불일치다)")
        void rejectsOverpayment() {
            givenOrder(ORDER_ID, BUYER, "10000");
            givenNoReplay();

            assertThatThrownBy(() -> service.confirmTossPayment(
                    ORDER_ID, "pay-key-1", "toss-order-1", 99999L, BUYER, null))
                    .isInstanceOf(PaymentAmountMismatchException.class);

            verify(tossConfirmApiClient, never()).confirm(any(), any(), any());
        }

        @Test
        @DisplayName("소수부가 있는 주문 금액도 값으로 비교한다 (10000.00 == 10000)")
        void comparesByValueNotScale() {
            givenOrder(ORDER_ID, BUYER, "10000.00");
            givenNoReplay();
            givenPaymentPipelineSucceeds(capturedPayment(1L, ORDER_ID, "10000", "TOSS:tx-1"));

            PaymentDomain result = service.confirmTossPayment(
                    ORDER_ID, "pay-key-1", "toss-order-1", 10000L, BUYER, null);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        }
    }

    @Nested
    @DisplayName("승인 전 소유권 대조 (IDOR 차단)")
    class OwnershipVerification {

        @Test
        @DisplayName("남의 주문을 결제하려 하면 PG 호출 전에 403 으로 거절한다")
        void rejectsForeignOrder() {
            givenOrder(ORDER_ID, BUYER, "10000");
            givenNoReplay();

            assertThatThrownBy(() -> service.confirmTossPayment(
                    ORDER_ID, "pay-key-1", "toss-order-1", 10000L, 999L, null))
                    .isInstanceOf(PaymentOwnershipException.class);

            verify(tossConfirmApiClient, never()).confirm(any(), any(), any());
            verify(createPaymentPort, never()).createPayment(any());
        }

        @Test
        @DisplayName("주문 소유자를 알 수 없으면 통과가 아니라 거절한다 (fail-closed)")
        void rejectsWhenOwnerUnknown() {
            when(loadOrderPort.loadOrder(ORDER_ID))
                    .thenReturn(new LoadOrderPort.OrderInfo(ORDER_ID, new BigDecimal("10000"), "CREATED"));
            givenNoReplay();

            assertThatThrownBy(() -> service.confirmTossPayment(
                    ORDER_ID, "pay-key-1", "toss-order-1", 10000L, BUYER, null))
                    .isInstanceOf(PaymentOwnershipException.class);

            verify(tossConfirmApiClient, never()).confirm(any(), any(), any());
        }

        @Test
        @DisplayName("주문 자체가 없으면 소유권 판정 전에 404 로 끊는다")
        void rejectsMissingOrder() {
            when(loadOrderPort.loadOrder(ORDER_ID)).thenReturn(null);
            givenNoReplay();

            assertThatThrownBy(() -> service.confirmTossPayment(
                    ORDER_ID, "pay-key-1", "toss-order-1", 10000L, BUYER, null))
                    .isInstanceOf(OrderNotFoundException.class);

            verify(tossConfirmApiClient, never()).confirm(any(), any(), any());
        }

        @Test
        @DisplayName("운영자 경로(callerUserId=null)는 소유권 대조를 건너뛰되 금액 대조는 유지한다")
        void adminPathSkipsOwnershipButNotAmount() {
            givenOrder(ORDER_ID, BUYER, "10000");
            givenNoReplay();

            assertThatThrownBy(() -> service.confirmTossPayment(
                    ORDER_ID, "pay-key-1", "toss-order-1", 1L, null, null))
                    .isInstanceOf(PaymentAmountMismatchException.class);
        }
    }

    @Nested
    @DisplayName("멱등 (동일 승인 재요청은 최초 결과를 replay)")
    class Idempotency {

        @Test
        @DisplayName("이미 처리된 키면 PG 를 다시 부르지 않고 최초 결제를 반환한다")
        void replaysStoredPayment() {
            PaymentDomain first = capturedPayment(55L, ORDER_ID, "10000", "TOSS:pay-key-1");
            when(paymentIdempotencyPort.findPaymentId("single:7:idem-1")).thenReturn(Optional.of(55L));
            when(loadPaymentPort.loadById(55L)).thenReturn(Optional.of(first));

            PaymentDomain result = service.confirmTossPayment(
                    ORDER_ID, "pay-key-1", "toss-order-1", 10000L, BUYER, "idem-1");

            assertThat(result.getId()).isEqualTo(55L);
            verify(tossConfirmApiClient, never()).confirm(any(), any(), any());
            verify(createPaymentPort, never()).createPayment(any());
        }

        @Test
        @DisplayName("헤더가 없으면 paymentKey 를 멱등 키로 삼는다 — 헤더 미전송 클라이언트도 보호된다")
        void fallsBackToPaymentKey() {
            PaymentDomain first = capturedPayment(55L, ORDER_ID, "10000", "TOSS:pay-key-1");
            when(paymentIdempotencyPort.findPaymentId("single:7:pay-key-1")).thenReturn(Optional.of(55L));
            when(loadPaymentPort.loadById(55L)).thenReturn(Optional.of(first));

            PaymentDomain result = service.confirmTossPayment(
                    ORDER_ID, "pay-key-1", "toss-order-1", 10000L, BUYER, null);

            assertThat(result.getId()).isEqualTo(55L);
            verify(tossConfirmApiClient, never()).confirm(any(), any(), any());
        }

        @Test
        @DisplayName("매핑은 있는데 결제가 없으면 조용히 재승인하지 않고 드러낸다")
        void failsLoudWhenMappedPaymentMissing() {
            when(paymentIdempotencyPort.findPaymentId("single:7:idem-1")).thenReturn(Optional.of(55L));
            when(loadPaymentPort.loadById(55L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmTossPayment(
                    ORDER_ID, "pay-key-1", "toss-order-1", 10000L, BUYER, "idem-1"))
                    .isInstanceOf(PaymentNotFoundException.class);

            verify(tossConfirmApiClient, never()).confirm(any(), any(), any());
        }

        @Test
        @DisplayName("일괄 승인도 동일 키 재요청은 PG 를 다시 부르지 않는다")
        void cartConfirmReplays() {
            PaymentDomain first = capturedPayment(55L, 100L, "5000", "TOSS:pay-key-cart");
            when(paymentIdempotencyPort.findPaymentId("cart:7:idem-cart")).thenReturn(Optional.of(55L));
            when(loadPaymentPort.loadById(55L)).thenReturn(Optional.of(first));

            List<PaymentDomain> result = service.confirmTossCartPayment(
                    List.of(100L, 200L), "pay-key-cart", "toss-order-cart", 10000L, BUYER, "idem-cart");

            assertThat(result).extracting(PaymentDomain::getId).containsExactly(55L);
            verify(tossConfirmApiClient, never()).confirm(any(), any(), any());
            verify(createPaymentPort, never()).createPayment(any());
        }

        @Test
        @DisplayName("최초 승인은 키↔결제 매핑을 기록한다 (다음 재시도가 replay 될 근거)")
        void recordsMappingOnFirstConfirm() {
            givenOrder(ORDER_ID, BUYER, "10000");
            givenNoReplay();
            givenPaymentPipelineSucceeds(capturedPayment(77L, ORDER_ID, "10000", "TOSS:pay-key-1"));

            service.confirmTossPayment(ORDER_ID, "pay-key-1", "toss-order-1", 10000L, BUYER, "idem-1");

            verify(paymentIdempotencyPort).save("single:7:idem-1", 77L);
        }

        @Test
        @DisplayName("남이 쓴 키를 그대로 보내도 그 사람의 결제가 나오지 않는다 — 키는 호출자로 스코프된다")
        void keyIsScopedToCaller() {
            // 저장소에는 사용자 7 의 키만 있다. 사용자 9 가 같은 문자열을 보내도 다른 키가 된다.
            when(paymentIdempotencyPort.findPaymentId("single:9:idem-1")).thenReturn(Optional.empty());
            givenOrder(ORDER_ID, 9L, "10000");
            givenPaymentPipelineSucceeds(capturedPayment(78L, ORDER_ID, "10000", "TOSS:pay-key-1"));

            service.confirmTossPayment(ORDER_ID, "pay-key-1", "toss-order-1", 10000L, 9L, "idem-1");

            verify(paymentIdempotencyPort).findPaymentId("single:9:idem-1");
            verify(paymentIdempotencyPort).save("single:9:idem-1", 78L);
        }

        @Test
        @DisplayName("같은 키를 단건↔장바구니로 돌려 써도 섞이지 않는다 — 연산으로도 스코프된다")
        void keyIsScopedToOperation() {
            when(paymentIdempotencyPort.findPaymentId("cart:7:idem-1")).thenReturn(Optional.empty());
            givenOrder(100L, BUYER, "10000");
            givenPaymentPipelineSucceeds(capturedPayment(79L, 100L, "10000", "TOSS:pay-key-1"));

            service.confirmTossCartPayment(
                    List.of(100L), "pay-key-1", "toss-order-1", 10000L, BUYER, "idem-1");

            verify(paymentIdempotencyPort).findPaymentId("cart:7:idem-1");
        }

        @Test
        @DisplayName("같은 키를 다른 주문에 다시 쓰면 replay 가 아니라 409 다 — 결제 안 된 주문이 성공으로 보이면 안 된다")
        void rejectsKeyReuseForDifferentOrder() {
            PaymentDomain other = capturedPayment(55L, 999L, "10000", "TOSS:pay-key-1");
            when(paymentIdempotencyPort.findPaymentId("single:7:idem-1")).thenReturn(Optional.of(55L));
            when(loadPaymentPort.loadById(55L)).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> service.confirmTossPayment(
                    ORDER_ID, "pay-key-1", "toss-order-1", 10000L, BUYER, "idem-1"))
                    .isInstanceOf(PaymentIdempotencyConflictException.class);

            verify(tossConfirmApiClient, never()).confirm(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("장바구니 일괄 승인")
    class CartConfirm {

        @Test
        @DisplayName("여러 주문에 대해 순차적으로 결제 확인 처리")
        void confirmTossCartPayment_success() {
            givenOrder(100L, BUYER, "5000");
            givenOrder(200L, BUYER, "5000");
            givenNoReplay();
            when(createPaymentPort.createPayment(any())).thenAnswer(inv ->
                    PaymentDomain.create(1L, new BigDecimal("5000"), "TOSS_PAYMENTS"));
            when(savePaymentPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(capturePaymentPort.capturePayment(any())).thenReturn(
                    capturedPayment(1L, 100L, "5000", "TOSS:tx-1"),
                    capturedPayment(2L, 200L, "5000", "TOSS:tx-2"));

            List<PaymentDomain> results = service.confirmTossCartPayment(
                    List.of(100L, 200L), "pay-key-cart", "toss-order-cart", 10000L, BUYER, null);

            assertThat(results).hasSize(2);
            verify(createPaymentPort, org.mockito.Mockito.times(2)).createPayment(any());
            // 장바구니 일괄 결제도 PG 확인은 1회만 — 프록시 빈에 위임
            verify(tossConfirmApiClient).confirm("pay-key-cart", "toss-order-cart", 10000L);
        }

        @Test
        @DisplayName("주문 금액 합계와 총액이 다르면 PG 호출 전에 거절한다")
        void rejectsWhenSumMismatches() {
            givenOrder(100L, BUYER, "5000");
            givenOrder(200L, BUYER, "5000");
            givenNoReplay();

            assertThatThrownBy(() -> service.confirmTossCartPayment(
                    List.of(100L, 200L), "pay-key-cart", "toss-order-cart", 1000L, BUYER, null))
                    .isInstanceOf(PaymentAmountMismatchException.class);

            verify(tossConfirmApiClient, never()).confirm(any(), any(), any());
        }

        @Test
        @DisplayName("한 건이라도 남의 주문이면 전체를 거절한다")
        void rejectsWhenAnyOrderIsForeign() {
            givenOrder(100L, BUYER, "5000");
            givenOrder(200L, 999L, "5000");
            givenNoReplay();

            assertThatThrownBy(() -> service.confirmTossCartPayment(
                    List.of(100L, 200L), "pay-key-cart", "toss-order-cart", 10000L, BUYER, null))
                    .isInstanceOf(PaymentOwnershipException.class);

            verify(tossConfirmApiClient, never()).confirm(any(), any(), any());
        }
    }
}
