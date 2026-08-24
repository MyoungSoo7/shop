package github.lms.lemuel.order.application.service;

import java.math.BigDecimal;

/**
 * 요청이 주장한 결제 금액이 서버가 상품 마스터로 계산한 금액과 다를 때.
 *
 * <p>{@link IllegalArgumentException} 상속이라 공통 {@code GlobalExceptionHandler} 가 400 으로
 * 매핑한다. 조용히 서버 금액으로 덮어쓰지 않는 이유: 금액 불일치는 위변조이거나 클라이언트가
 * 낡은 가격을 들고 있다는 뜻이고, 둘 다 사용자에게 다시 확인시켜야 할 사건이다. 덮어쓰면
 * 고객은 자기가 본 적 없는 금액을 결제하게 된다.
 */
public class OrderAmountMismatchException extends IllegalArgumentException {

    public OrderAmountMismatchException(Long productId, BigDecimal requested, BigDecimal actual) {
        super("주문 금액이 상품 가격과 다릅니다: productId=" + productId
                + ", 요청=" + requested + ", 실제=" + actual);
    }
}
