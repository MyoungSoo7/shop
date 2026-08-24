package github.lms.lemuel.category.adapter.in.web;

import github.lms.lemuel.category.adapter.in.web.dto.EcommerceCategoryResponse;
import github.lms.lemuel.category.application.service.EcommerceCategoryService;
import github.lms.lemuel.category.domain.EcommerceCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "Public E-commerce Category", description = "공개 카테고리 조회 API")
@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
public class PublicEcommerceCategoryController {

    private final EcommerceCategoryService categoryService;

    /**
     * 활성 카테고리 트리 조회 (공개용)
     */
    @Operation(summary = "활성 카테고리 트리 조회", description = "공개된(활성화된) 카테고리의 트리를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ResponseEntity<List<EcommerceCategoryResponse>> getActiveCategories() {
        List<EcommerceCategory> categories = categoryService.getActiveCategoriesTree();
        return ResponseEntity.ok(categories.stream()
                .map(EcommerceCategoryResponse::from)
                .collect(Collectors.toList()));
    }

    /**
     * 단일 카테고리 조회 (slug)
     */
    @Operation(summary = "slug로 카테고리 조회", description = "slug를 이용해 단일 카테고리를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음")
    })
    @GetMapping("/{slug}")
    public ResponseEntity<EcommerceCategoryResponse> getCategoryBySlug(
            @Parameter(description = "카테고리 slug", required = true) @PathVariable String slug) {
        EcommerceCategory category = categoryService.getCategoryBySlug(slug);
        return ResponseEntity.ok(EcommerceCategoryResponse.fromWithoutChildren(category));
    }
}
