package github.lms.lemuel.payment.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

import java.math.BigDecimal;

/**
 * PG 승인 요청 금액이 서버가 보관한 주문 금액과 다를 때 던진다.
 *
 * <p><b>왜 필요한가</b> — Toss 는 "결제창을 열 때 등록한 금액 == confirm 금액" 만 대조한다.
 * 결제창 금액도 브라우저가 정하므로, 서버가 두 값을 이어주지 않으면 클라이언트가 10,000원짜리
 * 주문에 대해 1,000원짜리 결제창을 열어 정상 승인을 받고 주문은 전액 결제된 것으로 기록시킬 수
 * 있다. PG 는 이 공격을 막아주지 못한다 — 자기가 아는 금액끼리는 일치하기 때문이다.
 * 대조 주체는 주문 금액을 아는 우리 서버뿐이고, 그래서 PG 호출 <b>전에</b> 검사한다.
 */
public class PaymentAmountMismatchException extends BusinessException {

    public PaymentAmountMismatchException(Long orderId, BigDecimal expected, BigDecimal requested) {
        super(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                "결제 금액이 주문 금액과 일치하지 않습니다: orderId=" + orderId
                        + ", 주문금액=" + expected + ", 요청금액=" + requested);
    }

    public PaymentAmountMismatchException(String message) {
        super(ErrorCode.PAYMENT_AMOUNT_MISMATCH, message);
    }
}
