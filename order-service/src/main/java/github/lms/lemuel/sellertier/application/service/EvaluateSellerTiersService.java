package github.lms.lemuel.sellertier.application.service;

import github.lms.lemuel.sellertier.application.port.in.EvaluateSellerTiersUseCase;
import github.lms.lemuel.sellertier.application.port.out.LoadSellerNetSalesPort;
import github.lms.lemuel.sellertier.application.port.out.LoadSellerNetSalesPort.SellerNetSales;
import github.lms.lemuel.sellertier.application.port.out.LoadTierAssignmentPort;
import github.lms.lemuel.sellertier.domain.SellerTierGrade;
import github.lms.lemuel.sellertier.domain.SellerTierPolicy;
import github.lms.lemuel.sellertier.domain.TierAssignment;
import github.lms.lemuel.sellertier.domain.TierDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 셀러 등급 재산정 (ADR 0031).
 *
 * <p><b>트랜잭션을 배치 전체에 걸지 않는다(의도)</b> — 한 셀러의 실패로 이미 반영된 수백 건을 되돌리면
 * 운영자는 어디까지 됐는지 모른다. 등급은 셀러마다 독립적인 사실이므로 건별로 확정하고
 * ({@link SellerTierChangeProcessor} 가 셀러별 독립 트랜잭션을 연다), 실패는 카운터로 드러내
 * 다음 주기가 다시 집게 한다.
 *
 * <p>판정은 도메인이 한다({@code SellerTierPolicy}·{@code TierAssignment}). 여기서는 그 결과를
 * 집행하고 집계할 뿐이라, 미리보기와 실행이 같은 규칙을 본다.
 */
public class EvaluateSellerTiersService implements EvaluateSellerTiersUseCase {

    private static final Logger log = LoggerFactory.getLogger(EvaluateSellerTiersService.class);

    private final LoadSellerNetSalesPort netSalesPort;
    private final LoadTierAssignmentPort loadPort;
    private final SellerTierChangeProcessor processor;
    private final SellerTierPolicy policy;
    private final int missThreshold;

    public EvaluateSellerTiersService(LoadSellerNetSalesPort netSalesPort, LoadTierAssignmentPort loadPort,
                                      SellerTierChangeProcessor processor,
                                      SellerTierPolicy policy, int missThreshold) {
        this.netSalesPort = netSalesPort;
        this.loadPort = loadPort;
        this.processor = processor;
        this.policy = policy;
        this.missThreshold = missThreshold;
    }

    @Override
    public TierEvaluationReport evaluate(LocalDate today, boolean dryRun, int limit) {
        List<SellerNetSales> sellers = netSalesPort.findNetSalesForLast12Months(today, limit);

        List<TierEvaluationLine> lines = new ArrayList<>(sellers.size());
        int promoted = 0, demoted = 0, held = 0, guarded = 0, failed = 0;

        for (SellerNetSales seller : sellers) {
            try {
                TierAssignment assignment = loadPort.findBySellerId(seller.sellerId())
                        // 처음 평가되는 셀러는 NORMAL 에서 시작한다 — 상위 등급으로 태어나지 않는다.
                        .orElseGet(() -> TierAssignment.initial(
                                seller.sellerId(), SellerTierGrade.NORMAL, today));

                SellerTierGrade target = policy.tierFor(seller.net12m());
                SellerTierGrade from = assignment.getTier();
                TierDecision decision = assignment.decide(target, today, missThreshold);

                if (!dryRun) {
                    processor.apply(assignment, decision, seller.net12m(), today);
                }
                lines.add(new TierEvaluationLine(seller.sellerId(), from.name(),
                        decision.targetTier().name(), decision.outcome().name(),
                        seller.net12m(), decision.reason()));

                switch (decision.outcome()) {
                    case PROMOTED -> promoted++;
                    case DEMOTED -> demoted++;
                    case GUARDED -> guarded++;
                    case HELD -> held++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("등급 평가 실패 — 다음 주기에 재시도: sellerId={}, 사유={}",
                        seller.sellerId(), e.toString());
            }
        }
        TierEvaluationReport report = new TierEvaluationReport(sellers.size(), promoted, demoted,
                held, guarded, failed, dryRun, List.copyOf(lines));
        if (!sellers.isEmpty()) {
            log.info("등급 재산정{}: 평가={}, 승급={}, 강등={}, 유지={}, 보류={}, 실패={}",
                    dryRun ? "(dryRun)" : "", report.evaluated(), promoted, demoted, held, guarded, failed);
        }
        return report;
    }
}
