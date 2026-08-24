package github.lms.lemuel.sellertier.domain;

import github.lms.lemuel.sellertier.domain.exception.SellerTierPolicyException;

/**
 * 등급 정본과 읽기 캐시가 어긋난 한 건 (ADR 0031).
 *
 * <p>{@code users.seller_tier} 는 {@code seller_tier_assignment} 를 따라가는 캐시다. 정산은 결제 시점에
 * <b>캐시값</b>을 이벤트에 동봉해 확정하므로, 캐시가 정본과 어긋난 채로 결제가 일어나면 잘못된 요율·주기·
 * 홀드백으로 정산이 만들어진다. 게다가 그 정산은 스냅샷이라 나중에 정본을 고쳐도 되돌아오지 않는다 —
 * 그래서 이 드리프트는 "언젠가 맞추면 되는 것"이 아니라 결제 전에 잡아야 하는 것이다.
 *
 * <p>등급 문자열은 파싱하지 않고 <b>그대로</b> 싣는다. 알 수 없는 값(enum 밖)이 들어 있다면 그 자체가
 * 조사 대상인데, 여기서 valueOf 로 터뜨리면 검사가 첫 이상 행에서 멈춰 나머지를 못 본다.
 */
public record TierCacheDrift(Long sellerId, String authoritativeTier, String cachedTier,
                             TierCacheDriftKind kind) {

    /** 어긋나지 않은 두 값으로는 만들 수 없다 — 드리프트가 아닌 것이 목록에 섞이면 건수가 거짓이 된다. */
    public static TierCacheDrift of(Long sellerId, String authoritativeTier, String cachedTier) {
        if (authoritativeTier == null && cachedTier == null) {
            throw new SellerTierPolicyException(
                    "정본·캐시가 모두 비어 드리프트가 아닙니다: sellerId=" + sellerId);
        }
        if (authoritativeTier != null && authoritativeTier.equals(cachedTier)) {
            throw new SellerTierPolicyException(
                    "정본과 캐시가 같아 드리프트가 아닙니다: sellerId=" + sellerId);
        }
        return new TierCacheDrift(sellerId, authoritativeTier, cachedTier, classify(authoritativeTier, cachedTier));
    }

    private static TierCacheDriftKind classify(String authoritativeTier, String cachedTier) {
        if (authoritativeTier == null) {
            return TierCacheDriftKind.AUTHORITY_MISSING;
        }
        return cachedTier == null ? TierCacheDriftKind.CACHE_MISSING : TierCacheDriftKind.CACHE_STALE;
    }
}
