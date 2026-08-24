package github.lms.lemuel.commoncode.adapter.in.web;

import github.lms.lemuel.commoncode.adapter.in.web.dto.*;
import github.lms.lemuel.commoncode.application.port.in.CommonCodeGroupUseCase;
import github.lms.lemuel.commoncode.application.port.in.CommonCodeUseCase;
import github.lms.lemuel.commoncode.domain.CommonCode;
import github.lms.lemuel.commoncode.domain.CommonCodeGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "Admin Common Code", description = "관리자용 공통코드 그룹/항목 CRUD API")
@RestController
@RequestMapping("/admin/common-codes")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCommonCodeController {

    private final CommonCodeGroupUseCase commonCodeGroupUseCase;
    private final CommonCodeUseCase commonCodeUseCase;

    // ---- Group ----

    @Operation(summary = "공통코드 그룹 전체 목록 조회", description = "등록된 모든 공통코드 그룹을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음 (ADMIN 필요)")
    })
    @GetMapping("/groups")
    public ResponseEntity<List<CommonCodeGroupResponse>> getAllGroups() {
        List<CommonCodeGroupResponse> groups = commonCodeGroupUseCase.getAllGroups().stream()
                .map(CommonCodeGroupResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(groups);
    }

    @Operation(summary = "공통코드 그룹 생성", description = "새로운 공통코드 그룹을 생성한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @PostMapping("/groups")
    public ResponseEntity<CommonCodeGroupResponse> createGroup(
            @Valid @RequestBody CommonCodeGroupCreateRequest request) {
        CommonCodeGroup group = commonCodeGroupUseCase.createGroup(
                new CommonCodeGroupUseCase.CreateGroupCommand(
                        request.getGroupCode(),
                        request.getName(),
                        request.getDescription()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonCodeGroupResponse.from(group));
    }

    @Operation(summary = "공통코드 그룹 수정", description = "공통코드 그룹의 이름/설명/활성여부를 수정한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "404", description = "그룹을 찾을 수 없음")
    })
    @PutMapping("/groups/{groupCode}")
    public ResponseEntity<CommonCodeGroupResponse> updateGroup(
            @Parameter(description = "그룹코드", required = true) @PathVariable String groupCode,
            @Valid @RequestBody CommonCodeGroupUpdateRequest request) {
        CommonCodeGroup group = commonCodeGroupUseCase.updateGroup(
                groupCode,
                new CommonCodeGroupUseCase.UpdateGroupCommand(
                        request.getName(),
                        request.getDescription(),
                        request.isActive()
                )
        );
        return ResponseEntity.ok(CommonCodeGroupResponse.from(group));
    }

    @Operation(summary = "공통코드 그룹 삭제", description = "공통코드 그룹과 하위 코드를 모두 삭제한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "404", description = "그룹을 찾을 수 없음")
    })
    @DeleteMapping("/groups/{groupCode}")
    public ResponseEntity<Void> deleteGroup(
            @Parameter(description = "그룹코드", required = true) @PathVariable String groupCode) {
        commonCodeGroupUseCase.deleteGroup(groupCode);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "그룹별 공통코드 목록 조회", description = "특정 그룹의 공통코드 목록을 sort_order 순으로 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "그룹을 찾을 수 없음")
    })
    @GetMapping("/groups/{groupCode}/codes")
    public ResponseEntity<List<CommonCodeResponse>> getCodesByGroup(
            @Parameter(description = "그룹코드", required = true) @PathVariable String groupCode) {
        List<CommonCodeResponse> codes = commonCodeUseCase.getCodesByGroup(groupCode).stream()
                .map(CommonCodeResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(codes);
    }

    // ---- Code ----

    @Operation(summary = "공통코드 생성", description = "공통코드 항목을 생성한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "404", description = "그룹을 찾을 수 없음")
    })
    @PostMapping
    public ResponseEntity<CommonCodeResponse> createCode(
            @Valid @RequestBody CommonCodeCreateRequest request) {
        CommonCode code = commonCodeUseCase.createCode(
                new CommonCodeUseCase.CreateCodeCommand(
                        request.getGroupCode(),
                        request.getCode(),
                        request.getLabel(),
                        request.getSortOrder(),
                        request.getExtra1()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonCodeResponse.from(code));
    }

    @Operation(summary = "공통코드 수정", description = "공통코드 항목의 label/sortOrder/active/extra1을 수정한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "404", description = "코드를 찾을 수 없음")
    })
    @PutMapping("/{id}")
    public ResponseEntity<CommonCodeResponse> updateCode(
            @Parameter(description = "코드 ID", required = true) @PathVariable Long id,
            @Valid @RequestBody CommonCodeUpdateRequest request) {
        CommonCode code = commonCodeUseCase.updateCode(
                id,
                new CommonCodeUseCase.UpdateCodeCommand(
                        request.getLabel(),
                        request.getSortOrder(),
                        request.isActive(),
                        request.getExtra1()
                )
        );
        return ResponseEntity.ok(CommonCodeResponse.from(code));
    }

    @Operation(summary = "공통코드 삭제", description = "공통코드 항목을 삭제한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "404", description = "코드를 찾을 수 없음")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCode(
            @Parameter(description = "코드 ID", required = true) @PathVariable Long id) {
        commonCodeUseCase.deleteCode(id);
        return ResponseEntity.noContent().build();
    }
}
