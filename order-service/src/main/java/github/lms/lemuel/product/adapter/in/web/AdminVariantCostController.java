package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.product.application.port.in.ManageVariantCostUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * SKU 매입가·마진 (관리자 전용).
 *
 * <p><b>왜 {@code /products/{id}/variants} 에 메서드를 더하지 않고 경로를 새로 팠는가.</b>
 * 그 경로의 {@code POST .../resolve} 는 구매자가 주문할 때 부르고 인증만 통과하면 되는데,
 * 응답이 {@code VariantResponse} 한 종류다. 거기에 원가를 얹으면 로그인한 아무나 원가를 본다.
 * 게다가 {@code SecurityConfig} 의 매처는 메서드별로 걸려 있어, 그 경로에 새 메서드를 만들면
 * 매처가 없는 채로 {@code anyRequest().authenticated()} 로 떨어진다 — 조용히 열린다.
 * {@code /admin/products/**} 는 모든 메서드가 ADMIN 이라 그 구멍이 없다.
 *
 * <p>{@code @PreAuthorize} 는 이 저장소에 {@code @EnableMethodSecurity} 가 없어 무효다.
 * 실제 인가는 위 URL 매처 하나뿐이므로, 이 컨트롤러의 경로를 옮기면 곧바로 인가가 사라진다.
 */
@Tag(name = "Product Variant Cost (Admin)", description = "관리자 SKU 매입가·마진 조회/설정")
@RestController
@RequestMapping("/admin/products/{productId}/variants")
public class AdminVariantCostController {

    private final ManageVariantCostUseCase useCase;

    public AdminVariantCostController(ManageVariantCostUseCase useCase) {
        this.useCase = useCase;
    }

    @Operation(summary = "SKU 별 매입가·마진 목록",
            description = "판매가는 저장된 값이 아니라 기준가+추가금-할인으로 계산한 값이고, "
                    + "마진율은 판매가 대비(매출총이익률)다. 매입가 미입력 SKU 는 마진이 null 로 나온다.")
    @GetMapping("/costs")
    public ResponseEntity<List<ManageVariantCostUseCase.VariantCostView>> list(
            @PathVariable Long productId) {
        return ResponseEntity.ok(useCase.listByProductId(productId));
    }

    @Operation(summary = "SKU 매입가 설정/해제",
            description = "purchasePrice 를 null 로 보내면 '모른다'로 되돌린다. 0 으로 보내는 것과 다르다.")
    @PutMapping("/{variantId}/purchase-price")
    public ResponseEntity<ManageVariantCostUseCase.VariantCostView> changePurchasePrice(
            @PathVariable Long productId,
            @PathVariable Long variantId,
            @Valid @RequestBody PurchasePriceRequest request) {
        return ResponseEntity.ok(
                useCase.changePurchasePrice(productId, variantId, request.purchasePrice()));
    }

    /**
     * {@code purchasePrice} 는 null 을 허용한다 — 지우기가 정상 동작이라 {@code @NotNull} 을 걸지 않는다.
     * 값이 있을 때만 0 이상·12자리(소수 2자리) 를 강제한다. 컬럼 CHECK 와 같은 규칙이라 DB 까지 못 간다.
     */
    public record PurchasePriceRequest(
            @DecimalMin(value = "0", message = "매입가는 0 이상이어야 합니다")
            @Digits(integer = 10, fraction = 2, message = "매입가 형식이 올바르지 않습니다")
            BigDecimal purchasePrice) {}
}
