package github.lms.lemuel.cart.adapter.in.web;

import github.lms.lemuel.cart.application.port.in.CartUseCase;
import github.lms.lemuel.cart.application.port.in.CheckoutCartUseCase;
import github.lms.lemuel.cart.domain.Cart;
import github.lms.lemuel.cart.domain.CartItem;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.web.security.ResourceOwnership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Cart", description = "장바구니 + 체크아웃")
@RestController
@RequestMapping("/users/{userId}/cart")
public class CartController {

    private final CartUseCase cartUseCase;
    private final CheckoutCartUseCase checkoutUseCase;

    public CartController(CartUseCase cartUseCase, CheckoutCartUseCase checkoutUseCase) {
        this.cartUseCase = cartUseCase;
        this.checkoutUseCase = checkoutUseCase;
    }

    @Operation(summary = "장바구니 조회 (없으면 자동 생성)")
    @GetMapping
    public ResponseEntity<CartResponse> get(@PathVariable Long userId) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        return ResponseEntity.ok(CartResponse.from(cartUseCase.getOrCreate(userId)));
    }

    @Operation(summary = "장바구니 항목 추가",
            description = "같은 (productId, variantId) 면 수량 증가로 자동 변환")
    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(@PathVariable Long userId,
                                                 @RequestBody AddItemRequest request) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        Cart cart = cartUseCase.addItem(userId, request.productId(), request.variantId(), request.quantity());
        return ResponseEntity.ok(CartResponse.from(cart));
    }

    @Operation(summary = "장바구니 항목 수량 변경 (0 이면 삭제)")
    @PatchMapping("/items")
    public ResponseEntity<CartResponse> changeQuantity(@PathVariable Long userId,
                                                        @RequestBody ChangeQuantityRequest request) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        Cart cart = cartUseCase.changeQuantity(userId, request.productId(), request.variantId(),
                request.quantity());
        return ResponseEntity.ok(CartResponse.from(cart));
    }

    @Operation(summary = "장바구니 항목 삭제")
    @DeleteMapping("/items")
    public ResponseEntity<CartResponse> removeItem(@PathVariable Long userId,
                                                    @RequestParam Long productId,
                                                    @RequestParam(required = false) Long variantId) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        Cart cart = cartUseCase.removeItem(userId, productId, variantId);
        return ResponseEntity.ok(CartResponse.from(cart));
    }

    @Operation(summary = "장바구니 비우기")
    @DeleteMapping
    public ResponseEntity<CartResponse> clear(@PathVariable Long userId) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        Cart cart = cartUseCase.clear(userId);
        return ResponseEntity.ok(CartResponse.from(cart));
    }

    @Operation(summary = "체크아웃 — 장바구니 → 다건 주문 변환",
            description = "재고 차감 후 Order 생성 → 장바구니 자동 clear. 실패 시 장바구니 유지.")
    @PostMapping("/checkout")
    public ResponseEntity<Map<String, Object>> checkout(@PathVariable Long userId) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        Order order = checkoutUseCase.checkout(userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", order.getId());
        body.put("amount", order.getAmount());
        body.put("itemCount", order.getItems().size());
        body.put("status", order.getStatus().name());
        return ResponseEntity.ok(body);
    }

    public record AddItemRequest(@NotNull Long productId, Long variantId, @Min(1) int quantity) {}
    public record ChangeQuantityRequest(@NotNull Long productId, Long variantId, @Min(0) int quantity) {}

    public record CartResponse(Map<String, Object> cart, List<Map<String, Object>> items) {
        static CartResponse from(Cart cart) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("id", cart.getId());
            meta.put("userId", cart.getUserId());
            meta.put("totalQuantity", cart.totalQuantity());
            meta.put("itemCount", cart.getItems().size());
            meta.put("lastActiveAt", cart.getLastActiveAt());

            List<Map<String, Object>> items = cart.getItems().stream()
                    .map(CartResponse::toItemMap)
                    .toList();
            return new CartResponse(meta, items);
        }

        private static Map<String, Object> toItemMap(CartItem item) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", item.getId());
            m.put("productId", item.getProductId());
            m.put("variantId", item.getVariantId());
            m.put("quantity", item.getQuantity());
            m.put("addedAt", item.getAddedAt());
            return m;
        }
    }
}
