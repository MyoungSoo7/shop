package github.lms.lemuel.shipping.domain;

import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;

import java.time.OffsetDateTime;

/**
 * 안심번호(수취인 가상번호) 풀 항목.
 *
 * <p>배송 과정에서 기사·판매자에게 수취인 실번호를 그대로 넘기지 않기 위한 장치다. 주문에 가상번호
 * 하나를 배정하고, 배송이 끝날 무렵(유효기간 만료) 회수해 다음 주문이 재사용한다.
 *
 * <p><b>풀이 유한하다는 것이 이 도메인의 전부다.</b> 회수되지 않으면 풀이 말라 신규 주문에 번호를
 * 줄 수 없고, 만료 전에 회수하면 배송 중인 주문의 연락 수단이 끊긴다. 그래서 배정·회수 두 전이를
 * 도메인이 직접 막는다 — 이미 배정된 번호의 재배정, 배정되지 않은 번호의 회수는 예외다.
 *
 * <p><b>범위 한계(중요):</b> 이 구현은 번호의 <b>배정·수명·노출</b>만 관리한다. 050 번호가 실제로
 * 실번호로 착신 전환되려면 통신사(또는 안심번호 사업자) 연동이 필요하며, 그 연동은 여기에 없다.
 * 즉 지금 이 코드가 보장하는 것은 "실번호가 응답·화면에 노출되지 않는다"까지다.
 */
public final class SafetyNumber {

    private final Long id;
    private final String virtualNumber;
    private SafetyNumberStatus status;
    private Long orderId;
    private OffsetDateTime assignedAt;
    private OffsetDateTime expiresAt;

    private SafetyNumber(Long id, String virtualNumber, SafetyNumberStatus status,
                         Long orderId, OffsetDateTime assignedAt, OffsetDateTime expiresAt) {
        this.id = id;
        this.virtualNumber = virtualNumber;
        this.status = status;
        this.orderId = orderId;
        this.assignedAt = assignedAt;
        this.expiresAt = expiresAt;
    }

    /** 풀에 새 번호를 넣는다(운영 등록). */
    public static SafetyNumber ofPool(String virtualNumber) {
        if (virtualNumber == null || virtualNumber.isBlank()) {
            throw new ShipmentInvariantViolationException("가상번호는 필수입니다");
        }
        return new SafetyNumber(null, virtualNumber.trim(), SafetyNumberStatus.AVAILABLE, null, null, null);
    }

    public static SafetyNumber rehydrate(Long id, String virtualNumber, SafetyNumberStatus status,
                                         Long orderId, OffsetDateTime assignedAt, OffsetDateTime expiresAt) {
        return new SafetyNumber(id, virtualNumber, status == null ? SafetyNumberStatus.AVAILABLE : status,
                orderId, assignedAt, expiresAt);
    }

    /**
     * 주문에 배정한다.
     *
     * @param now         배정 기준 시각
     * @param validityDays 유효기간(일) — 만료되면 회수 대상이 된다
     */
    public void assignTo(Long orderId, OffsetDateTime now, int validityDays) {
        if (status != SafetyNumberStatus.AVAILABLE) {
            throw new ShipmentInvariantViolationException(
                    "이미 배정된 안심번호입니다: " + virtualNumber + " (order=" + this.orderId + ")");
        }
        if (orderId == null) {
            throw new ShipmentInvariantViolationException("안심번호를 배정할 주문이 필요합니다");
        }
        if (now == null) {
            throw new ShipmentInvariantViolationException("배정 기준 시각이 필요합니다");
        }
        if (validityDays <= 0) {
            throw new ShipmentInvariantViolationException("안심번호 유효기간은 양수여야 합니다: " + validityDays);
        }
        this.status = SafetyNumberStatus.ASSIGNED;
        this.orderId = orderId;
        this.assignedAt = now;
        this.expiresAt = now.plusDays(validityDays);
    }

    /** 회수해 풀로 되돌린다. 주문 연결을 끊어 다음 배정이 과거 주문과 섞이지 않게 한다. */
    public void release() {
        if (status != SafetyNumberStatus.ASSIGNED) {
            throw new ShipmentInvariantViolationException(
                    "배정되지 않은 안심번호는 회수할 수 없습니다: " + virtualNumber);
        }
        this.status = SafetyNumberStatus.AVAILABLE;
        this.orderId = null;
        this.assignedAt = null;
        this.expiresAt = null;
    }

    /** 만료 여부 — 만료 시각 정각은 아직 유효하다(경계 포함). */
    public boolean isExpiredAt(OffsetDateTime now) {
        return status == SafetyNumberStatus.ASSIGNED && expiresAt != null && now.isAfter(expiresAt);
    }

    public Long getId() { return id; }
    public String getVirtualNumber() { return virtualNumber; }
    public SafetyNumberStatus getStatus() { return status; }
    public Long getOrderId() { return orderId; }
    public OffsetDateTime getAssignedAt() { return assignedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
}
