package github.lms.lemuel.payment.application.port.out;

import github.lms.lemuel.payment.domain.CashReceipt;

import java.util.Optional;

/**
 * 현금영수증 영속 포트.
 *
 * <p>"결제 1 건당 유효 1 건"은 애플리케이션 검사만으로는 지킬 수 없다(동시 신청). DB 부분 UNIQUE
 * 인덱스가 최종 방어선이고, 여기 조회는 사용자에게 친절한 사전 안내를 위한 소프트 체크다.
 */
public interface CashReceiptPort {

    CashReceipt save(CashReceipt receipt);

    Optional<CashReceipt> findById(Long id);

    /** 유효 자리를 차지하는 건(REQUESTED·ISSUED·CANCEL_REQUESTED)만. 실패·취소 건은 재발급을 막지 않는다. */
    Optional<CashReceipt> findActiveByPaymentId(Long paymentId);
}
