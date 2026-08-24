package github.lms.lemuel.shipping.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.shipping.application.port.in.ShippingUseCase;
import github.lms.lemuel.shipping.application.port.out.LoadShipmentPort;
import github.lms.lemuel.shipping.domain.Shipment;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import github.lms.lemuel.shipping.domain.ShippingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
    @MockitoBean github.lms.lemuel.shipping.application.port.in.SafetyNumberUseCase safetyNumberUseCase;

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
        when(loadPort.loadByOrderId(500L)).thenReturn(Optional.of(shipment(ShippingStatus.SHIPPED)));

        mockMvc.perform(get("/orders/500/shipment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment.status").value("SHIPPED"))
                .andExpect(jsonPath("$.shipment.carrier").value("CJ"));
    }

    @Test
    @DisplayName("GET /orders/{id}/shipment: 없으면 404")
    void get_notFound() throws Exception {
        when(loadPort.loadByOrderId(500L)).thenReturn(Optional.empty());
        mockMvc.perform(get("/orders/500/shipment")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /orders/{id}/shipment/address: 배송지 변경")
    void changeAddress() throws Exception {
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
}
