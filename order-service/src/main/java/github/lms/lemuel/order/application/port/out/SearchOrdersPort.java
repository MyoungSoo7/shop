package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.application.port.in.SearchOrdersUseCase.OrderStatusCount;
import github.lms.lemuel.order.domain.Order;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 주문 조회 포트.
 *
 * <p>기간은 이미 정규화된 반개구간({@code createdFrom} 이상 {@code createdToExclusive} 미만)으로
 * 받는다. 경계를 어떻게 해석할지는 정책이라 서비스가 정하고, 어댑터는 다시 계산하지 않는다.
 */
public interface SearchOrdersPort {

    /** 조건에 맞는 주문을 최신순으로 한 페이지 조회한다. */
    List<Order> search(OrderCriteria criteria, int page, int size);

    /** 같은 조건의 총 건수. */
    long count(OrderCriteria criteria);

    /** 같은 조건의 상태별 건수·금액 합계. */
    List<OrderStatusCount> countByStatus(OrderCriteria criteria);

    /**
     * 정규화된 조회 조건.
     *
     * @param statuses          비었으면 상태 조건 미적용
     * @param createdFrom       null 이면 미적용
     * @param createdToExclusive null 이면 미적용
     */
    record OrderCriteria(
            List<String> statuses,
            LocalDateTime createdFrom,
            LocalDateTime createdToExclusive) {
    }
}
