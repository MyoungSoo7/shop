package github.lms.lemuel.marketing.adapter.in.scheduler;

import github.lms.lemuel.marketing.application.port.out.RewardGrantPort;
import github.lms.lemuel.marketing.domain.RewardGrant;
import github.lms.lemuel.marketing.domain.RewardSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보상 정체 게이지 검사.
 *
 * <p>이 지표는 <b>알람이 매달릴 시계열</b>이라 두 가지가 실제로 성립해야 한다: (1) 레지스트리에
 * 게이지가 등록될 것 — 등록 안 되면 알람 규칙은 빈 벡터를 평가하며 영원히 안 울린다. (2) 조회
 * 경계 시각이 {@code now - stuckAfter} 일 것 — 경계를 잘못 잡으면 정상 왕복까지 세어 상시 경보가
 * 되거나(그러면 사람이 알람을 끈다) 아무것도 못 세게 된다.
 *
 * <p>그래서 어댑터를 목으로 두지 않고 <b>넘어온 경계 시각을 기록하는 가짜 포트</b>를 쓴다.
 * 목의 인자 검증은 "무엇을 물었는가" 를 문자열로 확인하지만, 여기서는 그 값이 곧 판정 기준이다.
 */
class RewardStalenessMetricsTest {

    private static final Duration STUCK_AFTER = Duration.ofMinutes(15);

    /** 넘어온 경계 시각을 그대로 기록하는 가짜 포트. 나머지 메서드는 이 테스트가 안 쓴다. */
    private static final class RecordingRewards implements RewardGrantPort {
        private final List<Instant> asked = new ArrayList<>();
        private long answer;

        RecordingRewards(long answer) {
            this.answer = answer;
        }

        @Override
        public long countRequestedBefore(Instant before) {
            asked.add(before);
            return answer;
        }

        @Override
        public Optional<RewardGrant> findById(UUID rewardId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RewardGrant> findByReference(RewardSource source, UUID referenceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RewardGrant> findDue(LocalDate on, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RewardGrant> findByMember(String memberRef, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RewardGrant save(RewardGrant grant) {
            throw new UnsupportedOperationException();
        }
    }

    private static RewardStalenessMetrics metrics(MeterRegistry registry, RewardGrantPort rewards) {
        return new RewardStalenessMetrics(rewards, registry, STUCK_AFTER);
    }

    @Test
    @DisplayName("생성 시점에 게이지가 등록된다 — 한 번도 안 돌았어도 시계열이 있어야 한다")
    void registersGaugeEagerly() {
        // 첫 스캔 전에 게이지가 없으면 부팅 직후 구간이 '없는 시계열' 이 되고, 그 구간의 알람은
        // 발화하지 않는다. 초기값 0 이 곧 "확인했고 정체 없음" 이라는 뜻이어야 한다.
        var registry = new SimpleMeterRegistry();

        metrics(registry, new RecordingRewards(3));

        var gauge = registry.find(RewardStalenessMetrics.METRIC).gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isZero();
    }

    @Test
    @DisplayName("갱신하면 정체 건수가 게이지에 실린다")
    void publishesStuckCount() {
        var registry = new SimpleMeterRegistry();
        var metrics = metrics(registry, new RecordingRewards(7));

        metrics.refreshAt(Instant.parse("2026-08-28T12:00:00Z"));

        assertThat(registry.get(RewardStalenessMetrics.METRIC).gauge().value()).isEqualTo(7.0);
        assertThat(metrics.current()).isEqualTo(7L);
    }

    @Test
    @DisplayName("경계는 now - stuck-after 다 — 방금 나간 정상 왕복은 세지 않는다")
    void asksForGrantsOlderThanTheWindow() {
        var rewards = new RecordingRewards(0);
        var metrics = metrics(new SimpleMeterRegistry(), rewards);
        var now = Instant.parse("2026-08-28T12:00:00Z");

        metrics.refreshAt(now);

        // 15분 전. 이 경계가 now 로 밀리면 진행 중인 정상 왕복까지 전부 '정체' 가 되어
        // 게이지가 상시 0 이 아니게 되고, 알람은 사실상 무력해진다.
        assertThat(rewards.asked).containsExactly(now.minus(STUCK_AFTER));
    }

    @Test
    @DisplayName("정체가 풀리면 게이지도 내려간다 — 한 번 오른 값이 남으면 알람이 안 꺼진다")
    void fallsBackToZeroWhenResolved() {
        var registry = new SimpleMeterRegistry();
        var rewards = new RecordingRewards(4);
        var metrics = metrics(registry, rewards);
        var now = Instant.parse("2026-08-28T12:00:00Z");

        metrics.refreshAt(now);
        rewards.answer = 0;
        metrics.refreshAt(now.plus(Duration.ofMinutes(1)));

        assertThat(registry.get(RewardStalenessMetrics.METRIC).gauge().value()).isZero();
    }

    @Test
    @DisplayName("스케줄러 진입점도 같은 갱신을 한다")
    void scheduledRefreshUsesTheSamePath() {
        // refresh() 는 시계만 붙여 refreshAt 으로 넘긴다. 이 경로가 끊기면 게이지는 부팅 후
        // 영원히 0 이고 — 값이 없는 게 아니라 0 이라 더 나쁘다. 그래프는 정상으로 보인다.
        var registry = new SimpleMeterRegistry();
        var metrics = metrics(registry, new RecordingRewards(2));

        metrics.refresh();

        assertThat(registry.get(RewardStalenessMetrics.METRIC).gauge().value()).isEqualTo(2.0);
    }
}
