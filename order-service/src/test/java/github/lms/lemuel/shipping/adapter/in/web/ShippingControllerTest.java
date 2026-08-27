package github.lms.lemuel.shipping.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.shipping.application.port.in.ShippingUseCase;
import github.lms.lemuel.shipping.application.port.out.LoadOrderOwnerPort;
import github.lms.lemuel.shipping.application.port.out.LoadShipmentPort;
import github.lms.lemuel.shipping.domain.Shipment;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import github.lms.lemuel.shipping.domain.ShippingStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ShippingController.class)
@AutoConfigureMockMvc(addFilters = false)
class ShippingControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean ShippingUseCase useCase;
    @MockitoBean LoadShipmentPort loadPort;
    @MockitoBean LoadOrderOwnerPort loadOrderOwnerPort;
    @MockitoBean github.lms.lemuel.shipping.application.port.in.SafetyNumberUseCase safetyNumberUseCase;
    @MockitoBean github.lms.lemuel.shipping.application.port.in.GetShipmentTrackingUseCase trackingUseCase;

    /** JWT 주체를 SecurityContext 에 직접 세팅(addFilters=false 슬라이스 대응). */
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

    private Shipment shipment(ShippingStatus status) {
        LocalDateTime now = LocalDateTime.now();
        ShippingAddress addr = new ShippingAddress("홍길동", "010-1234-5678", "12345",
                "서울시 강남구", "101동", "경비실");
        return Shipment.rehydrate(1L, 500L, addr, "CJ", "TRK-1", status, now, null, now, now);
    }

    @Test
    @DisplayName("POST /orders/{id}/shipment: 배송 생성")
    void create() throws Exception {
        when(useCase.createForOrder(eq(500L), any())).thenReturn(shipment(ShippingStatus.PENDING));

        mockMvc.perform(post("/orders/500/shipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientName":"홍길동","phone":"010-1234-5678","postalCode":"12345",
                                 "address1":"서울시 강남구","address2":"101동","deliveryMemo":"경비실"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.orderId").value(500))
                .andExpect(jsonPath("$.shipment.status").value("PENDING"));
    }

    @Test
    @DisplayName("GET /orders/{id}/shipment: 존재 시 조회")
    void get_found() throws Exception {
        login(9L, "USER");
        when(loadOrderOwnerPort.findOwnerUserId(500L)).thenReturn(9L);
        when(loadPort.loadByOrderId(500L)).thenReturn(Optional.of(shipment(ShippingStatus.SHIPPED)));

        mockMvc.perform(get("/orders/500/shipment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.status").value("SHIPPED"))
                .andExpect(jsonPath("$.shipment.carrier").value("CJ"));
    }

    @Test
    @DisplayName("GET /orders/{id}/shipment: 없으면 404")
    void get_notFound() throws Exception {
        login(9L, "USER");
        when(loadOrderOwnerPort.findOwnerUserId(500L)).thenReturn(9L);
        when(loadPort.loadByOrderId(500L)).thenReturn(Optional.empty());
        mockMvc.perform(get("/orders/500/shipment")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /orders/{id}/shipment: 남의 주문 배송은 읽을 수 없다 (수취인 이름·연락처·주소 노출)")
    void get_otherUsersOrderForbidden() throws Exception {
        login(9L, "USER");
        when(loadOrderOwnerPort.findOwnerUserId(500L)).thenReturn(77L);

        mockMvc.perform(get("/orders/500/shipment")).andExpect(status().isForbidden());
        verify(loadPort, org.mockito.Mockito.never()).loadByOrderId(500L);
    }

    @Test
    @DisplayName("GET /orders/{id}/shipment: 소유자를 모르면 거부한다 (없는 주문과 남의 주문을 같게 취급)")
    void get_unknownOwnerForbidden() throws Exception {
        login(9L, "USER");
        when(loadOrderOwnerPort.findOwnerUserId(500L)).thenReturn(null);

        mockMvc.perform(get("/orders/500/shipment")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /orders/{id}/shipment: 운영자는 소유권 대조를 우회한다 (CS 지원)")
    void get_adminBypassesOwnership() throws Exception {
        login(1L, "ADMIN");
        when(loadPort.loadByOrderId(500L)).thenReturn(Optional.of(shipment(ShippingStatus.SHIPPED)));

        mockMvc.perform(get("/orders/500/shipment")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /orders/{id}/shipment/address: 배송지 변경")
    void changeAddress() throws Exception {
        login(9L, "USER");
        when(loadOrderOwnerPort.findOwnerUserId(500L)).thenReturn(9L);
        when(useCase.changeAddress(eq(500L), any())).thenReturn(shipment(ShippingStatus.PENDING));

        mockMvc.perform(patch("/orders/500/shipment/address")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientName":"김철수","phone":"010-9999-8888","postalCode":"54321",
                                 "address1":"부산시"}
                                """))
                .andExpect(status().isOk());
        verify(useCase).changeAddress(eq(500L), any());
    }

    @Test
    @DisplayName("PATCH /orders/{id}/shipment/address: 남의 주문 배송지는 바꿀 수 없다 (택배 가로채기)")
    void changeAddress_otherUsersOrderForbidden() throws Exception {
        login(9L, "USER");
        when(loadOrderOwnerPort.findOwnerUserId(500L)).thenReturn(77L);

        mockMvc.perform(patch("/orders/500/shipment/address")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientName":"공격자","phone":"010-0000-0000","postalCode":"54321",
                                 "address1":"부산시"}
                                """))
                .andExpect(status().isForbidden());
        verify(useCase, org.mockito.Mockito.never()).changeAddress(eq(500L), any());
    }

    @Test
    @DisplayName("POST /orders/{id}/shipment/ship: 출고")
    void ship() throws Exception {
        when(useCase.ship(500L, "한진", "TRK-99")).thenReturn(shipment(ShippingStatus.SHIPPED));

        mockMvc.perform(post("/orders/500/shipment/ship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"carrier":"한진","trackingNumber":"TRK-99"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.status").value("SHIPPED"));
    }

    @Test
    @DisplayName("POST /orders/{id}/shipment/in-transit: 배송중")
    void inTransit() throws Exception {
        when(useCase.markInTransit(500L)).thenReturn(shipment(ShippingStatus.IN_TRANSIT));
        mockMvc.perform(post("/orders/500/shipment/in-transit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.status").value("IN_TRANSIT"));
    }

    @Test
    @DisplayName("POST /orders/{id}/shipment/delivered: 배송완료")
    void delivered() throws Exception {
        when(useCase.markDelivered(500L)).thenReturn(shipment(ShippingStatus.DELIVERED));
        mockMvc.perform(post("/orders/500/shipment/delivered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.status").value("DELIVERED"));
    }

    @Test
    @DisplayName("POST /orders/{id}/shipment/returned: 반품")
    void returned() throws Exception {
        when(useCase.markReturned(500L)).thenReturn(shipment(ShippingStatus.RETURNED));
        mockMvc.perform(post("/orders/500/shipment/returned"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.status").value("RETURNED"));
    }

    private github.lms.lemuel.shipping.domain.ShipmentTimeline timeline(String carrierNote) {
        LocalDateTime at = LocalDateTime.of(2026, 8, 20, 9, 0);
        return github.lms.lemuel.shipping.domain.ShipmentTimeline.of(
                shipment(ShippingStatus.SHIPPED),
                java.util.List.of(github.lms.lemuel.shipping.domain.ShipmentTrackingEvent.rehydrate(
                        1L, 500L, ShippingStatus.SHIPPED,
                        github.lms.lemuel.shipping.domain.TrackingEventSource.INTERNAL,
                        "CJ에 상품을 인계했습니다.", null, at)),
                java.util.List.of(github.lms.lemuel.shipping.domain.ShipmentTrackingEvent.carrier(
                        500L, ShippingStatus.IN_TRANSIT, "간선상차", "동서울허브", at.plusHours(2))),
                carrierNote);
    }

    @Test
    @DisplayName("GET /orders/{id}/shipment/tracking: 내부 이력과 택배사 스캔을 출처와 함께 돌려준다")
    void tracking() throws Exception {
        login(9L, "USER");
        when(loadOrderOwnerPort.findOwnerUserId(500L)).thenReturn(9L);
        when(trackingUseCase.getTimeline(500L)).thenReturn(Optional.of(timeline(null)));

        mockMvc.perform(get("/orders/500/shipment/tracking"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(500))
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].source").value("INTERNAL"))
                .andExpect(jsonPath("$.events[1].source").value("CARRIER"))
                .andExpect(jsonPath("$.events[1].location").value("동서울허브"))
                .andExpect(jsonPath("$.carrierNote").isEmpty());
    }

    @Test
    @DisplayName("GET /orders/{id}/shipment/tracking: 택배사 조회가 실패해도 내부 이력은 그대로 나간다")
    void tracking_carrierFailureKeepsEvents() throws Exception {
        login(9L, "USER");
        when(loadOrderOwnerPort.findOwnerUserId(500L)).thenReturn(9L);
        when(trackingUseCase.getTimeline(500L))
                .thenReturn(Optional.of(timeline("택배사 배송 정보를 불러오지 못했습니다.")));

        mockMvc.perform(get("/orders/500/shipment/tracking"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.carrierNote").value("택배사 배송 정보를 불러오지 못했습니다."));
    }

    @Test
    @DisplayName("GET /orders/{id}/shipment/tracking: 배송이 없으면 404")
    void tracking_notFound() throws Exception {
        login(9L, "USER");
        when(loadOrderOwnerPort.findOwnerUserId(500L)).thenReturn(9L);
        when(trackingUseCase.getTimeline(500L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/orders/500/shipment/tracking")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /orders/{id}/shipment/tracking: 남의 주문 이력은 읽을 수 없다")
    void tracking_otherUsersOrderForbidden() throws Exception {
        login(9L, "USER");
        when(loadOrderOwnerPort.findOwnerUserId(500L)).thenReturn(77L);

        mockMvc.perform(get("/orders/500/shipment/tracking")).andExpect(status().isForbidden());
        verify(trackingUseCase, org.mockito.Mockito.never()).getTimeline(500L);
    }

    @Test
    @DisplayName("GET /orders/{id}/shipment/tracking: 운영자는 소유권 대조를 우회한다 (CS 지원)")
    void tracking_adminBypassesOwnership() throws Exception {
        login(1L, "ADMIN");
        when(trackingUseCase.getTimeline(500L)).thenReturn(Optional.of(timeline(null)));

        mockMvc.perform(get("/orders/500/shipment/tracking")).andExpect(status().isOk());
    }
}
