package github.lms.lemuel.point.application.port.out;

import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.ExpiringLotView;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointAccountRow;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointEarnPolicyView;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointEntryView;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointLedgerTotals;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointLotView;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 포인트 콘솔 조회 포트 — <b>원시 집계만</b> 돌려준다.
 *
 * <p>3자 대조 판정({@code PointLedgerHealth})을 어댑터가 아니라 서비스가 조립하는 이유:
 * "무엇이 균형인가"는 SQL 이 아니라 도메인의 규칙이고, 규칙이 SQL 안에 들어가면 테스트할 수 없다.
 * 여기서는 세 축의 <b>숫자</b>만 가져온다.
 */
public interface PointConsoleQueryPort {

    Optional<PointAccountRow> findAccount(Long userId);

    /** ACTIVE 로트의 잔액 합. 로트가 없으면 0. */
    BigDecimal activeLotRemaining(Long accountId);

    /** 원장 누계 = Σ(GRANT+RESTORE) − Σ(USE+EXPIRE+REVOKE). 엔트리가 없으면 0. */
    BigDecimal entryNet(Long accountId);

    /** 최근 로트 — 발급 최신순. */
    List<PointLotView> recentLots(Long accountId, int limit);

    /** 최근 원장 엔트리 — 기록 최신순. */
    List<PointEntryView> recentEntries(Long accountId, int limit);

    /** 적립률 정책 전체(종료 행 포함). */
    List<PointEarnPolicyView> policies();

    /** {@code until} 이전에 만료되는 ACTIVE 로트 — 만료 임박 순. */
    List<ExpiringLotView> expiringLots(OffsetDateTime until, int limit);

    /** 기간 내 소멸 예정 금액 합. */
    BigDecimal expiringAmount(OffsetDateTime until);

    /** 전체 3자 대조 집계와 드리프트 계정 수. */
    PointLedgerTotals overallTotals();
}
