package github.lms.lemuel.commoncode.adapter.in.web.dto;

import github.lms.lemuel.commoncode.domain.CommonCodeGroup;

import java.time.LocalDateTime;

public record CommonCodeGroupResponse(
        String groupCode,
        String name,
        String description,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CommonCodeGroupResponse from(CommonCodeGroup group) {
        return new CommonCodeGroupResponse(
                group.getGroupCode(),
                group.getName(),
                group.getDescription(),
                group.isActive(),
                group.getCreatedAt(),
                group.getUpdatedAt()
        );
    }
}
