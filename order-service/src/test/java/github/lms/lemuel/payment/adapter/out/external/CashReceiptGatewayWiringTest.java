package github.lms.lemuel.payment.adapter.out.external;

import github.lms.lemuel.payment.application.port.out.CashReceiptGatewayPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 현금영수증 게이트웨이 <b>배선</b> 테스트 — 어느 프로파일에서 어떤 구현이 뜨는지를 못박는다.
 *
 * <p>여기서 지키려는 사고는 하나다: <b>모의 구현이 운영에 올라가는 것</b>. 모의 어댑터는 언제나
 * "발급 성공"을 돌려주므로, 운영에 등록되면 고객 화면에는 발급 완료로 뜨고 국세청에는 아무것도
 * 없는 상태가 조용히 쌓인다. 세금 서류에서 이건 단순 버그가 아니라 미발급 신고 누락이다.
 *
 * <p>컴파일러도 커버리지 게이트도 이 종류의 사고를 보지 못한다 — 애노테이션 한 줄이 빠져도
 * 모든 테스트가 그대로 통과하기 때문이다. 그래서 배선 자체를 단정한다.
 */
class CashReceiptGatewayWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    MockCashReceiptGatewayAdapter.class,
                    DisabledCashReceiptGatewayAdapter.class,
                    CashReceiptGatewayConfig.class);

    @Test
    @DisplayName("운영이 아닌 프로파일에서는 모의 어댑터 하나만 뜬다")
    void mockOnlyOutsideProd() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CashReceiptGatewayPort.class);
            assertThat(context.getBean(CashReceiptGatewayPort.class))
                    .isInstanceOf(MockCashReceiptGatewayAdapter.class);
        });
    }

    @Test
    @DisplayName("운영 프로파일에는 모의 어댑터가 절대 등록되지 않는다")
    void mockNeverInProd() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(MockCashReceiptGatewayAdapter.class));
    }

    @Test
    @DisplayName("운영 + 연동 설정 ON — 실 연동 어댑터가 뜬다")
    void liveInProdWhenEnabled() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.cash-receipt.enabled=true",
                        "app.cash-receipt.base-url=https://pg.example.com",
                        "app.cash-receipt.issue-path=/v1/cash-receipts",
                        "app.cash-receipt.cancel-path=/v1/cash-receipts/cancel",
                        "app.cash-receipt.merchant-id=LEMUEL",
                        "app.cash-receipt.secret-key=secret-key-value")
                .run(context -> {
                    assertThat(context).hasSingleBean(CashReceiptGatewayPort.class);
                    assertThat(context.getBean(CashReceiptGatewayPort.class))
                            .isInstanceOf(LiveCashReceiptGatewayAdapter.class);
                });
    }

    /**
     * 현금영수증 대행 계약이 아직 없는 운영 환경도 있다. 그때 기동을 막으면 부수 기능 하나 때문에
     * 커머스 전체가 못 뜬다 — 대신 "발급 불가"를 명시적으로 돌려주는 어댑터가 그 자리를 채운다.
     */
    @Test
    @DisplayName("운영 + 연동 설정 OFF — 발급 불가 어댑터가 그 자리를 채운다(기동은 계속된다)")
    void disabledInProdWhenNotConfigured() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasSingleBean(CashReceiptGatewayPort.class);
                    assertThat(context.getBean(CashReceiptGatewayPort.class))
                            .isInstanceOf(DisabledCashReceiptGatewayAdapter.class);
                });
    }

    /**
     * 연동을 켜 놓고 자격증명을 주지 않는 것은 "발급되는 줄 알았는데 매 건 실패"로 이어진다.
     * 그 상태는 기동 시점에 멈춘다 — 켠 사람이 바로 알아야 한다.
     */
    @Test
    @DisplayName("연동 ON 인데 자격증명이 비어 있으면 기동을 거부한다(fail-closed)")
    void failsFastWhenEnabledWithoutCredentials() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.cash-receipt.enabled=true",
                        "app.cash-receipt.base-url=https://pg.example.com")
                .run(context -> assertThat(context).hasFailed());
    }
}
