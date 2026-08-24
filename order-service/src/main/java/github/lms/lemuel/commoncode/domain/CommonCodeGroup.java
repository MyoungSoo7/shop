package github.lms.lemuel.commoncode.domain;
import github.lms.lemuel.commoncode.domain.exception.CommonCodeInvariantViolationException;

import java.time.LocalDateTime;

/**
 * 공통코드 그룹 도메인 모델 (순수 POJO)
 */
public class CommonCodeGroup {

    private String groupCode;
    private String name;
    private String description;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public CommonCodeGroup() {
        this.active = true;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public static CommonCodeGroup create(String groupCode, String name, String description) {
        if (groupCode == null || groupCode.isBlank()) {
            throw new CommonCodeInvariantViolationException("그룹코드는 필수입니다.");
        }
        if (name == null || name.isBlank()) {
            throw new CommonCodeInvariantViolationException("그룹명은 필수입니다.");
        }
        CommonCodeGroup group = new CommonCodeGroup();
        group.groupCode = groupCode.trim().toUpperCase();
        group.name = name.trim();
        group.description = description;
        return group;
    }

    public void update(String name, String description, boolean active) {
        if (name == null || name.isBlank()) {
            throw new CommonCodeInvariantViolationException("그룹명은 필수입니다.");
        }
        this.name = name.trim();
        this.description = description;
        this.active = active;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 영속 레코드 복원 팩토리 — no-arg + setter 대신 이 경로로만 도메인을 재구성한다.
     */
    public static CommonCodeGroup rehydrate(String groupCode, String name, String description,
                                            boolean active, LocalDateTime createdAt, LocalDateTime updatedAt) {
        CommonCodeGroup group = new CommonCodeGroup();
        group.groupCode = groupCode;
        group.name = name;
        group.description = description;
        group.active = active;
        group.createdAt = createdAt;
        group.updatedAt = updatedAt;
        return group;
    }

    // Getters
    public String getGroupCode() { return groupCode; }

    public String getName() { return name; }

    public String getDescription() { return description; }

    public boolean isActive() { return active; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
