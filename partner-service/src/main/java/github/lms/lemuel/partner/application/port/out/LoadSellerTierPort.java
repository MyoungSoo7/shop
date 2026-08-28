package github.lms.lemuel.partner.application.port.out;

import github.lms.lemuel.partner.domain.SellerTier;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 현재 셀러 등급 스냅샷 조회 — 콘솔 헤더 표시 전용.
 *
 * <p>매출 조회와 분리한 이유는 <b>이 값이 계산에 쓰이면 안 되기 때문</b>이다. 정산 조건은 결제
 * 시점 등급이 정하고(ADR 0031 비소급), 이 포트는 "지금 등급이 무엇인가" 만 답한다. 매출 포트에
 * 섞어 두면 언젠가 집계 쿼리가 이 값을 조인해 과거를 다시 계산하게 된다.
 */
public interface LoadSellerTierPort {

    Optional<TierSnapshot> findBySellerId(long sellerId);

    record TierSnapshot(SellerTier tier, LocalDate effectiveFrom) {
    }
}
