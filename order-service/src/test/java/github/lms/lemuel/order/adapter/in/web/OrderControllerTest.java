package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.order.application.port.in.CreateOrderUseCase;
import github.lms.lemuel.order.application.port.in.IdempotentMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.in.GetOrderUseCase;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean CreateOrderUseCase createOrderUseCase;
    @MockitoBean IdempotentMultiItemOrderUseCase createMultiItemOrderUseCase;
    @MockitoBean GetOrderUseCase getOrderUseCase;
    @MockitoBean ChangeOrderStatusUseCase changeOrderStatusUseCase;
    @MockitoBean github.lms.lemuel.order.application.port.in.CancelOrderItemsUseCase cancelOrderItemsUseCase;
    @MockitoBean github.lms.lemuel.order.application.port.in.WithdrawOrderRequestUseCase withdrawOrderRequestUseCase;

    /** JWT 주체를 SecurityContext 에 직접 세팅(addFilters=false 슬라이스 대응). */
    private static void login(long uid, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(uid, uid + "@x.com", role),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test @DisplayName("GET /orders/{id} - 성공") void getOrder() throws Exception {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        when(getOrderUseCase.getOrderById(1L)).thenReturn(order);

        mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(10000));
    }

    @Test @DisplayName("GET /orders/{id} - 404") void getOrder_notFound() throws Exception {
        when(getOrderUseCase.getOrderById(999L)).thenThrow(new OrderNotFoundException(999L));

        mockMvc.perform(get("/orders/999"))
                .andExpect(status().isNotFound());
    }

    @Test @DisplayName("GET /orders/user/{userId} - 본인 조회") void getUserOrders() throws Exception {
        login(1L, "USER");
        when(getOrderUseCase.getOrdersByUserId(1L, null, null, null)).thenReturn(List.of());

        mockMvc.perform(get("/orders/user/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test @DisplayName("GET /orders/user/{userId} - 타인 조회는 403 (IDOR)") void getUserOrders_otherForbidden() throws Exception {
        login(2L, "USER");
        mockMvc.perform(get("/orders/user/1"))
                .andExpect(status().isForbidden());
        verify(getOrderUseCase, never()).getOrdersByUserId(anyLong(), any(), any(), any());
    }

    @Test @DisplayName("GET /orders/user/{userId} - ADMIN 은 타인도 조회") void getUserOrders_adminBypass() throws Exception {
        login(999L, "ADMIN");
        when(getOrderUseCase.getOrdersByUserId(1L, null, null, null)).thenReturn(List.of());
        mockMvc.perform(get("/orders/user/1"))
                .andExpect(status().isOk());
    }

    @Test @DisplayName("POST /orders/{id}/items/cancel - 타인 주문은 403 (IDOR)") void cancelItems_otherForbidden() throws Exception {
        login(2L, "USER");
        when(getOrderUseCase.getOrderById(5L)).thenReturn(Order.create(1L, 1L, new BigDecimal("10000")));

        mockMvc.perform(post("/orders/5/items/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemIds\":[1],\"reason\":\"변심\"}"))
                .andExpect(status().isForbidden());

        verify(cancelOrderItemsUseCase, never()).cancelItems(anyLong(), any(), any(), any());
    }

    @Test @DisplayName("POST /orders/{id}/items/cancel - 본인 주문은 취소 결과를 돌려준다") void cancelItems_self() throws Exception {
        login(1L, "USER");
        when(getOrderUseCase.getOrderById(5L)).thenReturn(Order.create(1L, 1L, new BigDecimal("10000")));
        when(cancelOrderItemsUseCase.cancelItems(anyLong(), any(), any(), any())).thenReturn(
                new github.lms.lemuel.order.application.port.in.CancelOrderItemsUseCase.Result(
                        5L, new BigDecimal("40000"), new BigDecimal("3000"),
                        new BigDecimal("37000"), false));

        mockMvc.perform(post("/orders/5/items/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemIds\":[1],\"reason\":\"변심\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundedAmount").value(37000))
                .andExpect(jsonPath("$.additionalShippingFee").value(3000));
    }

    @Test @DisplayName("POST /orders - 생성") void createOrder() throws Exception {
        Order order = Order.create(1L, 1L, new BigDecimal("15000"));
        when(createOrderUseCase.createOrder(any())).thenReturn(order);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 1, "productId": 1, "amount": 15000}
                                """))
                .andExpect(status().isCreated());
    }

    @Test @DisplayName("PATCH /orders/{id}/cancel") void cancelOrder() throws Exception {
        Order order = Order.create(1L, 1L, new BigDecimal("10000"));
        order.cancel();
        when(changeOrderStatusUseCase.cancelOrder(1L)).thenReturn(order);

        mockMvc.perform(patch("/orders/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }
}
