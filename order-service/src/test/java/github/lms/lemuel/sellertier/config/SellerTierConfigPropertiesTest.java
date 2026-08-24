package github.lms.lemuel.sellertier.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 승인된 등급 임계가 실제 설정에 살아 있는지 고정한다 (ADR 0031, 2026-08-09 승인).
 *
 * <p>이 값들은 코드 기본값(사실상 승급 불가 크기)에 가려지기 쉽다 — 오타나 키 이름 변경으로 설정이
 * 무시되면 애플리케이션은 정상 기동하고 배치도 정상 실행되는데 <b>아무도 승급하지 않는다</b>.
 * 조용히 정책이 사라지는 종류의 사고라, 설정 파일 자체를 검증한다.
 *
 * <p>스케줄러 비활성도 함께 고정한다. 이것이 켜지면 사람 확인 없이 수수료·정산주기·홀드백이 바뀐다 —
 * 켜는 것은 의도적 결정이어야 하고, 실수로 켜졌다면 이 테스트가 먼저 깨져야 한다.
 */
class SellerTierConfigPropertiesTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> sellerTier() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in).as("application.yml 이 클래스패스에 있어야 한다").isNotNull();
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> app = (Map<String, Object>) root.get("app");
            assertThat(app).as("app 섹션").isNotNull();
            Map<String, Object> tier = (Map<String, Object>) app.get("seller-tier");
            assertThat(tier).as("app.seller-tier 섹션 — 없으면 코드 기본값(승급 불가)이 조용히 적용된다")
                    .isNotNull();
            return tier;
        } catch (Exception e) {
            throw new IllegalStateException("application.yml 을 읽지 못했습니다", e);
        }
    }

    /** {@code ${ENV:default}} 형태에서 기본값만 뽑는다 — 배포 환경 변수가 없을 때 실제로 적용되는 값. */
    private String defaultOf(Object placeholder) {
        String s = String.valueOf(placeholder);
        int colon = s.indexOf(':');
        return colon < 0 ? s : s.substring(colon + 1, s.length() - 1);
    }

    @Test @DisplayName("VIP 임계는 5억 — 승인된 값")
    void vipThreshold() {
        assertThat(new BigDecimal(defaultOf(sellerTier().get("vip-threshold"))))
                .isEqualByComparingTo("500000000");
    }

    @Test @DisplayName("STRATEGIC 임계는 30억 — 승인된 값")
    void strategicThreshold() {
        assertThat(new BigDecimal(defaultOf(sellerTier().get("strategic-threshold"))))
                .isEqualByComparingTo("3000000000");
    }

    @Test @DisplayName("임계는 순서가 지켜져야 한다 — 뒤집히면 기동이 실패한다(SellerTierPolicy.of)")
    void thresholdsAreOrdered() {
        BigDecimal vip = new BigDecimal(defaultOf(sellerTier().get("vip-threshold")));
        BigDecimal strategic = new BigDecimal(defaultOf(sellerTier().get("strategic-threshold")));

        assertThat(strategic).isGreaterThan(vip);
        assertThat(vip).isPositive();
    }

    @Test @DisplayName("강등 유예는 3개월 + 연속 미달 2회")
    void guardValues() {
        assertThat(defaultOf(sellerTier().get("guard-months"))).isEqualTo("3");
        assertThat(defaultOf(sellerTier().get("miss-threshold"))).isEqualTo("2");
    }

    @Test @DisplayName("자동 재산정은 꺼져 있다 — 사람 확인 없이 수수료가 바뀌면 안 된다")
    @SuppressWarnings("unchecked")
    void autoEvaluateIsOff() {
        Map<String, Object> auto = (Map<String, Object>) sellerTier().get("auto-evaluate");

        assertThat(defaultOf(auto.get("enabled"))).isEqualTo("false");
    }
}
