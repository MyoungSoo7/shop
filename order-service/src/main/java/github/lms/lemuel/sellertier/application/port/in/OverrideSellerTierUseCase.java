package github.lms.lemuel.sellertier.application.port.in;

import github.lms.lemuel.sellertier.domain.SellerTierGrade;
import github.lms.lemuel.sellertier.domain.TierAssignment;

import java.time.LocalDate;

/**
 * 관리자 등급 지정 (ADR 0031).
 *
 * <p>자동 판정으로는 담을 수 없는 사정(전략 파트너 계약, 보상, 분쟁 합의)을 반영하는 정식 경로다.
 * 이 경로가 없으면 운영자는 결국 {@code users.seller_tier} 를 직접 UPDATE 하게 되고, 그러면
 * 이력도 유예도 남지 않아 다음 배치가 조용히 되돌려 버린다.
 */
public interface OverrideSellerTierUseCase {

    /**
     * @param memo      변경 근거 — 필수. 근거 없는 등급 변경이 이력에 쌓이면 감사가 의미를 잃는다
     * @param changedBy 지정한 운영자
     */
    TierAssignment override(Long sellerId, SellerTierGrade target, String memo,
                            String changedBy, LocalDate today);
}
