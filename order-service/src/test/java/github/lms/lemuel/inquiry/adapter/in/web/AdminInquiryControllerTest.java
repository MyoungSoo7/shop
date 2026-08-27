package github.lms.lemuel.inquiry.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.inquiry.application.port.in.InquiryUseCase;
import github.lms.lemuel.inquiry.domain.Inquiry;
import github.lms.lemuel.inquiry.domain.InquiryAnswer;
import github.lms.lemuel.inquiry.domain.InquiryType;
import github.lms.lemuel.inquiry.domain.exception.InquiryAnswerNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 문의 응대 콘솔.
 *
 * <p>인가 자체(누가 이 경로에 닿을 수 있는가)는 SecurityConfig 의 매처가 정하며 여기서는 필터를
 * 끄고 핸들러만 본다. 매처가 실제로 붙어 있는지는 {@code security-matcher-gate} 와
 * {@code SecurityAuthorizationMatrixTest} 가 따로 지킨다 — 이 저장소에는
 * {@code @EnableMethodSecurity} 가 없어 {@code @PreAuthorize} 는 조용히 무효다.
 */
@WebMvcTest(controllers = AdminInquiryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("문의 응대 콘솔 컨트롤러")
class AdminInquiryControllerTest {

    private static final Long ADMIN = 900L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 27, 10, 0);

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean InquiryUseCase inquiryUseCase;

    private static void login(long uid, String role) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(uid, uid + "@x.com", role),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static Inquiry inquiry(Long id, boolean secret, List<InquiryAnswer> answers) {
        return new Inquiry(id, 7L, InquiryType.PRODUCT, 100L, null,
                "사이즈 문의", "정사이즈인가요?", secret, NOW, answers);
    }

    private static InquiryAnswer answer() {
        return new InquiryAnswer(10L, ADMIN, "정사이즈입니다.", NOW.plusHours(1));
    }

    @Test
    @DisplayName("답변 대기 목록은 답변 유무로 판정된 것들이다")
    void listWaiting() throws Exception {
        when(inquiryUseCase.listWaiting()).thenReturn(List.of(inquiry(1L, false, List.of())));

        mockMvc.perform(get("/admin/inquiries/waiting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].status").value("WAITING"));
    }

    @Test
    @DisplayName("상세는 admin=true 로 조회한다 — 비밀글도 읽어야 답할 수 있다")
    void getReadsAsAdmin() throws Exception {
        login(ADMIN, "ADMIN");
        when(inquiryUseCase.get(eq(1L), eq(ADMIN), eq(true))).thenReturn(inquiry(1L, true, List.of()));

        mockMvc.perform(get("/admin/inquiries/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").value(true))
                .andExpect(jsonPath("$.readable").value(true));

        verify(inquiryUseCase).get(1L, ADMIN, true);
    }

    @Test
    @DisplayName("답변자는 본문이 아니라 토큰이 정한다")
    void answererComesFromToken() throws Exception {
        login(ADMIN, "MANAGER");
        when(inquiryUseCase.answer(eq(1L), eq(ADMIN), anyString()))
                .thenReturn(inquiry(1L, false, List.of(answer())));

        mockMvc.perform(post("/admin/inquiries/1/answers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "정사이즈입니다."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.answers[0].answeredBy").value(900));

        verify(inquiryUseCase).answer(1L, ADMIN, "정사이즈입니다.");
    }

    @Test
    @DisplayName("빈 답변은 400 이고 유스케이스까지 가지 않는다")
    void blankAnswerRejected() throws Exception {
        login(ADMIN, "ADMIN");

        mockMvc.perform(post("/admin/inquiries/1/answers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "   "}
                                """))
                .andExpect(status().isBadRequest());

        verify(inquiryUseCase, never()).answer(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("답변을 지우면 상태가 다시 대기로 나간다")
    void deleteAnswerReturnsToWaiting() throws Exception {
        when(inquiryUseCase.deleteAnswer(1L, 10L)).thenReturn(inquiry(1L, false, List.of()));

        mockMvc.perform(delete("/admin/inquiries/1/answers/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.answers.length()").value(0));
    }

    @Test
    @DisplayName("다른 문의의 답변 번호를 넣으면 404 — 레거시는 번호 하나만 보고 남의 답변을 지웠다")
    void deletingForeignAnswerIsNotFound() throws Exception {
        when(inquiryUseCase.deleteAnswer(1L, 777L)).thenThrow(new InquiryAnswerNotFoundException(1L, 777L));

        mockMvc.perform(delete("/admin/inquiries/1/answers/777"))
                .andExpect(status().isNotFound());
    }
}
