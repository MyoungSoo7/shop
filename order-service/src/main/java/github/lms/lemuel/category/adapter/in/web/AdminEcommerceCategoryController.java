package github.lms.lemuel.category.adapter.in.web;

import github.lms.lemuel.category.adapter.in.web.dto.*;
import github.lms.lemuel.category.application.port.in.CheckCategoryCountIntegrityUseCase;
import github.lms.lemuel.category.application.service.EcommerceCategoryService;
import github.lms.lemuel.category.domain.EcommerceCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "Admin E-commerce Category", description = "관리자용 카테고리 CRUD 및 트리 관리 API")
@RestController
@RequestMapping("/admin/categories")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminEcommerceCategoryController {

    private final EcommerceCategoryService categoryService;
    private final CheckCategoryCountIntegrityUseCase countIntegrityUseCase;

    /**
     * 전체 카테고리 트리 조회 (관리자용)
     */
    @Operation(summary = "전체 카테고리 트리 조회", description = "비활성 카테고리를 포함한 전체 카테고리 트리를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음 (ADMIN 필요)")
    })
    @GetMapping
    public ResponseEntity<List<EcommerceCategoryResponse>> getAllCategories() {
        List<EcommerceCategory> categories = categoryService.getAllCategoriesTree();
        return ResponseEntity.ok(categories.stream()
                .map(EcommerceCategoryResponse::from)
                .collect(Collectors.toList()));
    }

    /**
     * 카테고리 생성
     */
    @Operation(summary = "카테고리 생성", description = "새로운 카테고리를 생성한다. 부모 카테고리를 지정할 수 있다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @PostMapping
    public ResponseEntity<EcommerceCategoryResponse> createCategory(
            @RequestBody EcommerceCategoryRequest request) {
        EcommerceCategory category = categoryService.createCategory(
                request.getName(),
                request.getSlug(),
                request.getParentId(),
                request.getSortOrder()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(EcommerceCategoryResponse.fromWithoutChildren(category));
    }

    /**
     * 카테고리 업데이트
     */
    @Operation(summary = "카테고리 업데이트", description = "카테고리의 이름/slug를 수정한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @PutMapping("/{id}")
    public ResponseEntity<EcommerceCategoryResponse> updateCategory(
            @Parameter(description = "카테고리 ID", required = true) @PathVariable Long id,
            @RequestBody EcommerceCategoryUpdateRequest request) {
        EcommerceCategory category = categoryService.updateCategory(
                id,
                request.getName(),
                request.getSlug()
        );
        return ResponseEntity.ok(EcommerceCategoryResponse.fromWithoutChildren(category));
    }

    /**
     * 카테고리 이동 (부모 변경)
     */
    @Operation(summary = "카테고리 이동", description = "카테고리의 부모를 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이동 성공"),
            @ApiResponse(responseCode = "400", description = "순환 참조 등 잘못된 이동"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @PatchMapping("/{id}/move")
    public ResponseEntity<EcommerceCategoryResponse> moveCategory(
            @Parameter(description = "카테고리 ID", required = true) @PathVariable Long id,
            @RequestBody CategoryMoveRequest request) {
        EcommerceCategory category = categoryService.moveCategory(id, request.getNewParentId());
        return ResponseEntity.ok(EcommerceCategoryResponse.fromWithoutChildren(category));
    }

    /**
     * 정렬 순서 변경
     */
    @Operation(summary = "카테고리 정렬 순서 변경", description = "카테고리의 정렬 순서를 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "변경 성공"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @PatchMapping("/{id}/sort")
    public ResponseEntity<EcommerceCategoryResponse> changeSortOrder(
            @Parameter(description = "카테고리 ID", required = true) @PathVariable Long id,
            @RequestBody CategorySortRequest request) {
        EcommerceCategory category = categoryService.changeSortOrder(id, request.getSortOrder());
        return ResponseEntity.ok(EcommerceCategoryResponse.fromWithoutChildren(category));
    }

    /**
     * 카테고리 활성화
     */
    @Operation(summary = "카테고리 활성화", description = "비활성 상태의 카테고리를 활성화한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "활성화 성공"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @PatchMapping("/{id}/activate")
    public ResponseEntity<EcommerceCategoryResponse> activateCategory(
            @Parameter(description = "카테고리 ID", required = true) @PathVariable Long id) {
        EcommerceCategory category = categoryService.activateCategory(id);
        return ResponseEntity.ok(EcommerceCategoryResponse.fromWithoutChildren(category));
    }

    /**
     * 카테고리 비활성화
     */
    @Operation(summary = "카테고리 비활성화", description = "활성 상태의 카테고리를 비활성화한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "비활성화 성공"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<EcommerceCategoryResponse> deactivateCategory(
            @Parameter(description = "카테고리 ID", required = true) @PathVariable Long id) {
        EcommerceCategory category = categoryService.deactivateCategory(id);
        return ResponseEntity.ok(EcommerceCategoryResponse.fromWithoutChildren(category));
    }

    /**
     * 카테고리 삭제 (soft delete)
     */
    @Operation(summary = "카테고리 삭제", description = "카테고리를 soft delete한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(
            @Parameter(description = "카테고리 ID", required = true) @PathVariable Long id) {
        categoryService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 상품수 캐시 재계산.
     *
     * <p>응답의 갱신 행 수가 0 이면 캐시와 정본(product_ecommerce_categories 실계수)이 일치한다는 뜻이라,
     * 정합성 점검용 신호로도 쓸 수 있다.
     */
    /**
     * 상품수 캐시 정합 점검.
     *
     * <p>고치지 않고 세기만 한다 — 재계산은 아래 경로가 하고, 점검이 조용히 고치면 무엇이 얼마나
     * 어긋나 있었는지가 사라져 갱신을 빠뜨린 경로를 되짚을 수 없다.
     */
    @Operation(summary = "카테고리 상품수 캐시 정합 점검",
            description = "product_count 캐시와 product_ecommerce_categories 실계수를 대조한다. "
                    + "전수 규모와 방향별 집계, 차이가 큰 순 표본을 함께 준다. 읽기 전용이다.")
    @GetMapping("/count-integrity")
    public ResponseEntity<CategoryCountIntegrityResponse> checkCountIntegrity(
            @Parameter(description = "표본 상한 (기본 50)")
            @RequestParam(defaultValue = "50") int sampleLimit) {
        return ResponseEntity.ok(CategoryCountIntegrityResponse.from(
                countIntegrityUseCase.check(sampleLimit)));
    }

    @Operation(summary = "카테고리 상품수 캐시 재계산",
            description = "product_ecommerce_categories 실계수로 product_count 를 다시 채운다. 갱신된 행 수를 반환한다.")
    @PostMapping("/refresh-counts")
    public ResponseEntity<Integer> refreshProductCounts() {
        return ResponseEntity.ok(categoryService.refreshProductCounts());
    }
}
