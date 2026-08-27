package github.lms.lemuel.marketing.domain;

import github.lms.lemuel.marketing.domain.exception.NoPrizeAvailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 가중치 추첨.
 *
 * <p>레거시와 달라진 세 지점을 각각 잡는다.
 *
 * <ul>
 *   <li>{@code roll_0_은_첫_경품이_아니라_첫_구간에_들어간다} + {@code 가중치_0_은_난수가_0_이어도_안_뽑힌다}
 *       — 레거시의 {@code <=} 경계 버그.</li>
 *   <li>{@code 난수는_반개구간_밖이면_거절한다} — 조용한 clamp 금지.</li>
 *   <li>{@code 마지막_후보가_상한을_닫는다} — 부동소수 잔차로 아무것도 안 뽑히는 경로 차단.</li>
 * </ul>
 */
class PrizeDrawTest {

    private static final UUID CAMPAIGN = UUID.randomUUID();

    @Test
    void 후보가_비면_거절한다() {
        assertThrows(NoPrizeAvailableException.class, () -> PrizeDraw.select(List.of(), 0.5d));
    }

    @Test
    void 비활성_경품만_있으면_거절한다() {
        LuckyboxPrize inactive = prize("꽝", BigDecimal.TEN, null, 0, false);

        assertThrows(NoPrizeAvailableException.class, () -> PrizeDraw.select(List.of(inactive), 0.5d));
    }

    @Test
    void 수량이_소진된_경품은_후보에서_빠진다() {
        LuckyboxPrize soldOut = prize("한정판", BigDecimal.TEN, 100, 100, true);
        LuckyboxPrize left = prize("일반", BigDecimal.ONE, 100, 3, true);

        // 난수를 어디로 굴려도 소진된 경품은 나오면 안 된다.
        for (int i = 0; i < 100; i++) {
            assertSame(left, PrizeDraw.select(List.of(soldOut, left), i / 100.0d));
        }
    }

    @Test
    void 가중치_합이_0_이면_거절한다() {
        // isDrawable 이 winRate > 0 을 요구하므로 실제로는 후보가 통째로 비는 경로다.
        LuckyboxPrize zero = prize("가중치0", BigDecimal.ZERO, null, 0, true);

        assertThrows(NoPrizeAvailableException.class, () -> PrizeDraw.select(List.of(zero), 0.0d));
    }

    @Test
    void 가중치_0_은_난수가_0_이어도_안_뽑힌다() {
        // 레거시의 randomRate <= cumulativeRate 는 난수가 정확히 0 일 때 가중치 0 인
        // 첫 경품을 당첨시켰다. 반개구간이면 이 경로가 없다.
        LuckyboxPrize zero = prize("절대_안_나와야_함", BigDecimal.ZERO, null, 0, true);
        LuckyboxPrize real = prize("진짜", BigDecimal.ONE, null, 0, true);

        assertSame(real, PrizeDraw.select(List.of(zero, real), 0.0d));
    }

    @Test
    void roll_0_은_첫_구간에_들어간다() {
        LuckyboxPrize first = prize("A", BigDecimal.valueOf(30), null, 0, true);
        LuckyboxPrize second = prize("B", BigDecimal.valueOf(70), null, 0, true);

        assertSame(first, PrizeDraw.select(List.of(first, second), 0.0d));
    }

    @Test
    void 구간_경계는_아래_경품이_아니라_위_경품에_속한다() {
        // 가중치 30 : 70. 정규화 경계는 정확히 0.3 이다. [0, 0.3) 이 A, [0.3, 1) 이 B 여야 한다.
        LuckyboxPrize a = prize("A", BigDecimal.valueOf(30), null, 0, true);
        LuckyboxPrize b = prize("B", BigDecimal.valueOf(70), null, 0, true);
        List<LuckyboxPrize> candidates = List.of(a, b);

        assertSame(a, PrizeDraw.select(candidates, 0.29999d));
        assertSame(b, PrizeDraw.select(candidates, 0.3d), "경계값은 다음 구간이다 — <= 로 짜면 여기서 A 가 나온다");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.0001d, -1d, 1.0d, 1.0001d, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void 난수는_반개구간_밖이면_거절한다(double roll) {
        LuckyboxPrize only = prize("A", BigDecimal.ONE, null, 0, true);

        assertThrows(IllegalArgumentException.class, () -> PrizeDraw.select(List.of(only), roll),
                "clamp 하면 분포가 조용히 틀어진다 — NaN 도 여기서 걸려야 한다");
    }

    @Test
    void 마지막_후보가_상한을_닫는다() {
        // 1 로 나누어떨어지지 않는 가중치를 여럿 쌓아 부동소수 잔차를 만든 뒤, 상한에 붙은
        // 난수로 굴린다. 예외가 나거나 null 이 나오면 안 된다.
        List<LuckyboxPrize> candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            candidates.add(prize("P" + i, new BigDecimal("0.1"), null, 0, true));
        }

        LuckyboxPrize picked = PrizeDraw.select(candidates, Math.nextDown(1.0d));

        assertNotNull(picked);
        assertSame(candidates.get(candidates.size() - 1), picked);
    }

    @Test
    void 가중치_비율대로_분포한다() {
        // 20:80 을 1000 등분으로 훑는다. 난수원이 밖에 있으니 통계가 아니라 결정적 계수다.
        LuckyboxPrize rare = prize("희귀", BigDecimal.valueOf(20), null, 0, true);
        LuckyboxPrize common = prize("흔함", BigDecimal.valueOf(80), null, 0, true);
        List<LuckyboxPrize> candidates = List.of(rare, common);

        Map<UUID, Integer> counts = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            LuckyboxPrize picked = PrizeDraw.select(candidates, i / 1000.0d);
            counts.merge(picked.id(), 1, Integer::sum);
        }

        assertEquals(200, counts.get(rare.id()));
        assertEquals(800, counts.get(common.id()));
    }

    @Test
    void 경품을_하나_비활성화해도_남은_것들의_상대_비율은_유지된다() {
        // winRate 는 확률이 아니라 가중치다. 합이 100 이 아니어도 정규화된다.
        LuckyboxPrize a = prize("A", BigDecimal.valueOf(1), null, 0, true);
        LuckyboxPrize b = prize("B", BigDecimal.valueOf(3), null, 0, true);
        LuckyboxPrize removed = prize("삭제됨", BigDecimal.valueOf(96), null, 0, false);
        List<LuckyboxPrize> candidates = List.of(a, b, removed);

        int aCount = 0;
        for (int i = 0; i < 400; i++) {
            if (PrizeDraw.select(candidates, i / 400.0d).id().equals(a.id())) {
                aCount++;
            }
        }

        assertEquals(100, aCount, "1:3 이면 합이 4 로 정규화돼 A 가 1/4 이다");
    }

    @Test
    void 후보_목록이_null_이면_거절한다() {
        assertThrows(NullPointerException.class, () -> PrizeDraw.select(null, 0.5d));
    }

    @Test
    void 포인트_경품은_0_포인트로_만들_수_없다() {
        UUID id = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new LuckyboxPrize(
                id, CAMPAIGN, PrizeType.POINT, BigDecimal.ZERO, null,
                null, null, BigDecimal.ONE, 0, true, 1, 0L));
    }

    @Test
    void 수량_무제한_경품은_아무리_나가도_후보에_남는다() {
        LuckyboxPrize unlimited = prize("무제한", BigDecimal.ONE, null, 999_999, true);

        assertTrue(unlimited.isDrawable());
        assertTrue(unlimited.hasTotalQuotaLeft());
    }

    // ---------------------------------------------------------------- fixtures

    private static LuckyboxPrize prize(String label, BigDecimal winRate,
                                       Integer totalQuota, int issuedCount, boolean active) {
        return new LuckyboxPrize(
                UUID.randomUUID(), CAMPAIGN, PrizeType.TEXT,
                null, label,
                totalQuota, null, winRate, issuedCount, active, 1, 0L);
    }
}
