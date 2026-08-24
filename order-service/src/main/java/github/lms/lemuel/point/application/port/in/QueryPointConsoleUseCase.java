package github.lms.lemuel.point.application.port.in;

import github.lms.lemuel.point.domain.PointLedgerHealth;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 포인트 운영 콘솔 조회 유스케이스 — 지급·소멸을 <b>누르기 전에</b> 봐야 할 것들.
 *
 * <p>기존 콘솔은 쓰기 두 개(수기 지급·소멸 실행)만 있었다. 그래서 운영자는 "이 계정이 지금 얼마이고
 * 왜 그런가"를 모르는 채로 되돌리기 어려운 조작을 눌러야 했다. 여기서 답하는 것은 넷이다.
 *
 * <ol>
 *   <li><b>전체 장부가 성립하는가</b> — {@link #summary()}: 잔고 총액 · 로트 합계 · 원장 누계 3자 대조
 *   <li><b>이 사람 잔액이 왜 이런가</b> — {@link #account(Long)}: 계정 + 3자 대조 + 로트·원장 내역
 *   <li><b>지금 적립률이 얼마인가</b> — {@link #policies()}: 유효기간을 가진 정책 이력
 *   <li><b>곧 무엇이 사라지는가</b> — {@link #expiringLots(int, int)}: 소멸 예정 로트
 * </ol>
 */
public interface QueryPointConsoleUseCase {

    /** 전체 요약. 소멸 예정 금액은 {@code withinDays} 일 안에 만료되는 ACTIVE 로트 잔액 합이다. */
    PointConsoleSummary summary(int withinDays);

    /** 계정 상세. 포인트를 한 번도 쓴 적 없는 사용자는 계정 자체가 없으므로 비어 있을 수 있다. */
    Optional<PointAccountDetail> account(Long userId);

    /** 적립률 정책 이력 — 종료된 행도 함께 준다(왜 그때 그 요율이었는지 설명해야 하므로). */
    List<PointEarnPolicyView> policies();

    /** 소멸 예정 로트 — 만료 임박 순. */
    List<ExpiringLotView> expiringLots(int withinDays, int limit);

    /**
     * @param driftedAccountCount 잔고와 로트 합계가 어긋난 계정 수. <b>0 이 아니면 조사 대상</b>이다.
     * @param expiringAmount      기간 내 소멸 예정 금액 — 고객에게 사라질 재산의 규모
     */
    record PointConsoleSummary(long accountCount,
                               BigDecimal totalBalance,
                               BigDecimal totalActiveLotRemaining,
                               BigDecimal totalEntryNet,
                               long driftedAccountCount,
                               int expiringWithinDays,
                               BigDecimal expiringAmount) {
    }

    /** @param health 계정 요약·로트 상세·원장 누계의 3자 대조 결과 */
    record PointAccountDetail(Long userId,
                              Long accountId,
                              String status,
                              BigDecimal available,
                              BigDecimal locked,
                              BigDecimal total,
                              PointLedgerHealth health,
                              List<PointLotView> lots,
                              List<PointEntryView> entries) {
    }

    record PointLotView(Long lotId,
                        String origin,
                        BigDecimal originalAmount,
                        BigDecimal remainingAmount,
                        String status,
                        OffsetDateTime grantedAt,
                        OffsetDateTime expiresAt,
                        String referenceType,
                        String referenceId) {
    }

    record PointEntryView(Long entryId,
                          String entryType,
                          BigDecimal amount,
                          String referenceType,
                          String referenceId,
                          String memo,
                          String createdBy,
                          OffsetDateTime createdAt) {
    }

    /**
     * @param active   오늘 기준으로 적용 중인 행인지 — <b>날짜 범위만</b>으로 판정한다
     *                 ({@code effective_from <= 오늘 < effective_to})
     * @param closedAt 운영자가 종료를 지정한 시각. <b>적용 여부가 아니다</b> — 종료일이 미래면
     *                 그날까지는 {@code active} 가 계속 참이다. "언제 누가 끊었나"의 감사 기록이다
     */
    record PointEarnPolicyView(Long id,
                               String scope,
                               String scopeKey,
                               BigDecimal earnRate,
                               int validityDays,
                               LocalDate effectiveFrom,
                               LocalDate effectiveTo,
                               String reason,
                               String createdBy,
                               boolean active,
                               OffsetDateTime closedAt) {
    }

    record ExpiringLotView(Long userId,
                           Long lotId,
                           String origin,
                           BigDecimal remainingAmount,
                           OffsetDateTime expiresAt) {
    }

    /** 계정 1행의 잔고 3필드 — 포트가 돌려주는 원시 값. */
    record PointAccountRow(Long accountId, String status,
                           BigDecimal available, BigDecimal locked, BigDecimal total) {
    }

    /** 전체 집계 원시 값 — 3자 대조와 드리프트 계정 수. */
    record PointLedgerTotals(long accountCount,
                             BigDecimal totalBalance,
                             BigDecimal totalActiveLotRemaining,
                             BigDecimal totalEntryNet,
                             long driftedAccountCount) {
    }
}
