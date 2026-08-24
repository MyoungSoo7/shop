package github.lms.lemuel.sellertier.application.port.out;

import github.lms.lemuel.sellertier.domain.SellerTierGrade;
import github.lms.lemuel.sellertier.domain.TierChangeReason;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 등급 변경 이력 — append-only. 돈에 직결되는 값이 흔적 없이 바뀌지 않게 한다. */
public interface SaveTierHistoryPort {

    void append(TierHistoryEntry entry);

    /**
     * @param basisAmount 판정 근거 거래액 — 나중에 "왜 이때 올랐나"를 설명하려면 값이 남아야 한다
     */
    record TierHistoryEntry(Long sellerId, SellerTierGrade prevTier, SellerTierGrade newTier,
                            TierChangeReason reason, BigDecimal basisAmount,
                            LocalDate basisPeriodStart, LocalDate basisPeriodEnd,
                            String changedBy, String memo) { }
}
