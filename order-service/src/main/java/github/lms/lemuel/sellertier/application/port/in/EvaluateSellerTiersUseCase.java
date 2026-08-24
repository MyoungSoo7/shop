package github.lms.lemuel.sellertier.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 셀러 등급 재산정 (ADR 0031).
 *
 * <p>등급 하나가 수수료·정산주기·홀드백을 동시에 바꾸므로 실행 전 미리보기가 기본이다.
 */
public interface EvaluateSellerTiersUseCase {

    /**
     * @param dryRun true 면 아무 것도 저장하지 않고 "바뀔 결과"만 산출한다
     */
    TierEvaluationReport evaluate(LocalDate today, boolean dryRun, int limit);

    /** @param guarded 강등 조건이지만 유예·연속미달 미달로 내리지 않은 건 */
    record TierEvaluationReport(int evaluated, int promoted, int demoted, int held, int guarded,
                                int failed, boolean dryRun, List<TierEvaluationLine> lines) { }

    record TierEvaluationLine(Long sellerId, String fromTier, String toTier, String outcome,
                              BigDecimal netSales, String reason) { }
}
