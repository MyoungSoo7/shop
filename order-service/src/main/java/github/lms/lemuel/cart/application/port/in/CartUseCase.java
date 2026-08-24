package github.lms.lemuel.cart.application.port.in;

import github.lms.lemuel.cart.domain.Cart;

/**
 * 장바구니 운영 인바운드 포트 — REST 컨트롤러가 호출하는 단일 진입점.
 */
public interface CartUseCase {

    Cart getOrCreate(Long userId);

    Cart addItem(Long userId, Long productId, Long variantId, int quantity);

    Cart changeQuantity(Long userId, Long productId, Long variantId, int newQuantity);

    Cart removeItem(Long userId, Long productId, Long variantId);

    Cart clear(Long userId);
}
