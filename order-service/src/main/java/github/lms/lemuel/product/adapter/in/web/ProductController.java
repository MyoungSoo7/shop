package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.product.adapter.in.web.request.CreateProductRequest;
import github.lms.lemuel.product.adapter.in.web.request.UpdateProductInfoRequest;
import github.lms.lemuel.product.adapter.in.web.request.UpdateProductPriceRequest;
import github.lms.lemuel.product.adapter.in.web.request.UpdateProductStockRequest;
import github.lms.lemuel.product.adapter.in.web.response.ProductResponse;
import github.lms.lemuel.product.application.port.in.CreateProductUseCase;
import github.lms.lemuel.product.application.port.in.GetProductUseCase;
import github.lms.lemuel.product.application.port.in.SearchProductFacetsUseCase;
import github.lms.lemuel.product.application.port.in.ManageProductStatusUseCase;
import github.lms.lemuel.product.application.port.in.UpdateProductUseCase;
import github.lms.lemuel.product.application.port.in.UpdateProductUseCase.UpdateProductInfoCommand;
import github.lms.lemuel.product.application.port.in.UpdateProductUseCase.UpdateProductPriceCommand;
import github.lms.lemuel.product.application.port.in.UpdateProductUseCase.UpdateProductStockCommand;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "Product", description = "상품 CRUD / 가격/재고/상태 관리 API")
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final CreateProductUseCase createProductUseCase;
    private final GetProductUseCase getProductUseCase;
    private final UpdateProductUseCase updateProductUseCase;
    private final ManageProductStatusUseCase manageProductStatusUseCase;
    private final github.lms.lemuel.product.application.service.ProductImageService productImageService;
    private final SearchProductFacetsUseCase searchProductFacetsUseCase;

    @Operation(summary = "상품 생성", description = "새 상품을 등록한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(@RequestBody CreateProductRequest request) {
        Product product = createProductUseCase.createProduct(request.toCommand());
        String primaryImageUrl = productImageService.getPrimaryImageUrl(product.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.from(product, primaryImageUrl));
    }

    @Operation(summary = "상품 단건 조회", description = "상품 ID로 상품을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "상품을 찾을 수 없음")
    })
    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> getProduct(
            @Parameter(description = "상품 ID", required = true) @PathVariable Long productId) {
        Product product = getProductUseCase.getProductById(productId);
        String primaryImageUrl = productImageService.getPrimaryImageUrl(productId);
        return ResponseEntity.ok(ProductResponse.from(product, primaryImageUrl));
    }

    @Operation(summary = "전체 상품 조회", description = "등록된 모든 상품을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ResponseEntity<List<ProductResponse>> getAllProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "latest") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {
        List<Product> products = (keyword != null || categoryId != null)
                ? getProductUseCase.searchProducts(keyword, categoryId, sortBy, sortDirection)
                : getProductUseCase.getAllProducts();
        List<ProductResponse> responses = products.stream()
                .map(p -> ProductResponse.from(p, productImageService.getPrimaryImageUrl(p.getId())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "옵션 파셋 검색",
            description = "option=축:값 을 여러 번 넘긴다. 같은 축은 OR, 다른 축은 AND 이며 "
                    + "AND 는 SKU 하나 안에서 성립한다(빨강 SKU 와 L SKU 를 따로 가진 상품은 걸리지 않는다). "
                    + "응답의 facets 는 이어서 고를 수 있는 값과 그때 남는 상품 수다.")
    @GetMapping("/facets")
    public ResponseEntity<FacetSearchResponse> searchByFacets(
            @Parameter(description = "옵션 필터. 예: option=색상:빨강&option=사이즈:L")
            @RequestParam(name = "option", required = false) List<String> options,
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "품절·단종 SKU 를 제외할지. 기본 true — 눌러도 못 사는 값을 보여주지 않는다.")
            @RequestParam(defaultValue = "true") boolean availableOnly) {
        SearchProductFacetsUseCase.FacetSearchResult result =
                searchProductFacetsUseCase.search(options, categoryId, availableOnly);
        return ResponseEntity.ok(new FacetSearchResponse(
                result.products().stream()
                        .map(p -> ProductResponse.from(p, productImageService.getPrimaryImageUrl(p.getId())))
                        .toList(),
                result.facets()));
    }

    /** 파셋 검색 응답 — 결과 상품과 이어 고를 수 있는 파셋. */
    public record FacetSearchResponse(List<ProductResponse> products,
                                      List<SearchProductFacetsUseCase.Facet> facets) {
    }

    @Operation(summary = "상품 검색", description = "상품명/설명 키워드, 카테고리, 정렬 조건으로 상품을 검색한다.")
    @GetMapping("/search")
    public ResponseEntity<List<ProductResponse>> searchProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "latest") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {
        List<ProductResponse> responses = getProductUseCase
                .searchProducts(keyword, categoryId, sortBy, sortDirection)
                .stream()
                .map(p -> ProductResponse.from(p, productImageService.getPrimaryImageUrl(p.getId())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "상태별 상품 조회", description = "지정한 상태의 상품을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/status/{status}")
    public ResponseEntity<List<ProductResponse>> getProductsByStatus(
            @Parameter(description = "상품 상태", required = true) @PathVariable ProductStatus status) {
        List<Product> products = getProductUseCase.getProductsByStatus(status);
        List<ProductResponse> responses = products.stream()
                .map(p -> ProductResponse.from(p, productImageService.getPrimaryImageUrl(p.getId())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "판매 가능 상품 조회", description = "판매 가능한 상품 목록을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/available")
    public ResponseEntity<List<ProductResponse>> getAvailableProducts() {
        List<Product> products = getProductUseCase.getAvailableProducts();
        List<ProductResponse> responses = products.stream()
                .map(p -> ProductResponse.from(p, productImageService.getPrimaryImageUrl(p.getId())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "상품 정보 수정", description = "상품 이름/설명을 수정한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "404", description = "상품을 찾을 수 없음")
    })
    @PutMapping("/{productId}/info")
    public ResponseEntity<ProductResponse> updateProductInfo(
            @Parameter(description = "상품 ID", required = true) @PathVariable Long productId,
            @RequestBody UpdateProductInfoRequest request) {
        UpdateProductInfoCommand command = new UpdateProductInfoCommand(
                productId, request.name(), request.description());
        Product product = updateProductUseCase.updateProductInfo(command);
        String primaryImageUrl = productImageService.getPrimaryImageUrl(productId);
        return ResponseEntity.ok(ProductResponse.from(product, primaryImageUrl));
    }

    @Operation(summary = "상품 가격 변경", description = "상품 가격을 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "변경 성공"),
            @ApiResponse(responseCode = "404", description = "상품을 찾을 수 없음")
    })
    @PutMapping("/{productId}/price")
    public ResponseEntity<ProductResponse> updateProductPrice(
            @Parameter(description = "상품 ID", required = true) @PathVariable Long productId,
            @RequestBody UpdateProductPriceRequest request) {
        UpdateProductPriceCommand command = new UpdateProductPriceCommand(
                productId, request.newPrice());
        Product product = updateProductUseCase.updateProductPrice(command);
        String primaryImageUrl = productImageService.getPrimaryImageUrl(productId);
        return ResponseEntity.ok(ProductResponse.from(product, primaryImageUrl));
    }

    @Operation(summary = "상품 재고 변경", description = "상품 재고를 변경(증감)한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "변경 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "404", description = "상품을 찾을 수 없음")
    })
    @PutMapping("/{productId}/stock")
    public ResponseEntity<ProductResponse> updateProductStock(
            @Parameter(description = "상품 ID", required = true) @PathVariable Long productId,
            @RequestBody UpdateProductStockRequest request) {
        UpdateProductStockCommand command = new UpdateProductStockCommand(
                productId, request.quantity(), request.operation());
        Product product = updateProductUseCase.updateProductStock(command);
        String primaryImageUrl = productImageService.getPrimaryImageUrl(productId);
        return ResponseEntity.ok(ProductResponse.from(product, primaryImageUrl));
    }

    @Operation(summary = "상품 활성화", description = "상품을 활성 상태로 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "활성화 성공"),
            @ApiResponse(responseCode = "404", description = "상품을 찾을 수 없음")
    })
    @PostMapping("/{productId}/activate")
    public ResponseEntity<ProductResponse> activateProduct(
            @Parameter(description = "상품 ID", required = true) @PathVariable Long productId) {
        Product product = manageProductStatusUseCase.activateProduct(productId);
        String primaryImageUrl = productImageService.getPrimaryImageUrl(productId);
        return ResponseEntity.ok(ProductResponse.from(product, primaryImageUrl));
    }

    @Operation(summary = "상품 비활성화", description = "상품을 비활성 상태로 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "비활성화 성공"),
            @ApiResponse(responseCode = "404", description = "상품을 찾을 수 없음")
    })
    @PostMapping("/{productId}/deactivate")
    public ResponseEntity<ProductResponse> deactivateProduct(
            @Parameter(description = "상품 ID", required = true) @PathVariable Long productId) {
        Product product = manageProductStatusUseCase.deactivateProduct(productId);
        String primaryImageUrl = productImageService.getPrimaryImageUrl(productId);
        return ResponseEntity.ok(ProductResponse.from(product, primaryImageUrl));
    }

    @Operation(summary = "상품 단종 처리", description = "상품을 단종(DISCONTINUED) 상태로 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "단종 처리 성공"),
            @ApiResponse(responseCode = "404", description = "상품을 찾을 수 없음")
    })
    @PostMapping("/{productId}/discontinue")
    public ResponseEntity<ProductResponse> discontinueProduct(
            @Parameter(description = "상품 ID", required = true) @PathVariable Long productId) {
        Product product = manageProductStatusUseCase.discontinueProduct(productId);
        String primaryImageUrl = productImageService.getPrimaryImageUrl(productId);
        return ResponseEntity.ok(ProductResponse.from(product, primaryImageUrl));
    }
}
