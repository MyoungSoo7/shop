package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.SearchOrdersUseCase;
import github.lms.lemuel.order.application.port.out.SearchOrdersPort;
import github.lms.lemuel.order.application.port.out.SearchOrdersPort.OrderCriteria;
import github.lms.lemuel.order.domain.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 주문 조회 서비스 — 조건 정규화와 페이지 상한이 이 계층의 일이다.
 *
 * <p><b>size 상한을 두는 이유</b>: 페이징은 클라이언트가 지키기로 한 약속이 아니라 서버가 거는
 * 제한이어야 한다. 상한이 없으면 {@code size=1000000} 호출 한 번으로 무페이징 시절이 그대로
 * 돌아오고, 그 호출은 <b>정상 요청처럼 보인다</b>.
 *
 * <p><b>기간에 기본값을 두지 않는다</b>: 운영자가 찾는 주문은 대개 언제 들어왔는지 모르는
 * 주문이다. "최근 30일"을 몰래 깔면 오래된 주문을 찾을 때마다 빈 화면이 나오고, 운영자는
 * 그 이유를 알 수 없다. 응답 크기는 기간이 아니라 페이지 크기로 지킨다.
 */
@Service
public class SearchOrdersService implements SearchOrdersUseCase {

    /** 한 페이지 최대 건수. */
    public static final int MAX_PAGE_SIZE = 200;

    /** 한 페이지 기본 건수. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    private final SearchOrdersPort searchOrdersPort;

    public SearchOrdersService(SearchOrdersPort searchOrdersPort) {
        this.searchOrdersPort = searchOrdersPort;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPage search(OrderQuery query) {
        OrderCriteria criteria = toCriteria(query);
        int page = Math.max(query.page(), 0);
        int size = normalizeSize(query.size());

        long total = searchOrdersPort.count(criteria);
        List<Order> content = total == 0
                ? List.of()
                : searchOrdersPort.search(criteria, page, size);

        int totalPages = (int) ((total + size - 1) / size);
        return new OrderPage(content, page, size, total, totalPages);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderStatusCount> countByStatus(OrderQuery query) {
        return searchOrdersPort.countByStatus(toCriteria(query));
    }

    /**
     * 화면 질의를 어댑터 조건으로 옮긴다.
     *
     * <p>뒤집힌 기간은 거부하지 않고 바로잡는다 — 달력에서 순서를 바꿔 고르는 일은 흔하고,
     * 그때 에러를 던지면 운영자는 아무 주문도 못 본다.
     */
    private OrderCriteria toCriteria(OrderQuery query) {
        LocalDateTime from = query.from();
        LocalDateTime to = query.to();
        if (from != null && to != null && from.isAfter(to)) {
            LocalDateTime swap = from;
            from = to;
            to = swap;
        }

        List<String> statuses = query.statuses() == null ? List.of() : query.statuses().stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toUpperCase())
                .distinct()
                .toList();

        return new OrderCriteria(statuses, from, to);
    }

    private static int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
