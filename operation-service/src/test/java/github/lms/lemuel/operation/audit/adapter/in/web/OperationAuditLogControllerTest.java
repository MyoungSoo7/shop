package github.lms.lemuel.operation.audit.adapter.in.web;

import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditActionCount;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogExport;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogPage;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogQuery;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogRow;
import github.lms.lemuel.common.audit.domain.AuditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 감사 로그 조회 표면.
 *
 * <p>스프링 컨텍스트를 띄우지 않는다 — 여기서 지키려는 것은 라우팅이 아니라 <b>파라미터가
 * 유스케이스 질의로 옮겨지는 방식</b>과 CSV·헤더의 모양이다. 목을 쓰지 않고 손으로 만든
 * 스텁을 쓰는 이유는 넘어온 질의를 그대로 붙잡아 두고 여러 번 들여다보기 위해서다.
 */
class OperationAuditLogControllerTest {

    private StubSearch search;
    private OperationAuditLogController controller;

    @BeforeEach
    void setUp() {
        search = new StubSearch();
        controller = new OperationAuditLogController(search);
    }

    @Test
    @DisplayName("검색 파라미터가 그대로 질의로 넘어간다")
    void searchPassesFilters() {
        controller.search("admin@x.com", 42L, "BOARD_DELETED", "Board", "7",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 25), 2, 20);

        AuditLogQuery q = search.lastQuery;
        assertThat(q.actorEmail()).isEqualTo("admin@x.com");
        assertThat(q.actorId()).isEqualTo(42L);
        assertThat(q.action()).isEqualTo(AuditAction.BOARD_DELETED);
        assertThat(q.resourceType()).isEqualTo("Board");
        assertThat(q.resourceId()).isEqualTo("7");
        assertThat(q.from()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(q.to()).isEqualTo(LocalDate.of(2026, 8, 25));
        assertThat(q.page()).isEqualTo(2);
        assertThat(q.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("모르는 액션 이름은 400 이 아니라 '필터 미적용'으로 흘린다")
    void unknownActionFallsBackToNoFilter() {
        // 화면이 옛 액션 이름을 캐시한 순간 감사 조회 전체가 막히는 편이 더 나쁘다.
        controller.search(null, null, "BOARD_TELEPORTED", null, null, null, null, 0, 50);

        assertThat(search.lastQuery.action()).isNull();
    }

    @Test
    @DisplayName("빈 문자열 액션도 필터로 취급하지 않는다")
    void blankActionIsNotAFilter() {
        // 화면의 '전체' 선택은 빈 값으로 온다. 이걸 파싱하려 들면 매번 예외 경로를 탄다.
        controller.search(null, null, "   ", null, null, null, null, 0, 50);

        assertThat(search.lastQuery.action()).isNull();
    }

    @Test
    @DisplayName("액션 이름은 대소문자를 가리지 않는다")
    void actionParsingIsCaseInsensitive() {
        controller.search(null, null, "board_created", null, null, null, null, 0, 50);

        assertThat(search.lastQuery.action()).isEqualTo(AuditAction.BOARD_CREATED);
    }

    @Test
    @DisplayName("액션 목록은 이 서비스가 실제로 남기는 것만, 정렬해서 준다")
    void actionsAreScopedToThisService() {
        List<String> actions = controller.actions().getBody();

        assertThat(actions).containsExactly(
                "BOARD_ACTIVATED", "BOARD_CREATED", "BOARD_DEACTIVATED", "BOARD_DELETED", "BOARD_UPDATED");
        // 공용 enum 전체를 흘리면 운영자가 고른 값 대부분이 영원히 0건이 된다.
        assertThat(actions).hasSizeLessThan(AuditAction.values().length);
    }

    @Test
    @DisplayName("액션별 건수는 목록과 같은 조건으로 묻는다")
    void actionCountsReuseTheSameFilters() {
        controller.actionCounts("admin@x.com", null, "BOARD_UPDATED", "Board", null,
                LocalDate.of(2026, 8, 1), null);

        assertThat(search.lastQuery.actorEmail()).isEqualTo("admin@x.com");
        assertThat(search.lastQuery.action()).isEqualTo(AuditAction.BOARD_UPDATED);
        assertThat(search.lastQuery.from()).isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    @DisplayName("CSV 는 헤더 순서대로 값을 싣고 null 은 빈 칸이 된다")
    void exportWritesRowsInHeaderOrder() {
        search.exportResult = new AuditLogExport(List.of(new AuditLogRow(
                1L, 42L, "admin@x.com", "BOARD_DELETED", "Board", "7",
                "{\"boardId\":7}", "10.0.0.1", null,
                LocalDateTime.of(2026, 8, 25, 9, 30))), false, 1);

        String csv = body(controller.export(null, null, null, null, null, null, null));

        assertThat(csv).contains("\"일시\",\"행위자ID\",\"행위자\",\"액션\"");
        assertThat(csv).contains("\"2026-08-25T09:30\",\"42\",\"admin@x.com\",\"BOARD_DELETED\","
                + "\"Board\",\"7\",\"10.0.0.1\",\"{\"\"boardId\"\":7}\"");
    }

    @Test
    @DisplayName("잘린 내보내기는 본문이 아니라 헤더로 알린다")
    void exportReportsTruncationInHeaders() {
        search.exportResult = new AuditLogExport(List.of(), true, 120_000);

        ResponseEntity<ByteArrayResource> response =
                controller.export(null, null, null, null, null, null, null);

        assertThat(response.getHeaders().getFirst("X-Export-Truncated")).isEqualTo("true");
        assertThat(response.getHeaders().getFirst("X-Export-Total")).isEqualTo("120000");
        // 경고를 본문 행으로 끼우면 그 행이 데이터로 읽혀 집계를 오염시킨다.
        assertThat(body(response)).doesNotContain("truncated");
    }

    @Test
    @DisplayName("내보내기도 화면과 같은 조건을 쓴다")
    void exportReusesTheSameFilters() {
        controller.export(null, 42L, "BOARD_CREATED", null, null, null, LocalDate.of(2026, 8, 25));

        assertThat(search.lastQuery.actorId()).isEqualTo(42L);
        assertThat(search.lastQuery.action()).isEqualTo(AuditAction.BOARD_CREATED);
        assertThat(search.lastQuery.to()).isEqualTo(LocalDate.of(2026, 8, 25));
    }

    private static String body(ResponseEntity<ByteArrayResource> response) {
        return new String(response.getBody().getByteArray(), StandardCharsets.UTF_8);
    }

    /** 넘어온 질의를 붙잡아 두는 스텁. */
    private static final class StubSearch implements SearchAuditLogsUseCase {

        private AuditLogQuery lastQuery;
        private AuditLogExport exportResult = new AuditLogExport(new ArrayList<>(), false, 0);

        @Override
        public AuditLogPage search(AuditLogQuery query) {
            this.lastQuery = query;
            return new AuditLogPage(List.of(), query.page(), query.size(), 0, 0);
        }

        @Override
        public List<AuditActionCount> countByAction(AuditLogQuery query) {
            this.lastQuery = query;
            return List.of();
        }

        @Override
        public AuditLogExport export(AuditLogQuery query) {
            this.lastQuery = query;
            return exportResult;
        }
    }
}
