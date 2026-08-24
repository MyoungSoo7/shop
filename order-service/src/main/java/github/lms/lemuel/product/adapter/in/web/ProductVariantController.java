package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.product.application.port.in.CreateProductVariantUseCase;
import github.lms.lemuel.product.application.port.in.DecreaseVariantStockUseCase;
import github.lms.lemuel.product.application.port.in.ResolveOptionSelectionUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductVariantPort;
import github.lms.lemuel.product.domain.ProductVariant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Product Variants", description = "상품 옵션(SKU) 관리 + 재고 차감")
@RestController
@RequestMapping("/products/{productId}/variants")
public class ProductVariantController {

    private final CreateProductVariantUseCase createUseCase;
    private final DecreaseVariantStockUseCase decreaseStockUseCase;
    private final LoadProductVariantPort loadPort;
    private final ResolveOptionSelectionUseCase resolveUseCase;

    public ProductVariantController(CreateProductVariantUseCase createUseCase,
                                     DecreaseVariantStockUseCase decreaseStockUseCase,
                                     LoadProductVariantPort loadPort,
                                     ResolveOptionSelectionUseCase resolveUseCase) {
        this.createUseCase = createUseCase;
        this.decreaseStockUseCase = decreaseStockUseCase;
        this.loadPort = loadPort;
        this.resolveUseCase = resolveUseCase;
    }

    @Operation(summary = "옵션(SKU) 생성")
    @PostMapping
    public ResponseEntity<VariantResponse> create(@PathVariable Long productId,
                                                   @RequestBody CreateVariantRequest request) {
        ProductVariant variant = createUseCase.create(productId, request.sku(),
                request.optionName(), request.additionalPrice(), request.initialStock());
        return ResponseEntity.ok(VariantResponse.from(variant));
    }

    @Operation(summary = "특정 상품의 옵션(SKU) 목록")
    @GetMapping
    public ResponseEntity<List<VariantResponse>> list(@PathVariable Long productId) {
        return ResponseEntity.ok(loadPort.loadByProductId(productId).stream()
                .map(VariantResponse::from).toList());
    }

    @Operation(summary = "옵션(SKU) 재고 차감",
            description = "Optimistic Lock 충돌 시 자동 재시도. 한계 초과 시 409 가능 — 운영팀 알람.")
    @PostMapping("/{variantId}/decrease-stock")
    public ResponseEntity<VariantResponse> decreaseStock(@PathVariable Long productId,
                                                          @PathVariable Long variantId,
                                                          @RequestBody DecreaseStockRequest request) {
        ProductVariant updated = decreaseStockUseCase.decrease(variantId, request.quantity());
        return ResponseEntity.ok(VariantResponse.from(updated));
    }

    @Operation(summary = "옵션 트리 선택 → SKU 해석",
            description = "상품의 options_json 트리에서 선택 경로를 검증하고 대응하는 옵션(SKU)을 반환한다. "
                    + "주문 시 선택 경로를 variantId 로 변환하는 용도.")
    @PostMapping("/resolve")
    public ResponseEntity<VariantResponse> resolve(@PathVariable Long productId,
                                                   @RequestBody ResolveRequest request) {
        List<ResolveOptionSelectionUseCase.Selection> selections = request.selections().stream()
                .map(s -> new ResolveOptionSelectionUseCase.Selection(s.name(), s.value()))
                .toList();
        ProductVariant variant = resolveUseCase.resolve(productId, selections);
        return ResponseEntity.ok(VariantResponse.from(variant));
    }

    public record ResolveRequest(List<SelectionDto> selections) {}

    public record SelectionDto(String name, String value) {}

    public record CreateVariantRequest(
            @NotBlank String sku,
            @NotBlank String optionName,
            BigDecimal additionalPrice,
            @Min(0) int initialStock) {}

    public record DecreaseStockRequest(@Min(1) int quantity) {}

    public record VariantResponse(Map<String, Object> variant) {

        /**
         * {@code Map.of} 는 null 값을 거부한다 — 할인 두 필드는 미설정이 정상(대부분의 SKU 가 할인이 없다)이라
         * 그대로 두면 할인 없는 SKU 를 담을 때마다 NPE 로 500 이 난다. 순서를 보존하는 맵에 담아 null 을 허용한다.
         */
        static VariantResponse from(ProductVariant v) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("id", v.getId());
            fields.put("productId", v.getProductId());
            fields.put("sku", v.getSku());
            fields.put("optionName", v.getOptionName());
            fields.put("additionalPrice", v.getAdditionalPrice());
            fields.put("discountPrice", v.getDiscountPrice());
            fields.put("discountRate", v.getDiscountRate());
            fields.put("stockQuantity", v.getStockQuantity());
            fields.put("version", v.getVersion());
            fields.put("status", v.getStatus().name());
            fields.put("optionSignature", v.getOptionSignature());
            return new VariantResponse(fields);
        }
    }
}
