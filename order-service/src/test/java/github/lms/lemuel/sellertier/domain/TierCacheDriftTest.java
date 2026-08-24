package github.lms.lemuel.sellertier.domain;

import github.lms.lemuel.sellertier.domain.exception.SellerTierPolicyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 등급 캐시 드리프트 분류 (ADR 0031).
 *
 * <p>{@code users.seller_tier} 는 {@code seller_tier_assignment} 를 따라가는 읽기 캐시다. 둘이 어긋나면
 * 정산 금액이 정본이 아닌 캐시를 따라 계산되므로(결제 이벤트가 캐시값을 동봉한다) 조용한 금액 오류가 된다.
 *
 * <p>드리프트는 <b>종류마다 복구 방법이 다르다</b> — 그래서 건수만 세지 않고 분류한다.
 */
class TierCacheDriftTest {

    @Test @DisplayName("캐시가 정본과 다르면 STALE — 정본으로 덮어쓰면 된다")
    void staleCache() {
        TierCacheDrift drift = TierCacheDrift.of(7L, "VIP", "NORMAL");

        assertThat(drift.kind()).isEqualTo(TierCacheDriftKind.CACHE_STALE);
    }

    @Test @DisplayName("정본은 있는데 캐시가 비면 MISSING — 동기화가 한 번도 안 닿은 셀러다")
    void missingCache() {
        TierCacheDrift drift = TierCacheDrift.of(7L, "VIP", null);

        assertThat(drift.kind()).isEqualTo(TierCacheDriftKind.CACHE_MISSING);
    }

    @Test @DisplayName("캐시만 있고 정본이 없으면 ORPHAN — 정본 도입 전 수기 UPDATE 의 흔적이다")
    void orphanCache() {
        TierCacheDrift drift = TierCacheDrift.of(7L, null, "STRATEGIC");

        assertThat(drift.kind()).isEqualTo(TierCacheDriftKind.AUTHORITY_MISSING);
    }

    @Test @DisplayName("둘이 같으면 드리프트가 아니다 — 만들 수 없다")
    void identicalIsNotDrift() {
        assertThatThrownBy(() -> TierCacheDrift.of(7L, "VIP", "VIP"))
                .isInstanceOf(SellerTierPolicyException.class);
    }

    @Test @DisplayName("양쪽 다 비면 드리프트가 아니다 — 조회 결과가 잘못 들어온 것이다")
    void bothNullIsNotDrift() {
        assertThatThrownBy(() -> TierCacheDrift.of(7L, null, null))
                .isInstanceOf(SellerTierPolicyException.class);
    }

    @Test @DisplayName("정본·캐시 값을 그대로 보존한다 — 운영자가 무엇이 무엇으로 바뀔지 봐야 한다")
    void keepsBothValues() {
        TierCacheDrift drift = TierCacheDrift.of(7L, "VIP", "NORMAL");

        assertThat(drift.sellerId()).isEqualTo(7L);
        assertThat(drift.authoritativeTier()).isEqualTo("VIP");
        assertThat(drift.cachedTier()).isEqualTo("NORMAL");
    }

    @Test @DisplayName("알 수 없는 등급 문자열도 그대로 실어 드러낸다 — 그 자체가 조사 대상이다")
    void unknownTierStringIsReported() {
        TierCacheDrift drift = TierCacheDrift.of(7L, "VIP", "GOLD");

        assertThat(drift.kind()).isEqualTo(TierCacheDriftKind.CACHE_STALE);
        assertThat(drift.cachedTier()).isEqualTo("GOLD");
    }
}
