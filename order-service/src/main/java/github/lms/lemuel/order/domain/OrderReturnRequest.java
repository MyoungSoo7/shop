package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.InvalidReturnRequestStateException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import java.time.LocalDateTime;

/**
 * 반품·교환·취소 <b>신청</b> — 주문 상태 옆에 붙는 사실의 기록.
 *
 * <p>이 클래스가 생기기 전, 신청은 주문 상태 하나와 상태 이력의 사유 문자열이 전부였다. 그래서
 * 신청에 딸려 오는 것들이 갈 곳이 없었다 — 교환이라는 종류 자체, 돈이 돌아갈 계좌, 물건이
 * 돌아온 송장. 셋 다 "주문이 어디까지 왔는가"가 아니라 "그 신청이 어떻게 처리되는가"에 속한다.
 *
 * <p><b>주문 상태를 여기서 다시 계산하지 않는다.</b> 주문 상태는 {@link OrderStatus} 전이표가
 * 유일한 권위이고, 이 애그리거트는 {@link ReturnRequestStatus} 로 자기 진행만 관리한다. 같은
 * 사실을 두 곳에 두면 언젠가 어긋나고, 그때 어느 쪽이 맞는지 판단할 근거가 없다.
 */
public class OrderReturnRequest {

    private static final int MAX_REASON_CODE = 40;
    private static final int MAX_REASON_DETAIL = 500;
    private static final int MAX_REJECT_REASON = 500;

    private Long id;
    private final Long orderId;
    private final Long userId;
    private final ReturnRequestType type;
    private ReturnRequestStatus status;

    private final String reasonCode;
    private final String reasonDetail;

    /**
     * 이 신청의 돈이 <b>사람 손으로 계좌 송금</b>되어야 하는지 — 접수 시점에 결제 슬라이스에 물어
     * 확정한 사실을 그대로 들고 있는다.
     *
     * <p>매번 결제를 다시 조회해 파생시키지 않는 이유가 둘 있다. 대기열 화면은 신청 100 건을 한 번에
     * 부르는데 그때마다 결제 조회 100 번이 붙고, 더 중요하게는 접수 후 결제 쪽이 바뀌면(부분 취소·
     * 텐더 정리) 고객이 계좌를 낸 근거와 지금의 판정이 어긋난다. 신청은 접수 당시의 판단으로 처리한다.
     */
    private final boolean refundAccountRequired;

    private RefundAccount refundAccount;
    private ReturnWaybill returnWaybill;
    private ReturnWaybill exchangeWaybill;

    private final String requestedBy;
    private String processedBy;
    private String rejectReason;

    private final LocalDateTime requestedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime collectedAt;
    private LocalDateTime exchangeShippedAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;

    // ───────── 생성 ─────────

    /**
     * 고객이 새 신청을 낸다.
     *
     * @param refundAccountRequired 결제 수단이 PG 로 되돌릴 수 없어(무통장·가상계좌) 계좌 송금이
     *                              필요한지. 호출자가 결제 슬라이스에 물어 온 사실이다.
     */
    public static OrderReturnRequest open(Long orderId,
                                          Long userId,
                                          ReturnRequestType type,
                                          String reasonCode,
                                          String reasonDetail,
                                          RefundAccount refundAccount,
                                          boolean refundAccountRequired,
                                          String requestedBy) {
        if (orderId == null) throw new OrderInvariantViolationException("orderId 필수");
        if (userId == null) throw new OrderInvariantViolationException("userId 필수");
        if (type == null) throw new OrderInvariantViolationException("신청 종류 필수");

        // 교환은 돈이 움직이지 않으므로 계좌를 요구하지도, 받아 두지도 않는다 — 필요 없는 계좌번호를
        // 보관하는 것 자체가 새 위험이다.
        if (!type.refundsMoney() && refundAccount != null) {
            throw new OrderInvariantViolationException("교환 신청에는 환불 계좌를 받지 않습니다");
        }
        if (type.refundsMoney() && refundAccountRequired && refundAccount == null) {
            throw new OrderInvariantViolationException(
                    "이 주문은 계좌로 환불해야 합니다 — 환불받을 계좌를 입력해 주세요");
        }

        OrderReturnRequest request = new OrderReturnRequest(
                null, orderId, userId, type, ReturnRequestStatus.REQUESTED,
                requireText(reasonCode, "신청 사유", MAX_REASON_CODE),
                trimToNull(reasonDetail, "상세 사유", MAX_REASON_DETAIL),
                refundAccount, null, null,
                requireText(requestedBy, "신청자", 255), null, null,
                LocalDateTime.now(), null, null, null, null, LocalDateTime.now(),
                // 교환은 돈이 돌아가지 않으므로 호출자가 true 를 줘도 계좌를 기다리지 않는다.
                type.refundsMoney() && refundAccountRequired);
        return request;
    }

    /** 영속 계층 복원 전용 — 검증 없이 있는 그대로 되살린다. */
    public static OrderReturnRequest restore(Long id, Long orderId, Long userId,
                                             ReturnRequestType type, ReturnRequestStatus status,
                                             String reasonCode, String reasonDetail,
                                             RefundAccount refundAccount,
                                             ReturnWaybill returnWaybill, ReturnWaybill exchangeWaybill,
                                             String requestedBy, String processedBy, String rejectReason,
                                             LocalDateTime requestedAt, LocalDateTime approvedAt,
                                             LocalDateTime collectedAt, LocalDateTime exchangeShippedAt,
                                             LocalDateTime completedAt, LocalDateTime updatedAt,
                                             boolean refundAccountRequired) {
        return new OrderReturnRequest(id, orderId, userId, type, status, reasonCode, reasonDetail,
                refundAccount, returnWaybill, exchangeWaybill, requestedBy, processedBy, rejectReason,
                requestedAt, approvedAt, collectedAt, exchangeShippedAt, completedAt, updatedAt,
                refundAccountRequired);
    }

    private OrderReturnRequest(Long id, Long orderId, Long userId,
                               ReturnRequestType type, ReturnRequestStatus status,
                               String reasonCode, String reasonDetail,
                               RefundAccount refundAccount,
                               ReturnWaybill returnWaybill, ReturnWaybill exchangeWaybill,
                               String requestedBy, String processedBy, String rejectReason,
                               LocalDateTime requestedAt, LocalDateTime approvedAt,
                               LocalDateTime collectedAt, LocalDateTime exchangeShippedAt,
                               LocalDateTime completedAt, LocalDateTime updatedAt,
                               boolean refundAccountRequired) {
        this.id = id;
        this.orderId = orderId;
        this.userId = userId;
        this.type = type;
        this.status = status;
        this.reasonCode = reasonCode;
        this.reasonDetail = reasonDetail;
        this.refundAccount = refundAccount;
        this.returnWaybill = returnWaybill;
        this.exchangeWaybill = exchangeWaybill;
        this.requestedBy = requestedBy;
        this.processedBy = processedBy;
        this.rejectReason = rejectReason;
        this.requestedAt = requestedAt;
        this.approvedAt = approvedAt;
        this.collectedAt = collectedAt;
        this.exchangeShippedAt = exchangeShippedAt;
        this.completedAt = completedAt;
        this.updatedAt = updatedAt;
        this.refundAccountRequired = refundAccountRequired;
    }

    // ───────── 진행 ─────────

    public void approve(String operator) {
        transitionTo(ReturnRequestStatus.APPROVED, operator);
        this.approvedAt = LocalDateTime.now();
    }

    public void reject(String operator, String reason) {
        transitionTo(ReturnRequestStatus.REJECTED, operator);
        this.rejectReason = trimToNull(reason, "거절 사유", MAX_REJECT_REASON);
    }

    /** 고객이 신청을 거둬들인다. 물건이 이미 돌아온 뒤에는 전이표가 막는다. */
    public void withdraw(String actor) {
        transitionTo(ReturnRequestStatus.WITHDRAWN, actor);
    }

    /**
     * 회수 송장을 적는다. 고객이 직접 적기도 하고, 전화로 받은 운영자가 대신 적기도 한다.
     *
     * <p>승인 전에도 적을 수 있다 — 실무에서 고객은 승인을 기다리지 않고 먼저 보내는 일이 잦고,
     * 그때 송장을 받아 둘 곳이 없으면 물건이 도착해도 어느 신청인지 맞출 수 없다.
     */
    public void registerReturnWaybill(ReturnWaybill waybill) {
        if (waybill == null) {
            throw new OrderInvariantViolationException("회수 송장 필수");
        }
        if (!type.collectsGoods()) {
            throw new OrderInvariantViolationException("출고 전 취소에는 회수 송장이 없습니다");
        }
        if (status.isTerminal()) {
            throw new InvalidReturnRequestStateException(status, "이미 끝난 신청에는 송장을 적을 수 없습니다");
        }
        this.returnWaybill = waybill;
        touch();
    }

    /** 물건이 판매자에게 도착했다. 회수 송장이 있어야 한다 — 무엇이 도착했는지가 근거다. */
    public void markCollected(String operator) {
        if (returnWaybill == null) {
            throw new OrderInvariantViolationException("회수 송장이 없어 회수 완료로 표시할 수 없습니다");
        }
        transitionTo(ReturnRequestStatus.COLLECTED, operator);
        this.collectedAt = LocalDateTime.now();
    }

    /**
     * 교환품을 다시 보낸다 — 여기서 신청이 끝난다.
     *
     * <p>회수(COLLECTED)를 거치도록 강제하는 이유: 받지도 않은 물건에 대해 새 물건을 보내면
     * 재고와 돈이 동시에 나간다. 선교환이 필요하면 회수 송장을 먼저 적고 회수 확인을 거친다.
     */
    public void shipExchange(ReturnWaybill waybill, String operator) {
        if (type != ReturnRequestType.EXCHANGE) {
            throw new OrderInvariantViolationException("교환 신청에만 재배송할 수 있습니다");
        }
        if (waybill == null) {
            throw new OrderInvariantViolationException("교환 재배송 송장 필수");
        }
        if (status != ReturnRequestStatus.COLLECTED) {
            throw new InvalidReturnRequestStateException(status,
                    "회수 확인 후에만 교환품을 보낼 수 있습니다");
        }
        this.exchangeWaybill = waybill;
        this.exchangeShippedAt = LocalDateTime.now();
        transitionTo(ReturnRequestStatus.COMPLETED, operator);
        this.completedAt = LocalDateTime.now();
    }

    /** 반품·취소의 환불까지 끝났다. */
    public void complete(String operator) {
        transitionTo(ReturnRequestStatus.COMPLETED, operator);
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 운영자가 고객 대신 환불 계좌를 채운다(전화로 받은 계좌 등).
     *
     * <p>이미 적힌 계좌를 <b>덮어쓰는 것</b>도 이 경로다 — 오타로 송금이 반송되는 일이 실제로
     * 잦다. 환불이 끝난 뒤에는 바꿀 수 없다(그 계좌로 이미 돈이 나갔다).
     */
    public void changeRefundAccount(RefundAccount account) {
        if (account == null) {
            throw new OrderInvariantViolationException("환불 계좌 필수");
        }
        if (!type.refundsMoney()) {
            throw new OrderInvariantViolationException("교환 신청에는 환불 계좌를 받지 않습니다");
        }
        if (status.isTerminal()) {
            throw new InvalidReturnRequestStateException(status, "끝난 신청의 환불 계좌는 바꿀 수 없습니다");
        }
        this.refundAccount = account;
        touch();
    }

    private void transitionTo(ReturnRequestStatus target, String operator) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidReturnRequestStateException(status,
                    "허용되지 않은 신청 상태 전이: " + status + " → " + target);
        }
        this.status = target;
        this.processedBy = trimToNull(operator, "처리자", 255);
        touch();
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    // ───────── 조회 ─────────

    public boolean isOpen() {
        return status.isOpen();
    }

    /**
     * 사람이 계좌로 송금해야 하는데 계좌가 아직 없는 상태인지 — 운영 화면의 경고 근거.
     *
     * <p>카드 결제 반품은 여기 걸리지 않는다. 돈이 카드로 되돌아가므로 계좌가 <b>없는 것이 정상</b>이고,
     * 그것까지 경고로 띄우면 대기열의 거의 모든 줄에 경고가 붙어 진짜 막힌 건을 가린다.
     */
    public boolean awaitsRefundAccount() {
        return refundAccountRequired && refundAccount == null;
    }

    public void assignId(Long assignedId) {
        if (this.id != null) {
            throw new OrderInvariantViolationException("이미 식별자가 있는 신청입니다");
        }
        this.id = assignedId;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public Long getUserId() { return userId; }
    public ReturnRequestType getType() { return type; }
    public ReturnRequestStatus getStatus() { return status; }
    public String getReasonCode() { return reasonCode; }
    public String getReasonDetail() { return reasonDetail; }
    public boolean isRefundAccountRequired() { return refundAccountRequired; }
    public RefundAccount getRefundAccount() { return refundAccount; }
    public ReturnWaybill getReturnWaybill() { return returnWaybill; }
    public ReturnWaybill getExchangeWaybill() { return exchangeWaybill; }
    public String getRequestedBy() { return requestedBy; }
    public String getProcessedBy() { return processedBy; }
    public String getRejectReason() { return rejectReason; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
    public LocalDateTime getCollectedAt() { return collectedAt; }
    public LocalDateTime getExchangeShippedAt() { return exchangeShippedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    private static String requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new OrderInvariantViolationException(label + " 필수");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new OrderInvariantViolationException(label + " 는 " + maxLength + "자를 넘을 수 없습니다");
        }
        return trimmed;
    }

    private static String trimToNull(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new OrderInvariantViolationException(label + " 는 " + maxLength + "자를 넘을 수 없습니다");
        }
        return trimmed;
    }
}
