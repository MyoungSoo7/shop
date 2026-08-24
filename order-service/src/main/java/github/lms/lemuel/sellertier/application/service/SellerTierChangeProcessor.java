package github.lms.lemuel.sellertier.application.service;

import github.lms.lemuel.sellertier.application.port.out.PublishSellerTierEventPort;
import github.lms.lemuel.sellertier.application.port.out.SaveTierAssignmentPort;
import github.lms.lemuel.sellertier.application.port.out.SaveTierHistoryPort;
import github.lms.lemuel.sellertier.application.port.out.SaveTierHistoryPort.TierHistoryEntry;
import github.lms.lemuel.sellertier.domain.SellerTierGrade;
import github.lms.lemuel.sellertier.domain.TierAssignment;
import github.lms.lemuel.sellertier.domain.TierChangeReason;
import github.lms.lemuel.sellertier.domain.TierDecision;
import github.lms.lemuel.sellertier.domain.TierOutcome;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 등급 변경 1건 반영기 — <b>셀러별 독립 트랜잭션</b>.
 *
 * <p>배치 루프와 트랜잭션 경계를 분리하는 이유는 두 가지다.
 *
 * <p>첫째, 한 셀러의 실패가 이미 반영된 앞 건들을 함께 롤백하면 운영자는 어디까지 됐는지 모른다.
 * 등급은 셀러마다 독립적인 사실이라 건별로 확정하고, 실패는 카운터로 드러내 다음 주기가 다시 집는다.
 *
 * <p>둘째, <b>등급 저장·이력·Outbox 통지가 실제로 한 커밋이어야 한다.</b> 경계가 없으면 세 쓰기가
 * 각자 커밋되어 "등급은 바뀌었는데 통지가 안 나감"(또는 이력만 남음)이 생긴다 — Outbox 를 쓰는
 * 이유가 바로 그 원자성이므로, 경계 없이 outbox 에 쓰는 것은 Outbox 패턴이 아니다.
 *
 * <p>등급이 바뀌지 않으면 쓰지 않는다 — 매달 전 셀러를 갱신하면 이력이 의미 없는 행으로 뒤덮인다.
 * 다만 강등 보류(GUARDED)는 미달 카운트가 쌓여야 다음에 강등되므로 <b>상태만</b> 저장하고
 * 등급 이력·통지는 남기지 않는다(등급이 안 바뀌었으므로).
 */
@Component
public class SellerTierChangeProcessor {

    private final SaveTierAssignmentPort savePort;
    private final SaveTierHistoryPort historyPort;
    private final PublishSellerTierEventPort eventPort;

    public SellerTierChangeProcessor(SaveTierAssignmentPort savePort, SaveTierHistoryPort historyPort,
                                     PublishSellerTierEventPort eventPort) {
        this.savePort = savePort;
        this.historyPort = historyPort;
        this.eventPort = eventPort;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void apply(TierAssignment assignment, TierDecision decision, BigDecimal net12m, LocalDate today) {
        if (decision.outcome() == TierOutcome.HELD) {
            return;   // 바뀐 것이 없으면 쓰지 않는다
        }
        SellerTierGrade from = assignment.getTier();
        assignment.apply(decision, today);
        savePort.save(assignment);

        if (decision.outcome() == TierOutcome.GUARDED) {
            return;   // 미달 카운트만 남기고 등급 이력은 남기지 않는다
        }
        TierChangeReason reason = decision.outcome() == TierOutcome.PROMOTED
                ? TierChangeReason.AUTO_PROMOTION : TierChangeReason.AUTO_DEMOTION;

        historyPort.append(new TierHistoryEntry(assignment.getSellerId(), from, assignment.getTier(),
                reason, net12m, today.minusMonths(12), today, "SYSTEM", decision.reason()));

        // 소비측은 조회용 뷰 갱신에만 쓰므로 늦거나 유실돼도 정산 금액은 틀리지 않는다(비소급).
        eventPort.publishTierChanged(assignment.getSellerId(), from, assignment.getTier(),
                reason, today, net12m);
    }
}
