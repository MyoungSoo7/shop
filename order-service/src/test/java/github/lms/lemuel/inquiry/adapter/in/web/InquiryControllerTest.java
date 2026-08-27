package github.lms.lemuel.inquiry.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.inquiry.application.port.in.InquiryUseCase;
import github.lms.lemuel.inquiry.domain.Inquiry;
import github.lms.lemuel.inquiry.domain.InquiryAnswer;
import github.lms.lemuel.inquiry.domain.InquiryType;
import github.lms.lemuel.inquiry.domain.exception.InquiryAlreadyAnsweredException;
import github.lms.lemuel.inquiry.domain.exception.InquiryNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.argThat;

@WebMvcTest(controllers = InquiryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("문의 컨트롤러")
class InquiryControllerTest {

    private static final Long OWNER = 7L;
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

    private static Inquiry inquiry(Long id, Long userId, boolean secret, List<InquiryAnswer> answers) {
        return new Inquiry(id, userId, InquiryType.PRODUCT, 100L, null,
                "사이즈 문의", "정사이즈인가요?", secret, NOW, answers);
    }

    // ------------------------------------------------------------ 등록

    @Test
    @DisplayName("등록은 201 이고, 작성자는 본문이 아니라 토큰이 정한다")
    void askUsesTokenIdentity() throws Exception {
        login(OWNER, "USER");
        when(inquiryUseCase.ask(any())).thenReturn(inquiry(1L, OWNER, false, List.of()));

        String body = """
                {"type": "PRODUCT", "productId": 100,
                 "subject": "사이즈 문의", "content": "정사이즈인가요?", "secret": false}
                """;

        mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("WAITING"));

        verify(inquiryUseCase).ask(argThat(cmd -> OWNER.equals(cmd.userId())));
    }

    @Test
    @DisplayName("본문에 남의 userId 를 적어 보내도 소용없다 — 레거시는 그 값을 그대로 저장했다")
    void askIgnoresUserIdInBody() throws Exception {
        login(OWNER, "USER");
        when(inquiryUseCase.ask(any())).thenReturn(inquiry(1L, OWNER, false, List.of()));

        String body = """
                {"userId": 999, "type": "PRODUCT", "productId": 100,
                 "subject": "사이즈 문의", "content": "정사이즈인가요?", "secret": false}
                """;

        mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        verify(inquiryUseCase).ask(argThat(cmd -> OWNER.equals(cmd.userId())));
    }

    @Test
    @DisplayName("제목이 비면 400 이고 유스케이스까지 가지 않는다")
    void askRejectsBlankSubject() throws Exception {
        login(OWNER, "USER");

        String body = """
                {"type": "GENERAL", "subject": "  ", "content": "본문", "secret": false}
                """;

        mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verify(inquiryUseCase, never()).ask(any());
    }

    @Test
    @DisplayName("종류가 없으면 400")
    void askRejectsMissingType() throws Exception {
        login(OWNER, "USER");

        String body = """
                {"subject": "제목", "content": "본문", "secret": false}
                """;

        mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verify(inquiryUseCase, never()).ask(any());
    }

    // ------------------------------------------------------------ 조회

    @Test
    @DisplayName("내 문의 목록은 토큰의 사용자로 조회한다")
    void listMineUsesTokenIdentity() throws Exception {
        login(OWNER, "USER");
        when(inquiryUseCase.listMine(eq(OWNER), any())).thenReturn(List.of(inquiry(1L, OWNER, false, List.of())));

        mockMvc.perform(get("/inquiries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].readable").value(true));

        verify(inquiryUseCase).listMine(OWNER, null);
    }

    @Test
    @DisplayName("type 파라미터는 그대로 넘어간다")
    void listMinePassesType() throws Exception {
        login(OWNER, "USER");
        when(inquiryUseCase.listMine(eq(OWNER), eq(InquiryType.ORDER))).thenReturn(List.of());

        mockMvc.perform(get("/inquiries").param("type", "ORDER"))
                .andExpect(status().isOk());

        verify(inquiryUseCase).listMine(OWNER, InquiryType.ORDER);
    }

    @Test
    @DisplayName("답변이 붙으면 상태가 ANSWERED 로 나간다 — 저장된 칼럼이 아니라 답변 유무에서 계산된 값이다")
    void statusIsDerived() throws Exception {
        login(OWNER, "USER");
        when(inquiryUseCase.get(eq(1L), eq(OWNER), anyBoolean()))
                .thenReturn(inquiry(1L, OWNER, false,
                        List.of(new InquiryAnswer(10L, 900L, "정사이즈입니다.", NOW.plusHours(1)))));

        mockMvc.perform(get("/inquiries/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.answers[0].id").value(10));
    }

    @Test
    @DisplayName("상품 문의 목록에서 못 읽는 글은 readable=false 로 나간다 — 줄은 남고 제목만 가려진다")
    void maskedRowsAreMarkedUnreadable() throws Exception {
        login(99L, "USER");
        when(inquiryUseCase.listForProduct(eq(100L), eq(99L), eq(false)))
                .thenReturn(List.of(
                        inquiry(1L, OWNER, false, List.of()),
                        new Inquiry(2L, OWNER, InquiryType.PRODUCT, 100L, null,
                                Inquiry.MASKED_SUBJECT, ".", true, NOW, List.of())));

        mockMvc.perform(get("/inquiries/products/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].readable").value(true))
                .andExpect(jsonPath("$[1].readable").value(false))
                .andExpect(jsonPath("$[1].subject").value(Inquiry.MASKED_SUBJECT));
    }

    @Test
    @DisplayName("관리자·매니저 권한은 admin 플래그로 유스케이스에 전달된다")
    void adminFlagIsPassedThrough() throws Exception {
        login(900L, "MANAGER");
        when(inquiryUseCase.listForProduct(anyLong(), anyLong(), anyBoolean())).thenReturn(List.of());

        mockMvc.perform(get("/inquiries/products/100"))
                .andExpect(status().isOk());

        verify(inquiryUseCase).listForProduct(100L, 900L, true);
    }

    @Test
    @DisplayName("남의 비밀 문의를 열면 403")
    void forbiddenWhenNotReadable() throws Exception {
        login(99L, "USER");
        when(inquiryUseCase.get(eq(1L), eq(99L), anyBoolean()))
                .thenThrow(new AccessDeniedException("본인 문의가 아닙니다."));

        mockMvc.perform(get("/inquiries/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("없는 문의는 404")
    void notFound() throws Exception {
        login(OWNER, "USER");
        when(inquiryUseCase.get(eq(1L), eq(OWNER), anyBoolean()))
                .thenThrow(new InquiryNotFoundException(1L));

        mockMvc.perform(get("/inquiries/1"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ 수정·철회

    @Test
    @DisplayName("수정은 200 이고 요청자는 토큰이 정한다")
    void edit() throws Exception {
        login(OWNER, "USER");
        when(inquiryUseCase.edit(eq(1L), eq(OWNER), anyString(), anyString(), anyBoolean()))
                .thenReturn(inquiry(1L, OWNER, true, List.of()));

        String body = """
                {"subject": "새 제목", "content": "새 본문", "secret": true}
                """;

        mockMvc.perform(put("/inquiries/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").value(true));

        verify(inquiryUseCase).edit(1L, OWNER, "새 제목", "새 본문", true);
    }

    @Test
    @DisplayName("답변이 달린 문의를 고치면 409")
    void editAfterAnswerIsConflict() throws Exception {
        login(OWNER, "USER");
        when(inquiryUseCase.edit(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new InquiryAlreadyAnsweredException("답변이 달린 문의는 수정할 수 없습니다."));

        String body = """
                {"subject": "새 제목", "content": "새 본문", "secret": false}
                """;

        mockMvc.perform(put("/inquiries/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("철회는 204 이고 본문이 없다")
    void withdraw() throws Exception {
        login(OWNER, "USER");

        String content = mockMvc.perform(delete("/inquiries/1"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getContentAsString();

        assertThat(content).isEmpty();
        verify(inquiryUseCase).withdraw(1L, OWNER);
    }

    @Test
    @DisplayName("답변이 달린 문의를 철회하면 409")
    void withdrawAfterAnswerIsConflict() throws Exception {
        login(OWNER, "USER");
        org.mockito.Mockito.doThrow(new InquiryAlreadyAnsweredException("답변이 달린 문의는 삭제할 수 없습니다."))
                .when(inquiryUseCase).withdraw(anyLong(), anyLong());

        mockMvc.perform(delete("/inquiries/1"))
                .andExpect(status().isConflict());
    }
}
