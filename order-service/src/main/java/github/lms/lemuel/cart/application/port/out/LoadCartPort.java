package github.lms.lemuel.cart.application.port.out;

import github.lms.lemuel.cart.domain.Cart;

import java.util.Optional;

public interface LoadCartPort {

    /**
     * 사용자별 활성 장바구니 조회. 없으면 empty.
     */
    Optional<Cart> loadByUserId(Long userId);
}
