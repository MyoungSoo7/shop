package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.Order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 주문 조회 유스케이스 — 페이지와 집계.
 *
 * <p><b>왜 필요한가</b>: 관리자 화면이 쓰던 {@code GET /orders/admin/all} 은 <b>전 주문을
 * 페이징 없이</b> 돌려줬다. 주문은 회원과 달리 지우지 않고 계속 쌓이므로, 이 API 는 시간이
 * 지나면 반드시 느려지다 죽는다 — 그리고 죽기 전까지는 잘 도는 것처럼 보인다. 배송 관리
 * 화면은 그 목록을 받아 <b>주문마다 배송 조회를 한 번 더</b> 했으므로, 목록 하나가 요청
 * N+1 개로 번지고 있었다.
 *
 * <p><b>왜 목록과 집계가 같이 있나</b>: 목록을 자르면 화면이 "전체 주문 수"·"매출 합계"를
 * 배열에서 셀 수 없게 된다. 그런데 그 계산은 사라지지 않고 <b>조용히 첫 페이지만 세는
 * 계산</b>이 된다 — 화면에는 여전히 숫자가 찍히고, 그 숫자가 틀렸다고 말해 주는 것이 없다.
 * 그래서 자르는 것과 세는 것을 함께 넣는다. 집계는 DB 가 전 범위에서 하고, 목록만 자른다.
 */
public interface SearchOrdersUseCase {

    /** 조건에 맞는 주문을 최신순 한 페이지로 조회한다. */
    OrderPage search(OrderQuery query);

    /**
     * 같은 조건의 상태별 건수·금액 합계.
     *
     * <p>목록과 달리 <b>페이지에 잘리지 않는다</b>. 대시보드의 카드와 상태 분포 막대는
     * 반드시 이 값으로 그린다.
     */
    List<OrderStatusCount> countByStatus(OrderQuery query);

    /**
     * 조회 조건.
     *
     * @param statuses 상태 정확일치(OR). 비었으면 미적용. 여럿인 이유는 승인 큐가 "취소 신청 +
     *                 환불 신청"을 한 화면에서 보기 때문이다 — 전건을 받아 클라이언트가 걸러내던
     *                 방식은 페이징이 붙는 순간 대기 건을 조용히 빠뜨린다
     * @param from     주문일시 시작(포함). null 이면 미적용
     * @param to       주문일시 종료(미포함). null 이면 미적용
     */
    record OrderQuery(
            List<String> statuses,
            LocalDateTime from,
            LocalDateTime to,
            int page,
            int size) {

        /** 조건 없이 첫 페이지. */
        public static OrderQuery firstPage(int size) {
            return new OrderQuery(List.of(), null, null, 0, size);
        }
    }

    /** 한 페이지. */
    record OrderPage(
            List<Order> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    /**
     * 상태별 건수와 금액 합계.
     *
     * <p>상태를 enum 이 아니라 DB 에 적힌 문자열 그대로 담는다. enum 으로 바꾸면 enum 이
     * 모르는 값(구 버전이 남긴 상태 등)을 만났을 때 통째로 터지거나 조용히 다른 상태로
     * 둔갑하는데, 집계는 <b>DB 에 실제로 무엇이 있는지</b>를 말해야 하는 자리다.
     */
    record OrderStatusCount(String status, long count, BigDecimal amountSum) {
    }
}
