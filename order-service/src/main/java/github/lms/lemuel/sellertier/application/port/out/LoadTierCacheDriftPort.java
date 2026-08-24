package github.lms.lemuel.sellertier.application.port.out;

import java.util.List;

/**
 * 등급 정본({@code seller_tier_assignment})과 읽기 캐시({@code users.seller_tier})의 불일치 조회.
 *
 * <p>등급 문자열을 파싱하지 않은 <b>원본 그대로</b> 돌려준다 — 분류는 도메인
 * ({@code TierCacheDrift})의 몫이고, enum 밖의 값도 드러나야 한다.
 */
public interface LoadTierCacheDriftPort {

    /** @param limit 표본 상한 — 전수 스캔이 운영 DB 를 오래 잡지 않게 한다 */
    List<RawDrift> findDrifts(int limit);

    /** 총 불일치 건수 — 표본을 잘라도 규모는 정확히 보고한다. */
    long countDrifts();

    record RawDrift(Long sellerId, String authoritativeTier, String cachedTier) { }
}
