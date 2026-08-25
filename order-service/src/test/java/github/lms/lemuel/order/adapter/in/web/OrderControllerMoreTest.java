package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.order.application.port.in.CreateOrderUseCase;
import github.lms.lemuel.order.application.port.in.GetOrderUseCase;
import github.lms.lemuel.order.application.port.in.IdempotentMultiItemOrderUseCase;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.ShippingAddressSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * OrderController 보완 테스트 — 기존 OrderControllerTest 가 다루지 않는
 * 다건 주문/관리자 전체조회/취소·환불 신청/관리자 승인/배송상태 변경 엔드포인트를 커버한다.
 */
@WebMvcTest(controllers = OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class OrderControllerMoreTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean CreateOrderUseCase createOrderUseCase;
    @MockitoBean IdempotentMultiItemOrderUseCase createMultiItemOrderUseCase;
    @MockitoBean GetOrderUseCase getOrderUseCase;
    @MockitoBean ChangeOrderStatusUseCase changeOrderStatusUseCase;
    @MockitoBean github.lms.lemuel.order.application.port.in.CancelOrderItemsUseCase cancelOrderItemsUseCase;
    @MockitoBean github.lms.lemuel.order.application.port.in.WithdrawOrderRequestUseCase withdrawOrderRequestUseCase;
    @MockitoBean github.lms.lemuel.order.application.port.in.PreviewCouponUseCase previewCouponUseCase;

    /** JWT 주체를 SecurityContext 에 직접 세팅(addFilters=false 슬라이스 대응 — OrderControllerTest 와 동일). */
    private static void login(long uid, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(uid, uid + "@x.com", role),
                        null,
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Order order() {
        Order o = Order.create(1L, 1L, new BigDecimal("10000"));
        o.assignId(7L);
        return o;
    }

    /** 할인·배송비가 붙은 2라인 주문 — 응답의 금액 구성을 확인하기 위한 고정물. */
    private Order multiItemOrder() {
        Order o = Order.createMultiItem(1L, List.of(
                        github.lms.lemuel.order.domain.OrderItem.newItem(
                                1L, null, "SKU-1", "티셔츠", new BigDecimal("10000"), 2),
                        github.lms.lemuel.order.domain.OrderItem.newItem(
                                2L, null, "SKU-2", "바지", new BigDecimal("30000"), 1)),
                new BigDecimal("5000"), new BigDecimal("3000"));
        o.assignId(7L);
        o.attachShippingAddress(new ShippingAddressSnapshot("홍길동", "010-1234-5678", "06236",
                "서울시 강남구 테헤란로 1", "3층", "부재시 경비실"));
        return o;
    }

    /** 배송지 JSON 조각 — /orders/multi 는 배송지 없이는 주문을 받지 않는다. */
    private static final String ADDRESS_JSON = """
            "shippingAddress":{"recipientName":"홍길동","phone":"010-1234-5678",
                               "postalCode":"06236","address1":"서울시 강남구 테헤란로 1",
                               "address2":"3층","deliveryMemo":"부재시 경비실"}
            """;

    @Test
    @DisplayName("POST /orders/multi: Idempotency-Key 와 함께 다건 주문 생성")
    void createMultiItemOrder() throws Exception {
        login(1L, "USER");
        when(createMultiItemOrderUseCase.create(eq(1L), any(), eq("SAVE10"), any(), eq("idem-1")))
                .thenReturn(order());

        mockMvc.perform(post("/orders/multi")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"lines":[{"productId":1,"variantId":null,"quantity":2}],
                                 "couponCode":"SAVE10",""" + ADDRESS_JSON + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7));
        // 배송지가 요청 그대로 유스케이스에 전달되는지 — 여기서 끊기면 주문서에 주소가 안 남는다.
        verify(createMultiItemOrderUseCase).create(eq(1L), any(), eq("SAVE10"),
                eq(new ShippingAddressSnapshot("홍길동", "010-1234-5678", "06236",
                        "서울시 강남구 테헤란로 1", "3층", "부재시 경비실")),
                eq("idem-1"));
    }

    @Test
    @DisplayName("POST /orders/multi: 배송지가 없으면 주문을 만들지 않는다 (어디로 보낼지 모르는 주문 방지)")
    void createMultiItemOrder_rejectsMissingAddress() throws Exception {
        login(1L, "USER");

        mockMvc.perform(post("/orders/multi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"lines":[{"productId":1,"variantId":null,"quantity":2}]}
                                """))
                .andExpect(status().isBadRequest());
        verify(createMultiItemOrderUseCase, org.mockito.Mockito.never())
                .create(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /orders/multi: 라인과 금액 구성(소계·할인·배송비)을 함께 돌려준다")
    void createMultiItemOrder_returnsBreakdown() throws Exception {
        login(1L, "USER");
        when(createMultiItemOrderUseCase.create(eq(1L), any(), any(), any(), any()))
                .thenReturn(multiItemOrder());

        mockMvc.perform(post("/orders/multi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"lines":[{"productId":1,"variantId":null,"quantity":2},
                                                     {"productId":2,"variantId":null,"quantity":1}],""" + ADDRESS_JSON + "}"))
                .andExpect(status().isCreated())
                // 20000 + 30000 - 5000 + 3000
                .andExpect(jsonPath("$.subtotal").value(50000))
                .andExpect(jsonPath("$.discountAmount").value(5000))
                .andExpect(jsonPath("$.shippingFee").value(3000))
                .andExpect(jsonPath("$.amount").value(48000))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productName").value("티셔츠"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].lineAmount").value(20000))
                // 확정된 주문서를 그대로 화면에 보여줄 수 있어야 한다 — 주소를 다시 조회하지 않도록.
                .andExpect(jsonPath("$.shippingAddress.recipientName").value("홍길동"))
                .andExpect(jsonPath("$.shippingAddress.postalCode").value("06236"));
    }

    @Test
    @DisplayName("POST /orders/multi: 남의 userId 로는 주문할 수 없다 (쿠폰이 대신 소진된다)")
    void createMultiItemOrder_otherUserForbidden() throws Exception {
        login(2L, "USER");

        mockMvc.perform(post("/orders/multi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"lines":[{"productId":1,"variantId":null,"quantity":1}],
                                 "couponCode":"SAVE10",""" + ADDRESS_JSON + "}"))
                .andExpect(status().isForbidden());
        verify(createMultiItemOrderUseCase, org.mockito.Mockito.never())
                .create(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /orders: 남의 userId 로는 주문할 수 없다")
    void createOrder_otherUserForbidden() throws Exception {
        login(2L, "USER");

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"productId\":1,\"amount\":10000}"))
                .andExpect(status().isForbidden());
        verify(createOrderUseCase, org.mockito.Mockito.never())
                .createOrder(any());
    }

    @Test
    @DisplayName("GET /orders/user/{id}: status·from·to 필터 전달")
    void getUserOrders_withFilters() throws Exception {
        login(1L, "USER");   // 소유권 대조(ResourceOwnership) 통과 — 본인 조회
        when(getOrderUseCase.getOrdersByUserId(eq(1L), eq("PAID"), any(), any()))
                .thenReturn(List.of(order()));

        mockMvc.perform(get("/orders/user/1")
                        .param("status", "PAID")
                        .param("from", "2026-01-01T00:00:00")
                        .param("to", "2026-12-31T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7));
    }

    @Test
    @DisplayName("GET /orders/admin/all: 전체 주문")
    void getAllOrders() throws Exception {
        when(getOrderUseCase.getAllOrders()).thenReturn(List.of(order()));
        mockMvc.perform(get("/orders/admin/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7));
    }

    @Test
    @DisplayName("POST /orders/{id}/cancellation-request: 취소 신청 (principal actor)")
    void requestCancellation() throws Exception {
        when(changeOrderStatusUseCase.requestCancellation(eq(7L), eq("변심"), eq("alice")))
                .thenReturn(order());

        mockMvc.perform(post("/orders/7/cancellation-request")
                        .principal(() -> "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"변심"}
                                """))
                .andExpect(status().isOk());
        verify(changeOrderStatusUseCase).requestCancellation(7L, "변심", "alice");
    }

    @Test
    @DisplayName("POST /orders/{id}/refund-request: 환불 신청 (principal 없으면 system)")
    void requestRefund() throws Exception {
        when(changeOrderStatusUseCase.requestRefund(eq(7L), eq("불량"), eq("system")))
                .thenReturn(order());

        mockMvc.perform(post("/orders/7/refund-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"불량"}
                                """))
                .andExpect(status().isOk());
        verify(changeOrderStatusUseCase).requestRefund(7L, "불량", "system");
    }

    @Test
    @DisplayName("POST /orders/admin/{id}/cancellation-approve: 취소 승인")
    void approveCancellation() throws Exception {
        when(changeOrderStatusUseCase.approveCancellation(anyLong(), any(), any())).thenReturn(order());
        mockMvc.perform(post("/orders/admin/7/cancellation-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"승인"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /orders/admin/{id}/refund-approve: 환불 승인")
    void approveRefund() throws Exception {
        when(changeOrderStatusUseCase.approveRefund(anyLong(), any(), any())).thenReturn(order());
        mockMvc.perform(post("/orders/admin/7/refund-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"승인"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /orders/admin/{id}/shipping-status: 배송 상태 변경")
    void changeShippingStatus() throws Exception {
        when(changeOrderStatusUseCase.changeShippingStatus(eq(7L), eq("IN_TRANSIT"), any(), any()))
                .thenReturn(order());
        mockMvc.perform(patch("/orders/admin/7/shipping-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_TRANSIT","reason":"출고"}
                                """))
                .andExpect(status().isOk());
        verify(changeOrderStatusUseCase).changeShippingStatus(eq(7L), eq("IN_TRANSIT"), any(), any());
    }
}
