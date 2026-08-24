package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.in.CashReceiptUseCase;
import github.lms.lemuel.payment.application.port.out.CashReceiptGatewayPort;
import github.lms.lemuel.payment.application.port.out.CashReceiptPort;
import github.lms.lemuel.payment.application.port.out.LoadPaymentOwnerPort;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.domain.CashReceipt;
import github.lms.lemuel.payment.domain.CashReceiptIdentifier;
import github.lms.lemuel.payment.domain.CashReceiptPurpose;
import github.lms.lemuel.payment.domain.CashReceiptStatus;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.exception.CashReceiptNotAllowedException;
import github.lms.lemuel.payment.domain.exception.DuplicateCashReceiptException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 현금영수증 발급·취소 흐름.
 *
 * <p>지키는 것은 순서와 실패 처리다: 외부 발급 <b>전에</b> REQUESTED 를 남기고, 발급 실패는 예외가
 * 아니라 FAILED 로 확정하며(예외면 시도 흔적까지 롤백된다), 취소 실패는 ISSUED 로 되돌린다.
 */
@DisplayName("CashReceiptService — 발급 · 소유권 · 취소")
class CashReceiptServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 12, 0);
    private static final long PAYMENT_ID = 7L;
    private static final long OWNER_ID = 100L;

    private CashReceiptPort cashReceiptPort;
    private CashReceiptGatewayPort gatewayPort;
    private LoadPaymentPort loadPaymentPort;
    private LoadPaymentOwnerPort loadPaymentOwnerPort;
    private CashReceiptService service;

    @BeforeEach
    void setUp() {
        cashReceiptPort = mock(CashReceiptPort.class);
        gatewayPort = mock(CashReceiptGatewayPort.class);
        loadPaymentPort = mock(LoadPaymentPort.class);
        loadPaymentOwnerPort = mock(LoadPaymentOwnerPort.class);
        Clock fixed = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneId.of("UTC"));
        service = new CashReceiptService(cashReceiptPort, gatewayPort, loadPaymentPort,
                loadPaymentOwnerPort, fixed);
        when(cashReceiptPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private PaymentDomain payment(PaymentStatus status, String method) {
        return PaymentDomain.rehydrate(PAYMENT_ID, 10L, new BigDecimal("11000"), BigDecimal.ZERO,
                status, method, "PG-1", NOW.minusHours(1), NOW.minusHours(2), NOW.minusHours(1));
    }

    private CashReceiptUseCase.IssueCommand command(long requesterId) {
        return new CashReceiptUseCase.IssueCommand(PAYMENT_ID, requesterId,
                CashReceiptPurpose.INCOME_DEDUCTION, CashReceiptIdentifier.Type.MOBILE, "010-1234-5678");
    }

    private void givenCapturedCashPaymentOwnedBy(long ownerId) {
        when(loadPaymentPort.loadById(PAYMENT_ID))
                .thenReturn(Optional.of(payment(PaymentStatus.CAPTURED, "BANK_TRANSFER")));
        when(loadPaymentOwnerPort.findOwnerUserId(PAYMENT_ID)).thenReturn(Optional.of(ownerId));
        when(cashReceiptPort.findActiveByPaymentId(PAYMENT_ID)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("발급 성공: 승인번호가 붙고 공급가액·부가세가 확정된다")
    void issue_success() {
        givenCapturedCashPaymentOwnedBy(OWNER_ID);
        when(gatewayPort.issue(any())).thenReturn(CashReceiptGatewayPort.Result.issued("APV-1"));

        CashReceipt receipt = service.issue(command(OWNER_ID));

        assertThat(receipt.getStatus()).isEqualTo(CashReceiptStatus.ISSUED);
        assertThat(receipt.getApprovalNumber()).isEqualTo("APV-1");
        assertThat(receipt.getSupplyAmount()).isEqualByComparingTo("10000");
        assertThat(receipt.getVatAmount()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("외부 호출 전에 REQUESTED 를 먼저 저장한다 — 응답을 못 받아도 흔적이 남는다")
    void issue_persistsRequestBeforeCallingGateway() {
        givenCapturedCashPaymentOwnedBy(OWNER_ID);
        when(gatewayPort.issue(any())).thenAnswer(inv -> {
            CashReceipt inFlight = inv.getArgument(0);
            assertThat(inFlight.getStatus()).isEqualTo(CashReceiptStatus.REQUESTED);
            return CashReceiptGatewayPort.Result.issued("APV-1");
        });

        service.issue(command(OWNER_ID));

        verify(cashReceiptPort, org.mockito.Mockito.times(2)).save(any()); // REQUESTED → ISSUED
    }

    @Test
    @DisplayName("발급 실패는 예외가 아니라 FAILED 확정 — 예외면 시도 흔적까지 롤백된다")
    void issue_failureIsRecordedNotThrown() {
        givenCapturedCashPaymentOwnedBy(OWNER_ID);
        when(gatewayPort.issue(any()))
                .thenReturn(CashReceiptGatewayPort.Result.failed("국세청 응답 없음"));

        CashReceipt receipt = service.issue(command(OWNER_ID));

        assertThat(receipt.getStatus()).isEqualTo(CashReceiptStatus.FAILED);
        assertThat(receipt.getFailureReason()).isEqualTo("국세청 응답 없음");
        assertThat(receipt.isActive()).isFalse(); // 재신청 가능
    }

    @Test
    @DisplayName("남의 결제로는 발급할 수 없다 — 결제 id 만 알면 뚫리는 IDOR")
    void issue_rejectsForeignPayment() {
        givenCapturedCashPaymentOwnedBy(OWNER_ID);

        assertThatThrownBy(() -> service.issue(command(999L)))
                .isInstanceOf(AccessDeniedException.class);

        verify(gatewayPort, never()).issue(any());
    }

    @Test
    @DisplayName("소유자를 알 수 없으면 통과시키지 않는다 — fail-closed")
    void issue_failsClosedWhenOwnerUnknown() {
        when(loadPaymentPort.loadById(PAYMENT_ID))
                .thenReturn(Optional.of(payment(PaymentStatus.CAPTURED, "BANK_TRANSFER")));
        when(loadPaymentOwnerPort.findOwnerUserId(PAYMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(command(OWNER_ID)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("입금 전 결제는 발급 대상이 아니다 — 돈보다 세금 서류가 먼저 나가면 안 된다")
    void issue_requiresCapturedPayment() {
        when(loadPaymentPort.loadById(PAYMENT_ID))
                .thenReturn(Optional.of(payment(PaymentStatus.READY, "VIRTUAL_ACCOUNT")));
        when(loadPaymentOwnerPort.findOwnerUserId(PAYMENT_ID)).thenReturn(Optional.of(OWNER_ID));

        assertThatThrownBy(() -> service.issue(command(OWNER_ID)))
                .isInstanceOf(CashReceiptNotAllowedException.class);
    }

    @Test
    @DisplayName("카드 결제는 거부 — 카드사 전표로 이미 신고돼 이중 공제가 된다")
    void issue_rejectsCardPayment() {
        when(loadPaymentPort.loadById(PAYMENT_ID))
                .thenReturn(Optional.of(payment(PaymentStatus.CAPTURED, "CARD")));
        when(loadPaymentOwnerPort.findOwnerUserId(PAYMENT_ID)).thenReturn(Optional.of(OWNER_ID));
        when(cashReceiptPort.findActiveByPaymentId(PAYMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(command(OWNER_ID)))
                .isInstanceOf(CashReceiptNotAllowedException.class);
    }

    @Test
    @DisplayName("유효한 영수증이 이미 있으면 중복 발급을 막는다")
    void issue_rejectsDuplicate() {
        when(loadPaymentPort.loadById(PAYMENT_ID))
                .thenReturn(Optional.of(payment(PaymentStatus.CAPTURED, "BANK_TRANSFER")));
        when(loadPaymentOwnerPort.findOwnerUserId(PAYMENT_ID)).thenReturn(Optional.of(OWNER_ID));
        when(cashReceiptPort.findActiveByPaymentId(PAYMENT_ID))
                .thenReturn(Optional.of(issuedReceipt()));

        assertThatThrownBy(() -> service.issue(command(OWNER_ID)))
                .isInstanceOf(DuplicateCashReceiptException.class);
    }

    @Test
    @DisplayName("조회도 본인 결제만 — 식별번호가 붙은 세금 서류다")
    void find_requiresOwnership() {
        when(loadPaymentOwnerPort.findOwnerUserId(PAYMENT_ID)).thenReturn(Optional.of(OWNER_ID));

        assertThatThrownBy(() -> service.findActiveByPayment(PAYMENT_ID, 999L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("전액 환불 시 취소 — 남겨 두면 받지 않은 돈에 공제가 붙는다")
    void cancel_onRefund() {
        CashReceipt issued = issuedReceipt();
        when(cashReceiptPort.findActiveByPaymentId(PAYMENT_ID)).thenReturn(Optional.of(issued));
        when(gatewayPort.cancel(any(), anyString())).thenReturn(CashReceiptGatewayPort.Result.ok());

        service.cancelForPayment(PAYMENT_ID, "결제 전액 환불");

        assertThat(issued.getStatus()).isEqualTo(CashReceiptStatus.CANCELED);
    }

    @Test
    @DisplayName("취소 실패는 ISSUED 로 되돌린다 — 국세청과 어긋난 채 종단에 박히지 않게")
    void cancel_failureReverts() {
        CashReceipt issued = issuedReceipt();
        when(cashReceiptPort.findActiveByPaymentId(PAYMENT_ID)).thenReturn(Optional.of(issued));
        when(gatewayPort.cancel(any(), anyString()))
                .thenReturn(CashReceiptGatewayPort.Result.failed("취소 거부"));

        service.cancelForPayment(PAYMENT_ID, "결제 전액 환불");

        assertThat(issued.getStatus()).isEqualTo(CashReceiptStatus.ISSUED);
    }

    @Test
    @DisplayName("발급 이력이 없는 결제 취소는 조용히 끝난다 — 현금 결제가 아니었을 뿐")
    void cancel_noReceiptIsNoOp() {
        when(cashReceiptPort.findActiveByPaymentId(PAYMENT_ID)).thenReturn(Optional.empty());

        service.cancelForPayment(PAYMENT_ID, "결제 전액 환불");

        verify(gatewayPort, never()).cancel(any(), anyString());
    }

    @Test
    @DisplayName("발급 응답 대기 중(REQUESTED)인 건은 억지로 취소하지 않는다 — 발급 결과와 경합한다")
    void cancel_skipsNonCancellable() {
        CashReceipt requested = CashReceipt.request(PAYMENT_ID, 10L, OWNER_ID, "BANK_TRANSFER",
                new BigDecimal("11000"), CashReceiptPurpose.INCOME_DEDUCTION,
                CashReceiptIdentifier.of(CashReceiptIdentifier.Type.MOBILE, "01012345678"), NOW);
        when(cashReceiptPort.findActiveByPaymentId(PAYMENT_ID)).thenReturn(Optional.of(requested));

        service.cancelForPayment(PAYMENT_ID, "결제 전액 환불");

        assertThat(requested.getStatus()).isEqualTo(CashReceiptStatus.REQUESTED);
        verify(gatewayPort, never()).cancel(any(), anyString());
    }

    @Test
    @DisplayName("결제가 없으면 발급 자체가 성립하지 않는다")
    void issue_paymentNotFound() {
        when(loadPaymentPort.loadById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(command(OWNER_ID)))
                .isInstanceOf(github.lms.lemuel.payment.domain.exception.PaymentNotFoundException.class);
    }

    @Test
    @DisplayName("주문 기준 발급: 결제를 내부에서 해석한다 — 고객이 쥔 식별자는 주문번호다")
    void issueForOrder_resolvesPayment() {
        givenCapturedCashPaymentOwnedBy(OWNER_ID);
        when(loadPaymentPort.loadByOrderId(10L))
                .thenReturn(Optional.of(payment(PaymentStatus.CAPTURED, "BANK_TRANSFER")));
        when(gatewayPort.issue(any())).thenReturn(CashReceiptGatewayPort.Result.issued("APV-1"));

        CashReceipt receipt = service.issueForOrder(10L, OWNER_ID,
                CashReceiptPurpose.INCOME_DEDUCTION, CashReceiptIdentifier.Type.MOBILE, "01012345678");

        assertThat(receipt.getStatus()).isEqualTo(CashReceiptStatus.ISSUED);
    }

    @Test
    @DisplayName("결제가 없는 주문은 발급 대상이 아니다")
    void issueForOrder_withoutPayment() {
        when(loadPaymentPort.loadByOrderId(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueForOrder(10L, OWNER_ID,
                CashReceiptPurpose.INCOME_DEDUCTION, CashReceiptIdentifier.Type.MOBILE, "01012345678"))
                .isInstanceOf(CashReceiptNotAllowedException.class);
    }

    @Test
    @DisplayName("주문 기준 조회도 결제가 없으면 빈 값 — 예외로 화면을 깨뜨리지 않는다")
    void findActiveByOrder_withoutPayment() {
        when(loadPaymentPort.loadByOrderId(10L)).thenReturn(Optional.empty());

        assertThat(service.findActiveByOrder(10L, OWNER_ID)).isEmpty();
    }

    private CashReceipt issuedReceipt() {
        CashReceipt receipt = CashReceipt.request(PAYMENT_ID, 10L, OWNER_ID, "BANK_TRANSFER",
                new BigDecimal("11000"), CashReceiptPurpose.INCOME_DEDUCTION,
                CashReceiptIdentifier.of(CashReceiptIdentifier.Type.MOBILE, "01012345678"), NOW);
        receipt.markIssued("APV-1", NOW);
        return receipt;
    }
}
