package github.lms.lemuel.sellertier.application.port.out;

import github.lms.lemuel.sellertier.domain.SellerTierGrade;
import github.lms.lemuel.sellertier.domain.TierChangeReason;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 등급 변경 통지 (ADR 0031 §4).
 *
 * <p>소비측은 조회·리포트용 뷰 갱신에만 쓴다 — 정산 계산은 결제 시점 등급을 그대로 쓰며, 등급 변경은
 * 미래 정산에만 반영된다(비소급, ADR 0014 §4). 그래서 이 이벤트가 늦거나 유실돼도 정산 금액은 틀리지 않는다.
 */
public interface PublishSellerTierEventPort {

    void publishTierChanged(Long sellerId, SellerTierGrade prevTier, SellerTierGrade newTier,
                            TierChangeReason reason, LocalDate effectiveFrom, BigDecimal basisAmount);
}
