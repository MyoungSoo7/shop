package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.payment.application.TossPaymentService;
import github.lms.lemuel.payment.application.port.in.AuthorizePaymentPort;
import github.lms.lemuel.payment.application.port.in.CapturePaymentPort;
import github.lms.lemuel.payment.application.port.in.CreatePaymentPort;
import github.lms.lemuel.payment.application.port.in.GetPaymentPort;
import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PaymentController 의 authorize/capture/Toss confirm/Toss cart confirm 엔드포인트 커버리지.
 * (기본 CRUD 경로는 {@link PaymentControllerTest} 가 담당.)
 */
@WebMvcTest(controllers = PaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class PaymentControllerExtraTest {

    /**
     * {@code addFilters=false} 라 보안 필터가 SecurityContext 를 채워주지 않는다. Toss confirm 은
     * 소유권 대조 기준을 JWT 주체에서 파생하므로, 주체가 없으면 403 이다 — 직접 세팅한다.
     */
    @BeforeEach
    void login() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(7L, "buyer@x.com", "USER"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean CreatePaymentPort createPaymentPort;
    @MockitoBean AuthorizePaymentPort authorizePaymentPort;
    @MockitoBean CapturePaymentPort capturePaymentPort;
    @MockitoBean RefundPaymentPort refundPaymentPort;
    @MockitoBean GetPaymentPort getPaymentPort;
    @MockitoBean TossPaymentService tossPaymentService;

    private PaymentDomain domain(PaymentStatus status) {
        return PaymentDomain.rehydrate(1L, 10L, new BigDecimal("15000"), BigDecimal.ZERO,
                status, "CARD", "TOSS:tx-1", null, null, null);
    }

    @Test
    @DisplayName("PATCH /payments/{id}/authorize 는 AUTHORIZED 로 전이한다")
    void authorizePayment() throws Exception {
        when(authorizePaymentPort.authorizePayment(1L)).thenReturn(domain(PaymentStatus.AUTHORIZED));

        mockMvc.perform(patch("/payments/1/authorize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    @DisplayName("PATCH /payments/{id}/capture 는 CAPTURED 로 전이한다")
    void capturePayment() throws Exception {
        when(capturePaymentPort.capturePayment(1L)).thenReturn(domain(PaymentStatus.CAPTURED));

        mockMvc.perform(patch("/payments/1/capture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    @DisplayName("POST /payments/toss/confirm 는 Toss 결제 확인을 위임한다")
    void confirmTossPayment() throws Exception {
        when(tossPaymentService.confirmTossPayment(10L, "pay-key", "toss-order-1", 15000L, 7L, null))
                .thenReturn(domain(PaymentStatus.CAPTURED));

        mockMvc.perform(post("/payments/toss/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dbOrderId":10,"paymentKey":"pay-key","tossOrderId":"toss-order-1","amount":15000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    @DisplayName("POST /payments/toss/confirm 는 ADMIN 이면 소유권 대조를 건너뛰도록 callerUserId=null 로 넘긴다")
    void confirmTossPaymentAsAdminBypassesOwnership() throws Exception {
        // 운영 지원은 타인 주문 결제를 대행할 수 있어야 한다 — 대신 금액 대조는 서비스가 그대로 건다.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(1L, "admin@x.com", "ADMIN"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(tossPaymentService.confirmTossPayment(10L, "pay-key", "toss-order-1", 15000L, null, null))
                .thenReturn(domain(PaymentStatus.CAPTURED));

        mockMvc.perform(post("/payments/toss/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dbOrderId":10,"paymentKey":"pay-key","tossOrderId":"toss-order-1","amount":15000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    @DisplayName("POST /payments/toss/confirm 는 Idempotency-Key 헤더를 그대로 서비스에 넘긴다")
    void confirmTossPaymentPassesIdempotencyKey() throws Exception {
        when(tossPaymentService.confirmTossPayment(10L, "pay-key", "toss-order-1", 15000L, 7L, "idem-1"))
                .thenReturn(domain(PaymentStatus.CAPTURED));

        mockMvc.perform(post("/payments/toss/confirm")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dbOrderId":10,"paymentKey":"pay-key","tossOrderId":"toss-order-1","amount":15000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    @DisplayName("POST /payments/toss/confirm 는 잘못된 요청을 거부한다")
    void confirmTossPaymentInvalidBody() throws Exception {
        mockMvc.perform(post("/payments/toss/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dbOrderId":10}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /payments/toss/cart/confirm 는 여러 결제 응답 목록을 반환한다")
    void confirmTossCartPayment() throws Exception {
        when(tossPaymentService.confirmTossCartPayment(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(domain(PaymentStatus.CAPTURED), domain(PaymentStatus.CAPTURED)));

        mockMvc.perform(post("/payments/toss/cart/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderIds":[10,20],"paymentKey":"pay-key","tossOrderId":"toss-order-cart","totalAmount":30000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}
