package github.lms.lemuel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 게이트웨이 스모크 검증 — 컨텍스트가 부팅되고 application.yml 의 라우트 정의가
 * {@link RouteLocator} 로 실제 로드되는지 확인한다(백엔드 URI 는 기본값 사용, 실 연결 없음).
 *
 * <p>RANDOM_PORT 로 Netty 를 띄워 리액티브 웹 스택 조립까지 포함해 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayServiceApplicationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    RouteLocator routeLocator;

    @Test
    @DisplayName("스프링 컨텍스트가 정상 부팅된다")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("application.yml 의 서비스 라우트가 RouteLocator 에 로드된다")
    void routesAreConfigured() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).isNotNull().isNotEmpty();
        // 라우트 id 는 application.yml 이 정본이다. 서비스를 합치거나 떼어 내면 이 목록도 함께
        // 고쳐야 한다 — 예전에 흡수 커밋이 이 단정을 안 고쳐 게이트가 빨간 채로 방치된 적이 있다.
        // notification-stream 은 operation 이 서빙하는 알림 SSE 라우트다(ADR 0041).
        // marketing-service 는 이벤트 프로모션(출석·럭키박스) 라우트다(ADR 0045).
        // partner-service 는 입점사 콘솔(/api/partner, 읽기 전용) 라우트다.
        assertThat(routes).extracting(Route::getId)
                .containsExactlyInAnyOrder(
                        "order-service-orders", "operation-service", "marketing-service",
                        "partner-service", "notification-stream");
    }
}
