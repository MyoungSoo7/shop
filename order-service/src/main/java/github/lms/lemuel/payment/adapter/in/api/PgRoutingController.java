package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.payment.adapter.out.pg.PgRouter;
import github.lms.lemuel.payment.domain.PaymentGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 운영자용 PG 라우팅 점검 API.
 *
 * <p>장애 발생 시 어느 PG 가 OPEN 인지 즉시 확인하고, Grafana 의 {@code pg.routing.requests}
 * 카운터와 교차 분석할 수 있게 한다.
 */
@Tag(name = "PG Routing Admin", description = "다중 PG 라우터 상태 점검")
@RestController
@RequestMapping("/admin/pg")
public class PgRoutingController {

    private final PgRouter router;

    public PgRoutingController(PgRouter router) {
        this.router = router;
    }

    @Operation(summary = "PG 별 health 상태",
            description = "각 PG 어댑터의 CircuitBreaker 가 OPEN 이면 false. 라우터는 false 인 PG 를 후보에서 제외한다.")
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<PaymentGateway, Boolean> snapshot = router.healthSnapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Boolean> providers = new LinkedHashMap<>();
        snapshot.forEach((pg, ok) -> providers.put(pg.name(), ok));
        body.put("providers", providers);
        body.put("healthy", snapshot.values().stream().allMatch(Boolean::booleanValue));
        return ResponseEntity.ok(body);
    }
}
