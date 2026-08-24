package github.lms.lemuel.operation.board.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.operation.board.application.port.in.BoardPage;
import github.lms.lemuel.operation.board.application.port.in.BoardAttachmentUseCase;
import github.lms.lemuel.operation.board.application.port.in.BoardCommentUseCase;
import github.lms.lemuel.operation.board.application.port.in.ManagePostUseCase;
import github.lms.lemuel.operation.board.application.port.in.QueryBoardUseCase;
import github.lms.lemuel.operation.board.application.port.in.QueryPostUseCase;
import github.lms.lemuel.operation.board.domain.BoardAccessPolicy;
import github.lms.lemuel.operation.board.domain.BoardAttachmentPolicy;
import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardContentPolicy;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardPost;
import github.lms.lemuel.operation.board.domain.BoardPostStatus;
import github.lms.lemuel.operation.board.domain.BoardSkin;
import github.lms.lemuel.operation.board.domain.exception.BoardAccessDeniedException;
import github.lms.lemuel.operation.board.domain.exception.BoardPostNotFoundException;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게시글 표면 테스트 — 검증 대상은 <b>주체 파생과 상태코드 매핑</b>이다.
 * 인가 판정 자체는 도메인 테스트가, 유스케이스 흐름은 서비스 테스트가 이미 고정한다.
 */
@WebMvcTest(controllers = BoardPostController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(github.lms.lemuel.common.config.JacksonCompatConfig.class)
class BoardPostControllerTest {

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
    QueryPostUseCase queryPostUseCase;
    @MockitoBean
    ManagePostUseCase managePostUseCase;
    @MockitoBean
    QueryBoardUseCase queryBoardUseCase;
    @MockitoBean
    BoardAttachmentUseCase boardAttachmentUseCase;
    @MockitoBean
    BoardCommentUseCase boardCommentUseCase;

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

    private static BoardPost samplePost() {
        return BoardPost.rehydrate(5L, 1L, null, "제목", "본문", BoardContentFormat.TEXT,
                new BoardAuthor(10L, "au***"), false, false, BoardPostStatus.PUBLISHED, 3L, NOW, NOW);
    }

    @Test
    @DisplayName("목록은 본문을 싣지 않는다 — 한 쪽이 메가바이트로 부풀지 않게")
    void listOmitsContent() throws Exception {
        when(queryBoardUseCase.getByKey(anyString())).thenReturn(definition());
        when(queryPostUseCase.list(anyString(), any(), any()))
                .thenReturn(BoardPage.of(List.of(samplePost()), 0, 20, 1));

        mockMvc.perform(get("/api/boards/notice/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("제목"))
                .andExpect(jsonPath("$.content[0].content").doesNotExist());
    }

    @Test
    @DisplayName("상세는 본문을 싣고, 볼 수 없는 글은 404 로 내려간다")
    void readDetail() throws Exception {
        when(queryBoardUseCase.getByKey(anyString())).thenReturn(definition());
        when(queryPostUseCase.read(anyString(), any(), any())).thenReturn(samplePost());

        mockMvc.perform(get("/api/boards/notice/posts/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("본문"))
                .andExpect(jsonPath("$.authorName").value("au***"));

        when(queryPostUseCase.read(anyString(), any(), any())).thenThrow(BoardPostNotFoundException.byId(6L));
        mockMvc.perform(get("/api/boards/notice/posts/6")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("작성자 본인에게는 editable·mine 이 참으로 내려간다")
    void mineFlag() throws Exception {
        login(10L, "author@lemuel.local", "USER");
        when(queryBoardUseCase.getByKey(anyString())).thenReturn(definition());
        when(queryPostUseCase.read(anyString(), any(), any())).thenReturn(samplePost());

        mockMvc.perform(get("/api/boards/notice/posts/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    @DisplayName("작성은 201 이고 작성자는 JWT 에서만 파생된다 — 요청 본문에 작성자 필드가 없다")
    void create() throws Exception {
        login(10L, "author@lemuel.local", "USER");
        when(queryBoardUseCase.getByKey(anyString())).thenReturn(definition());
        when(managePostUseCase.create(anyString(), any(), any(), any())).thenReturn(samplePost());

        mockMvc.perform(post("/api/boards/notice/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "제목", "content", "본문", "secret", false))))
                .andExpect(status().isCreated());

        verify(managePostUseCase).create(anyString(), any(),
                org.mockito.ArgumentMatchers.eq(new BoardAuthor(10L, "au***")), any());
    }

    @Test
    @DisplayName("식별자 없는 토큰으로는 쓸 수 없다 — 주인을 세울 수 없는 글은 만들지 않는다")
    void createWithoutUserId() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "legacy@lemuel.local", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        mockMvc.perform(post("/api/boards/notice/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "제목", "content", "본문", "secret", false))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("권한 없는 조작은 403 이다 — 대상의 존재를 이미 아는 쓰기 경로이므로 404 로 감추지 않는다")
    void forbiddenMutation() throws Exception {
        login(11L, "stranger@lemuel.local", "USER");
        doThrow(new BoardAccessDeniedException("이 글을 수정·삭제할 권한이 없습니다."))
                .when(managePostUseCase).delete(anyString(), any(), any());

        mockMvc.perform(delete("/api/boards/notice/posts/5"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("필수 필드 누락은 400")
    void validation() throws Exception {
        login(10L, "author@lemuel.local", "USER");

        mockMvc.perform(post("/api/boards/notice/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "본문"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("삭제 성공은 204")
    void delete_() throws Exception {
        login(10L, "author@lemuel.local", "USER");

        mockMvc.perform(delete("/api/boards/notice/posts/5")).andExpect(status().isNoContent());
        verify(managePostUseCase).delete(anyString(), any(), any());
    }
    @Test
    @DisplayName("상세는 첨부를 함께 싣는다 — 게시판이 첨부를 꺼도 이미 붙은 것은 실어 보낸다")
    void detailCarriesAttachments() throws Exception {
        when(queryBoardUseCase.getByKey(anyString())).thenReturn(definition());
        when(queryPostUseCase.read(anyString(), any(), any())).thenReturn(samplePost());
        when(boardAttachmentUseCase.listByPost(anyString(), any(), any())).thenReturn(List.of(
                github.lms.lemuel.operation.board.domain.BoardAttachment.rehydrate(9L, 5L, 1L,
                        github.lms.lemuel.operation.board.domain.BoardAttachmentKind.IMAGE, "벚꽃.png",
                        "uuid.png", "board-1/post-5/uuid.png", null, "image/png", 2048, 0, NOW)));

        mockMvc.perform(get("/api/boards/notice/posts/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments.length()").value(1))
                .andExpect(jsonPath("$.attachments[0].originalName").value("벚꽃.png"))
                .andExpect(jsonPath("$.attachments[0].downloadUrl")
                        .value("/api/boards/notice/attachments/9/download"));
    }

    @Test
    @DisplayName("목록에는 첨부를 싣지 않는다 — 한 쪽이 부풀지 않게")
    void listOmitsAttachments() throws Exception {
        when(queryBoardUseCase.getByKey(anyString())).thenReturn(definition());
        when(queryPostUseCase.list(anyString(), any(), any()))
                .thenReturn(BoardPage.of(List.of(samplePost()), 0, 20, 1));

        mockMvc.perform(get("/api/boards/notice/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].attachments.length()").value(0));
    }
}
