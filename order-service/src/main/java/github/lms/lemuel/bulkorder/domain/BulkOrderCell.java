package github.lms.lemuel.bulkorder.domain;

/**
 * 업로드된 셀 1칸 — 원본 값 + 검증 결과.
 *
 * <p>검증 결과를 <b>셀 단위로</b> 남기는 것이 레거시 커머스(ssgb2e)의 실무 노하우다. 행 단위
 * 메시지만 남기면 열 30 개짜리 양식에서 운영자는 "이 행 어딘가가 틀렸다"만 알게 되고, 결국 눈으로
 * 훑는다. 어느 칸이 왜 틀렸는지가 남아야 화면이 그 칸을 붉게 칠할 수 있다.
 */
public class BulkOrderCell {

    private Long id;
    private final int columnIndex;
    private final String value;
    private boolean valid;
    private String errorMessage;

    private BulkOrderCell(Long id, int columnIndex, String value, boolean valid, String errorMessage) {
        this.id = id;
        this.columnIndex = columnIndex;
        this.value = value;
        this.valid = valid;
        this.errorMessage = errorMessage;
    }

    /** 업로드 직후 — 아직 검증하지 않았으므로 통과로 두지 않는다(검증 전 상태는 valid=false). */
    public static BulkOrderCell uploaded(int columnIndex, String value) {
        return new BulkOrderCell(null, columnIndex, value, false, null);
    }

    public static BulkOrderCell rehydrate(Long id, int columnIndex, String value,
                                          boolean valid, String errorMessage) {
        return new BulkOrderCell(id, columnIndex, value, valid, errorMessage);
    }

    /**
     * 스펙으로 이 칸을 검증하고 결과를 기록한다.
     *
     * <p>스펙이 없는 열(양식보다 열이 많은 파일)은 <b>통과</b>시킨다 — 운영자가 메모 열을 덧붙이는
     * 일이 흔하고, 그 때문에 파일 전체를 거절하면 쓸 수 없는 도구가 된다.
     *
     * @return 오류 메시지, 통과하면 {@code null}
     */
    public String validate(BulkOrderColumnSpec spec) {
        this.errorMessage = spec == null ? null : spec.validate(value);
        this.valid = this.errorMessage == null;
        return this.errorMessage;
    }

    public void assignId(Long id) {
        this.id = id;
    }

    public Long getId() { return id; }
    public int getColumnIndex() { return columnIndex; }
    public String getValue() { return value; }
    public boolean isValid() { return valid; }
    public String getErrorMessage() { return errorMessage; }
}
