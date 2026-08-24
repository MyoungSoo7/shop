package github.lms.lemuel.rbac.adapter.in.web.dto;

import github.lms.lemuel.rbac.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "역할 응답 DTO")
public record RoleResponse(
        @Schema(description = "역할 ID") Long id,
        @Schema(description = "역할 코드 (예: ADMIN, MANAGER, USER)") String code,
        @Schema(description = "역할 이름") String name,
        @Schema(description = "설명") String description,
        @Schema(description = "시스템 내장 역할 여부") boolean builtin,
        @Schema(description = "생성일시") LocalDateTime createdAt,
        @Schema(description = "보유 권한 ID 목록") List<Long> permissionIds,
        @Schema(description = "보유 권한 코드 목록") List<String> permissionCodes
) {
    public static RoleResponse from(Role domain) {
        List<Long> ids = domain.getPermissions() == null ? List.of() :
                domain.getPermissions().stream().map(p -> p.getId()).toList();
        List<String> codes = domain.getPermissions() == null ? List.of() :
                domain.getPermissions().stream().map(p -> p.getCode()).toList();
        return new RoleResponse(
                domain.getId(),
                domain.getCode(),
                domain.getName(),
                domain.getDescription(),
                domain.isBuiltin(),
                domain.getCreatedAt(),
                ids,
                codes
        );
    }
}
