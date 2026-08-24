package github.lms.lemuel.payment.application.port.in;

import github.lms.lemuel.payment.domain.CashReceipt;
import github.lms.lemuel.payment.domain.CashReceiptIdentifier;
import github.lms.lemuel.payment.domain.CashReceiptPurpose;

import java.util.Optional;

/**
 * 현금영수증 발급·조회·취소 유스케이스.
 *
 * <p>계좌이체·가상계좌로 받은 돈은 카드 매출전표가 없어 <b>발급하지 않으면 어디에도 신고되지 않는다</b>.
 * 개인에게는 소득공제가, 사업자에게는 매입세액공제가 그대로 사라진다.
 */
public interface CashReceiptUseCase {

    /**
     * 발급 신청. 대상 판정(현금성 수단·결제 완료)과 중복 방지는 이 경로가 책임진다.
     *
     * @param requesterUserId 신청자 — 결제 소유자와 대조한다(IDOR 방지)
     */
    CashReceipt issue(IssueCommand command);

    /**
     * 주문 기준 발급 신청 — <b>고객이 손에 쥔 식별자는 주문번호</b>다. 결제 id 는 화면에 노출되지도,
     * 주문 응답에 실리지도 않으므로 사용자 경로에서는 주문으로 받아 내부에서 결제를 해석한다.
     */
    CashReceipt issueForOrder(Long orderId, Long requesterUserId, CashReceiptPurpose purpose,
                              CashReceiptIdentifier.Type identifierType, String identifierValue);

    /** 결제 1 건의 <b>유효한</b> 현금영수증(요청 중·발급·취소요청). 실패·취소 건은 돌려주지 않는다. */
    Optional<CashReceipt> findActiveByPayment(Long paymentId, Long requesterUserId);

    /** 주문 기준 조회. 결제가 없는 주문이면 빈 값. */
    Optional<CashReceipt> findActiveByOrder(Long orderId, Long requesterUserId);

    /**
     * 취소. 환불로 돈이 되돌아가면 영수증도 함께 취소해야 한다 — 남겨 두면 받지 않은 돈에
     * 공제가 붙는다. 취소할 영수증이 없으면 아무 일도 하지 않는다(멱등).
     */
    void cancelForPayment(Long paymentId, String reason);

    record IssueCommand(
            Long paymentId,
            Long requesterUserId,
            CashReceiptPurpose purpose,
            CashReceiptIdentifier.Type identifierType,
            String identifierValue
    ) { }
}
