package github.lms.lemuel.cart.application.service;

import github.lms.lemuel.cart.application.port.in.CartUseCase;
import github.lms.lemuel.cart.application.port.out.LoadCartPort;
import github.lms.lemuel.cart.application.port.out.SaveCartPort;
import github.lms.lemuel.cart.domain.Cart;
import github.lms.lemuel.cart.domain.exception.CartInvariantViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CartService implements CartUseCase {

    private final LoadCartPort loadCartPort;
    private final SaveCartPort saveCartPort;

    public CartService(LoadCartPort loadCartPort, SaveCartPort saveCartPort) {
        this.loadCartPort = loadCartPort;
        this.saveCartPort = saveCartPort;
    }

    @Override
    public Cart getOrCreate(Long userId) {
        return loadCartPort.loadByUserId(userId)
                .orElseGet(() -> saveCartPort.save(Cart.createEmpty(userId)));
    }

    @Override
    public Cart addItem(Long userId, Long productId, Long variantId, int quantity) {
        Cart cart = getOrCreate(userId);
        cart.addItem(productId, variantId, quantity);
        return saveCartPort.save(cart);
    }

    @Override
    public Cart changeQuantity(Long userId, Long productId, Long variantId, int newQuantity) {
        Cart cart = loadCartPort.loadByUserId(userId)
                .orElseThrow(() -> new CartInvariantViolationException("장바구니가 비어있습니다"));
        cart.changeQuantity(productId, variantId, newQuantity);
        return saveCartPort.save(cart);
    }

    @Override
    public Cart removeItem(Long userId, Long productId, Long variantId) {
        Cart cart = loadCartPort.loadByUserId(userId)
                .orElseThrow(() -> new CartInvariantViolationException("장바구니가 비어있습니다"));
        cart.removeItem(productId, variantId);
        return saveCartPort.save(cart);
    }

    @Override
    public Cart clear(Long userId) {
        Cart cart = loadCartPort.loadByUserId(userId)
                .orElseGet(() -> Cart.createEmpty(userId));
        cart.clear();
        return saveCartPort.save(cart);
    }
}
