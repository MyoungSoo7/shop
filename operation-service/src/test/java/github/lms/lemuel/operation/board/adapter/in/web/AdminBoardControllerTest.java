package github.lms.lemuel.operation.board.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase;
import github.lms.lemuel.operation.board.application.port.in.QueryBoardUseCase;
import github.lms.lemuel.operation.board.domain.BoardAccessPolicy;
import github.lms.lemuel.operation.board.domain.BoardAttachmentPolicy;
import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardContentPolicy;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardSkin;
import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;
import github.lms.lemuel.operation.board.domain.exception.BoardNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.DuplicateBoardKeyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리 콘솔 표면 테스트 — 검증 대상은 <b>상태코드 매핑</b>이다.
 * 정책 판정은 도메인 테스트가, 유스케이스 흐름은 서비스 테스트가 이미 고정한다.
 */
@WebMvcTest(controllers = AdminBoardController.class)
@AutoConfigureMockMvc(addFilters = false)
// Boot 4 는 레거시 ObjectMapper 빈을 자동 등록하지 않는다. 요청 본문을 만들 매퍼가 필요하므로
// shared-common 의 호환 설정을 슬라이스에 명시적으로 물린다(제한 스캔 서비스의 공통 함정).
@Import(github.lms.lemuel.common.config.JacksonCompatConfig.class)
class AdminBoardControllerTest {

    /** OpsWebhookAuthFilter(@Component Filter)가 웹 슬라이스 스캔에 걸려 요구한다 — 필터는 addFilters=false 로 실행되지 않으므로 목으로 충분. */
    @MockitoBean
    github.lms.lemuel.operation.config.OpsProperties opsProperties;

    /** JwtAuthenticationFilter(shared-common @Component Filter)도 같은 이유로 스캔에 걸린다 — 저장소 관례(@MockitoBean JwtUtil)를 따른다. */
    @MockitoBean
    github.lms.lemuel.common.config.jwt.JwtUtil jwtUtil;

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @MockitoBean
    ManageBoardUseCase manageBoardUseCase;
    @MockitoBean
    QueryBoardUseCase queryBoardUseCase;

    private static BoardDefinition sample() {
        return BoardDefinition.rehydrate(1L, "notice", "공지사항", "안내", BoardSkin.LIST,
                BoardContentPolicy.rehydrate(BoardContentFormat.TEXT, true, false, null),
                BoardAttachmentPolicy.disabled(),
                BoardAccessPolicy.rehydrate(List.of(), List.of("ADMIN"), List.of("USER"), List.of("ADMIN")),
                true, OffsetDateTime.parse("2026-08-15T00:00Z"), OffsetDateTime.parse("2026-08-15T00:00Z"));
    }

    private static Map<String, Object> validBody() {
        return Map.of(
                "boardKey", "notice",
                "name", "공지사항",
                "skin", "LIST",
                "content", Map.of("contentFormat", "TEXT", "commentsEnabled", true, "secretEnabled", false),
                "attachment", Map.of("enabled", false, "maxCount", 0, "maxSizeKb", 0,
                        "allowedExtensions", List.of()),
                "access", Map.of("readRoles", List.of(), "writeRoles", List.of("ADMIN"),
                        "commentRoles", List.of("USER"), "manageRoles", List.of("ADMIN")));
    }

    @Test
    @DisplayName("목록은 비활성 포함 전체를 돌려준다")
    void list() throws Exception {
        when(queryBoardUseCase.findAll()).thenReturn(List.of(sample()));

        mockMvc.perform(get("/admin/boards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].boardKey").value("notice"))
                .andExpect(jsonPath("$[0].path").value("/boards/notice"));
    }

    @Test
    @DisplayName("생성 성공은 201 이고 응답에 파생 경로가 담긴다")
    void create() throws Exception {
        when(manageBoardUseCase.create(any())).thenReturn(sample());

        mockMvc.perform(post("/admin/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.path").value("/boards/notice"))
                .andExpect(jsonPath("$.access.publicRead").value(true));
    }

    @Test
    @DisplayName("키 중복은 409 로 내려간다 — 사용자가 고칠 수 있는 오류가 500 으로 보이지 않게")
    void duplicateKeyIsConflict() throws Exception {
        when(manageBoardUseCase.create(any())).thenThrow(new DuplicateBoardKeyException("notice"));

        mockMvc.perform(post("/admin/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("notice")));
    }

    @Test
    @DisplayName("도메인 불변식 위반은 400 이다")
    void invariantViolationIsBadRequest() throws Exception {
        when(manageBoardUseCase.create(any()))
                .thenThrow(new BoardInvariantViolationException("GALLERY 스킨은 첨부를 켜야 합니다"));

        mockMvc.perform(post("/admin/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBody())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("필수 필드 누락은 400 이고 어떤 필드인지 알려 준다")
    void validationFailure() throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(validBody());
        body.remove("name");

        mockMvc.perform(post("/admin/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    @DisplayName("없는 게시판 조회는 404 다")
    void notFound() throws Exception {
        when(queryBoardUseCase.getById(99L)).thenThrow(BoardNotFoundException.byId(99L));

        mockMvc.perform(get("/admin/boards/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("닫기·열기는 갱신된 정의를 돌려준다")
    void activation() throws Exception {
        when(manageBoardUseCase.deactivate(1L)).thenReturn(sample());
        when(manageBoardUseCase.activate(1L)).thenReturn(sample());

        mockMvc.perform(post("/admin/boards/1/deactivate")).andExpect(status().isOk());
        mockMvc.perform(post("/admin/boards/1/activate")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("삭제 성공은 204, 운영 중인 게시판 삭제는 400")
    void delete_() throws Exception {
        mockMvc.perform(delete("/admin/boards/1")).andExpect(status().isNoContent());
        verify(manageBoardUseCase).delete(1L);

        doThrow(new BoardInvariantViolationException("먼저 비활성화하세요"))
                .when(manageBoardUseCase).delete(anyLong());

        mockMvc.perform(delete("/admin/boards/2")).andExpect(status().isBadRequest());
    }
}
