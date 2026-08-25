package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.SearchOperatorsUseCase.OperatorExport;
import github.lms.lemuel.user.application.port.in.SearchOperatorsUseCase.OperatorPage;
import github.lms.lemuel.user.application.port.in.SearchOperatorsUseCase.OperatorQuery;
import github.lms.lemuel.user.application.port.in.SearchOperatorsUseCase.OperatorSummary;
import github.lms.lemuel.user.application.port.out.SearchOperatorsPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영자 콘솔 조회 서비스 단위 테스트.
 *
 * <p>여기서 지키는 것은 <b>대상 역할 고정</b>과 조회 조건 정규화다. 역할 고정이 뚫리면 이 화면은
 * 전 회원 로그인 상태 조회가 되고, 미사용 조건이 틀리면 가장 위험한 계정(한 번도 안 쓴 관리자
 * 계정)이 결과에서 조용히 빠진다.
 */
class SearchOperatorsServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 14, 0);
    private static final Clock FIXED =
            Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);

    static final class RecordingPort implements SearchOperatorsPort {
        final List<OperatorCriteria> searchCriteria = new ArrayList<>();
        final List<OperatorCriteria> countCriteria = new ArrayList<>();
        final List<Integer> pages = new ArrayList<>();
        final List<Integer> sizes = new ArrayList<>();
        long total;
        List<OperatorSummary> rows = List.of();

        @Override
        public List<OperatorSummary> search(OperatorCriteria criteria, int page, int size) {
            searchCriteria.add(criteria);
            pages.add(page);
            sizes.add(size);
            return rows;
        }

        @Override
        public long count(OperatorCriteria criteria) {
            countCriteria.add(criteria);
            return total;
        }
    }

    private final RecordingPort port = new RecordingPort();
    private final SearchOperatorsService service = new SearchOperatorsService(port, FIXED);

    private static OperatorQuery query(String role) {
        return new OperatorQuery(null, role, false, null, false, 0, 10);
    }

    private static OperatorSummary row(long id) {
        return new OperatorSummary(id, "op" + id + "@x.com", "운영자" + id, "ADMIN", true,
                null, 0, null, false, NOW, NOW);
    }

    @Test
    @DisplayName("역할을 안 주면 ADMIN·MANAGER 둘 다 — 이 콘솔의 대상은 요청이 정하지 않는다")
    void defaultsToOperatorRoles() {
        port.total = 0;

        service.search(query(null));

        assertThat(port.countCriteria.get(0).roles())
                .containsExactlyElementsOf(SearchOperatorsService.OPERATOR_ROLES);
    }

    @Test
    @DisplayName("USER 를 넘겨도 운영자 역할로 되돌린다 — 뚫리면 전 회원 로그인 상태 조회가 된다")
    void rejectsNonOperatorRole() {
        port.total = 0;

        service.search(query("USER"));

        assertThat(port.countCriteria.get(0).roles())
                .containsExactlyElementsOf(SearchOperatorsService.OPERATOR_ROLES);
    }

    @Test
    @DisplayName("MANAGER 로 좁히면 그 역할만 — 소문자·공백도 받는다")
    void narrowsToSingleRole() {
        port.total = 0;

        service.search(query("  manager "));

        assertThat(port.countCriteria.get(0).roles()).containsExactly("MANAGER");
    }

    @Test
    @DisplayName("idleDays 는 기준 시각에서 역산한다 — 어댑터가 시계를 다시 보지 않는다")
    void idleDaysBecomesAbsoluteInstant() {
        port.total = 0;

        service.search(new OperatorQuery(null, null, false, 90, false, 0, 10));

        SearchOperatorsPort.OperatorCriteria criteria = port.countCriteria.get(0);
        assertThat(criteria.idleBefore()).isEqualTo(NOW.minusDays(90));
        assertThat(criteria.now()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("idleDays 0·음수는 조건 미적용 — '0일 이상 미사용'은 전부라 조건이 아니다")
    void nonPositiveIdleDaysIgnored() {
        port.total = 0;

        service.search(new OperatorQuery(null, null, false, 0, false, 0, 10));
        service.search(new OperatorQuery(null, null, false, -5, false, 0, 10));

        assertThat(port.countCriteria).allSatisfy(c -> assertThat(c.idleBefore()).isNull());
    }

    @Test
    @DisplayName("목록 조회와 건수 조회가 같은 기준 시각을 본다 — 갈라지면 '잠김 3건'인데 목록엔 2건이 된다")
    void sameNowForCountAndSearch() {
        port.total = 1;
        port.rows = List.of(row(1));

        service.search(query(null));

        assertThat(port.countCriteria.get(0).now()).isEqualTo(port.searchCriteria.get(0).now());
    }

    @Test
    @DisplayName("size 상한 200 — 상한이 없으면 한 호출이 전 운영자 계정 상태를 실어 나른다")
    void capsPageSize() {
        port.total = 1;
        port.rows = List.of(row(1));

        OperatorPage page = service.search(new OperatorQuery(null, null, false, null, false, 0, 100_000));

        assertThat(page.size()).isEqualTo(SearchOperatorsService.MAX_PAGE_SIZE);
        assertThat(port.sizes.get(0)).isEqualTo(SearchOperatorsService.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("총원이 0 이면 목록 조회를 아예 하지 않는다")
    void skipsSearchWhenEmpty() {
        port.total = 0;

        OperatorPage page = service.search(query(null));

        assertThat(page.content()).isEmpty();
        assertThat(port.searchCriteria).isEmpty();
    }

    @Test
    @DisplayName("내보내기가 상한에 걸리면 잘렸다는 사실을 함께 돌려준다")
    void exportReportsTruncation() {
        port.total = SearchOperatorsService.MAX_EXPORT_ROWS + 1;
        port.rows = IntStream.range(0, SearchOperatorsService.MAX_PAGE_SIZE)
                .mapToObj(i -> row(i))
                .toList();

        OperatorExport exported = service.export(query(null));

        assertThat(exported.truncated()).isTrue();
        assertThat(exported.rows()).hasSize(SearchOperatorsService.MAX_EXPORT_ROWS);
        assertThat(exported.totalElements()).isEqualTo(SearchOperatorsService.MAX_EXPORT_ROWS + 1);
    }

    @Test
    @DisplayName("조회 도중 대상이 줄어 빈 페이지가 오면 멈춘다 — 무한 루프 방지")
    void exportStopsOnEmptyChunk() {
        port.total = 100;
        port.rows = List.of();

        OperatorExport exported = service.export(query(null));

        assertThat(exported.rows()).isEmpty();
        assertThat(exported.truncated()).isFalse();
    }
}
