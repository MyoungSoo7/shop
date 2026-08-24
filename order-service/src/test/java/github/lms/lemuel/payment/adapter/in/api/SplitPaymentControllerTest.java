package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.payment.application.port.in.CreateSplitPaymentUseCase;
import github.lms.lemuel.payment.application.service.RefundSplitPaymentService;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentTender;
import github.lms.lemuel.payment.domain.TenderType;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SplitPaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class SplitPaymentControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean CreateSplitPaymentUseCase createUseCase;
    @MockitoBean RefundSplitPaymentService refundService;
    @MockitoBean github.lms.lemuel.payment.application.port.in.ConfirmDepositUseCase confirmDepositUseCase;

    /**
     * JWT 주체를 SecurityContext 에 직접 세팅한다. addFilters=false 슬라이스에는 보안 필터가 없어
     * 홀더에 직접 넣어야 컨트롤러의 주체 파생이 동작한다.
     */
    private static void login(long uid) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(uid, uid + "@x.com", "USER"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private PaymentDomain splitPayment() {
        PaymentTender point = PaymentTender.newTender(TenderType.POINT, new BigDecimal("5000"), 1);
        PaymentTender card = PaymentTender.newTender(TenderType.CARD, new BigDecimal("45000"), 2);
        return PaymentDomain.createWithTenders(100L, List.of(point, card), "SPLIT");
    }

    @Test
    @DisplayName("POST /payments/split 는 분할결제를 생성하고 201 을 반환한다")
    void create() throws Exception {
        login(42L);
        when(createUseCase.createWithTenders(eq(100L), any(), eq(42L))).thenReturn(splitPayment());

        mockMvc.perform(post("/payments/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":100,"tenders":[
                                    {"type":"POINT","amount":5000},
                                    {"type":"CARD","amount":45000}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.orderId").value(100))
                .andExpect(jsonPath("$.payment.isSplit").value(true))
                .andExpect(jsonPath("$.tenders.length()").value(2));
    }

    @Test
    @DisplayName("POST /payments/split 는 tender 목록이 비어있으면 400 을 반환한다")
    void create_invalidBody() throws Exception {
        mockMvc.perform(post("/payments/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":100,"tenders":[]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /payments/split/{paymentId}/refund 는 분할결제 환불을 위임한다")
    void refund() throws Exception {
        PaymentDomain refunded = splitPayment();
        when(refundService.refundSplit(eq(1L), any())).thenReturn(refunded);

        mockMvc.perform(post("/payments/split/1/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":10000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.orderId").value(100));
    }
}
