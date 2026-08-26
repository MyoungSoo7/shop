package github.lms.lemuel.order.application.port.out;

/**
 * 이 주문의 환불이 <b>어느 길로 나가는가</b>를 결제 컨텍스트에 묻는 포트.
 *
 * <p>주문 슬라이스는 결제 수단을 모른다(알아서도 안 된다). 그런데 "환불받을 계좌를 입력하세요"를
 * 언제 요구해야 하는지는 결제 수단이 정한다 — 카드·간편결제는 승인 취소로 돌아가지만, 무통장·
 * 가상계좌는 사람이 송금해야 한다. 그 한 가지 사실만 이 포트로 건너온다.
 *
 * <p>{@code RefundOrderPaymentPort} 와 나눠 둔 이유: 저쪽은 <b>환불을 실행</b>하는 명령이고
 * 여기는 신청 화면을 그리기 위한 <b>질의</b>다. 한 포트에 섞으면 조회를 하려다 환불을 부를 수 있는
 * 인터페이스가 된다.
 */
public interface LoadOrderRefundRoutePort {

    /**
     * 이 주문의 환불이 계좌 송금으로만 가능한지 — 즉 {@code TenderType.awaitsDeposit()} 인 수단이
     * 섞여 있는지.
     *
     * <p>결제가 아직 없는 주문(미결제 취소)은 되돌릴 돈이 없으므로 {@code false} 다.
     */
    boolean requiresBankRefund(Long orderId);
}
