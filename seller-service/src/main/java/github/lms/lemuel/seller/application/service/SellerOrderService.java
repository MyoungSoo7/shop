package github.lms.lemuel.seller.application.service;

import github.lms.lemuel.seller.application.port.dto.SellerOrderPage;
import github.lms.lemuel.seller.application.port.dto.SellerOrderQuery;
import github.lms.lemuel.seller.application.port.dto.SellerOrderView;
import github.lms.lemuel.seller.application.port.in.ViewSellerOrdersUseCase;
import github.lms.lemuel.seller.application.port.out.SellerOrderQueryPort;
import github.lms.lemuel.seller.domain.SellerScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 내 상품이 주문된 것 — 목록·상세.
 *
 * <p>두 경로가 같은 조회 포트를 쓰고, 둘 다 첫 줄에서 {@code scope.requireSellerId()} 를
 * 통과한다. 상세를 별도 쿼리로 만들면 언젠가 한쪽에만 필터가 빠진다.
 */
@Service
@Transactional(readOnly = true)
public class SellerOrderService implements ViewSellerOrdersUseCase {

    private final SellerOrderQueryPort orderQueryPort;
    private final Clock clock;

    public SellerOrderService(SellerOrderQueryPort orderQueryPort, Clock clock) {
        this.orderQueryPort = orderQueryPort;
        this.clock = clock;
    }

    @Override
    public SellerOrderPage orders(SellerScope scope, SellerOrderQuery query) {
        long sellerId = scope.requireSellerId();
        SellerOrderQuery q = query.normalized(LocalDate.now(clock));

        long total = orderQueryPort.countOrders(sellerId, q.from(), q.to(), q.orderId(), q.unshippedOnly());
        List<SellerOrderView> content = total == 0
                // 총건수가 0 이면 두 번째 쿼리는 반드시 빈 결과다. 안 쏘는 게 맞다.
                ? List.of()
                : orderQueryPort.findOrders(sellerId, q.from(), q.to(), q.orderId(), q.unshippedOnly(),
                        q.size(), (long) q.page() * q.size());

        int totalPages = (int) Math.ceil((double) total / q.size());
        return new SellerOrderPage(content, q.page(), q.size(), total, totalPages);
    }

    @Override
    public Optional<SellerOrderView> order(SellerScope scope, long orderId) {
        long sellerId = scope.requireSellerId();
        // 기간을 열어 두고 주문번호로만 찾는다. 송장 등록 화면은 목록 밖(메일·메모)에서도 열리므로
        // 기본 30일 창을 적용하면 "목록에는 있는데 상세는 없다" 가 된다. 출고 필터도 걸지 않는다 —
        // 이미 등록한 건의 송장번호를 다시 확인하는 것이 이 화면의 두 번째 용도다.
        List<SellerOrderView> found = orderQueryPort.findOrders(
                sellerId, LocalDate.EPOCH, LocalDate.now(clock).plusDays(1), orderId, false, 1, 0L);
        return found.stream().findFirst();
    }
}
