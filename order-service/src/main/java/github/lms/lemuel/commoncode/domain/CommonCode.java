package github.lms.lemuel.commoncode.domain;
import github.lms.lemuel.commoncode.domain.exception.CommonCodeInvariantViolationException;

import java.time.LocalDateTime;

/**
 * 공통코드 항목 도메인 모델 (순수 POJO)
 */
public class CommonCode {

    private Long id;
    private String groupCode;
    private String code;
    private String label;
    private int sortOrder;
    private boolean active;
    private String extra1;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public CommonCode() {
        this.active = true;
        this.sortOrder = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public static CommonCode create(String groupCode, String code, String label, int sortOrder, String extra1) {
        if (groupCode == null || groupCode.isBlank()) {
            throw new CommonCodeInvariantViolationException("그룹코드는 필수입니다.");
        }
        if (code == null || code.isBlank()) {
            throw new CommonCodeInvariantViolationException("코드는 필수입니다.");
        }
        if (label == null || label.isBlank()) {
            throw new CommonCodeInvariantViolationException("코드명(label)은 필수입니다.");
        }
        CommonCode commonCode = new CommonCode();
        commonCode.groupCode = groupCode.trim().toUpperCase();
        commonCode.code = code.trim().toUpperCase();
        commonCode.label = label.trim();
        commonCode.sortOrder = sortOrder;
        commonCode.extra1 = extra1;
        return commonCode;
    }

    public void update(String label, int sortOrder, boolean active, String extra1) {
        if (label == null || label.isBlank()) {
            throw new CommonCodeInvariantViolationException("코드명(label)은 필수입니다.");
        }
        this.label = label.trim();
        this.sortOrder = sortOrder;
        this.active = active;
        this.extra1 = extra1;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 영속 레코드 복원 팩토리 — no-arg + setter 대신 이 경로로만 도메인을 재구성한다.
     */
    public static CommonCode rehydrate(Long id, String groupCode, String code, String label,
                                       int sortOrder, boolean active, String extra1,
                                       LocalDateTime createdAt, LocalDateTime updatedAt) {
        CommonCode c = new CommonCode();
        c.id = id;
        c.groupCode = groupCode;
        c.code = code;
        c.label = label;
        c.sortOrder = sortOrder;
        c.active = active;
        c.extra1 = extra1;
        c.createdAt = createdAt;
        c.updatedAt = updatedAt;
        return c;
    }

    /** DB 부여 PK 주입(setter 대체). */
    public void assignId(Long id) { this.id = id; }

    // Getters
    public Long getId() { return id; }

    public String getGroupCode() { return groupCode; }

    public String getCode() { return code; }

    public String getLabel() { return label; }

    public int getSortOrder() { return sortOrder; }

    public boolean isActive() { return active; }

    public String getExtra1() { return extra1; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
