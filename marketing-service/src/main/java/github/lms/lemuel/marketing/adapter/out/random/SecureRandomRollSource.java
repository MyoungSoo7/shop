package github.lms.lemuel.marketing.adapter.out.random;

import github.lms.lemuel.marketing.application.port.out.RollSource;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 추첨 난수원.
 *
 * <p>{@link SecureRandom} 을 쓴다. 당첨 확률이 걸린 자리에서 {@code Math.random()} 은
 * 시드가 예측 가능하고 — 레거시가 그랬다 — 스레드 간 상태를 공유해 동시 참여자에게 같은 값이
 * 나올 수 있다. 성능 차이는 참여 한 번에 난수 한 개라 의미가 없다.
 *
 * <p>{@code nextDouble()} 은 {@code [0, 1)} 을 준다. 상한이 열려 있는 것이 중요하다 —
 * 가중치 누적과 비교하는 {@code PrizeDraw.select} 는 1.0 이 들어오면 어떤 구간에도 걸리지 않는다.
 */
@Component
class SecureRandomRollSource implements RollSource {

    private final SecureRandom random = new SecureRandom();

    @Override
    public double nextRoll() {
        return random.nextDouble();
    }
}
