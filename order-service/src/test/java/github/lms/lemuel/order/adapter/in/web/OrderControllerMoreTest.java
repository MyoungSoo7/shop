package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.order.application.port.in.ChangeOrderStatusUseCase;
import github.lms.lemuel.order.application.port.in.CreateOrderUseCase;
import github.lms.lemuel.order.application.port.in.GetOrderUseCase;
import github.lms.lemuel.order.application.port.in.IdempotentMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.in.SearchOrdersUseCase;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.ShippingAddressSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    @MockitoBean github.lms.lemuel.order.application.port.in.CreateMultiDestinationOrderUseCase createMultiDestinationOrderUseCase;
    @MockitoBean GetOrderUseCase getOrderUseCase;
    @MockitoBean ChangeOrderStatusUseCase changeOrderStatusUseCase;
    @MockitoBean github.lms.lemuel.order.application.port.in.CancelOrderItemsUseCase cancelOrderItemsUseCase;
    @MockitoBean github.lms.lemuel.order.application.port.in.WithdrawOrderRequestUseCase withdrawOrderRequestUseCase;
    @MockitoBean github.lms.lemuel.order.application.port.in.PreviewCouponUseCase previewCouponUseCase;
    @MockitoBean github.lms.lemuel.order.application.port.in.SearchOrdersUseCase searchOrdersUseCase;

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
        // 인자 6개짜리 정본으로 스텁한다. default 오버로드(5개)로 스텁하면 컨트롤러가 부르는 정본은
        // 스텁되지 않은 채 null 을 돌려주고, 응답은 201 이 아니라 500 이 된다.
        when(createMultiItemOrderUseCase.create(eq(1L), any(), eq("SAVE10"), any(), any(), eq("idem-1")))
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
                any(), eq("idem-1"));
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
                .create(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /orders/multi: 라인과 금액 구성(소계·할인·배송비)을 함께 돌려준다")
    void createMultiItemOrder_returnsBreakdown() throws Exception {
        login(1L, "USER");
        when(createMultiItemOrderUseCase.create(eq(1L), any(), any(), any(), any(), any()))
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
                .create(anyLong(), any(), any(), any(), any(), any());
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
    @DisplayName("GET /orders/admin: 주문 페이지 — 전체 규모가 페이지 밖에 있다")
    void searchOrders() throws Exception {
        when(searchOrdersUseCase.search(any()))
                .thenReturn(new SearchOrdersUseCase.OrderPage(List.of(order()), 0, 50, 137L, 3));

        mockMvc.perform(get("/orders/admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                // 137건 중 1건만 실려 왔다는 사실이 응답 안에 있어야 한다. 없으면 화면은
                // content.length 를 총 건수로 쓰고, 그 숫자는 조용히 틀린다.
                .andExpect(jsonPath("$.totalElements").value(137))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    @DisplayName("GET /orders/admin: status 를 여러 번 주면 그대로 전달된다")
    void searchOrdersWithRepeatedStatus() throws Exception {
        when(searchOrdersUseCase.search(any()))
                .thenReturn(new SearchOrdersUseCase.OrderPage(List.of(), 0, 50, 0L, 0));

        mockMvc.perform(get("/orders/admin")
                        .param("status", "CANCELLATION_REQUESTED")
                        .param("status", "REFUND_REQUESTED")
                        .param("page", "2")
                        .param("size", "20"))
                .andExpect(status().isOk());

        ArgumentCaptor<SearchOrdersUseCase.OrderQuery> captor =
                ArgumentCaptor.forClass(SearchOrdersUseCase.OrderQuery.class);
        verify(searchOrdersUseCase).search(captor.capture());
        // 승인 큐가 두 상태를 한 번에 묻는 경로다. 하나로 접히면 그 화면은 대기 건의
        // 절반을 조용히 못 본다.
        org.assertj.core.api.Assertions.assertThat(captor.getValue().statuses())
                .containsExactly("CANCELLATION_REQUESTED", "REFUND_REQUESTED");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().page()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().size()).isEqualTo(20);
    }

    @Test
    @DisplayName("GET /orders/admin/summary: 상태별 집계와 총합")
    void orderSummary() throws Exception {
        when(searchOrdersUseCase.countByStatus(any())).thenReturn(List.of(
                new SearchOrdersUseCase.OrderStatusCount("PAID", 10L, new BigDecimal("1000.00")),
                new SearchOrdersUseCase.OrderStatusCount("CANCELED", 2L, new BigDecimal("200.00"))));

        mockMvc.perform(get("/orders/admin/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(12))
                .andExpect(jsonPath("$.totalAmount").value(1200.00))
                .andExpect(jsonPath("$.statuses[0].status").value("PAID"))
                .andExpect(jsonPath("$.statuses[0].count").value(10));
    }

    @Test
    @DisplayName("GET /orders/admin/summary: 금액이 없어도 총합은 0 이지 null 이 아니다")
    void orderSummaryWithNullAmount() throws Exception {
        when(searchOrdersUseCase.countByStatus(any())).thenReturn(List.of(
                new SearchOrdersUseCase.OrderStatusCount("CREATED", 3L, null)));

        mockMvc.perform(get("/orders/admin/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.totalAmount").value(0));
    }

    @Test
    @DisplayName("POST /orders/{id}/cancellation-request: 취소 신청 (principal actor)")
    void requestCancellation() throws Exception {
        login(1L, "USER");
        when(getOrderUseCase.getOrderById(7L)).thenReturn(order());
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
        // 이력에 남는 actor 는 요청 Principal 에서 오고, 소유권 대조는 JWT 주체에서 온다 — 출처가 다르다.
        login(1L, "USER");
        when(getOrderUseCase.getOrderById(7L)).thenReturn(order());
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

    /**
     * 남의 주문에 환불 신청을 밀어 넣는 것은 "대신 환불해 주는" 정도로 끝나지 않는다.
     * 전이표상 REFUND_REQUESTED 에서 갈 수 있는 곳은 REFUNDED 뿐이라, 신청이 꽂히는 순간
     * 그 주문의 배송은 운영자가 손대기 전까지 멈춘다 — 즉 서비스 거부다.
     */
    @Test
    @DisplayName("POST /orders/{id}/refund-request: 타인 주문은 403 (IDOR — 남의 배송을 멈출 수 있다)")
    void requestRefund_otherForbidden() throws Exception {
        login(2L, "USER");
        when(getOrderUseCase.getOrderById(7L)).thenReturn(order());   // 주인은 1L

        mockMvc.perform(post("/orders/7/refund-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"불량"}
                                """))
                .andExpect(status().isForbidden());
        verify(changeOrderStatusUseCase, never()).requestRefund(anyLong(), any(), any());
    }

    @Test
    @DisplayName("POST /orders/{id}/cancellation-request: 타인 주문은 403 (IDOR)")
    void requestCancellation_otherForbidden() throws Exception {
        login(2L, "USER");
        when(getOrderUseCase.getOrderById(7L)).thenReturn(order());

        mockMvc.perform(post("/orders/7/cancellation-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"변심"}
                                """))
                .andExpect(status().isForbidden());
        verify(changeOrderStatusUseCase, never()).requestCancellation(anyLong(), any(), any());
    }

    /** CS 처리를 하려면 운영자는 남의 주문을 대신 신청할 수 있어야 한다. */
    @Test
    @DisplayName("POST /orders/{id}/refund-request: ADMIN 은 남의 주문도 대신 신청한다")
    void requestRefund_adminBypasses() throws Exception {
        login(999L, "ADMIN");
        when(getOrderUseCase.getOrderById(7L)).thenReturn(order());
        when(changeOrderStatusUseCase.requestRefund(eq(7L), eq("CS 접수"), any())).thenReturn(order());

        mockMvc.perform(post("/orders/7/refund-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"CS 접수"}
                                """))
                .andExpect(status().isOk());
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

    /**
     * 여러 곳 배송 — 배송지가 자기 라인을 들고 온다.
     *
     * <p>원본(ssg-front)의 같은 화면은 라인 목록 한 벌과 배송지 수만 보냈고, 서버가 총액을 그 수만큼
     * 곱해 더했다(재고는 한 벌만 뺐다). 여기서 확인하는 것은 그 형태가 애초에 표현될 수 없다는 것 —
     * 서울에는 서울 라인만, 부산에는 부산 라인만 간다.
     */
    private static final String TWO_DESTINATIONS_JSON = """
            {"userId":1,"destinations":[
              {"shippingAddress":{"recipientName":"홍길동","phone":"010-1234-5678","postalCode":"06236",
                                  "address1":"서울시 강남구 테헤란로 1","address2":"3층","deliveryMemo":null},
               "lines":[{"productId":1,"variantId":null,"quantity":2}]},
              {"shippingAddress":{"recipientName":"김영희","phone":"010-9999-8888","postalCode":"48058",
                                  "address1":"부산시 해운대구 해운대해변로 2","address2":null,"deliveryMemo":null},
               "lines":[{"productId":2,"variantId":null,"quantity":1}]}]}
            """;

    @Test
    @DisplayName("POST /orders/multi-destination: 묶음 id 와 주문 2건, 총액은 주문들의 합")
    void createMultiDestinationOrder() throws Exception {
        login(1L, "USER");
        Order seoul = multiItemOrder();
        Order busan = Order.createMultiItem(1L, List.of(
                github.lms.lemuel.order.domain.OrderItem.newItem(
                        2L, null, "SKU-2", "바지", new BigDecimal("30000"), 1)));
        busan.assignId(8L);
        when(createMultiDestinationOrderUseCase.create(any()))
                .thenReturn(new github.lms.lemuel.order.application.port.in
                        .CreateMultiDestinationOrderUseCase.Result("group-1", List.of(seoul, busan)));

        mockMvc.perform(post("/orders/multi-destination")
                        .header("Idempotency-Key", "idem-md-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_DESTINATIONS_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.destinationGroupId").value("group-1"))
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].id").value(7))
                .andExpect(jsonPath("$.orders[1].id").value(8))
                // 서울 48,000(50,000 - 5,000 + 3,000) + 부산 30,000
                .andExpect(jsonPath("$.totalAmount").value(78000));

        ArgumentCaptor<github.lms.lemuel.order.application.port.in
                .CreateMultiDestinationOrderUseCase.Command> command =
                ArgumentCaptor.forClass(github.lms.lemuel.order.application.port.in
                        .CreateMultiDestinationOrderUseCase.Command.class);
        verify(createMultiDestinationOrderUseCase).create(command.capture());
        assertThat(command.getValue().idempotencyKey()).isEqualTo("idem-md-1");
        assertThat(command.getValue().destinations())
                .extracting(d -> d.shippingAddress().recipientName(),
                        d -> d.lines().get(0).productId(),
                        d -> d.lines().get(0).quantity())
                .containsExactly(tuple("홍길동", 1L, 2), tuple("김영희", 2L, 1));
    }

    @Test
    @DisplayName("POST /orders/multi-destination: 배송지가 한 곳이면 400 — 그 요청은 그냥 주문이다")
    void createMultiDestinationOrder_rejectsSingleDestination() throws Exception {
        login(1L, "USER");

        mockMvc.perform(post("/orders/multi-destination")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"destinations":[
                                  {"shippingAddress":{"recipientName":"홍길동","phone":"010-1234-5678",
                                                      "postalCode":"06236","address1":"서울시 강남구 테헤란로 1"},
                                   "lines":[{"productId":1,"variantId":null,"quantity":2}]}]}
                                """))
                .andExpect(status().isBadRequest());
        verify(createMultiDestinationOrderUseCase, org.mockito.Mockito.never()).create(any());
    }

    @Test
    @DisplayName("POST /orders/multi-destination: 남의 userId 로는 만들 수 없다 (IDOR)")
    void createMultiDestinationOrder_rejectsOtherUser() throws Exception {
        login(2L, "USER");

        mockMvc.perform(post("/orders/multi-destination")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_DESTINATIONS_JSON))
                .andExpect(status().isForbidden());
        verify(createMultiDestinationOrderUseCase, org.mockito.Mockito.never()).create(any());
    }
}
