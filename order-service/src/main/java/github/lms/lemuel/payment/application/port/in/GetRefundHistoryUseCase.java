package github.lms.lemuel.payment.application.port.in;

import github.lms.lemuel.payment.domain.Refund;

import java.util.List;

public interface GetRefundHistoryUseCase {

    List<Refund> getRefundsByPaymentId(Long paymentId);

    /** 상태별 환불 조회(최신순) — 관리자 콘솔에서 FAILED 등 운영 대상 환불을 훑을 때 사용. */
    List<Refund> getRefundsByStatus(Refund.Status status);
}
