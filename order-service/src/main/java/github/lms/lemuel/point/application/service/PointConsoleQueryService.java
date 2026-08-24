package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase;
import github.lms.lemuel.point.application.port.out.PointConsoleQueryPort;
import github.lms.lemuel.point.domain.PointLedgerHealth;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 포인트 운영 콘솔 조회 서비스.
 *
 * <p>판단은 셋뿐이다.
 *
 * <ol>
 *   <li><b>3자 대조를 조립한다</b> — 잔고·로트 합계·원장 누계를 {@link PointLedgerHealth} 로 묶는다.
 *       "무엇이 균형인가"는 SQL 이 아니라 도메인이 답해야 테스트할 수 있다.
 *   <li><b>"며칠 이내"를 절대 시각으로 바꾼다</b> — 상대 표현은 화면의 말이고, 저장소는 시각으로 묻는다.
 *   <li><b>상한을 클램프한다</b> — 콘솔 조회 한 번이 전 계정 로트를 끌어오면 안 된다.
 * </ol>
 *
 * <p>소멸 예정 창의 상한이 365일인 이유: 그 이상을 열면 사실상 "무기한을 제외한 전 로트"가 되어
 * "곧 사라질 것"이라는 화면의 의미가 사라진다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PointConsoleQueryService implements QueryPointConsoleUseCase {

    static final int MIN_WINDOW_DAYS = 1;
    static final int MAX_WINDOW_DAYS = 365;
    static final int MAX_LIST_LIMIT = 200;
    /** 계정 상세에 함께 싣는 최근 로트·엔트리 건수. 더 필요하면 전용 목록 조회를 붙일 자리다. */
    static final int DETAIL_HISTORY_LIMIT = 50;

    private final PointConsoleQueryPort port;

    @Override
    public PointConsoleSummary summary(int withinDays) {
        int window = clampWindow(withinDays);
        PointLedgerTotals totals = port.overallTotals();
        BigDecimal expiring = port.expiringAmount(horizon(window));
        return new PointConsoleSummary(
                totals.accountCount(),
                totals.totalBalance(),
                totals.totalActiveLotRemaining(),
                totals.totalEntryNet(),
                totals.driftedAccountCount(),
                window,
                expiring);
    }

    @Override
    public Optional<PointAccountDetail> account(Long userId) {
        // 계정이 없으면 뒤따르는 집계를 묻지 않는다 — 없는 계정 id 로 SUM 을 돌리면
        // 0 이 나와 "잔액 0 인 정상 계정"과 구분되지 않는다.
        return port.findAccount(userId).map(row -> new PointAccountDetail(
                userId,
                row.accountId(),
                row.status(),
                row.available(),
                row.locked(),
                row.total(),
                // 선점(locked)이 걸린 계정에서도 성립하는 축은 total 이다 — 선점은 로트를 건드리지
                // 않으므로 available 로 비교하면 선점액만큼 정상 계정이 드리프트로 잡힌다.
                PointLedgerHealth.of(row.total(),
                        port.activeLotRemaining(row.accountId()),
                        port.entryNet(row.accountId())),
                port.recentLots(row.accountId(), DETAIL_HISTORY_LIMIT),
                port.recentEntries(row.accountId(), DETAIL_HISTORY_LIMIT)));
    }

    @Override
    public List<PointEarnPolicyView> policies() {
        return port.policies();
    }

    @Override
    public List<ExpiringLotView> expiringLots(int withinDays, int limit) {
        return port.expiringLots(horizon(clampWindow(withinDays)), clampLimit(limit));
    }

    private static OffsetDateTime horizon(int days) {
        return OffsetDateTime.now().plusDays(days);
    }

    private static int clampWindow(int days) {
        return Math.min(Math.max(days, MIN_WINDOW_DAYS), MAX_WINDOW_DAYS);
    }

    private static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);
    }
}
