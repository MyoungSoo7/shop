package github.lms.lemuel.bulkorder.domain;

import github.lms.lemuel.bulkorder.domain.exception.BulkOrderInvariantViolationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 업로드된 행 1줄 — 셀들 + 행 단위 검증 결과 + 확정으로 만들어진 주문 id.
 *
 * <p>행이 {@code createdOrderId} 를 들고 있는 것이 재확정 방지의 뿌리다. 확정 도중 일부 행이
 * 실패해 다시 확정을 눌렀을 때, 이미 주문이 나간 행을 건너뛸 근거가 여기 있다 — 없으면 재시도가
 * 곧 중복 주문이다.
 */
public class BulkOrderRow {

    private Long id;
    private final int rowNumber;
    private final List<BulkOrderCell> cells;
    private boolean valid;
    private String errorMessage;
    private Long createdOrderId;

    private BulkOrderRow(Long id, int rowNumber, List<BulkOrderCell> cells,
                         boolean valid, String errorMessage, Long createdOrderId) {
        this.id = id;
        this.rowNumber = rowNumber;
        this.cells = new ArrayList<>(cells);
        this.valid = valid;
        this.errorMessage = errorMessage;
        this.createdOrderId = createdOrderId;
    }

    public static BulkOrderRow uploaded(int rowNumber, List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new BulkOrderInvariantViolationException("빈 행은 만들 수 없습니다: rowNumber=" + rowNumber);
        }
        List<BulkOrderCell> cells = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            cells.add(BulkOrderCell.uploaded(i, values.get(i)));
        }
        return new BulkOrderRow(null, rowNumber, cells, false, null, null);
    }

    public static BulkOrderRow rehydrate(Long id, int rowNumber, List<BulkOrderCell> cells,
                                         boolean valid, String errorMessage, Long createdOrderId) {
        return new BulkOrderRow(id, rowNumber, cells, valid, errorMessage, createdOrderId);
    }

    /**
     * 스펙 목록으로 이 행 전체를 검증한다.
     *
     * <p><b>첫 오류에서 멈추지 않는다.</b> 운영자는 한 번 고칠 때 다 고치고 싶어 한다 — 한 칸씩
     * 알려 주면 20 개 틀린 파일에 20 번 왕복하게 된다.
     *
     * <p>필수 열이 파일에 아예 없는 경우(열 부족)도 잡는다: 셀이 없으면 빈 값으로 취급해 스펙에
     * 물어본다. 그래야 "열이 모자란다"가 필수 누락 메시지로 드러난다.
     */
    public boolean validate(List<BulkOrderColumnSpec> specs) {
        Map<Integer, BulkOrderColumnSpec> byIndex = new LinkedHashMap<>();
        for (BulkOrderColumnSpec spec : specs) {
            byIndex.put(spec.columnIndex(), spec);
        }

        StringJoiner errors = new StringJoiner(" ");
        for (BulkOrderCell cell : cells) {
            String error = cell.validate(byIndex.get(cell.getColumnIndex()));
            if (error != null) {
                errors.add(error);
            }
        }
        // 파일에 아예 없는 열 — 셀이 없으므로 위 루프가 닿지 않는다. 빈 값으로 스펙에 물어본다.
        for (BulkOrderColumnSpec spec : specs) {
            if (spec.columnIndex() >= cells.size()) {
                String error = spec.validate(null);
                if (error != null) {
                    errors.add(error);
                }
            }
        }

        this.errorMessage = errors.length() == 0 ? null : errors.toString();
        this.valid = this.errorMessage == null;
        return this.valid;
    }

    /** 확정으로 만들어진 주문을 기록한다. 이미 기록됐으면 거부 — 중복 주문의 마지막 방어선. */
    public void markOrderCreated(Long orderId) {
        if (this.createdOrderId != null) {
            throw new BulkOrderInvariantViolationException(
                    "이미 주문이 생성된 행입니다: rowNumber=" + rowNumber + ", orderId=" + createdOrderId);
        }
        this.createdOrderId = orderId;
    }

    /** 확정 중 이 행만 실패했을 때 사유를 남긴다(다른 행의 성공은 유지된다). */
    public void markConfirmFailed(String reason) {
        this.valid = false;
        this.errorMessage = reason;
    }

    /** 아직 주문이 나가지 않았고 검증을 통과한 행 — 확정 대상. */
    public boolean pendingOrder() {
        return valid && createdOrderId == null;
    }

    /** 업무 코드로 셀 값을 꺼낸다. 열 위치가 아니라 의미로 접근한다(양식 순서가 바뀌어도 견딘다). */
    public String value(List<BulkOrderColumnSpec> specs, String itemCode) {
        for (BulkOrderColumnSpec spec : specs) {
            if (spec.itemCode().equals(itemCode)) {
                return spec.columnIndex() < cells.size()
                        ? cells.get(spec.columnIndex()).getValue()
                        : null;
            }
        }
        return null;
    }

    public void assignId(Long id) {
        this.id = id;
    }

    public Long getId() { return id; }
    public int getRowNumber() { return rowNumber; }
    public List<BulkOrderCell> getCells() { return List.copyOf(cells); }
    public boolean isValid() { return valid; }
    public String getErrorMessage() { return errorMessage; }
    public Long getCreatedOrderId() { return createdOrderId; }
}
