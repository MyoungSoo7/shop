package github.lms.lemuel.sellertier.application.service;

import github.lms.lemuel.sellertier.application.port.in.CheckSellerTierIntegrityUseCase;
import github.lms.lemuel.sellertier.application.port.out.LoadTierCacheDriftPort;
import github.lms.lemuel.sellertier.application.port.out.LoadTierCacheDriftPort.RawDrift;
import github.lms.lemuel.sellertier.domain.TierCacheDrift;
import github.lms.lemuel.sellertier.domain.exception.SellerTierPolicyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 등급 캐시 정합 검사 (ADR 0031).
 *
 * <p>정본({@code seller_tier_assignment})과 읽기 캐시({@code users.seller_tier})가 어긋난 채로 결제가
 * 일어나면, 그 시점 캐시값이 이벤트에 실려 정산이 확정된다. 정산은 스냅샷이라 나중에 정본을 고쳐도
 * 되돌아오지 않는다 — 그래서 이 검사는 사후 대사가 아니라 <b>사전 점검</b>이다.
 *
 * <p>읽기 전용이다. 고치지 않는 이유는 복구 방법이 종류마다 다르기 때문이다 —
 * {@code CACHE_STALE}/{@code CACHE_MISSING} 은 재산정 또는 관리자 지정으로 정본이 다시 쓰이면 함께
 * 동기화되지만, {@code AUTHORITY_MISSING} 은 정본 자체가 없어 "무엇이 옳은 등급인가"를 사람이 정해야 한다.
 * 일괄 덮어쓰기를 여기 넣으면 그 판단 없이 돈이 움직인다.
 *
 * <p>이상 행 하나로 검사가 멈추지 않는다 — 규모를 못 보면 조치 우선순위를 정할 수 없다.
 */
public class CheckSellerTierIntegrityService implements CheckSellerTierIntegrityUseCase {

    private static final Logger log = LoggerFactory.getLogger(CheckSellerTierIntegrityService.class);

    private final LoadTierCacheDriftPort driftPort;

    public CheckSellerTierIntegrityService(LoadTierCacheDriftPort driftPort) {
        this.driftPort = driftPort;
    }

    @Override
    public TierIntegrityReport check(int sampleLimit) {
        long drifted = driftPort.countDrifts();

        List<TierCacheDrift> samples = new ArrayList<>();
        Map<String, Integer> byKind = new LinkedHashMap<>();
        int unreadable = 0;

        for (RawDrift raw : driftPort.findDrifts(sampleLimit)) {
            try {
                TierCacheDrift drift = TierCacheDrift.of(
                        raw.sellerId(), raw.authoritativeTier(), raw.cachedTier());
                samples.add(drift);
                byKind.merge(drift.kind().name(), 1, Integer::sum);
            } catch (SellerTierPolicyException e) {
                // 드리프트가 아닌 행이 조회에 걸렸다 — 조회 조건 자체가 의심스럽다는 신호라 세어서 드러낸다.
                unreadable++;
                log.warn("드리프트로 인정되지 않는 행: sellerId={}, 사유={}", raw.sellerId(), e.getMessage());
            }
        }

        if (drifted > 0 || unreadable > 0) {
            log.warn("등급 캐시 정합 이상: 불일치={}, 종류별(표본)={}, 비드리프트행={}",
                    drifted, byKind, unreadable);
        }
        return new TierIntegrityReport(drifted, Map.copyOf(byKind), List.copyOf(samples), unreadable);
    }
}
