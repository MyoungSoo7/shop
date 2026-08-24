package github.lms.lemuel.shipping.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.shipping.application.port.in.RegisterTrackingNumbersUseCase;
import github.lms.lemuel.shipping.application.port.in.RegisterTrackingNumbersUseCase.BulkTrackingResult;
import github.lms.lemuel.shipping.application.port.in.RegisterTrackingNumbersUseCase.ResultLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 송장 일괄 업로드 콘솔 — 미리보기가 기본이다.
 */
class AdminTrackingUploadControllerTest {

    private RegisterTrackingNumbersUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(RegisterTrackingNumbersUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AdminTrackingUploadController(new TrackingNumberCsvParser(), useCase))
                // ErrorResponse.timestamp 가 LocalDateTime 이라 모듈 미등록 ObjectMapper 로는 직렬화가 깨진다
                // — 그러면 advice 가 돌고도 500 으로 보여 400 계약 검증이 무력해진다.
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                // 프로덕션과 같은 매핑을 검증한다 — BusinessException → 400 은 전역 advice 의 책임이라,
                // 붙이지 않으면 400 계약이 아니라 MockMvc 설정을 테스트하게 된다.
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "tracking.csv", "text/csv",
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test @DisplayName("dryRun 파라미터가 없으면 미리보기로 동작한다(안전 기본값)")
    void defaultsToDryRun() throws Exception {
        when(useCase.register(any(), anyBoolean()))
                .thenReturn(new BulkTrackingResult(1, 0, true, List.of()));

        mockMvc.perform(multipart("/admin/shipments/tracking-upload")
                        .file(csv("order_id,carrier,tracking_number\n7,CJ,111\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.applied").value(1));

        ArgumentCaptor<Boolean> dryRun = ArgumentCaptor.forClass(Boolean.class);
        verify(useCase).register(any(), dryRun.capture());
        assertThat(dryRun.getValue()).isTrue();
    }

    @Test @DisplayName("dryRun=false 를 명시해야 실제로 출고한다")
    void executesOnlyWhenExplicit() throws Exception {
        when(useCase.register(any(), anyBoolean()))
                .thenReturn(new BulkTrackingResult(1, 0, false, List.of()));

        mockMvc.perform(multipart("/admin/shipments/tracking-upload")
                        .file(csv("order_id,carrier,tracking_number\n7,CJ,111\n"))
                        .param("dryRun", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false));

        ArgumentCaptor<Boolean> dryRun = ArgumentCaptor.forClass(Boolean.class);
        verify(useCase).register(any(), dryRun.capture());
        assertThat(dryRun.getValue()).isFalse();
    }

    @Test @DisplayName("실패 행은 사유와 함께 돌려준다")
    void reportsFailedLines() throws Exception {
        when(useCase.register(any(), anyBoolean())).thenReturn(new BulkTrackingResult(
                0, 1, true, List.of(new ResultLine(7L, "CJ", "111", false, "배송 없음"))));

        mockMvc.perform(multipart("/admin/shipments/tracking-upload")
                        .file(csv("order_id,carrier,tracking_number\n7,CJ,111\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.lines[0].reason").value("배송 없음"));
    }

    @Test @DisplayName("헤더가 깨진 파일은 400 으로 거절한다 — 행별로 알릴 수 없다")
    void malformedHeaderIsRejected() throws Exception {
        mockMvc.perform(multipart("/admin/shipments/tracking-upload")
                        .file(csv("order_id,carrier\n7,CJ\n")))
                .andExpect(status().isBadRequest());
    }
}
