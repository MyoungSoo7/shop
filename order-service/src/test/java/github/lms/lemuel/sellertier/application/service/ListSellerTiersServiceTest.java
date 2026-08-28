package github.lms.lemuel.sellertier.application.service;

import github.lms.lemuel.sellertier.application.port.in.ListSellerTiersUseCase.SellerTierRoster;
import github.lms.lemuel.sellertier.application.port.in.ListSellerTiersUseCase.SellerTierRow;
import github.lms.lemuel.sellertier.application.port.out.LoadSellerTierRosterPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 셀러 명부의 계약 (ADR 0031).
 *
 * <p>이 유스케이스가 지키는 것은 두 가지다. 첫째, <b>아무 것도 판정하지 않는다</b> — 저장된 값을
 * 그대로 옮긴다. 명부가 재산정을 흉내 내면 화면의 등급과 실제 적용 등급이 갈라진다.
 * 둘째, <b>불일치 판정이 정합 검사와 같다</b> — 두 수치가 어긋나면 관리자는 어느 쪽도 믿을 수 없다.
 */
class ListSellerTiersServiceTest {

    /** 조회 인자를 그대로 기록하는 fake — 상한이 실제로 어댑터까지 전달되는지 보려면 필요하다. */
    private static final class FakeRosterPort implements LoadSellerTierRosterPort {
        private final List<RawSellerRow> rows;
        private final long total;
        private final List<Integer> requestedLimits = new ArrayList<>();
        private LocalDate requestedDate;

        FakeRosterPort(List<RawSellerRow> rows, long total) {
            this.rows = rows;
            this.total = total;
        }

        @Override
        public List<RawSellerRow> findRoster(LocalDate today, int limit) {
            requestedDate = today;
            requestedLimits.add(limit);
            return rows.size() > limit ? rows.subList(0, limit) : rows;
        }

        @Override
        public long countSellers() {
            return total;
        }
    }

    private static LoadSellerTierRosterPort.RawSellerRow raw(long id, String tier, String cached) {
        return new LoadSellerTierRosterPort.RawSellerRow(id, "s" + id + "@lemuel.co.kr", "셀러" + id,
                tier, cached, LocalDate.of(2026, 8, 1), null, 0, new BigDecimal("1000"), 3);
    }

    @Test
    @DisplayName("저장된 값을 그대로 옮긴다 — 명부는 등급을 다시 판정하지 않는다")
    void carriesStoredValuesThrough() {
        FakeRosterPort port = new FakeRosterPort(List.of(raw(13L, "VIP", "VIP")), 1L);

        SellerTierRow row = new ListSellerTiersService(port).list(LocalDate.of(2026, 8, 29), 50).rows().get(0);

        assertThat(row.sellerId()).isEqualTo(13L);
        assertThat(row.email()).isEqualTo("s13@lemuel.co.kr");
        assertThat(row.tier()).isEqualTo("VIP");
        assertThat(row.cachedTier()).isEqualTo("VIP");
        assertThat(row.netSales12m()).isEqualByComparingTo("1000");
        assertThat(row.productCount()).isEqualTo(3);
        assertThat(port.requestedDate).isEqualTo(LocalDate.of(2026, 8, 29));
    }

    @Test
    @DisplayName("아직 산정되지 않은 셀러는 등급 없음으로 나오되 불일치는 아니다")
    void unevaluatedSellerIsListedButNotFlagged() {
        // 여기가 운영에서 실제로 터진 지점이다. users.seller_tier 는 NOT NULL DEFAULT 'NORMAL' 이라
        // 한 번도 산정되지 않은 계정도 값을 갖는다. 이걸 불일치로 세면 명부가 통째로 붉어지고,
        // 그 순간 진짜 불일치는 묻힌다 — 정합 검사가 거짓 13건을 낸 것과 같은 원인이다.
        FakeRosterPort port = new FakeRosterPort(List.of(raw(21L, null, "NORMAL")), 1L);

        SellerTierRow row = new ListSellerTiersService(port).list(LocalDate.now(), 50).rows().get(0);

        assertThat(row.tier()).isNull();
        assertThat(row.cachedTier()).isEqualTo("NORMAL");
        assertThat(row.mismatched()).isFalse();
    }

    @Test
    @DisplayName("정본과 캐시가 실제로 어긋난 행만 표시된다")
    void onlyRealDriftIsFlagged() {
        assertThat(ListSellerTiersService.mismatched("VIP", "NORMAL")).as("캐시가 낡음").isTrue();
        assertThat(ListSellerTiersService.mismatched("VIP", null)).as("캐시 없음").isTrue();
        // 정본이 없는데 캐시가 기본값이 아니면 누군가 손으로 바꾼 것이다 — 이건 계속 잡아야 한다.
        assertThat(ListSellerTiersService.mismatched(null, "VIP")).as("정본 없는 수기 캐시").isTrue();
        assertThat(ListSellerTiersService.mismatched("VIP", "VIP")).isFalse();
        assertThat(ListSellerTiersService.mismatched(null, "NORMAL")).isFalse();
        assertThat(ListSellerTiersService.mismatched(null, null)).isFalse();
    }

    @Test
    @DisplayName("상한에 잘려도 전체 규모는 정확히 보고한다")
    void reportsTotalEvenWhenTruncated() {
        // 잘린 사실을 숨기면 화면은 "셀러가 이게 전부"라고 읽는다. 없는 셀러를 찾는 것보다
        // 있는 셀러가 안 보이는 쪽이 훨씬 오래 안 들킨다.
        FakeRosterPort port = new FakeRosterPort(
                List.of(raw(1L, "VIP", "VIP"), raw(2L, "NORMAL", "NORMAL")), 57L);

        SellerTierRoster roster = new ListSellerTiersService(port).list(LocalDate.now(), 2);

        assertThat(roster.rows()).hasSize(2);
        assertThat(roster.total()).isEqualTo(57L);
        assertThat(roster.truncated()).isTrue();
    }

    @Test
    @DisplayName("전부 담겼으면 잘렸다고 말하지 않는다 — 상한과 셀러 수가 같을 때가 함정이다")
    void doesNotClaimTruncationWhenEverythingFits() {
        FakeRosterPort port = new FakeRosterPort(
                List.of(raw(1L, "VIP", "VIP"), raw(2L, "NORMAL", "NORMAL")), 2L);

        assertThat(new ListSellerTiersService(port).list(LocalDate.now(), 2).truncated()).isFalse();
    }

    @Test
    @DisplayName("상한은 방어값 안으로 강제된다 — 0·음수·과대 요청이 그대로 DB 로 가지 않게")
    void limitIsClamped() {
        FakeRosterPort port = new FakeRosterPort(List.of(), 0L);
        ListSellerTiersService service = new ListSellerTiersService(port);

        service.list(LocalDate.now(), 0);
        service.list(LocalDate.now(), -5);
        service.list(LocalDate.now(), Integer.MAX_VALUE);

        assertThat(port.requestedLimits).containsExactly(1, 1, ListSellerTiersService.MAX_LIMIT);
    }
}
