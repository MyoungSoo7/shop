package github.lms.lemuel.rbac.adapter.in.web.dto;

import github.lms.lemuel.rbac.domain.Permission;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "권한 응답 DTO")
public record PermissionResponse(
        @Schema(description = "권한 ID") Long id,
        @Schema(description = "권한 코드 (예: ORDER_READ)") String code,
        @Schema(description = "권한 이름") String name,
        @Schema(description = "카테고리 (예: ORDER, PRODUCT, SETTLEMENT, USER, COUPON, SYSTEM)") String category,
        @Schema(description = "설명") String description
) {
    public static PermissionResponse from(Permission domain) {
        return new PermissionResponse(
                domain.getId(),
                domain.getCode(),
                domain.getName(),
                domain.getCategory(),
                domain.getDescription()
        );
    }
}
