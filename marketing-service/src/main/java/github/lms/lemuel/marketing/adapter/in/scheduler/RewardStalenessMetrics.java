package github.lms.lemuel.marketing.adapter.in.scheduler;

import github.lms.lemuel.marketing.application.port.out.RewardGrantPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 돌아오지 않는 보상을 센다.
 *
 * <p>보상 지급은 왕복이다 — 여기서 {@code lemuel.marketing.reward_requested} 를 내고, order 가
 * 원장에 적립한 뒤 {@code lemuel.point.granted} 로 돌려주면 그때 CONFIRMED 가 된다. 나가는
 * 다리가 막히면 outbox 적체 알람이 잡는다. 문제는 <b>돌아오는</b> 다리다: 발행은 성공했으니
 * outbox 는 깨끗하고, 이 서비스도 멀쩡히 200 을 주고, 원장도 자기 관점에서는 정상이다. 그런데
 * 사용자 화면은 "적립 처리 중" 에서 움직이지 않는다. 아무 지표도 안 움직이므로 문의가 들어올
 * 때까지 아무도 모른다.
 *
 * <p>그래서 "요청한 지 한참 됐는데 확정이 안 온 건수" 를 직접 센다. 이 값이 0 보다 크게 유지되면
 * 왕복의 어느 한쪽이 끊긴 것이다(MarketingRewardRoundTripStalled).
 *
 * <h2>왜 스케줄러로 캐싱하나</h2>
 * <p>{@link Gauge} 에 조회 람다를 그대로 물리면 스크레이프마다 DB 를 친다. 스크레이프 주기와
 * 프로메테우스 인스턴스 수에 따라 DB 부하가 정해지는 구조는 감시 대상이 감시 때문에 느려지는
 * 길이다. 값은 여기서 주기적으로 갱신하고 게이지는 그 스냅샷만 읽는다 — outbox 폴러가
 * {@code outbox.pending.count} 를 다루는 방식과 같다.
 *
 * <h2>임계 시간</h2>
 * <p>{@code app.marketing.reward.stuck-after} 이전에 요청된 건만 센다. 방금 나가서 아직 돌고 있는
 * 정상 왕복까지 세면 이 지표는 상시 0 이 아니게 되고, 그러면 알람 임계를 올려야 하고, 그러면
 * 진짜 정체를 놓친다. 기본 15분은 브로커 지연이 아니라 <b>고장</b>이라고 볼 만한 폭이다.
 */
@Component
public class RewardStalenessMetrics {

    static final String METRIC = "marketing.reward.stuck.count";

    private final RewardGrantPort rewards;
    private final Duration stuckAfter;
    private final AtomicLong stuck = new AtomicLong();

    public RewardStalenessMetrics(RewardGrantPort rewards,
                                  MeterRegistry registry,
                                  @Value("${app.marketing.reward.stuck-after:PT15M}") Duration stuckAfter) {
        this.rewards = rewards;
        this.stuckAfter = stuckAfter;
        Gauge.builder(METRIC, stuck, AtomicLong::get)
                .description("요청 후 " + stuckAfter + " 안에 확정되지 않은 보상 건수")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${app.marketing.reward.stuck-scan-delay-ms:60000}")
    public void refresh() {
        refreshAt(Instant.now());
    }

    /** 시각을 밖에서 받는 형태 — 테스트가 시계를 붙잡을 수 있게. */
    void refreshAt(Instant now) {
        stuck.set(rewards.countRequestedBefore(now.minus(stuckAfter)));
    }

    long current() {
        return stuck.get();
    }
}
