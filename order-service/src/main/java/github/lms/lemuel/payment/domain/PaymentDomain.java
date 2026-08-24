package github.lms.lemuel.payment.domain;

import github.lms.lemuel.payment.domain.exception.InvalidPaymentStateException;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;
import github.lms.lemuel.payment.domain.exception.RefundExceedsPaymentException;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Payment Domain Entity - Pure domain model without framework dependencies.
 *
 * <p>분할결제(Split Payment) 지원: {@code tenders} 리스트가 채워지면 분할결제, 비어있으면 단일결제.
 * 분할결제의 경우 {@code SUM(tenders.amount) == amount} 가 도메인 불변식.
 *
 * <p><b>금액 표현 경계 — raw {@link BigDecimal} 의도적 사용({@code Order.createMultiItem} 과 동일 근거).</b>
 * 결제 금액({@code amount}·{@code refundedAmount}·tender 합산)은 정수 KRW(scale 0) 위에서의 합산·차감뿐이라
 * 항상 정확한 정수 연산이다 — 반올림 여지가 없어 공용 Money VO(shared-common)의 scale 2 HALF_UP 정규화 이득이 0 이다.
 * 반대로 Money 를 통과시키면 amount 가 scale 2(예: 3088000.00)로 바뀌어, 이 금액이 흘러가는 정산 프로젝션의
 * 금액 비교(MSA 경계, settlement_payment_view)에 scale drift 만 유발한다. Money javadoc 의
 * "scale 2 HALF_UP 통화 전용" 경계와 일치하는 판단으로, 정수 결제 금액은 raw {@code BigDecimal} 로 둔다.
 */
@Getter
public class PaymentDomain {

    private final Long id;
    private final Long orderId;
    private final BigDecimal amount;
    private BigDecimal refundedAmount;
    private PaymentStatus status;
    private final String paymentMethod;
    private String pgTransactionId;
    private LocalDateTime capturedAt;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 분할결제 라인. 비어있으면 단일결제 (legacy). */
    private final List<PaymentTender> tenders = new ArrayList<>();

    /**
     * 신규 결제 생성 팩토리 — 봉인된 정본 생성자에 위임한다(Payment/Settlement 동형: 생성자 비공개, 팩토리 공개).
     * id·pgTransactionId·capturedAt 는 신규 시점에 없으므로 null, 상태는 READY, 환불 누적은 0 으로 못박는다.
     */
    public static PaymentDomain create(Long orderId, BigDecimal amount, String paymentMethod) {
        LocalDateTime now = LocalDateTime.now();
        return new PaymentDomain(null, orderId, amount, BigDecimal.ZERO, PaymentStatus.READY,
                paymentMethod, null, null, now, now);
    }

    /**
     * 텐더 기반 결제 팩토리. tenders 합계를 amount 로 자동 계산해 도메인 불변식을 보장한다
     * (외부에서 amount 수동 지정 불가).
     *
     * <p><b>왜 1 개도 허용하는가</b> — 예전에는 "최소 2 개"였고, 1 개면 일반 결제
     * ({@link #create})를 쓰라고 했다. 그런데 일반 결제는 {@code paymentMethod} 문자열만 알 뿐
     * <b>지불수단을 모델링하지 않아서</b>, 포인트·기프트카드 원장의 차감·복원이 걸릴 자리가 없다.
     * 그 결과 포인트 원장을 다 만들어 놓고도 <b>포인트만으로 결제하는 것이 불가능</b>했다
     * (docs/plan/point-ledger.md §6 ③). 텐더 개념을 두 번째로 만드는 대신 이 팩토리의 하한을
     * 1 로 내린다 — 지불수단이 하나든 셋이든 같은 경로를 타야 환불 복원도 한 곳에서 끝난다.
     *
     * <p>0 개는 여전히 거부한다. 금액을 계산할 근거가 없기 때문이다.
     *
     * @param orderId 주문 ID
     * @param tenders 지불수단 라인들. 1 개 이상
     * @param paymentMethod 표시용 메서드명 (1 개면 그 수단명, 2 개 이상이면 {@code "SPLIT:"} + 최대 tender 의 type)
     */
    public static PaymentDomain createWithTenders(Long orderId, List<PaymentTender> tenders,
                                             String paymentMethod) {
        if (tenders == null || tenders.isEmpty()) {
            throw new PaymentInvariantViolationException("결제에는 최소 1 개의 지불수단이 필요합니다");
        }
        BigDecimal total = tenders.stream()
                .map(PaymentTender::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        PaymentDomain p = create(orderId, total, paymentMethod);
        p.tenders.addAll(tenders);
        return p;
    }

    /**
     * 영속 레코드 복원 팩토리(MapStruct/매퍼의 toDomain 전용). 저장된 필드를 그대로 재구성한다.
     * 봉인된 정본 생성자에 위임해 생성 경로를 create/rehydrate 둘로 못박는다.
     */
    public static PaymentDomain rehydrate(Long id, Long orderId, BigDecimal amount, BigDecimal refundedAmount,
                   PaymentStatus status, String paymentMethod, String pgTransactionId,
                   LocalDateTime capturedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new PaymentDomain(id, orderId, amount, refundedAmount, status, paymentMethod,
                pgTransactionId, capturedAt, createdAt, updatedAt);
    }

    // 정본 생성자 — create/rehydrate 팩토리만 통과한다.
    private PaymentDomain(Long id, Long orderId, BigDecimal amount, BigDecimal refundedAmount,
                   PaymentStatus status, String paymentMethod, String pgTransactionId,
                   LocalDateTime capturedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.orderId = orderId;
        this.amount = amount;
        this.refundedAmount = refundedAmount;
        this.status = status;
        this.paymentMethod = paymentMethod;
        this.pgTransactionId = pgTransactionId;
        this.capturedAt = capturedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Business logic: Authorize payment
    public void authorize(String pgTransactionId) {
        requireTransition(PaymentStatus.AUTHORIZED);
        this.status = PaymentStatus.AUTHORIZED;
        this.pgTransactionId = pgTransactionId;
        this.updatedAt = LocalDateTime.now();
    }

    // Business logic: Capture payment
    public void capture() {
        requireTransition(PaymentStatus.CAPTURED);
        this.status = PaymentStatus.CAPTURED;
        this.capturedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // Business logic: Cancel authorization (승인취소) — 매입(capture) 전 AUTHORIZED 건만 취소 가능.
    // capture 이후 자금 회수는 refund 경로를 사용한다(상태머신: AUTHORIZED ↘ CANCELED).
    public void cancel() {
        requireTransition(PaymentStatus.CANCELED);
        this.status = PaymentStatus.CANCELED;
        this.updatedAt = LocalDateTime.now();
    }

    // Business logic: Expire payment (미입금 만료) — 입금을 기다리는 READY 건만 만료 가능.
    // 승인 이후에는 자금이 잡혀 있으므로 cancel/refund 경로를 쓴다(상태머신: READY ↘ EXPIRED).
    // 만료 여부 판정(수단·기한)은 PaymentExpiryPolicy 가, 전이 가능 여부는 이 가드가 책임진다.
    public void expire() {
        requireTransition(PaymentStatus.EXPIRED);
        this.status = PaymentStatus.EXPIRED;
        this.updatedAt = LocalDateTime.now();
    }

    // Business logic: Refund payment
    public void refund() {
        requireTransition(PaymentStatus.REFUNDED);
        this.status = PaymentStatus.REFUNDED;
        this.updatedAt = LocalDateTime.now();
    }

    // 상태 전이 가드 — 허용 전이는 PaymentStatus#canTransitionTo 단일 출처에 위임한다.
    private void requireTransition(PaymentStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new InvalidPaymentStateException(this.status, target);
        }
    }

    // Business logic: Calculate refundable amount
    public BigDecimal getRefundableAmount() {
        return amount.subtract(refundedAmount);
    }

    // Business logic: Check if fully refunded
    public boolean isFullyRefunded() {
        return refundedAmount.compareTo(amount) >= 0;
    }

    // Business logic: Add refunded amount
    // 도메인 불변식(최종 방어선): 누적 환불액은 결제 금액을 초과할 수 없다(초과환불 차단).
    // Settlement.adjustForRefund 와 동형 — 유스케이스 선행 가드가 아니라 도메인이 불변식을 강제한다.
    public void addRefundedAmount(BigDecimal refundAmount) {
        BigDecimal newRefunded = this.refundedAmount.add(refundAmount);
        if (newRefunded.compareTo(this.amount) > 0) {
            throw new RefundExceedsPaymentException(
                    "Cumulative refund " + newRefunded + " exceeds payment amount " + this.amount);
        }
        this.refundedAmount = newRefunded;
        this.updatedAt = LocalDateTime.now();
    }

    // ───────── 분할결제 (Split Payment) 지원 ─────────

    public boolean isSplit() {
        return !tenders.isEmpty();
    }

    /**
     * 이 결제가 <b>돈이 아직 들어오지 않은</b> 상태인가 — 즉시 캡처 금지·포인트 선점·미입금 만료의 판정축.
     *
     * <p>텐더 결제는 <b>텐더 목록</b>으로 판정한다. {@code paymentMethod} 는 가장 큰 텐더에서 만든
     * 표시값이라(카드 90,000 + 가상계좌 10,000 → {@code "SPLIT:CARD"}) 그 문자열만 보면 가상계좌가
     * 섞인 결제를 통째로 놓친다 — 그런 결제는 재고를 붙잡은 채 영원히 만료되지 않는다.
     *
     * <p>텐더가 없는 일반 결제만 수단 문자열로 판정한다(그때는 그것이 유일한 정보다).
     */
    public boolean awaitsDeposit() {
        if (isSplit()) {
            return tenders.stream().anyMatch(t -> t.getType().awaitsDeposit());
        }
        return PaymentExpiryPolicy.isDepositMethod(paymentMethod);
    }

    public List<PaymentTender> getTenders() {
        return List.copyOf(tenders);
    }

    /**
     * 분할결제 환불 정책: tender 들을 sequence <b>역순</b> 으로 순회하며 환불액을 차감한다.
     * 외부 PG 가 먼저 환불(실 거래 취소) → 내부 잔액(포인트/상품권) 마지막 복원 — 운영 사고 방지.
     */
    public List<TenderRefundPlan> planRefundFromTenders(BigDecimal totalToRefund) {
        if (!isSplit()) {
            throw new InvalidPaymentStateException("단일결제는 planRefundFromTenders 호출 불가");
        }
        if (totalToRefund == null || totalToRefund.signum() <= 0) {
            throw new PaymentInvariantViolationException("환불 금액은 양수여야 합니다");
        }
        BigDecimal totalRefundable = tenders.stream()
                .map(PaymentTender::getRefundableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalToRefund.compareTo(totalRefundable) > 0) {
            throw new PaymentInvariantViolationException(
                    "전체 환불 가능액 초과: 요청=" + totalToRefund + ", 잔여=" + totalRefundable);
        }

        List<TenderRefundPlan> plans = new ArrayList<>();
        BigDecimal remaining = totalToRefund;
        // sequence 역순 (큰 sequence 먼저)
        List<PaymentTender> sortedDesc = new ArrayList<>(tenders);
        sortedDesc.sort(Comparator.comparingInt(PaymentTender::getSequence).reversed());

        for (PaymentTender t : sortedDesc) {
            if (remaining.signum() <= 0) break;
            BigDecimal availableInTender = t.getRefundableAmount();
            if (availableInTender.signum() <= 0) continue;
            BigDecimal portion = availableInTender.compareTo(remaining) <= 0
                    ? availableInTender : remaining;
            plans.add(new TenderRefundPlan(t, portion));
            remaining = remaining.subtract(portion);
        }
        return plans;
    }

    /** 도메인 검증: 분할결제일 때 tender 합계와 amount 가 일치하는지. */
    public void validateTenderSum() {
        if (!isSplit()) return;
        BigDecimal sum = tenders.stream()
                .map(PaymentTender::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(this.amount) != 0) {
            throw new InvalidPaymentStateException(
                    "분할결제 tender 합계가 amount 와 불일치: tenderSum=" + sum + ", amount=" + amount);
        }
    }

    public void replaceTenders(List<PaymentTender> reloaded) {
        this.tenders.clear();
        if (reloaded != null) this.tenders.addAll(reloaded);
    }

    public void attachTendersToPayment() {
        if (this.id == null) throw new IllegalStateException("Payment id 부여 후 호출");
        for (PaymentTender t : tenders) t.attachToPayment(this.id);
    }

    /** 분할결제 환불 1 라인 계획. 환불 서비스가 이걸 받아 PG 호출 또는 내부 잔액 복원. */
    public record TenderRefundPlan(PaymentTender tender, BigDecimal amount) { }
}
