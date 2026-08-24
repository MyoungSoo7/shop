package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberExport;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberPage;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberQuery;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberStatusCount;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberSummary;
import github.lms.lemuel.user.application.port.out.SearchMembersPort;
import github.lms.lemuel.user.domain.MembershipStatus;
import github.lms.lemuel.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회원 콘솔 조회 서비스 단위 테스트.
 *
 * <p>여기서 지키는 것은 조회 조건 정규화다. 이 계층이 틀리면 운영자가 찾는 회원이 조용히
 * 목록에서 빠지고(가입일 경계 하루 어긋남), PII 한 뭉치가 한 응답에 실려 나간다(size 상한).
 */
class SearchMembersServiceTest {

    static final class RecordingPort implements SearchMembersPort {
        final List<MemberCriteria> searchCriteria = new ArrayList<>();
        final List<MemberCriteria> countCriteria = new ArrayList<>();
        final List<Integer> pages = new ArrayList<>();
        final List<Integer> sizes = new ArrayList<>();
        long total;
        List<MemberSummary> rows = List.of();
        List<MemberStatusCount> statusCounts = List.of();

        @Override
        public List<MemberSummary> search(MemberCriteria criteria, int page, int size) {
            searchCriteria.add(criteria);
            pages.add(page);
            sizes.add(size);
            return rows;
        }

        @Override
        public long count(MemberCriteria criteria) {
            countCriteria.add(criteria);
            return total;
        }

        @Override
        public List<MemberStatusCount> countByStatus(MemberCriteria criteria) {
            countCriteria.add(criteria);
            return statusCounts;
        }
    }

    private final RecordingPort port = new RecordingPort();
    private final SearchMembersService service = new SearchMembersService(port);

    private static MemberQuery query(int page, int size) {
        return new MemberQuery(null, null, null, null, null, null, page, size);
    }

    @Test
    @DisplayName("가입일을 안 주면 기간 조건 자체를 걸지 않는다 — 언제 가입했는지 모르는 회원을 찾는 화면이다")
    void noDefaultDateRange() {
        port.total = 0;

        service.search(query(0, 10));

        SearchMembersPort.MemberCriteria criteria = port.countCriteria.get(0);
        assertThat(criteria.joinedFrom()).isNull();
        assertThat(criteria.joinedToExclusive()).isNull();
    }

    @Test
    @DisplayName("가입 종료일은 그날을 포함한다 — 경계는 다음 날 00:00 미포함")
    void joinedToIsInclusiveDay() {
        port.total = 0;

        service.search(new MemberQuery(null, null, null, null,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 0, 10));

        SearchMembersPort.MemberCriteria criteria = port.countCriteria.get(0);
        assertThat(criteria.joinedFrom()).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
        assertThat(criteria.joinedToExclusive()).isEqualTo(LocalDateTime.of(2026, 4, 1, 0, 0));
    }

    @Test
    @DisplayName("뒤집힌 가입일 구간은 거부하지 않고 바로잡는다")
    void swapsInvertedRange() {
        port.total = 0;

        service.search(new MemberQuery(null, null, null, null,
                LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 1), 0, 10));

        SearchMembersPort.MemberCriteria criteria = port.countCriteria.get(0);
        assertThat(criteria.joinedFrom()).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
        assertThat(criteria.joinedToExclusive()).isEqualTo(LocalDateTime.of(2026, 4, 1, 0, 0));
    }

    @Test
    @DisplayName("size 는 상한 200 으로 잘린다 — 회원 목록은 PII 덩어리다")
    void clampsPageSize() {
        port.total = 10_000;
        port.rows = List.of(row());

        service.search(query(0, 100_000));
        assertThat(port.sizes.get(0)).isEqualTo(200);

        service.search(query(0, 0));
        assertThat(port.sizes.get(1)).isEqualTo(50);
    }

    @Test
    @DisplayName("음수 페이지는 0 으로 내린다")
    void clampsNegativePage() {
        port.total = 10;
        port.rows = List.of(row());

        service.search(query(-3, 10));

        assertThat(port.pages.get(0)).isZero();
    }

    @Test
    @DisplayName("총 인원이 0 이면 목록 쿼리를 아예 던지지 않는다")
    void skipsListQueryWhenEmpty() {
        port.total = 0;

        MemberPage result = service.search(query(0, 10));

        assertThat(port.searchCriteria).isEmpty();
        assertThat(result.content()).isEmpty();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    @DisplayName("totalPages 는 올림이다 — 101명 / 50 = 3페이지")
    void totalPagesRoundsUp() {
        port.total = 101;
        port.rows = List.of(row());

        assertThat(service.search(query(0, 50)).totalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("공백 키워드는 조건에서 빠지고, 양끝 공백은 다듬는다")
    void normalizesKeyword() {
        port.total = 0;

        service.search(new MemberQuery("   ", null, null, null, null, null, 0, 10));
        assertThat(port.countCriteria.get(0).keyword()).isNull();

        service.search(new MemberQuery("  hong  ", null, null, null, null, null, 0, 10));
        assertThat(port.countCriteria.get(1).keyword()).isEqualTo("hong");
    }

    @Test
    @DisplayName("역할·상태는 enum 이름 문자열로, active 는 3-상태(null 포함)로 넘긴다")
    void mapsEnumsAndTriStateActive() {
        port.total = 0;

        service.search(new MemberQuery(null, UserRole.MANAGER, MembershipStatus.SUSPENDED, false,
                null, null, 0, 10));

        SearchMembersPort.MemberCriteria criteria = port.countCriteria.get(0);
        assertThat(criteria.role()).isEqualTo("MANAGER");
        assertThat(criteria.membershipStatus()).isEqualTo("SUSPENDED");
        assertThat(criteria.active()).isFalse();

        service.search(query(0, 10));
        // null 은 "조건 없음"이다 — 탈퇴 회원도 함께 본다.
        assertThat(port.countCriteria.get(1).active()).isNull();
    }

    @Test
    @DisplayName("내보내기는 상한 5000 에서 끊고 잘렸다고 알린다")
    void exportTruncatesAtCap() {
        port.total = 12_345;
        port.rows = pageOf(200);

        MemberExport export = service.export(query(0, 10));

        assertThat(export.rows()).hasSize(5_000);
        assertThat(export.truncated()).isTrue();
        assertThat(export.totalElements()).isEqualTo(12_345);
    }

    @Test
    @DisplayName("상한 이하면 총 인원만큼만 담고 잘리지 않는다")
    void exportKeepsEverythingUnderCap() {
        port.total = 350;
        port.rows = pageOf(200);

        MemberExport export = service.export(query(0, 10));

        assertThat(export.rows()).hasSize(350);
        assertThat(export.truncated()).isFalse();
    }

    @Test
    @DisplayName("조회 도중 데이터가 사라져 빈 페이지가 와도 무한 루프에 빠지지 않는다")
    void exportStopsOnEmptyPage() {
        port.total = 1_000;
        port.rows = List.of();

        assertThat(service.export(query(0, 10)).rows()).isEmpty();
    }

    @Test
    @DisplayName("총 인원 0 이면 내보내기도 목록 쿼리를 던지지 않는다")
    void exportSkipsQueryWhenEmpty() {
        port.total = 0;

        assertThat(service.export(query(0, 10)).rows()).isEmpty();
        assertThat(port.searchCriteria).isEmpty();
    }

    @Test
    @DisplayName("상태별 집계도 같은 정규화를 거친다")
    void statusCountsShareNormalization() {
        port.statusCounts = List.of(new MemberStatusCount("PENDING", 4));

        List<MemberStatusCount> counts = service.countByStatus(
                new MemberQuery("  kim ", null, null, null, null, null, 0, 1));

        assertThat(counts).containsExactly(new MemberStatusCount("PENDING", 4));
        assertThat(port.countCriteria.get(0).keyword()).isEqualTo("kim");
    }

    private static List<MemberSummary> pageOf(int size) {
        List<MemberSummary> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rows.add(row());
        }
        return rows;
    }

    private static MemberSummary row() {
        return new MemberSummary(1L, "a@b.c", "홍길동", "010-0000-0000", "USER", "APPROVED", true,
                LocalDateTime.of(2026, 3, 1, 12, 0), LocalDateTime.of(2026, 3, 1, 12, 0));
    }
}
