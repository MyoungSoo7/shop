package github.lms.lemuel.payment.adapter.out.pg;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG 어댑터 4종이 전부 모의라는 사실을 <b>운영 기동 차단</b>으로 못박는다.
 *
 * <p>{@code TossPgAdapter} 등은 이름과 달리 실제 PG 를 호출하지 않는다(승인 ID 를 UUID 로 만들고
 * capture/refund 는 no-op). 그런데 어떤 프로파일 게이트도 없어서, 운영 프로파일로 띄우면 결제가
 * "성공"하고 주문·정산까지 그대로 흘러간다 — 돈은 한 푼도 움직이지 않은 채로.
 *
 * <p>{@link PgRouter} 는 어댑터를 {@code List} 로 주입받으므로 빈 목록이어도 컨텍스트는 뜬다.
 * 그래서 프로파일 게이트만으로는 부족하고, 라우터가 스스로 "결제 가능한 PG 가 하나도 없다"를
 * 기동 시점에 거부해야 한다. 두 장치가 함께 있어야 fail-closed 가 성립한다.
 */
class PgAdapterProdGateTest {

    @Configuration
    static class Support {
        @Bean CircuitBreakerRegistry circuitBreakerRegistry() { return CircuitBreakerRegistry.ofDefaults(); }
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Support.class,
                    TossPgAdapter.class, KcpPgAdapter.class, NicePgAdapter.class, InicisPgAdapter.class);

    @Test
    @DisplayName("prod 프로파일에서는 모의 PG 어댑터가 하나도 등록되지 않는다")
    void mockPgAdaptersAreNotRegistered_inProdProfile() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(PaymentGatewayAdapter.class));
    }

    @Test
    @DisplayName("비운영에서는 모의 PG 어댑터 4종이 등록된다 (로컬·시연 경로 보존)")
    void mockPgAdaptersAreRegistered_outsideProd() {
        runner.run(ctx -> assertThat(ctx.getBeansOfType(PaymentGatewayAdapter.class)).hasSize(4));
    }

    @Test
    @DisplayName("결제 가능한 PG 가 하나도 없으면 PgRouter 가 기동을 거부한다")
    void routerRefusesToStart_whenNoAdapterAvailable() {
        new ApplicationContextRunner()
                .withUserConfiguration(Support.class, PgRouter.class)
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("PG"));
    }
}
