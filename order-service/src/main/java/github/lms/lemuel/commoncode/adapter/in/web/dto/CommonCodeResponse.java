package github.lms.lemuel.commoncode.adapter.in.web.dto;

import github.lms.lemuel.commoncode.domain.CommonCode;

import java.time.LocalDateTime;

public record CommonCodeResponse(
        Long id,
        String groupCode,
        String code,
        String label,
        int sortOrder,
        boolean active,
        String extra1,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CommonCodeResponse from(CommonCode commonCode) {
        return new CommonCodeResponse(
                commonCode.getId(),
                commonCode.getGroupCode(),
                commonCode.getCode(),
                commonCode.getLabel(),
                commonCode.getSortOrder(),
                commonCode.isActive(),
                commonCode.getExtra1(),
                commonCode.getCreatedAt(),
                commonCode.getUpdatedAt()
        );
    }
}
