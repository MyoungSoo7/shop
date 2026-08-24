package github.lms.lemuel.common.audit.application.service;

import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditActionCount;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogPage;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogQuery;
import github.lms.lemuel.common.audit.application.port.in.SearchAuditLogsUseCase.AuditLogRow;
import github.lms.lemuel.common.audit.application.port.out.SearchAuditLogsPort;
import github.lms.lemuel.common.audit.domain.AuditAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 감사 로그 조회 서비스 단위 테스트.
 *
 * <p>여기서 지키는 것은 <b>조회 조건 정규화</b> 하나다. 조회 자체는 어댑터의 일이고, 이 계층이
 * 틀리면 파티션 프루닝이 깨져(기간 누락) 운영 DB 를 전수 스캔하거나, 종료일이 하루 어긋나
 * "오늘 한 조작이 안 보이는" 사고가 난다.
 */
class SearchAuditLogsServiceTest {

    /**
     * 조건을 받아 적는 가짜 포트. 모킹 대신 쓰는 이유는 검증 대상이 "무엇을 넘겼는가"이기
     * 때문이다 — 넘어온 조건을 그대로 붙잡아 두는 편이 읽기 쉽다.
     */
    static final class RecordingPort implements SearchAuditLogsPort {
        final List<AuditLogCriteria> searchCriteria = new ArrayList<>();
        final List<AuditLogCriteria> countCriteria = new ArrayList<>();
        final List<Integer> pages = new ArrayList<>();
        final List<Integer> sizes = new ArrayList<>();
        long total;
        List<AuditLogRow> rows = List.of();
        List<AuditActionCount> actionCounts = List.of();

        @Override
        public List<AuditLogRow> search(AuditLogCriteria criteria, int page, int size) {
            searchCriteria.add(criteria);
            pages.add(page);
            sizes.add(size);
            return rows;
        }

        @Override
        public long count(AuditLogCriteria criteria) {
            countCriteria.add(criteria);
            return total;
        }

        @Override
        public List<AuditActionCount> countByAction(AuditLogCriteria criteria) {
            countCriteria.add(criteria);
            return actionCounts;
        }
    }

    private final RecordingPort port = new RecordingPort();
    private final SearchAuditLogsService service = new SearchAuditLogsService(port);

    private static AuditLogQuery query(LocalDate from, LocalDate to, int page, int size) {
        return new AuditLogQuery(null, null, null, null, null, from, to, page, size);
    }

    @Test
    @DisplayName("기간을 안 주면 최근 30일로 채운다 — '전체 조회'는 선택지가 아니다(파티션 프루닝)")
    void fillsDefaultRangeWhenAbsent() {
        port.total = 1;
        port.rows = List.of(row());

        service.search(query(null, null, 0, 10));

        SearchAuditLogsPort.AuditLogCriteria criteria = port.countCriteria.get(0);
        LocalDate today = LocalDate.now();
        assertThat(criteria.from()).isEqualTo(today.minusDays(30).atStartOfDay());
        assertThat(criteria.toExclusive()).isEqualTo(today.plusDays(1).atStartOfDay());
    }

    @Test
    @DisplayName("종료일은 그날을 포함한다 — 경계는 다음 날 00:00 미포함")
    void endDateIsInclusiveDay() {
        port.total = 0;

        service.search(query(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 0, 10));

        SearchAuditLogsPort.AuditLogCriteria criteria = port.countCriteria.get(0);
        assertThat(criteria.from()).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
        assertThat(criteria.toExclusive()).isEqualTo(LocalDateTime.of(2026, 4, 1, 0, 0));
    }

    @Test
    @DisplayName("기간이 뒤집혀 오면 거부하지 않고 바로잡는다")
    void swapsInvertedRange() {
        port.total = 0;

        service.search(query(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 1), 0, 10));

        SearchAuditLogsPort.AuditLogCriteria criteria = port.countCriteria.get(0);
        assertThat(criteria.from()).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
        assertThat(criteria.toExclusive()).isEqualTo(LocalDateTime.of(2026, 4, 1, 0, 0));
    }

    @Test
    @DisplayName("size 는 상한 200 으로 잘리고, 0 이하는 기본 50 이 된다")
    void clampsPageSize() {
        port.total = 10_000;
        port.rows = List.of(row());

        service.search(query(null, null, 0, 5_000));
        assertThat(port.sizes.get(0)).isEqualTo(200);

        service.search(query(null, null, 0, 0));
        assertThat(port.sizes.get(1)).isEqualTo(50);

        service.search(query(null, null, 0, -3));
        assertThat(port.sizes.get(2)).isEqualTo(50);
    }

    @Test
    @DisplayName("음수 페이지는 0 으로 내린다")
    void clampsNegativePage() {
        port.total = 10;
        port.rows = List.of(row());

        service.search(query(null, null, -5, 10));

        assertThat(port.pages.get(0)).isZero();
    }

    @Test
    @DisplayName("총 건수가 0 이면 목록 쿼리를 아예 던지지 않는다")
    void skipsListQueryWhenEmpty() {
        port.total = 0;

        AuditLogPage result = service.search(query(null, null, 0, 10));

        assertThat(port.searchCriteria).isEmpty();
        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    @DisplayName("totalPages 는 올림이다 — 101건 / 50 = 3페이지")
    void totalPagesRoundsUp() {
        port.total = 101;
        port.rows = List.of(row());

        AuditLogPage result = service.search(query(null, null, 0, 50));

        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.totalElements()).isEqualTo(101);
        assertThat(result.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("공백 문자열 필터는 조건에서 빠지고, 양끝 공백은 다듬는다")
    void normalizesBlankFilters() {
        port.total = 0;

        service.search(new AuditLogQuery("   ", null, null, "", "  ORDER-1  ",
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1), 0, 10));

        SearchAuditLogsPort.AuditLogCriteria criteria = port.countCriteria.get(0);
        assertThat(criteria.actorEmail()).isNull();
        assertThat(criteria.resourceType()).isNull();
        assertThat(criteria.resourceId()).isEqualTo("ORDER-1");
    }

    @Test
    @DisplayName("action 은 enum 이름 문자열로 넘긴다 — 미지정이면 조건에서 빠진다")
    void mapsActionToName() {
        port.total = 0;

        service.search(new AuditLogQuery(null, null, AuditAction.USER_ROLE_CHANGED, null, null,
                null, null, 0, 10));
        assertThat(port.countCriteria.get(0).action()).isEqualTo("USER_ROLE_CHANGED");

        service.search(query(null, null, 0, 10));
        assertThat(port.countCriteria.get(1).action()).isNull();
    }

    @Test
    @DisplayName("액션별 집계도 같은 정규화를 거친다")
    void countByActionSharesNormalization() {
        port.actionCounts = List.of(new AuditActionCount("LOGIN_FAILED", 7));

        List<AuditActionCount> counts = service.countByAction(query(null, null, 0, 10));

        assertThat(counts).containsExactly(new AuditActionCount("LOGIN_FAILED", 7));
        LocalDate today = LocalDate.now();
        assertThat(port.countCriteria.get(0).from()).isEqualTo(today.minusDays(30).atStartOfDay());
    }

    @Test
    @DisplayName("내보내기는 상한 5000 에서 끊고 잘렸다고 알린다 — 잘린 줄 모르는 CSV 가 가장 나쁘다")
    void exportTruncatesAtCapAndSaysSo() {
        port.total = 12_345;
        port.rows = pageOf(200);

        var export = service.export(query(null, null, 0, 10));

        assertThat(export.rows()).hasSize(5_000);
        assertThat(export.truncated()).isTrue();
        assertThat(export.totalElements()).isEqualTo(12_345);
        assertThat(port.sizes).allMatch(size -> size == 200);
    }

    @Test
    @DisplayName("상한 이하면 잘리지 않고 총 건수만큼만 담는다")
    void exportKeepsEverythingUnderCap() {
        port.total = 350;
        port.rows = pageOf(200);

        var export = service.export(query(null, null, 0, 10));

        assertThat(export.rows()).hasSize(350);
        assertThat(export.truncated()).isFalse();
    }

    @Test
    @DisplayName("조회 도중 데이터가 사라져 빈 페이지가 와도 무한 루프에 빠지지 않는다")
    void exportStopsOnEmptyPage() {
        port.total = 1_000;
        port.rows = List.of();

        var export = service.export(query(null, null, 0, 10));

        assertThat(export.rows()).isEmpty();
    }

    @Test
    @DisplayName("총 건수가 0 이면 목록 쿼리를 던지지 않는다")
    void exportSkipsQueryWhenEmpty() {
        port.total = 0;

        var export = service.export(query(null, null, 0, 10));

        assertThat(port.searchCriteria).isEmpty();
        assertThat(export.rows()).isEmpty();
        assertThat(export.truncated()).isFalse();
    }

    private static List<AuditLogRow> pageOf(int size) {
        List<AuditLogRow> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rows.add(row());
        }
        return rows;
    }

    private static AuditLogRow row() {
        return new AuditLogRow(1L, 9L, "admin@lemuel.io", "LOGIN_SUCCESS", "USER", "9",
                "{}", "127.0.0.1", "junit", LocalDateTime.of(2026, 3, 1, 12, 0));
    }
}
