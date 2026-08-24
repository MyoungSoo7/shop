package github.lms.lemuel.bulkorder.domain;

import github.lms.lemuel.bulkorder.domain.exception.BulkOrderInvariantViolationException;
import github.lms.lemuel.bulkorder.domain.exception.InvalidBulkOrderStateException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 대량주문 초안 — 업로드된 파일 하나를 행으로 쪼갠 애그리거트.
 *
 * <p>레거시 커머스(ssgb2e-front {@code OrderMultiController})의 흐름을 옮긴 것이다:
 * <pre>
 *   업로드 → 검증(오류행 리포트) → [고쳐서 재업로드] → 임시주문 확정 전환 / 폐기
 * </pre>
 * 핵심은 <b>검증과 확정의 분리</b>다. 한 번에 처리하면 수백 행 파일의 뒷쪽 한 행이 틀렸을 때
 * 앞쪽 수백 건이 이미 실주문으로 나가 있고, 되돌리는 일이 그대로 취소·환불 작업이 된다.
 *
 * <p><b>"전 행 통과"가 아니면 확정을 열지 않는다.</b> 부분 확정은 편해 보이지만, 대량주문은
 * "이 파일 전체가 한 건의 발주"인 경우가 대부분이라 절반만 나가는 것이 더 나쁜 상태다.
 */
public class BulkOrderDraft {

    private Long id;
    private final Long uploaderUserId;
    private final String fileName;
    private final List<BulkOrderRow> rows;
    private BulkOrderStatus status;
    private final LocalDateTime uploadedAt;
    private LocalDateTime updatedAt;

    private BulkOrderDraft(Long id, Long uploaderUserId, String fileName, List<BulkOrderRow> rows,
                           BulkOrderStatus status, LocalDateTime uploadedAt, LocalDateTime updatedAt) {
        this.id = id;
        this.uploaderUserId = uploaderUserId;
        this.fileName = fileName;
        this.rows = new ArrayList<>(rows);
        this.status = status;
        this.uploadedAt = uploadedAt;
        this.updatedAt = updatedAt;
    }

    public static BulkOrderDraft upload(Long uploaderUserId, String fileName,
                                        List<BulkOrderRow> rows, LocalDateTime now) {
        if (uploaderUserId == null) {
            throw new BulkOrderInvariantViolationException("업로더 식별자는 필수입니다");
        }
        if (rows == null || rows.isEmpty()) {
            throw new BulkOrderInvariantViolationException("데이터 행이 없는 파일입니다");
        }
        return new BulkOrderDraft(null, uploaderUserId, fileName, rows,
                BulkOrderStatus.UPLOADED, now, now);
    }

    public static BulkOrderDraft rehydrate(Long id, Long uploaderUserId, String fileName,
                                           List<BulkOrderRow> rows, BulkOrderStatus status,
                                           LocalDateTime uploadedAt, LocalDateTime updatedAt) {
        return new BulkOrderDraft(id, uploaderUserId, fileName, rows, status, uploadedAt, updatedAt);
    }

    /**
     * 전 행 검증 후 상태를 확정한다: 한 행이라도 틀리면 {@link BulkOrderStatus#REJECTED}.
     *
     * <p>재검증(고친 뒤 다시 누르기)이 정상 흐름이라 REJECTED → VALIDATED 도 열려 있다.
     */
    public BulkOrderStatus validate(List<BulkOrderColumnSpec> specs, LocalDateTime now) {
        requireNotTerminal("검증");
        boolean allValid = true;
        for (BulkOrderRow row : rows) {
            allValid &= row.validate(specs);
        }
        transitionTo(allValid ? BulkOrderStatus.VALIDATED : BulkOrderStatus.REJECTED, now);
        return status;
    }

    /** 확정 시작 — 전 행 통과 상태에서만 열린다. */
    public void requireConfirmable() {
        if (!status.confirmable()) {
            throw new InvalidBulkOrderStateException(
                    "전 행이 검증을 통과한 초안만 확정할 수 있습니다. 현재 상태: " + status);
        }
    }

    /** 확정 완료 — 주문이 나갔으므로 종단이다. */
    public void markConfirmed(LocalDateTime now) {
        transitionTo(BulkOrderStatus.CONFIRMED, now);
    }

    /**
     * 확정 도중 일부 행이 실패 — 초안을 REJECTED 로 되돌려 <b>다시 확정할 수 있게</b> 둔다.
     * 이미 주문이 나간 행은 {@code createdOrderId} 를 들고 있어 재확정에서 건너뛴다.
     */
    public void markPartiallyConfirmed(LocalDateTime now) {
        transitionTo(BulkOrderStatus.REJECTED, now);
    }

    public void discard(LocalDateTime now) {
        requireNotTerminal("폐기");
        transitionTo(BulkOrderStatus.DISCARDED, now);
    }

    /** 이 초안을 올린 사람인지 — 남의 초안을 확정·폐기하지 못하게 하는 소유권 대조. */
    public boolean ownedBy(Long userId) {
        return uploaderUserId.equals(userId);
    }

    public List<BulkOrderRow> pendingRows() {
        return rows.stream().filter(BulkOrderRow::pendingOrder).toList();
    }

    public long validRowCount() {
        return rows.stream().filter(BulkOrderRow::isValid).count();
    }

    private void requireNotTerminal(String action) {
        if (status.terminal()) {
            throw new InvalidBulkOrderStateException(
                    "종단 상태의 초안은 " + action + "할 수 없습니다: " + status);
        }
    }

    private void transitionTo(BulkOrderStatus target, LocalDateTime now) {
        if (this.status == target) {
            this.updatedAt = now;
            return; // 멱등 no-op (재검증으로 같은 결과가 나온 경우 등)
        }
        if (!this.status.canTransitionTo(target)) {
            throw new InvalidBulkOrderStateException(
                    "대량주문 초안 상태 전이 불가: " + this.status + " → " + target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    public void assignId(Long id) {
        if (this.id != null && !this.id.equals(id)) {
            throw new BulkOrderInvariantViolationException("초안 id 는 이미 부여되어 변경할 수 없습니다");
        }
        this.id = id;
    }

    public Long getId() { return id; }
    public Long getUploaderUserId() { return uploaderUserId; }
    public String getFileName() { return fileName; }
    public List<BulkOrderRow> getRows() { return List.copyOf(rows); }
    public BulkOrderStatus getStatus() { return status; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
