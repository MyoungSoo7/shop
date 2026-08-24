package github.lms.lemuel.cart.application.port.out;

import github.lms.lemuel.cart.domain.Cart;

public interface SaveCartPort {

    /**
     * Cart + 모든 자식 CartItem 을 저장한다. 기존 자식들은 모두 삭제 후 현재 도메인 상태로 재구성
     * (단순한 정책 — 장바구니 항목 수가 적으므로 충분).
     */
    Cart save(Cart cart);
}
