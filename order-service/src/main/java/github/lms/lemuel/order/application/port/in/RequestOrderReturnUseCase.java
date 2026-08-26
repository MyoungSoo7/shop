package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.OrderReturnRequest;
import github.lms.lemuel.order.domain.ReturnRequestType;

import java.util.List;

/** 고객이 내는 반품·교환·취소 신청. */
public interface RequestOrderReturnUseCase {

    /**
     * 신청 명령.
     *
     * <p>계좌 3 칸을 통째로 받는 이유: 화면은 세 칸이 각각 비어 있을 수 있는 폼이고, "계좌를 안 냈다"와
     * "반쪽만 냈다"의 구분은 도메인({@code RefundAccount.ofNullable})이 한다. 여기서 미리 조립하면
     * 반쪽 계좌가 조용히 null 로 떨어져 "계좌를 안 낸 신청"이 된다.
     */
    record SubmitCommand(Long orderId,
                         Long userId,
                         ReturnRequestType type,
                         String reasonCode,
                         String reasonDetail,
                         String refundBankCode,
                         String refundAccountNumber,
                         String refundAccountHolder,
                         String requestedBy) {
    }

    OrderReturnRequest submit(SubmitCommand command);

    /** 고객(또는 대신 적어 주는 운영자)이 회수 송장을 등록한다. */
    OrderReturnRequest registerReturnWaybill(Long requestId, String carrier, String trackingNumber, String actor);

    /** 고객이 신청을 거둬들인다 — 주문 상태도 신청 직전으로 되돌아간다. */
    OrderReturnRequest withdraw(Long requestId, String reason, String actor);

    OrderReturnRequest getById(Long requestId);

    /** 그 주문의 신청 이력 — 최근 것이 앞. */
    List<OrderReturnRequest> findByOrderId(Long orderId);
}
