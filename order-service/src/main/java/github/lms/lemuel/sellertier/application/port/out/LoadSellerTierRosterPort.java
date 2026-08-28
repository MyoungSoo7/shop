package github.lms.lemuel.sellertier.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 셀러 명부 조회 포트 (ADR 0031).
 *
 * <p>기존 {@code LoadTierAssignmentPort.findAll()} 로는 명부를 만들 수 없다. 그쪽은 <b>정본이 있는</b>
 * 셀러만 돌려주므로, 한 번도 산정되지 않은 셀러 — 정확히 관리자가 찾고 있는 그 사람들 — 이 빠진다.
 * 여기서는 "셀러"를 <b>상품을 가졌거나 정본 등급이 있는 계정</b>으로 정의해, 아직 등급이 없는 셀러도
 * 등급 없음 상태로 보이게 한다.
 */
public interface LoadSellerTierRosterPort {

    List<RawSellerRow> findRoster(LocalDate today, int limit);

    /** 명부가 상한에 잘렸을 때도 전체 규모를 정확히 보고하기 위한 별도 집계. */
    long countSellers();

    /**
     * 저장된 값 그대로. 등급은 문자열로 싣는다 — enum 밖의 값이 있으면 그 자체가 조사 대상이지
     * 명부 전체를 못 뜨게 할 이유는 아니다.
     */
    record RawSellerRow(Long sellerId, String email, String name,
                        String tier, String cachedTier,
                        LocalDate effectiveFrom, LocalDate demotionGuardUntil,
                        int consecutiveMissCount, BigDecimal netSales12m, long productCount) { }
}
