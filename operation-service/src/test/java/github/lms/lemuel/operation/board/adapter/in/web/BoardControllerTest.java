package github.lms.lemuel.operation.board.adapter.in.web;

import github.lms.lemuel.operation.board.application.port.in.QueryBoardUseCase;
import github.lms.lemuel.operation.board.domain.BoardAccessPolicy;
import github.lms.lemuel.operation.board.domain.BoardAttachmentPolicy;
import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardContentPolicy;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardSkin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이용 표면 테스트 — 검증 대상은 <b>가시성이 도메인 판정을 따르는가</b>이다.
 *
 * <p>{@code addFilters = false} 라 보안 필터는 없다. 역할은 {@code SecurityContextHolder} 에
 * 직접 심는다 — 컨트롤러가 읽는 경로가 그곳이기 때문이다.
 */
@WebMvcTest(controllers = BoardController.class)
@AutoConfigureMockMvc(addFilters = false)
class BoardControllerTest {

    /** OpsWebhookAuthFilter(@Component Filter)가 웹 슬라이스 스캔에 걸려 요구한다 — 필터는 addFilters=false 로 실행되지 않으므로 목으로 충분. */
    @MockitoBean
    github.lms.lemuel.operation.config.OpsProperties opsProperties;

    /** JwtAuthenticationFilter(shared-common @Component Filter)도 같은 이유로 스캔에 걸린다 — 저장소 관례(@MockitoBean JwtUtil)를 따른다. */
    @MockitoBean
    github.lms.lemuel.common.config.jwt.JwtUtil jwtUtil;

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    QueryBoardUseCase queryBoardUseCase;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static BoardDefinition board(String key, List<String> readRoles, boolean active) {
        return BoardDefinition.rehydrate(1L, key, key, null, BoardSkin.LIST,
                BoardContentPolicy.rehydrate(BoardContentFormat.TEXT, true, false, null),
                BoardAttachmentPolicy.disabled(),
                BoardAccessPolicy.rehydrate(readRoles, List.of("ADMIN"), List.of("USER"), List.of("ADMIN")),
                active, OffsetDateTime.parse("2026-08-15T00:00Z"), OffsetDateTime.parse("2026-08-15T00:00Z"));
    }

    @Test
    @DisplayName("미인증 호출자는 공개 게시판만 본다")
    void anonymousSeesOnlyPublicBoards() throws Exception {
        when(queryBoardUseCase.findActive()).thenReturn(List.of(
                board("notice", List.of(), true),
                board("internal", List.of("ADMIN"), true)));

        mockMvc.perform(get("/api/boards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].boardKey").value("notice"));
    }

    @Test
    @DisplayName("역할이 맞으면 비공개 게시판도 목록에 담긴다")
    void roleSeesRestrictedBoard() throws Exception {
        authenticateAs("ADMIN");
        when(queryBoardUseCase.findActive()).thenReturn(List.of(
                board("notice", List.of(), true),
                board("internal", List.of("ADMIN"), true)));

        mockMvc.perform(get("/api/boards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("공개 게시판 단건 조회는 미인증도 200")
    void getPublicBoard() throws Exception {
        when(queryBoardUseCase.getByKey("notice")).thenReturn(board("notice", List.of(), true));

        mockMvc.perform(get("/api/boards/notice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardKey").value("notice"));
    }

    @Test
    @DisplayName("읽을 수 없는 게시판은 403 이 아니라 404 — 키 대입으로 존재를 훑을 수 없게")
    void unreadableBoardIsNotFound() throws Exception {
        when(queryBoardUseCase.getByKey("internal")).thenReturn(board("internal", List.of("ADMIN"), true));

        mockMvc.perform(get("/api/boards/internal"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("닫힌 게시판은 읽기 권한이 있어도 404")
    void inactiveBoardIsNotFound() throws Exception {
        authenticateAs("ADMIN");
        when(queryBoardUseCase.getByKey("closed")).thenReturn(board("closed", List.of(), false));

        mockMvc.perform(get("/api/boards/closed"))
                .andExpect(status().isNotFound());
    }
}
