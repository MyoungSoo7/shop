package github.lms.lemuel.cart.adapter.in.web;

import github.lms.lemuel.cart.application.port.in.CartUseCase;
import github.lms.lemuel.cart.application.port.in.CheckoutCartUseCase;
import github.lms.lemuel.cart.domain.Cart;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.order.domain.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CartController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class CartControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean CartUseCase cartUseCase;
    @MockitoBean CheckoutCartUseCase checkoutUseCase;

    /**
     * JWT 주체를 SecurityContext 에 직접 세팅한다. addFilters=false 슬라이스에서는 보안 필터가 없어
     * {@code .with(authentication(..))} 가 argument resolver 까지 전파되지 않으므로 홀더에 직접 넣는다.
     */
    private static void login(long uid, String role) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(uid, uid + "@x.com", role),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Cart cartWithItems() {
        return Cart.rehydrate(10L, 1L, LocalDateTime.now(),
                LocalDateTime.now(), LocalDateTime.now(),
                List.of(github.lms.lemuel.cart.domain.CartItem
                        .rehydrate(100L, 10L, 500L, null, 2, LocalDateTime.now())));
    }

    @Test
    @DisplayName("GET /users/{id}/cart: 본인 조회(없으면 자동생성)")
    void getCart() throws Exception {
        login(1L, "USER");
        when(cartUseCase.getOrCreate(1L)).thenReturn(cartWithItems());

        mockMvc.perform(get("/users/1/cart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cart.userId").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(500))
                .andExpect(jsonPath("$.cart.totalQuantity").value(2));
    }

    @Test
    @DisplayName("POST /users/{id}/cart/items: 본인 항목 추가")
    void addItem() throws Exception {
        login(1L, "USER");
        when(cartUseCase.addItem(eq(1L), eq(500L), any(), eq(2))).thenReturn(cartWithItems());

        mockMvc.perform(post("/users/1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":500,"variantId":null,"quantity":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    @DisplayName("PATCH /users/{id}/cart/items: 본인 수량 변경")
    void changeQuantity() throws Exception {
        login(1L, "USER");
        when(cartUseCase.changeQuantity(eq(1L), eq(500L), any(), eq(5))).thenReturn(cartWithItems());

        mockMvc.perform(patch("/users/1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":500,"variantId":null,"quantity":5}
                                """))
                .andExpect(status().isOk());
        verify(cartUseCase).changeQuantity(eq(1L), eq(500L), any(), eq(5));
    }

    @Test
    @DisplayName("DELETE /users/{id}/cart/items: 본인 항목 삭제")
    void removeItem() throws Exception {
        login(1L, "USER");
        when(cartUseCase.removeItem(1L, 500L, null)).thenReturn(cartWithItems());

        mockMvc.perform(delete("/users/1/cart/items").param("productId", "500"))
                .andExpect(status().isOk());
        verify(cartUseCase).removeItem(1L, 500L, null);
    }

    @Test
    @DisplayName("DELETE /users/{id}/cart: 본인 비우기")
    void clear() throws Exception {
        login(1L, "USER");
        when(cartUseCase.clear(1L)).thenReturn(cartWithItems());
        mockMvc.perform(delete("/users/1/cart")).andExpect(status().isOk());
        verify(cartUseCase).clear(1L);
    }

    @Test
    @DisplayName("POST /users/{id}/cart/checkout: 본인 체크아웃 → 주문 요약")
    void checkout() throws Exception {
        login(1L, "USER");
        Order order = Order.create(1L, 1L, new BigDecimal("20000"));
        order.assignId(99L);
        when(checkoutUseCase.checkout(1L)).thenReturn(order);

        mockMvc.perform(post("/users/1/cart/checkout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(99))
                .andExpect(jsonPath("$.amount").value(20000));
    }

    // ── IDOR 방어 (이번 수정의 본체) ──────────────────────────────────────────

    @Test
    @DisplayName("타인 장바구니 조회는 403 (IDOR 차단)")
    void otherUserCartForbidden() throws Exception {
        login(2L, "USER");
        mockMvc.perform(get("/users/1/cart"))
                .andExpect(status().isForbidden());
        verify(cartUseCase, org.mockito.Mockito.never()).getOrCreate(any());
    }

    @Test
    @DisplayName("타인 장바구니 체크아웃은 403 — 남의 카트로 주문 생성 차단")
    void otherUserCheckoutForbidden() throws Exception {
        login(2L, "USER");
        mockMvc.perform(post("/users/1/cart/checkout"))
                .andExpect(status().isForbidden());
        verify(checkoutUseCase, org.mockito.Mockito.never()).checkout(any());
    }

    @Test
    @DisplayName("ADMIN 은 타인 장바구니도 조회 가능(운영 지원)")
    void adminCanViewOtherCart() throws Exception {
        login(999L, "ADMIN");
        when(cartUseCase.getOrCreate(1L)).thenReturn(cartWithItems());
        mockMvc.perform(get("/users/1/cart")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("미인증 요청은 403")
    void unauthenticatedForbidden() throws Exception {
        mockMvc.perform(get("/users/1/cart"))
                .andExpect(status().isForbidden());
    }
}
