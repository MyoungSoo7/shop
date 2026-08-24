package github.lms.lemuel.menu.adapter.in.web;

import github.lms.lemuel.menu.adapter.in.web.dto.MenuCreateRequest;
import github.lms.lemuel.menu.adapter.in.web.dto.MenuReorderRequest;
import github.lms.lemuel.menu.adapter.in.web.dto.MenuResponse;
import github.lms.lemuel.menu.adapter.in.web.dto.MenuUpdateRequest;
import github.lms.lemuel.menu.application.port.in.MenuUseCase;
import github.lms.lemuel.menu.domain.Menu;
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

@Tag(name = "Admin Menu", description = "관리자용 메뉴 CRUD 및 트리 관리 API")
@RestController
@RequestMapping("/admin/menus")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminMenuController {

    private final MenuUseCase menuUseCase;

    /**
     * 전체 메뉴 트리 조회
     * GET /admin/menus
     */
    @Operation(summary = "전체 메뉴 트리 조회", description = "전체 메뉴를 트리 구조(children 배열 포함)로 반환한다. sort_order 기준 정렬.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음 (ADMIN 필요)")
    })
    @GetMapping
    public ResponseEntity<List<MenuResponse>> getMenuTree() {
        List<Menu> tree = menuUseCase.getMenuTree();
        List<MenuResponse> response = tree.stream()
                .map(MenuResponse::fromTree)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    /**
     * 평면 목록 조회 (부모 선택용)
     * GET /admin/menus/flat
     */
    @Operation(summary = "메뉴 평면 목록 조회", description = "부모 메뉴 선택용 평면 목록을 반환한다. children 배열은 빈 리스트.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @GetMapping("/flat")
    public ResponseEntity<List<MenuResponse>> getMenuFlat() {
        List<Menu> flat = menuUseCase.getAllFlat();
        List<MenuResponse> response = flat.stream()
                .map(MenuResponse::fromFlat)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    /**
     * 메뉴 생성
     * POST /admin/menus
     */
    @Operation(summary = "메뉴 생성", description = "새로운 메뉴를 생성한다. parentId를 지정하면 하위 메뉴로 등록된다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @PostMapping
    public ResponseEntity<MenuResponse> createMenu(@Valid @RequestBody MenuCreateRequest request) {
        Menu created = menuUseCase.createMenu(new MenuUseCase.CreateMenuCommand(
                request.toAttributes(),
                request.getParentId(),
                request.getSortOrder(),
                request.isVisible()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(MenuResponse.fromFlat(created));
    }

    /**
     * 메뉴 수정
     * PUT /admin/menus/{id}
     */
    @Operation(summary = "메뉴 수정", description = "메뉴의 이름/경로/아이콘/부모/정렬/권한/노출/활성화 여부를 수정한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "403", description = "권한 없음"),
            @ApiResponse(responseCode = "404", description = "메뉴를 찾을 수 없음")
    })
    @PutMapping("/{id}")
    public ResponseEntity<MenuResponse> updateMenu(
            @Parameter(description = "메뉴 ID", required = true) @PathVariable Long id,
            @Valid @RequestBody MenuUpdateRequest request) {
        Menu updated = menuUseCase.updateMenu(id, new MenuUseCase.UpdateMenuCommand(
                request.toAttributes(),
                request.getParentId(),
                request.getSortOrder(),
                request.isVisible(),
                request.isActive()
        ));
        return ResponseEntity.ok(MenuResponse.fromFlat(updated));
    }

    /**
     * 메뉴 배치 재배치
     * PATCH /admin/menus/reorder
     */
    @Operation(summary = "메뉴 배치 재배치",
            description = "여러 메뉴의 부모/정렬순서를 한 번에 저장한다. 순환 참조가 생기는 재배치는 전체 거부(400).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "재배치 성공"),
            @ApiResponse(responseCode = "400", description = "존재하지 않는 메뉴/부모 또는 순환 참조"),
            @ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @PatchMapping("/reorder")
    public ResponseEntity<List<MenuResponse>> reorderMenus(
            @Valid @RequestBody MenuReorderRequest request) {
        List<Menu> saved = menuUseCase.reorder(request.items().stream()
                .map(i -> new MenuUseCase.ReorderItemCommand(i.id(), i.parentId(), i.sortOrder()))
                .collect(Collectors.toList()));
        return ResponseEntity.ok(saved.stream()
                .map(MenuResponse::fromFlat)
                .collect(Collectors.toList()));
    }

    /**
     * 메뉴 삭제
     * DELETE /admin/menus/{id}
     */
    @Operation(summary = "메뉴 삭제", description = "메뉴를 삭제한다. 하위 메뉴가 존재하면 400을 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "400", description = "하위 메뉴 존재로 삭제 불가"),
            @ApiResponse(responseCode = "403", description = "권한 없음"),
            @ApiResponse(responseCode = "404", description = "메뉴를 찾을 수 없음")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMenu(
            @Parameter(description = "메뉴 ID", required = true) @PathVariable Long id) {
        menuUseCase.deleteMenu(id);
        return ResponseEntity.noContent().build();
    }
}
