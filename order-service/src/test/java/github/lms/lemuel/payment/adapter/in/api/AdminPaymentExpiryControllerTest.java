package github.lms.lemuel.payment.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.payment.application.port.in.ExpirePendingPaymentsUseCase;
import github.lms.lemuel.payment.application.port.in.ExpirePendingPaymentsUseCase.ExpiryReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 미입금 만료 콘솔 — 실행 전 미리보기(dryRun)가 기본이다.
 *
 * <p>돈·재고가 걸린 배치는 "먼저 보여주고 나중에 확정"이 원칙이라, 파라미터를 빠뜨리면
 * 실행이 아니라 미리보기가 되어야 한다.
 */
class AdminPaymentExpiryControllerTest {

    private ExpirePendingPaymentsUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(ExpirePendingPaymentsUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminPaymentExpiryController(useCase))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                        new ObjectMapper()))
                .build();
    }

    @Test @DisplayName("dryRun 파라미터가 없으면 미리보기로 동작한다(안전 기본값)")
    void defaultsToDryRun() throws Exception {
        when(useCase.expireDue(any(), anyBoolean())).thenReturn(new ExpiryReport(5, 3, 2, 0, true));

        mockMvc.perform(post("/admin/payment-expiry/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.scanned").value(5))
                .andExpect(jsonPath("$.expired").value(3))
                .andExpect(jsonPath("$.skipped").value(2));

        ArgumentCaptor<Boolean> dryRun = ArgumentCaptor.forClass(Boolean.class);
        verify(useCase).expireDue(any(), dryRun.capture());
        assertThat(dryRun.getValue()).isTrue();
    }

    @Test @DisplayName("dryRun=false 를 명시해야 실제로 만료시킨다")
    void executesOnlyWhenExplicit() throws Exception {
        when(useCase.expireDue(any(), anyBoolean())).thenReturn(new ExpiryReport(5, 3, 2, 1, false));

        mockMvc.perform(post("/admin/payment-expiry/run").param("dryRun", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.failed").value(1));

        ArgumentCaptor<Boolean> dryRun = ArgumentCaptor.forClass(Boolean.class);
        verify(useCase).expireDue(any(), dryRun.capture());
        assertThat(dryRun.getValue()).isFalse();
    }
}
