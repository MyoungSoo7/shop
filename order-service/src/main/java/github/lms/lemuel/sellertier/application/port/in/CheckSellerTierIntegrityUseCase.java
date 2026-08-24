package github.lms.lemuel.sellertier.application.port.in;

import github.lms.lemuel.sellertier.domain.TierCacheDrift;

import java.util.List;
import java.util.Map;

/**
 * 등급 캐시 정합 검사 (ADR 0031).
 *
 * <p>정본과 {@code users.seller_tier} 가 어긋나면 결제 시점에 잘못된 등급이 이벤트에 실려 정산이
 * 확정된다(스냅샷이라 사후 정정 불가). 읽기 전용 — 이 유스케이스는 아무 것도 고치지 않는다.
 */
public interface CheckSellerTierIntegrityUseCase {

    TierIntegrityReport check(int sampleLimit);

    /**
     * @param drifted    전체 불일치 건수(표본 상한과 무관한 실제 규모)
     * @param byKind     종류별 표본 건수 — 복구 방법이 종류마다 다르다
     * @param samples    상위 표본. {@code drifted} 보다 적을 수 있다
     * @param unreadable 도메인이 드리프트로 인정하지 않은 행 수(정본=캐시인데 조회에 걸린 경우 등) —
     *                   0 이 아니면 조회 조건 자체를 의심해야 한다
     */
    record TierIntegrityReport(long drifted, Map<String, Integer> byKind,
                               List<TierCacheDrift> samples, int unreadable) {

        public boolean healthy() {
            return drifted == 0 && unreadable == 0;
        }
    }
}
