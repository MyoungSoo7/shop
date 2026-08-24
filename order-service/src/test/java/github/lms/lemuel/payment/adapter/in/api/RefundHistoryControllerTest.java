package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.payment.application.port.in.GetRefundHistoryUseCase;
import github.lms.lemuel.payment.domain.Refund;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RefundHistoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class RefundHistoryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean GetRefundHistoryUseCase getRefundHistoryUseCase;

    @Test
    @DisplayName("GET /api/payments/{id}/refunds 는 완료된 환불만 합산해 응답한다")
    void getByPayment_sumsOnlyCompleted() throws Exception {
        Refund completed = Refund.request(1L, new BigDecimal("5000"), "key-1", "고객 요청");
        completed.assignId(1L);
        completed.markCompleted();

        Refund failed = Refund.request(1L, new BigDecimal("2000"), "key-2", "PG 오류");
        failed.assignId(2L);
        failed.markFailed("PG 5xx");

        when(getRefundHistoryUseCase.getRefundsByPaymentId(1L)).thenReturn(List.of(completed, failed));

        mockMvc.perform(get("/api/payments/1/refunds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(1))
                .andExpect(jsonPath("$.totalRefundedAmount").value(5000))
                .andExpect(jsonPath("$.refunds.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/payments/{id}/refunds 는 환불 이력이 없으면 0원 합계를 반환한다")
    void getByPayment_empty() throws Exception {
        when(getRefundHistoryUseCase.getRefundsByPaymentId(2L)).thenReturn(List.of());

        mockMvc.perform(get("/api/payments/2/refunds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRefundedAmount").value(0))
                .andExpect(jsonPath("$.refunds.length()").value(0));
    }
}
