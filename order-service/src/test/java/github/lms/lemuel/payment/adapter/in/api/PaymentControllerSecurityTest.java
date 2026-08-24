package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.common.config.jwt.JwtAuthenticationFilter;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.config.jwt.SecurityConfig;
import github.lms.lemuel.payment.application.TossPaymentService;
import github.lms.lemuel.payment.application.port.in.AuthorizePaymentPort;
import github.lms.lemuel.payment.application.port.in.CapturePaymentPort;
import github.lms.lemuel.payment.application.port.in.CreatePaymentPort;
import github.lms.lemuel.payment.application.port.in.GetPaymentPort;
import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityConfig 의 "환불 실행 API 는 운영자 전용" 규칙을 실제 필터체인으로 검증.
 *
 * <p>피드백: 직접 환불 API(/payments/{id}/refund)는 인증만 있으면 접근 가능했던 구조 →
 * "어드민 승인 후 환불" 원칙에 따라 ADMIN/MANAGER 전용으로 제한했음을 회귀 검증한다.
 */
@WebMvcTest(controllers = PaymentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@ActiveProfiles("test")
class PaymentControllerSecurityTest {

    @Autowired WebApplicationContext context;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 커스텀 SecurityFilterChain 슬라이스에서 @WithMockUser 가 필터체인까지 전달되도록
        // springSecurity() 를 명시적으로 적용한다.
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @MockitoBean CreatePaymentPort createPaymentPort;
    @MockitoBean AuthorizePaymentPort authorizePaymentPort;
    @MockitoBean CapturePaymentPort capturePaymentPort;
    @MockitoBean RefundPaymentPort refundPaymentPort;
    @MockitoBean GetPaymentPort getPaymentPort;
    @MockitoBean TossPaymentService tossPaymentService;
    @MockitoBean JwtUtil jwtUtil;

    @Test
    @DisplayName("PATCH /payments/{id}/refund — USER 롤은 403 (운영자 전용)")
    @WithMockUser(roles = "USER")
    void refund_forbidden_forUser() throws Exception {
        mockMvc.perform(patch("/payments/1/refund"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /payments/{id}/refund — ADMIN 롤은 통과")
    @WithMockUser(roles = "ADMIN")
    void refund_allowed_forAdmin() throws Exception {
        PaymentDomain refunded = PaymentDomain.rehydrate(1L, 10L, new BigDecimal("50000"),
                new BigDecimal("50000"), PaymentStatus.REFUNDED, "CARD", "pg-tx", null, null, null);
        when(refundPaymentPort.refundPayment(eq(1L), any(), any())).thenReturn(refunded);

        mockMvc.perform(patch("/payments/1/refund"))
                .andExpect(status().isOk());
    }
}
