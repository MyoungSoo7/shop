package github.lms.lemuel.sellertier.application.service;

import github.lms.lemuel.sellertier.application.port.in.ListSellerTiersUseCase;
import github.lms.lemuel.sellertier.application.port.out.LoadSellerTierRosterPort;
import github.lms.lemuel.sellertier.application.port.out.LoadSellerTierRosterPort.RawSellerRow;
import github.lms.lemuel.sellertier.domain.SellerTierGrade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 셀러 등급 명부 (ADR 0031).
 *
 * <p>읽기 전용이다. 아무 것도 판정하지 않고 저장된 값을 그대로 옮긴다 — 명부가 재산정 결과를
 * 흉내 내면 "보이는 등급"과 "적용된 등급"이 갈라져 지금 고치려는 문제가 반대 방향으로 다시 생긴다.
 *
 * <p>이 계층이 더하는 것은 {@code mismatched} 하나다. 정본↔캐시 불일치는 정합 검사가 총계로
 * 알려주지만 <b>어느 셀러인지</b>는 표본에만 나온다. 명부 행에 같이 실어 두면 관리자가 검사 결과와
 * 명부를 번갈아 보지 않고 그 자리에서 대상에 도달한다.
 */
public class ListSellerTiersService implements ListSellerTiersUseCase {

    /** 상한 없이 부르는 호출을 막기 위한 방어값. 셀러 수가 이보다 커지면 화면이 페이지를 넘겨야 한다. */
    static final int MAX_LIMIT = 1000;

    private final LoadSellerTierRosterPort rosterPort;

    public ListSellerTiersService(LoadSellerTierRosterPort rosterPort) {
        this.rosterPort = rosterPort;
    }

    @Override
    public SellerTierRoster list(LocalDate today, int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);

        List<SellerTierRow> rows = rosterPort.findRoster(today, capped).stream()
                .map(ListSellerTiersService::toRow)
                .toList();
        long total = rosterPort.countSellers();

        // 잘렸는지는 total 과 비교해서 판정한다. rows.size() == capped 만 보면 셀러 수가 상한과
        // 정확히 같을 때 "더 있다"고 잘못 말한다.
        return new SellerTierRoster(rows, total, total > rows.size());
    }

    private static SellerTierRow toRow(RawSellerRow raw) {
        return new SellerTierRow(
                raw.sellerId(), raw.email(), raw.name(),
                raw.tier(), raw.cachedTier(),
                raw.effectiveFrom(), raw.demotionGuardUntil(),
                raw.consecutiveMissCount(),
                raw.netSales12m() == null ? BigDecimal.ZERO : raw.netSales12m(),
                raw.productCount(),
                mismatched(raw.tier(), raw.cachedTier()));
    }

    /**
     * 정합 검사({@code SellerTierPersistenceAdapter.DRIFT_FROM})와 <b>같은 판정</b>이어야 한다.
     * 여기서 한 건 더 붉게 칠하면 명부와 검사 총계가 어긋나 관리자는 어느 쪽을 믿을지 알 수 없게 된다.
     *
     * <p>그래서 "정본 없음 + 캐시가 기본값" 은 불일치가 아니다 — {@code users.seller_tier} 는
     * {@code NOT NULL DEFAULT 'NORMAL'} 이라 아직 산정되지 않은 셀러도 값을 갖고 있을 뿐이다.
     */
    static boolean mismatched(String tier, String cachedTier) {
        if (Objects.equals(tier, cachedTier)) {
            return false;
        }
        return !(tier == null && SellerTierGrade.NORMAL.name().equals(cachedTier));
    }
}
