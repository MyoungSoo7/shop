package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.order.application.port.in.ViewOrderStatusHistoryUseCase;
import github.lms.lemuel.order.application.port.in.ViewOrderStatusHistoryUseCase.OrderStatusTimeline;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 주문 상태 이력 운영 콘솔.
 *
 * <pre>
 *   GET /orders/admin/{orderId}/status-history
 * </pre>
 *
 * <p>"이 주문 왜 이 상태예요?" 에 대한 답이 이 한 번의 호출로 끝나게 하는 것이 목적이다. 지금까지
 * 이 질문의 답은 운영 DB 의 {@code order_status_history} 를 손으로 조회하는 것뿐이었다 — 그건 CS 에게
 * DB 접근 권한을 주거나 개발자가 매번 대신 조회하거나 둘 중 하나를 뜻했다.
 *
 * <p><b>경로를 {@code /admin/orders} 가 아니라 {@code /orders/admin} 으로 둔 것은 의도적이다.</b>
 * 이 저장소에서 주문 <i>한 건</i>에 대한 운영자 조작은 이미 {@code /orders/admin/{id}/refund-approve}
 * · {@code /orders/admin/{id}/shipping-status} 처럼 이 아래에 모여 있고, SecurityConfig 의
 * {@code /orders/admin/**} 매처(ADMIN/MANAGER)와 게이트웨이의 {@code /orders/**} 라우트가 이미 덮는다.
 * 새 접두사를 만들면 <b>두 곳에 줄을 더 넣어야 하고, 빠뜨리면 각각 조용히 샌다</b> —
 * SecurityConfig 를 빠뜨리면 {@code anyRequest().authenticated()} 로 떨어져 로그인만 하면 남의 주문
 * 이력이 열리고, 게이트웨이를 빠뜨리면 전부 초록불인 채로 404 가 난다(실제로 {@code /admin/revenue} 등
 * 넷이 그렇게 빠져 있었다). 이미 보호되는 접두사에 얹는 쪽이 안 틀린다.
 *
 * <p>페이징이 없다. 한 주문의 상태 변경은 많아야 수십 건이고, 잘라서 보여주는 이력은 CS 가 찾는 바로
 * 그 한 줄이 잘린 쪽에 있을 때 아무 쓸모가 없다.
 */
@RestController
@RequestMapping("/orders")
public class AdminOrderStatusHistoryController {

    private final ViewOrderStatusHistoryUseCase useCase;

    public AdminOrderStatusHistoryController(ViewOrderStatusHistoryUseCase useCase) {
        this.useCase = useCase;
    }

    @Operation(summary = "주문 상태 이력 조회",
            description = "한 주문의 상태 전이를 시간순으로, 각 상태의 체류 시간과 함께 조회한다. "
                    + "historyMatchesOrder=false 면 이력을 남기지 않은 전이 경로가 있다는 뜻이다.")
    @GetMapping("/admin/{orderId}/status-history")
    public ResponseEntity<OrderStatusTimeline> history(@PathVariable("orderId") Long orderId) {
        return ResponseEntity.ok(useCase.view(orderId, LocalDateTime.now()));
    }
}
