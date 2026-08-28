package github.lms.lemuel.marketing.domain;

import github.lms.lemuel.marketing.domain.exception.NoPrizeAvailableException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Objects;

/**
 * 가중치 추첨 — 후보 경품들에서 하나를 고른다.
 *
 * <p>레거시 {@code EventServiceImpl.luckyboxApply} 의 누적 확률 걷기와 같은 알고리즘이지만
 * 세 군데가 다르다.
 *
 * <ol>
 *   <li><b>난수를 밖에서 받는다.</b> 레거시는 메서드 안에서 {@code new SecureRandom().nextDouble()}
 *       을 불렀다(그 전 버전은 {@code Math.random()}). 난수를 안에서 만들면 "그 사람이 왜 그
 *       경품에 당첨됐는지" 를 재현할 수 없고, 확률 분포가 맞는지 테스트할 수도 없다. 추첨 자체는
 *       순수 함수로 두고, 난수원은 호출자가 정한다.</li>
 *   <li><b>경계를 {@code <} 로 잡는다.</b> 레거시는 {@code randomRate <= cumulativeRate} 였다.
 *       난수가 정확히 0 일 때 첫 경품이 한 번 더 유리해지고, 가중치가 0 인 경품도 그 순간엔
 *       당첨 가능해진다. 반개구간 {@code [하한, 상한)} 으로 잡으면 두 경우 다 사라진다.</li>
 *   <li><b>가중치를 {@link BigDecimal} 로 더한다.</b> 레거시는 {@code Integer} 로 저장된 확률을
 *       double 로 승격해 합산했다 — 0.1 을 열 번 더하면 1.0 이 아니다. 마지막 경품이 상한을
 *       아주 조금 넘겨서 "아무것도 안 뽑히는" 경로가 생기는데, 그건 재현이 거의 안 된다.</li>
 * </ol>
 */
public final class PrizeDraw {

    private PrizeDraw() {
    }

    /**
     * @param candidates 추첨 후보 (활성·수량 남은 것만 걸러서 넘길 것)
     * @param roll       {@code [0, 1)} 난수. 경계 밖이면 거절한다 — 조용히 clamp 하면 분포가 틀어진다.
     */
    public static LuckyboxPrize select(List<LuckyboxPrize> candidates, double roll) {
        Objects.requireNonNull(candidates, "candidates");
        if (!(roll >= 0.0d) || roll >= 1.0d) {   // NaN 도 여기서 걸린다
            throw new IllegalArgumentException("roll 은 [0, 1) 이어야 한다: " + roll);
        }
        List<LuckyboxPrize> drawable = candidates.stream().filter(LuckyboxPrize::isDrawable).toList();
        if (drawable.isEmpty()) {
            throw new NoPrizeAvailableException("추첨 가능한 경품이 없습니다");
        }

        BigDecimal totalWeight = drawable.stream()
                .map(LuckyboxPrize::winRate)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.signum() <= 0) {
            throw new NoPrizeAvailableException("경품 가중치 합이 0 입니다");
        }

        BigDecimal target = totalWeight.multiply(BigDecimal.valueOf(roll), MathContext.DECIMAL64);
        BigDecimal cumulative = BigDecimal.ZERO;
        for (LuckyboxPrize prize : drawable) {
            cumulative = cumulative.add(prize.winRate());
            if (target.compareTo(cumulative) < 0) {
                return prize;
            }
        }
        // 부동소수 곱셈이 상한을 미세하게 넘길 수 있다. 마지막 후보로 닫는 것이 정답이다 —
        // 여기서 예외를 던지면 아주 드물게, 재현 불가능하게 추첨이 실패한다.
        return drawable.get(drawable.size() - 1);
    }
}
