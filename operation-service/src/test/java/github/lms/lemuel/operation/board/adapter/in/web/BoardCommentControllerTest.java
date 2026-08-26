package github.lms.lemuel.operation.board.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.operation.board.application.port.in.BoardCommentUseCase;
import github.lms.lemuel.operation.board.application.port.in.CommentModerationUseCase;
import github.lms.lemuel.operation.board.application.port.in.QueryBoardUseCase;
import github.lms.lemuel.operation.board.domain.BoardAccessPolicy;
import github.lms.lemuel.operation.board.domain.BoardAttachmentPolicy;
import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.BoardComment;
import github.lms.lemuel.operation.board.domain.BoardCommentStatus;
import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardContentPolicy;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardSkin;
import github.lms.lemuel.operation.board.domain.CommentReport;
import github.lms.lemuel.operation.board.domain.CommentReportReason;
import github.lms.lemuel.operation.board.domain.CommentReportStatus;
import github.lms.lemuel.operation.board.domain.exception.DuplicateCommentReportException;
import github.lms.lemuel.operation.board.domain.exception.BoardAccessDeniedException;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BoardCommentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(github.lms.lemuel.common.config.JacksonCompatConfig.class)
class BoardCommentControllerTest {

    /** OpsWebhookAuthFilter(@Component Filter)가 웹 슬라이스 스캔에 걸려 요구한다 — 필터는 addFilters=false 로 실행되지 않으므로 목으로 충분. */
    @MockitoBean
    github.lms.lemuel.operation.config.OpsProperties opsProperties;

    /** JwtAuthenticationFilter(shared-common @Component Filter)도 같은 이유로 스캔에 걸린다 — 저장소 관례(@MockitoBean JwtUtil)를 따른다. */
    @MockitoBean
    github.lms.lemuel.common.config.jwt.JwtUtil jwtUtil;

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-15T10:00:00Z");

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @MockitoBean
    BoardCommentUseCase boardCommentUseCase;
    @MockitoBean
    QueryBoardUseCase queryBoardUseCase;
    @MockitoBean
    CommentModerationUseCase commentModerationUseCase;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void login(long userId, String email, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, email, role), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static BoardDefinition definition() {
        return BoardDefinition.rehydrate(1L, "notice", "공지", null, BoardSkin.LIST,
                BoardContentPolicy.rehydrate(BoardContentFormat.TEXT, true, false, null),
                BoardAttachmentPolicy.disabled(),
                BoardAccessPolicy.rehydrate(List.of(), List.of("USER"), List.of("USER"), List.of("ADMIN")),
                true, NOW, NOW);
    }

    private static BoardComment comment(BoardCommentStatus status) {
        return BoardComment.rehydrate(7L, 5L, 1L, null, new BoardAuthor(10L, "co***"),
                "원문 내용", status, NOW, NOW);
    }

    @Test
    @DisplayName("삭제된 댓글은 원문 대신 자리표시가 나간다 — 원문은 감사용으로만 남는다")
    void deletedCommentIsMasked() throws Exception {
        when(queryBoardUseCase.getByKey(anyString())).thenReturn(definition());
        when(boardCommentUseCase.listByPost(anyString(), any(), any()))
                .thenReturn(List.of(comment(BoardCommentStatus.DELETED)));

        mockMvc.perform(get("/api/boards/notice/posts/5/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("삭제된 댓글입니다."))
                .andExpect(jsonPath("$[0].deletable").value(false));
    }

    @Test
    @DisplayName("작성은 201, 작성자는 JWT 에서만 파생된다")
    void create() throws Exception {
        login(10L, "commenter@lemuel.local", "USER");
        when(queryBoardUseCase.getByKey(anyString())).thenReturn(definition());
        when(boardCommentUseCase.create(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(comment(BoardCommentStatus.PUBLISHED));

        mockMvc.perform(post("/api/boards/notice/posts/5/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "댓글"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mine").value(true));
    }

    @Test
    @DisplayName("댓글이 꺼진 게시판·권한 없음은 403")
    void forbidden() throws Exception {
        login(11L, "stranger@lemuel.local", "USER");
        when(boardCommentUseCase.create(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new BoardAccessDeniedException("이 게시판에 댓글을 쓸 권한이 없습니다."));

        mockMvc.perform(post("/api/boards/notice/posts/5/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "댓글"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("내용이 비면 400")
    void validation() throws Exception {
        login(10L, "commenter@lemuel.local", "USER");

        mockMvc.perform(post("/api/boards/notice/posts/5/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "  "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("신고는 201, 신고자는 본문이 아니라 JWT 에서만 온다")
    void report() throws Exception {
        login(11L, "reporter@lemuel.local", "USER");
        when(commentModerationUseCase.report(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(CommentReport.rehydrate(3L, 7L, new BoardAuthor(11L, "re***"),
                        CommentReportReason.ABUSE, "욕설입니다", CommentReportStatus.RECEIVED, null, null, NOW));

        mockMvc.perform(post("/api/boards/notice/comments/7/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        // 본문에 남의 식별자를 실어 보내도 무시된다 — 아래 verify 가 그 지점이다.
                        .content(objectMapper.writeValueAsString(
                                Map.of("reason", "ABUSE", "detail", "욕설입니다", "reporterId", 99))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.reporterName").value("re***"));

        verify(commentModerationUseCase).report(eq("notice"), eq(7L), any(),
                eq(new BoardAuthor(11L, "re***")), eq(CommentReportReason.ABUSE), eq("욕설입니다"));
    }

    @Test
    @DisplayName("같은 사람의 중복 신고는 409 — 큐의 건수가 여론처럼 보이지 않게")
    void duplicateReport() throws Exception {
        login(11L, "reporter@lemuel.local", "USER");
        when(commentModerationUseCase.report(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateCommentReportException(7L));

        mockMvc.perform(post("/api/boards/notice/comments/7/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "SPAM"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("사유 없는 신고는 400")
    void reportRequiresReason() throws Exception {
        login(11L, "reporter@lemuel.local", "USER");

        mockMvc.perform(post("/api/boards/notice/comments/7/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("삭제 성공은 204, 권한 없음은 403")
    void delete_() throws Exception {
        login(10L, "commenter@lemuel.local", "USER");

        mockMvc.perform(delete("/api/boards/notice/comments/7")).andExpect(status().isNoContent());

        doThrow(new BoardAccessDeniedException("이 댓글을 삭제할 권한이 없습니다."))
                .when(boardCommentUseCase).delete(anyString(), any(), any());
        mockMvc.perform(delete("/api/boards/notice/comments/8")).andExpect(status().isForbidden());
    }
}
