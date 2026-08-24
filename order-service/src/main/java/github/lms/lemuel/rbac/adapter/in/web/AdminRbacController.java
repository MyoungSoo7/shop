package github.lms.lemuel.rbac.adapter.in.web;

import github.lms.lemuel.rbac.adapter.in.web.dto.PermissionResponse;
import github.lms.lemuel.rbac.adapter.in.web.dto.RoleCloneRequest;
import github.lms.lemuel.rbac.adapter.in.web.dto.RoleCreateRequest;
import github.lms.lemuel.rbac.adapter.in.web.dto.RoleResponse;
import github.lms.lemuel.rbac.adapter.in.web.dto.RoleUpdateRequest;
import github.lms.lemuel.rbac.adapter.in.web.dto.UpdateRolePermissionsRequest;
import github.lms.lemuel.rbac.application.port.in.RbacUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "Admin RBAC", description = "관리자용 역할·권한(RBAC) 관리 API")
@RestController
@RequestMapping("/admin/rbac")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminRbacController {

    private final RbacUseCase rbacUseCase;

    /**
     * 역할 목록 조회 (각 역할의 permissionCodes 포함)
     */
    @Operation(summary = "역할 목록 조회", description = "전체 역할 목록을 조회한다. 각 역할에 부여된 권한 ID·코드 포함.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음 (ADMIN 필요)")
    })
    @GetMapping("/roles")
    public ResponseEntity<List<RoleResponse>> getRoles() {
        List<RoleResponse> responses = rbacUseCase.getAllRoles().stream()
                .map(RoleResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    /**
     * 전체 권한 목록 조회 (평면 배열)
     */
    @Operation(summary = "전체 권한 목록 조회", description = "시스템에 정의된 모든 권한을 평면 배열로 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @GetMapping("/permissions")
    public ResponseEntity<List<PermissionResponse>> getPermissions() {
        List<PermissionResponse> responses = rbacUseCase.getAllPermissions().stream()
                .map(PermissionResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    /**
     * 역할 단건 조회 (권한 포함)
     */
    @Operation(summary = "역할 단건 조회", description = "역할 ID로 역할 상세 정보 및 부여된 권한을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "역할을 찾을 수 없음")
    })
    @GetMapping("/roles/{id}")
    public ResponseEntity<RoleResponse> getRole(
            @Parameter(description = "역할 ID", required = true) @PathVariable Long id) {
        return ResponseEntity.ok(RoleResponse.from(rbacUseCase.getRoleById(id)));
    }

    /**
     * 역할 권한 매트릭스 저장 (전체 교체)
     */
    @Operation(summary = "역할 권한 매트릭스 저장",
            description = "역할에 부여할 권한 ID 목록을 전달하면 기존 권한을 전체 교체한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "404", description = "역할을 찾을 수 없음")
    })
    @PutMapping("/roles/{id}/permissions")
    public ResponseEntity<RoleResponse> updateRolePermissions(
            @Parameter(description = "역할 ID", required = true) @PathVariable Long id,
            @RequestBody UpdateRolePermissionsRequest request) {
        return ResponseEntity.ok(
                RoleResponse.from(rbacUseCase.updateRolePermissions(id, request.permissionIds()))
        );
    }

    /**
     * 커스텀 역할 생성
     */
    @Operation(summary = "역할 생성", description = "새 커스텀 역할을 생성한다. 코드는 대문자로 정규화되며 중복 시 400.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공"),
            @ApiResponse(responseCode = "400", description = "코드 형식 오류 또는 중복"),
            @ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @PostMapping("/roles")
    public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody RoleCreateRequest request) {
        var role = rbacUseCase.createRole(new RbacUseCase.CreateRoleCommand(
                request.code(), request.name(), request.description()));
        return ResponseEntity.status(HttpStatus.CREATED).body(RoleResponse.from(role));
    }

    /**
     * 역할 이름/설명 수정 (코드 불변)
     */
    @Operation(summary = "역할 수정", description = "역할의 이름/설명을 수정한다. 코드는 변경할 수 없다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "404", description = "역할을 찾을 수 없음")
    })
    @PutMapping("/roles/{id}")
    public ResponseEntity<RoleResponse> updateRole(
            @Parameter(description = "역할 ID", required = true) @PathVariable Long id,
            @Valid @RequestBody RoleUpdateRequest request) {
        var role = rbacUseCase.updateRole(id, new RbacUseCase.UpdateRoleCommand(
                request.name(), request.description()));
        return ResponseEntity.ok(RoleResponse.from(role));
    }

    /**
     * 역할 삭제 (builtin 보호)
     */
    @Operation(summary = "역할 삭제", description = "커스텀 역할을 삭제한다. builtin 역할은 400.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "400", description = "builtin 역할 삭제 불가"),
            @ApiResponse(responseCode = "404", description = "역할을 찾을 수 없음")
    })
    @DeleteMapping("/roles/{id}")
    public ResponseEntity<Void> deleteRole(
            @Parameter(description = "역할 ID", required = true) @PathVariable Long id) {
        rbacUseCase.deleteRole(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 역할 복제 (권한 매핑 포함)
     */
    @Operation(summary = "역할 복제", description = "원본 역할의 권한 매핑을 그대로 복사한 새 커스텀 역할을 만든다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "복제 성공"),
            @ApiResponse(responseCode = "400", description = "코드 형식 오류 또는 중복"),
            @ApiResponse(responseCode = "404", description = "원본 역할을 찾을 수 없음")
    })
    @PostMapping("/roles/{id}/clone")
    public ResponseEntity<RoleResponse> cloneRole(
            @Parameter(description = "원본 역할 ID", required = true) @PathVariable Long id,
            @Valid @RequestBody RoleCloneRequest request) {
        var role = rbacUseCase.cloneRole(id, new RbacUseCase.CloneRoleCommand(
                request.code(), request.name()));
        return ResponseEntity.status(HttpStatus.CREATED).body(RoleResponse.from(role));
    }
}
