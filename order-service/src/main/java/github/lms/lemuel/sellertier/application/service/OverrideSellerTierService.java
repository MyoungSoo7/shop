package github.lms.lemuel.sellertier.application.service;

import github.lms.lemuel.sellertier.application.port.in.OverrideSellerTierUseCase;
import github.lms.lemuel.sellertier.application.port.out.LoadTierAssignmentPort;
import github.lms.lemuel.sellertier.application.port.out.PublishSellerTierEventPort;
import github.lms.lemuel.sellertier.application.port.out.SaveTierAssignmentPort;
import github.lms.lemuel.sellertier.application.port.out.SaveTierHistoryPort;
import github.lms.lemuel.sellertier.application.port.out.SaveTierHistoryPort.TierHistoryEntry;
import github.lms.lemuel.sellertier.domain.SellerTierGrade;
import github.lms.lemuel.sellertier.domain.TierAssignment;
import github.lms.lemuel.sellertier.domain.TierChangeReason;
import github.lms.lemuel.sellertier.domain.exception.SellerTierPolicyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 관리자 등급 지정 (ADR 0031).
 *
 * <p>자동 판정을 사람이 덮어쓰는 경로다. 여기서 지키는 것은 두 가지 — <b>근거를 남길 것</b>,
 * 그리고 <b>다음 배치가 조용히 되돌리지 못하게 할 것</b>. 유예 없이 지정하면 다음 재산정에서
 * 곧바로 강등돼 지정 자체가 무의미해지므로, 지정에는 항상 보호 기간이 따라붙는다(도메인
 * {@code TierAssignment#overrideTo}).
 *
 * <p>등급 저장·이력·통지가 <b>한 트랜잭션</b>이다. 배치와 달리 지정은 셀러 한 건이라 부분 성공을
 * 남길 이유가 없고, 등급만 바뀌고 이력이 빠지면 "누가 왜 바꿨나"에 답할 수 없다.
 *
 * <p>근거 거래액은 남기지 않는다({@code null}) — 관리자 지정은 실적 판정이 아니라 결정이다.
 * 0 을 넣으면 "실적 0원이라 이 등급"이라는 다른 사실로 읽힌다.
 */
@Transactional
public class OverrideSellerTierService implements OverrideSellerTierUseCase {

    private static final Logger log = LoggerFactory.getLogger(OverrideSellerTierService.class);

    private final LoadTierAssignmentPort loadPort;
    private final SaveTierAssignmentPort savePort;
    private final SaveTierHistoryPort historyPort;
    private final PublishSellerTierEventPort eventPort;
    private final int guardMonths;

    public OverrideSellerTierService(LoadTierAssignmentPort loadPort, SaveTierAssignmentPort savePort,
                                     SaveTierHistoryPort historyPort, PublishSellerTierEventPort eventPort,
                                     int guardMonths) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.historyPort = historyPort;
        this.eventPort = eventPort;
        this.guardMonths = guardMonths;
    }

    @Override
    public TierAssignment override(Long sellerId, SellerTierGrade target, String memo,
                                   String changedBy, LocalDate today) {
        if (memo == null || memo.isBlank()) {
            throw new SellerTierPolicyException("등급 지정 사유(memo)는 필수입니다");
        }
        TierAssignment assignment = loadPort.findBySellerId(sellerId)
                // 아직 평가된 적 없는 셀러도 지정할 수 있어야 한다 — 신규 계약이 흔한 경로다.
                .orElseGet(() -> TierAssignment.initial(sellerId, SellerTierGrade.NORMAL, today));

        SellerTierGrade from = assignment.getTier();
        assignment.overrideTo(target, today, guardMonths);
        savePort.save(assignment);

        historyPort.append(new TierHistoryEntry(sellerId, from, target, TierChangeReason.ADMIN_OVERRIDE,
                /*basisAmount*/ null, /*basisPeriodStart*/ null, /*basisPeriodEnd*/ null,
                changedBy, memo));

        eventPort.publishTierChanged(sellerId, from, target, TierChangeReason.ADMIN_OVERRIDE, today, null);

        log.info("등급 관리자 지정: sellerId={}, {} to {}, 유예={}까지, 지정자={}",
                sellerId, from, target, assignment.getDemotionGuardUntil(), changedBy);
        return assignment;
    }
}
