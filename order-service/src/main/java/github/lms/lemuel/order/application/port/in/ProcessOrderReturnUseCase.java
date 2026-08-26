package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.OrderReturnRequest;
import github.lms.lemuel.order.domain.ReturnRequestStatus;

import java.util.Collection;
import java.util.List;

/** 운영자가 처리하는 반품·교환 신청. */
public interface ProcessOrderReturnUseCase {

    /**
     * 신청을 승인한다.
     *
     * <p>승인이 곧 환불은 아니다 — 반품·교환은 물건이 돌아온 뒤에야 돈이 움직인다. 다만 출고 전
     * 취소({@code CANCEL})는 회수할 물건이 없어 승인이 곧 완료이며, 이때만 승인 시점에 환불이 실행된다.
     */
    OrderReturnRequest approve(Long requestId, String operator);

    /** 신청을 거절한다 — 주문 상태는 신청 직전으로 되돌아간다. */
    OrderReturnRequest reject(Long requestId, String reason, String operator);

    /** 회수된 물건이 도착했다. 회수 송장이 있어야 한다. */
    OrderReturnRequest markCollected(Long requestId, String operator);

    /** 교환품을 재배송한다 — 주문이 배송 흐름({@code SHIPPING_PENDING})으로 되돌아가고 신청이 끝난다. */
    OrderReturnRequest shipExchange(Long requestId, String carrier, String trackingNumber, String operator);

    /**
     * 반품 환불을 실행하고 신청을 끝낸다.
     *
     * <p>계좌 송금이 필요한 결제인데 계좌가 비어 있으면 여기서 막는다. PG 로 되돌릴 수 없는 돈을
     * "환불 완료"로 적어 두면 고객은 완료 안내를 받고 입금은 영원히 오지 않는다.
     */
    OrderReturnRequest completeRefund(Long requestId, String operator);

    /** 운영자가 환불 계좌를 채우거나 고친다(오타로 반송되는 일이 잦다). */
    OrderReturnRequest changeRefundAccount(Long requestId, String bankCode, String accountNumber,
                                           String holderName, String operator);

    /** 처리 대기열 — 상태로 걸러 오래된 순으로. */
    List<OrderReturnRequest> queue(Collection<ReturnRequestStatus> statuses, int limit);
}
