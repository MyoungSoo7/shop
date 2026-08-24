package github.lms.lemuel.rbac.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "역할 권한 매트릭스 저장 요청 DTO")
public record UpdateRolePermissionsRequest(
        @Schema(description = "부여할 권한 ID 목록 (기존 권한 전체 교체)", example = "[1, 2, 3]")
        List<Long> permissionIds
) {}
