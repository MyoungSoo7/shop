package github.lms.lemuel.payment.domain;

import github.lms.lemuel.payment.domain.exception.CashReceiptNotAllowedException;
import github.lms.lemuel.payment.domain.exception.InvalidCashReceiptStateException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * 현금영수증 (순수 도메인 — 프레임워크·시계 의존 0).
 *
 * <p><b>왜 카드 결제는 대상이 아닌가</b>: 카드 매출은 카드사 매출전표로 이미 국세청에 잡힌다. 거기에
 * 현금영수증까지 발급하면 같은 거래가 두 번 신고되어 <b>이중 공제</b>가 된다 — 나중에 가산세로 돌아온다.
 * 그래서 발급 대상은 계좌이체·가상계좌 같은 현금성 수단뿐이다({@link #CASH_TENDERS}).
 * 레거시 커머스(ssgb2e)도 급여공제·무통장 건에만 발급/취소 전문을 태웠다.
 *
 * <p><b>공급가액·부가세 분해</b>는 발급 시점에 확정해 보관한다. 표시할 때마다 다시 계산하면 세율이
 * 바뀌는 순간 과거 영수증의 숫자가 소급해 달라진다 — 세금 서류에서는 그것이 곧 위조다.
 */
public class CashReceipt {

    /** 현금성 지불수단 — 이 수단으로 받은 돈만 현금영수증 발급 대상이다. */
    private static final Set<TenderType> CASH_TENDERS =
            EnumSet.of(TenderType.BANK_TRANSFER, TenderType.VIRTUAL_ACCOUNT);

    /** 부가세율 10% → 총액에서 부가세를 역산하는 제수. 총액 = 공급가액 × 1.1 이므로 부가세 = 총액 / 11. */
    private static final BigDecimal VAT_DIVISOR = new BigDecimal("11");

    private Long id;
    private final Long paymentId;
    private final Long orderId;
    private final Long userId;
    private final CashReceiptPurpose purpose;
    private final CashReceiptIdentifier identifier;
    private final BigDecimal totalAmount;
    private final BigDecimal supplyAmount;
    private final BigDecimal vatAmount;
    private CashReceiptStatus status;
    private String approvalNumber;
    private String failureReason;
    private LocalDateTime issuedAt;
    private LocalDateTime canceledAt;
    private String cancelReason;
    private final LocalDateTime requestedAt;
    private LocalDateTime updatedAt;

    private CashReceipt(Long id, Long paymentId, Long orderId, Long userId,
                        CashReceiptPurpose purpose, CashReceiptIdentifier identifier,
                        BigDecimal totalAmount, BigDecimal supplyAmount, BigDecimal vatAmount,
                        CashReceiptStatus status, String approvalNumber, String failureReason,
                        LocalDateTime issuedAt, LocalDateTime canceledAt, String cancelReason,
                        LocalDateTime requestedAt, LocalDateTime updatedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.userId = userId;
        this.purpose = purpose;
        this.identifier = identifier;
        this.totalAmount = totalAmount;
        this.supplyAmount = supplyAmount;
        this.vatAmount = vatAmount;
        this.status = status;
        this.approvalNumber = approvalNumber;
        this.failureReason = failureReason;
        this.issuedAt = issuedAt;
        this.canceledAt = canceledAt;
        this.cancelReason = cancelReason;
        this.requestedAt = requestedAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 발급 요청 생성 — 대상 판정과 금액 분해를 여기서 끝낸다.
     *
     * @param paymentMethod 결제 수단 문자열({@link TenderType} 이름). 미상값은 발급 거부한다 —
     *                      모르는 수단을 현금으로 가정하면 카드 건에 이중 발급이 날 수 있다
     */
    public static CashReceipt request(Long paymentId, Long orderId, Long userId,
                                      String paymentMethod, BigDecimal paymentAmount,
                                      CashReceiptPurpose purpose, CashReceiptIdentifier identifier,
                                      LocalDateTime now) {
        requireCashTender(paymentMethod);
        requirePurposeMatchesIdentifier(purpose, identifier);
        if (paymentAmount == null || paymentAmount.signum() <= 0) {
            throw new CashReceiptNotAllowedException("현금영수증 발급 금액은 0 보다 커야 합니다: " + paymentAmount);
        }

        BigDecimal vat = paymentAmount.divide(VAT_DIVISOR, 0, RoundingMode.HALF_UP);
        BigDecimal supply = paymentAmount.subtract(vat);
        return new CashReceipt(null, paymentId, orderId, userId, purpose, identifier,
                paymentAmount, supply, vat, CashReceiptStatus.REQUESTED, null, null,
                null, null, null, now, now);
    }

    /** 영속 레코드 복원 전용. 저장된 상태를 그대로 재구성한다(생성 팩토리는 REQUESTED 로 못박으므로 사용 불가). */
    public static CashReceipt rehydrate(Long id, Long paymentId, Long orderId, Long userId,
                                        CashReceiptPurpose purpose, CashReceiptIdentifier identifier,
                                        BigDecimal totalAmount, BigDecimal supplyAmount, BigDecimal vatAmount,
                                        CashReceiptStatus status, String approvalNumber, String failureReason,
                                        LocalDateTime issuedAt, LocalDateTime canceledAt, String cancelReason,
                                        LocalDateTime requestedAt, LocalDateTime updatedAt) {
        return new CashReceipt(id, paymentId, orderId, userId, purpose, identifier,
                totalAmount, supplyAmount, vatAmount, status, approvalNumber, failureReason,
                issuedAt, canceledAt, cancelReason, requestedAt, updatedAt);
    }

    /** 이 결제 수단이 현금영수증 발급 대상인지. 미상 문자열은 false — 모르면 발급하지 않는다. */
    public static boolean isCashTender(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return false;
        }
        try {
            return CASH_TENDERS.contains(TenderType.valueOf(paymentMethod.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void requireCashTender(String paymentMethod) {
        if (!isCashTender(paymentMethod)) {
            throw new CashReceiptNotAllowedException(
                    "현금영수증은 계좌이체·가상계좌 결제에만 발급합니다(카드 매출은 카드사 전표로 이미 신고됨): "
                            + paymentMethod);
        }
    }

    private static void requirePurposeMatchesIdentifier(CashReceiptPurpose purpose,
                                                        CashReceiptIdentifier identifier) {
        if (purpose == null || identifier == null) {
            throw new CashReceiptNotAllowedException("발급 용도와 식별번호는 필수입니다");
        }
        if (!purpose.allows(identifier.getType())) {
            throw new CashReceiptNotAllowedException(
                    purpose.label() + " 용도로는 " + identifier.getType() + " 식별번호를 쓸 수 없습니다");
        }
    }

    /** 국세청 발급 승인 — 승인번호를 받아 확정한다. */
    public void markIssued(String approvalNumber, LocalDateTime now) {
        if (approvalNumber == null || approvalNumber.isBlank()) {
            throw new InvalidCashReceiptStateException("승인번호 없이 발급을 확정할 수 없습니다");
        }
        transitionTo(CashReceiptStatus.ISSUED, now);
        this.approvalNumber = approvalNumber;
        this.issuedAt = now;
        this.failureReason = null;
    }

    /** 발급 실패 — 자리를 비워 재신청이 가능하게 둔다. */
    public void markFailed(String reason, LocalDateTime now) {
        transitionTo(CashReceiptStatus.FAILED, now);
        this.failureReason = reason;
    }

    /** 취소 요청 — 국세청 왕복 전 중간 상태. */
    public void requestCancel(String reason, LocalDateTime now) {
        transitionTo(CashReceiptStatus.CANCEL_REQUESTED, now);
        this.cancelReason = reason;
    }

    /** 취소 확정. */
    public void markCanceled(LocalDateTime now) {
        transitionTo(CashReceiptStatus.CANCELED, now);
        this.canceledAt = now;
    }

    /**
     * 취소 실패 — 발급 상태로 되돌린다.
     *
     * <p>CANCELED 로 두면 국세청에는 발급이 살아 있는데 우리 장부에는 없는 어긋남이 남고,
     * 그 상태는 다음 취소 시도조차 막는다(CANCELED 는 종단).
     */
    public void revertCancel(String reason, LocalDateTime now) {
        transitionTo(CashReceiptStatus.ISSUED, now);
        this.failureReason = reason;
        this.cancelReason = null;
    }

    private void transitionTo(CashReceiptStatus target, LocalDateTime now) {
        if (this.status == target) {
            return; // 멱등 no-op — 재시도 경로가 여럿이다
        }
        if (!this.status.canTransitionTo(target)) {
            throw new InvalidCashReceiptStateException(
                    "현금영수증 상태 전이 불가: " + this.status + " → " + target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    /** 영속 저장이 부여한 식별자를 1 회 부여한다(식별자는 불변). */
    public void assignId(Long id) {
        if (this.id != null && !this.id.equals(id)) {
            throw new InvalidCashReceiptStateException("현금영수증 id 는 이미 부여되어 변경할 수 없습니다");
        }
        this.id = id;
    }

    public boolean isActive() {
        return status.occupiesActiveSlot();
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public Long getOrderId() { return orderId; }
    public Long getUserId() { return userId; }
    public CashReceiptPurpose getPurpose() { return purpose; }
    public CashReceiptIdentifier getIdentifier() { return identifier; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BigDecimal getSupplyAmount() { return supplyAmount; }
    public BigDecimal getVatAmount() { return vatAmount; }
    public CashReceiptStatus getStatus() { return status; }
    public String getApprovalNumber() { return approvalNumber; }
    public String getFailureReason() { return failureReason; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public LocalDateTime getCanceledAt() { return canceledAt; }
    public String getCancelReason() { return cancelReason; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
