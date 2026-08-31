package github.lms.lemuel.seller.adapter.in.web;

import github.lms.lemuel.seller.application.port.dto.SellerOrderPage;
import github.lms.lemuel.seller.application.port.dto.SellerOrderQuery;
import github.lms.lemuel.seller.application.port.dto.SellerOrderView;
import github.lms.lemuel.seller.application.port.in.RegisterShipmentUseCase;
import github.lms.lemuel.seller.application.port.in.ResolveSellerScopeUseCase;
import github.lms.lemuel.seller.application.port.in.ViewSellerOrdersUseCase;
import github.lms.lemuel.seller.domain.SellerScope;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 내 상품이 주문된 것 — 목록·단건과, 송장 등록.
 *
 * <p>{@code unshippedOnly} 가 이 화면의 실사용 필터다. 다만 여기서 "미출고" 는
 * <b>우리가 아직 송장을 등록하지 않았다</b>는 뜻이지, order-service 의 배송 상태를 다시
 * 계산한 것이 아니다. 이 서비스는 자기가 무엇을 요청했는지만 안다.
 */
@RestController
@RequestMapping("/api/seller/orders")
@RequiredArgsConstructor
public class SellerOrderController {

    private final ResolveSellerScopeUseCase resolveScope;
    private final ViewSellerOrdersUseCase viewOrders;
    private final RegisterShipmentUseCase registerShipment;

    @GetMapping
    public SellerOrderPage orders(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long orderId,
            @RequestParam(defaultValue = "false") boolean unshippedOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return viewOrders.orders(scope(), new SellerOrderQuery(from, to, orderId, unshippedOnly, page, size));
    }

    /**
     * 단건. 조회 기간에 매이지 않는다 — 목록에서 넘어온 링크를 나중에 다시 열었을 때 기본 30일
     * 밖이라는 이유로 404 가 나면, 존재하는 주문이 사라진 것처럼 보인다.
     */
    @GetMapping("/{orderId}")
    public SellerOrderView order(@PathVariable long orderId) {
        return viewOrders.order(scope(), orderId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "주문을 찾을 수 없습니다."));
    }

    /**
     * 송장 등록 — 셀러가 "보냈다" 고 말하는 유일한 경로. 주문당 한 번뿐이고 정정 경로는 없다.
     *
     * <p>본문만 받고 응답 본문은 두지 않는다. 여기서 무엇을 돌려줘도 그건 <b>요청을 접수했다</b>는
     * 뜻일 뿐이고, 실제 배송 전이는 order-service 가 이벤트를 받아 처리한다. 그 시차에 만들어진
     * 값을 돌려주면 화면이 그걸 확정으로 읽는다.
     */
    @PostMapping("/{orderId}/shipment")
    public ResponseEntity<Void> registerShipment(@PathVariable long orderId,
                                                 @RequestBody ShipmentRequest request) {
        long userId = CurrentSellerUser.requireUserId();
        registerShipment.register(resolveScope.resolve(userId), userId, orderId,
                request.carrier(), request.trackingNumber());
        return ResponseEntity.accepted().build();
    }

    private SellerScope scope() {
        return resolveScope.resolve(CurrentSellerUser.requireUserId());
    }

    /** 송장 등록 요청 본문. 값 검증(공백·길이)은 서비스가 한다. */
    public record ShipmentRequest(String carrier, String trackingNumber) {
    }
}
