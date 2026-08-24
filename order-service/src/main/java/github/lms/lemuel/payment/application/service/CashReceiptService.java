package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.in.CashReceiptUseCase;
import github.lms.lemuel.payment.application.port.out.CashReceiptGatewayPort;
import github.lms.lemuel.payment.application.port.out.CashReceiptPort;
import github.lms.lemuel.payment.application.port.out.LoadPaymentOwnerPort;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.domain.CashReceipt;
import github.lms.lemuel.payment.domain.CashReceiptIdentifier;
import github.lms.lemuel.payment.domain.CashReceiptPurpose;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.exception.CashReceiptNotAllowedException;
import github.lms.lemuel.payment.domain.exception.DuplicateCashReceiptException;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 현금영수증 발급·조회·취소.
 *
 * <p><b>발급 순서</b>: 소유권 대조 → 결제 상태 확인 → 중복 확인 → REQUESTED 저장 → 외부 발급 →
 * 결과 반영. 외부 호출 <b>전에</b> REQUESTED 를 저장하는 것이 핵심이다 — 저장 없이 외부부터 부르면
 * 응답을 못 받았을 때 "국세청에는 발급됐는데 우리는 모르는" 건이 생기고, 사용자가 재시도하면
 * 그대로 이중 발급이 된다. 요청 흔적이 먼저 있어야 그 뒤 무슨 일이 나든 추적된다.
 *
 * <p><b>발급 실패는 예외로 올리지 않는다.</b> FAILED 로 확정해 사유를 남기고 그대로 돌려준다.
 * 예외를 던지면 트랜잭션이 롤백되어 <b>시도한 흔적까지 사라지고</b>, 국세청 쪽에서 부분 성공했을
 * 경우를 영영 알 수 없게 된다.
 */
@Service
public class CashReceiptService implements CashReceiptUseCase {

    private static final Logger log = LoggerFactory.getLogger(CashReceiptService.class);

    private final CashReceiptPort cashReceiptPort;
    private final CashReceiptGatewayPort gatewayPort;
    private final LoadPaymentPort loadPaymentPort;
    private final LoadPaymentOwnerPort loadPaymentOwnerPort;
    private final Clock clock;

    public CashReceiptService(CashReceiptPort cashReceiptPort,
                              CashReceiptGatewayPort gatewayPort,
                              LoadPaymentPort loadPaymentPort,
                              LoadPaymentOwnerPort loadPaymentOwnerPort,
                              Clock clock) {
        this.cashReceiptPort = cashReceiptPort;
        this.gatewayPort = gatewayPort;
        this.loadPaymentPort = loadPaymentPort;
        this.loadPaymentOwnerPort = loadPaymentOwnerPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CashReceipt issue(IssueCommand command) {
        PaymentDomain payment = loadPaymentPort.loadById(command.paymentId())
                .orElseThrow(() -> new PaymentNotFoundException(command.paymentId()));
        return doIssue(payment, command);
    }

    @Override
    @Transactional
    public CashReceipt issueForOrder(Long orderId, Long requesterUserId, CashReceiptPurpose purpose,
                                     CashReceiptIdentifier.Type identifierType, String identifierValue) {
        PaymentDomain payment = loadPaymentPort.loadByOrderId(orderId)
                .orElseThrow(() -> new CashReceiptNotAllowedException(
                        "결제 내역이 없는 주문에는 현금영수증을 발급할 수 없습니다: orderId=" + orderId));
        return doIssue(payment, new IssueCommand(payment.getId(), requesterUserId, purpose,
                identifierType, identifierValue));
    }

    /**
     * 발급 본체 — 두 진입점(결제 기준 / 주문 기준)이 공유한다.
     *
     * <p>{@code issueForOrder} 가 {@code issue} 를 부르지 않고 여기로 모이는 이유: 같은 빈 안에서
     * {@code @Transactional} 메서드를 자기호출하면 프록시를 거치지 않아 그 애노테이션이 아무 일도
     * 하지 않는다. 지금은 바깥 메서드도 트랜잭션이라 결과가 같지만, 나중에 어느 한쪽의 전파 속성이
     * 바뀌는 날 조용히 어긋난다.
     */
    private CashReceipt doIssue(PaymentDomain payment, IssueCommand command) {
        LocalDateTime now = LocalDateTime.now(clock);

        Long ownerUserId = requireOwnership(command.paymentId(), command.requesterUserId());

        // 돈이 들어오기 전에 세금 서류부터 나가면 안 된다.
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw new CashReceiptNotAllowedException(
                    "입금이 확인된 결제에만 현금영수증을 발급합니다. 현재 상태: " + payment.getStatus());
        }

        cashReceiptPort.findActiveByPaymentId(command.paymentId()).ifPresent(existing -> {
            throw new DuplicateCashReceiptException(
                    "이미 처리 중이거나 발급된 현금영수증이 있습니다: status=" + existing.getStatus());
        });

        CashReceiptIdentifier identifier =
                CashReceiptIdentifier.of(command.identifierType(), command.identifierValue());

        CashReceipt receipt = CashReceipt.request(
                payment.getId(), payment.getOrderId(), ownerUserId,
                payment.getPaymentMethod(), payment.getAmount(),
                command.purpose(), identifier, now);
        CashReceipt saved = cashReceiptPort.save(receipt);

        CashReceiptGatewayPort.Result result = gatewayPort.issue(saved);
        if (result.success()) {
            saved.markIssued(result.approvalNumber(), now);
            log.info("현금영수증 발급: paymentId={}, purpose={}, 식별번호={}, 공급가액={}, 부가세={}",
                    saved.getPaymentId(), saved.getPurpose(), identifier.masked(),
                    saved.getSupplyAmount(), saved.getVatAmount());
        } else {
            saved.markFailed(result.message(), now);
            log.warn("현금영수증 발급 실패: paymentId={}, reason={}", saved.getPaymentId(), result.message());
        }
        return cashReceiptPort.save(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CashReceipt> findActiveByPayment(Long paymentId, Long requesterUserId) {
        return loadActive(paymentId, requesterUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CashReceipt> findActiveByOrder(Long orderId, Long requesterUserId) {
        return loadPaymentPort.loadByOrderId(orderId)
                .flatMap(payment -> loadActive(payment.getId(), requesterUserId));
    }

    /** 조회 본체 — 두 진입점이 공유한다(자기호출로 프록시를 우회하지 않기 위해 분리). */
    private Optional<CashReceipt> loadActive(Long paymentId, Long requesterUserId) {
        requireOwnership(paymentId, requesterUserId);
        return cashReceiptPort.findActiveByPaymentId(paymentId);
    }

    /**
     * 환불·취소에 딸린 영수증 취소.
     *
     * <p>소유권을 묻지 않는다 — 호출자가 사용자가 아니라 <b>환불 처리 흐름</b>이기 때문이다.
     * 발급된 영수증이 없으면 조용히 끝낸다(현금 결제가 아니었거나 애초에 신청하지 않은 주문).
     */
    @Override
    @Transactional
    public void cancelForPayment(Long paymentId, String reason) {
        Optional<CashReceipt> found = cashReceiptPort.findActiveByPaymentId(paymentId);
        if (found.isEmpty()) {
            return;
        }
        CashReceipt receipt = found.get();
        if (!receipt.getStatus().cancellable()) {
            // 발급 응답을 아직 못 받은 건(REQUESTED)이나 이미 취소 진행 중인 건.
            // 여기서 억지로 취소하면 발급 결과와 경합한다 — 운영이 보도록 남긴다.
            log.warn("현금영수증 취소 보류 — 취소 가능 상태가 아님: paymentId={}, status={}",
                    paymentId, receipt.getStatus());
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        receipt.requestCancel(reason, now);
        cashReceiptPort.save(receipt);

        CashReceiptGatewayPort.Result result = gatewayPort.cancel(receipt, reason);
        if (result.success()) {
            receipt.markCanceled(now);
            log.info("현금영수증 취소: paymentId={}, reason={}", paymentId, reason);
        } else {
            receipt.revertCancel(result.message(), now);
            log.error("현금영수증 취소 실패 — 발급 상태로 되돌림(수기 취소 필요): paymentId={}, reason={}",
                    paymentId, result.message());
        }
        cashReceiptPort.save(receipt);
    }

    /** 결제 소유자와 요청자를 대조한다. 소유자를 알 수 없으면 통과시키지 않는다(fail-closed). */
    private Long requireOwnership(Long paymentId, Long requesterUserId) {
        Long ownerUserId = loadPaymentOwnerPort.findOwnerUserId(paymentId)
                .orElseThrow(() -> new AccessDeniedException("결제 소유자를 확인할 수 없습니다"));
        if (requesterUserId == null || !ownerUserId.equals(requesterUserId)) {
            throw new AccessDeniedException("본인 결제의 현금영수증만 처리할 수 있습니다");
        }
        return ownerUserId;
    }
}
