package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.payment.application.TossPaymentService;
import github.lms.lemuel.payment.application.port.in.AuthorizePaymentPort;
import github.lms.lemuel.payment.application.port.in.CapturePaymentPort;
import github.lms.lemuel.payment.application.port.in.CreatePaymentPort;
import github.lms.lemuel.payment.application.port.in.GetPaymentPort;
import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class PaymentControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean CreatePaymentPort createPaymentPort;
    @MockitoBean AuthorizePaymentPort authorizePaymentPort;
    @MockitoBean CapturePaymentPort capturePaymentPort;
    @MockitoBean RefundPaymentPort refundPaymentPort;
    @MockitoBean GetPaymentPort getPaymentPort;
    @MockitoBean TossPaymentService tossPaymentService;

    @Test
    @DisplayName("POST /payments creates payment")
    void createPayment() throws Exception {
        when(createPaymentPort.createPayment(any()))
                .thenReturn(PaymentDomain.create(1L, new BigDecimal("15000"), "CARD"));

        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":1,"paymentMethod":"CARD"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(1))
                .andExpect(jsonPath("$.amount").value(15000))
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    @DisplayName("POST /payments rejects invalid body")
    void createPaymentInvalidBody() throws Exception {
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":1}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /payments/{id} returns 404 through exception handler")
    void getPaymentNotFound() throws Exception {
        when(getPaymentPort.getPayment(999L)).thenThrow(new PaymentNotFoundException(999L));

        mockMvc.perform(get("/payments/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("PATCH /payments/{id}/refund passes amount and idempotency key")
    void refundPayment() throws Exception {
        PaymentDomain payment = PaymentDomain.create(1L, new BigDecimal("20000"), "CARD");
        payment.authorize("pg-1");
        payment.capture();
        payment.addRefundedAmount(new BigDecimal("5000"));
        when(refundPaymentPort.refundPayment(1L, new BigDecimal("5000"), "refund-1"))
                .thenReturn(payment);

        mockMvc.perform(patch("/payments/1/refund")
                        .param("amount", "5000")
                        .header("Idempotency-Key", "refund-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }
}
