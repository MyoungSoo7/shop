package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.domain.OrderReturnRequest;
import github.lms.lemuel.order.domain.RefundAccount;
import github.lms.lemuel.order.domain.ReturnRequestStatus;
import github.lms.lemuel.order.domain.ReturnRequestType;
import github.lms.lemuel.order.domain.ReturnWaybill;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * {@code order_return_requests} 매핑.
 *
 * <p>enum 은 {@code String} 으로 담는다({@code @Enumerated(ORDINAL)} 이 아니라). 순서 기반 저장은
 * enum 상수를 하나 끼워 넣는 순간 이미 저장된 모든 행의 뜻이 조용히 바뀐다.
 */
@Entity
@Table(name = "order_return_requests")
public class OrderReturnRequestJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_type", nullable = false, length = 20)
    private String requestType;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "reason_code", nullable = false, length = 40)
    private String reasonCode;

    @Column(name = "reason_detail", length = 500)
    private String reasonDetail;

    @Column(name = "refund_account_required", nullable = false)
    private boolean refundAccountRequired;

    @Column(name = "refund_bank_code", length = 20)
    private String refundBankCode;

    @Column(name = "refund_account_no", length = 60)
    private String refundAccountNo;

    @Column(name = "refund_account_holder", length = 60)
    private String refundAccountHolder;

    @Column(name = "return_carrier", length = 40)
    private String returnCarrier;

    @Column(name = "return_tracking_no", length = 60)
    private String returnTrackingNo;

    @Column(name = "exchange_carrier", length = 40)
    private String exchangeCarrier;

    @Column(name = "exchange_tracking_no", length = 60)
    private String exchangeTrackingNo;

    @Column(name = "requested_by", nullable = false, length = 255)
    private String requestedBy;

    @Column(name = "processed_by", length = 255)
    private String processedBy;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "collected_at")
    private LocalDateTime collectedAt;

    @Column(name = "exchange_shipped_at")
    private LocalDateTime exchangeShippedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected OrderReturnRequestJpaEntity() {
    }

    static OrderReturnRequestJpaEntity fromDomain(OrderReturnRequest request) {
        OrderReturnRequestJpaEntity entity = new OrderReturnRequestJpaEntity();
        entity.id = request.getId();
        entity.applyFrom(request);
        return entity;
    }

    /** 이미 영속화된 행에 도메인의 현재 값을 덮어쓴다(식별자는 건드리지 않는다). */
    void applyFrom(OrderReturnRequest request) {
        this.orderId = request.getOrderId();
        this.userId = request.getUserId();
        this.requestType = request.getType().name();
        this.status = request.getStatus().name();
        this.reasonCode = request.getReasonCode();
        this.reasonDetail = request.getReasonDetail();

        this.refundAccountRequired = request.isRefundAccountRequired();

        RefundAccount account = request.getRefundAccount();
        this.refundBankCode = account == null ? null : account.bankCode();
        this.refundAccountNo = account == null ? null : account.accountNumber();
        this.refundAccountHolder = account == null ? null : account.holderName();

        ReturnWaybill returnWaybill = request.getReturnWaybill();
        this.returnCarrier = returnWaybill == null ? null : returnWaybill.carrier();
        this.returnTrackingNo = returnWaybill == null ? null : returnWaybill.trackingNumber();

        ReturnWaybill exchangeWaybill = request.getExchangeWaybill();
        this.exchangeCarrier = exchangeWaybill == null ? null : exchangeWaybill.carrier();
        this.exchangeTrackingNo = exchangeWaybill == null ? null : exchangeWaybill.trackingNumber();

        this.requestedBy = request.getRequestedBy();
        this.processedBy = request.getProcessedBy();
        this.rejectReason = request.getRejectReason();
        this.requestedAt = request.getRequestedAt();
        this.approvedAt = request.getApprovedAt();
        this.collectedAt = request.getCollectedAt();
        this.exchangeShippedAt = request.getExchangeShippedAt();
        this.completedAt = request.getCompletedAt();
        this.updatedAt = request.getUpdatedAt();
    }

    OrderReturnRequest toDomain() {
        return OrderReturnRequest.restore(
                id, orderId, userId,
                ReturnRequestType.fromString(requestType),
                ReturnRequestStatus.fromString(status),
                reasonCode, reasonDetail,
                RefundAccount.ofNullable(refundBankCode, refundAccountNo, refundAccountHolder),
                ReturnWaybill.ofNullable(returnCarrier, returnTrackingNo),
                ReturnWaybill.ofNullable(exchangeCarrier, exchangeTrackingNo),
                requestedBy, processedBy, rejectReason,
                requestedAt, approvedAt, collectedAt, exchangeShippedAt, completedAt, updatedAt,
                refundAccountRequired);
    }

    public Long getId() {
        return id;
    }
}
