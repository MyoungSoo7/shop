package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.OrderReturnRequest;

/** 반품·교환 신청 저장. */
public interface SaveOrderReturnRequestPort {

    /**
     * 신청을 저장하고 식별자가 채워진 결과를 돌려준다.
     *
     * <p>같은 주문에 열려 있는 신청이 이미 있으면 DB 의 부분 유니크 인덱스가 막는다
     * ({@code ux_order_return_requests_open}). 응용 계층의 사전 검사만으로는 동시에 두 번 눌린
     * "반품 신청"을 막지 못한다 — 두 트랜잭션이 각자 "열린 신청 없음"을 읽는다.
     */
    OrderReturnRequest save(OrderReturnRequest request);
}
