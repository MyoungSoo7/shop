package github.lms.lemuel.payment.adapter.out.external;

import github.lms.lemuel.payment.application.port.out.CashReceiptGatewayPort;
import github.lms.lemuel.payment.domain.CashReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 현금영수증 발급 대행 모의 어댑터 — 실제 국세청/PG 연동이 붙기 전까지의 자리 표시자.
 *
 * <p>승인번호는 <b>결제 id 로부터 결정적으로</b> 만든다. 난수를 쓰면 같은 결제가 두 번 발급됐을 때
 * 서로 다른 번호가 남아 데이터만 봐서는 이중 발급을 구분할 수 없다. 결정적 번호면 중복이 눈에 띈다.
 *
 * <p><b>운영에서는 등록되지 않는다</b>({@code @Profile("!prod")}). 모의 구현이 운영에 올라가면
 * <b>고객에게는 발급됐다고 표시되는데 국세청에는 아무것도 없는</b> 상태가 조용히 쌓이고, 세금
 * 서류에서 그것은 단순 버그가 아니라 미발급 신고 누락이다. 애노테이션 한 줄이 빠져도 모든
 * 테스트가 통과하므로, 배선 자체를 {@code CashReceiptGatewayWiringTest} 가 단정한다.
 * 운영에서는 {@link LiveCashReceiptGatewayAdapter}(연동 ON) 또는
 * {@link DisabledCashReceiptGatewayAdapter}(연동 OFF)가 이 자리를 채운다.
 *
 * <p>발급·취소마다 WARN 을 남기는 것은 개발·검증 환경에서도 "이건 진짜가 아니다"가 로그에
 * 보이게 하기 위해서다.
 */
@Component
@Profile("!prod")
public class MockCashReceiptGatewayAdapter implements CashReceiptGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(MockCashReceiptGatewayAdapter.class);

    @Override
    public Result issue(CashReceipt receipt) {
        log.warn("현금영수증 모의 발급 — 실제 국세청 연동 아님: paymentId={}, amount={}",
                receipt.getPaymentId(), receipt.getTotalAmount());
        return Result.issued(String.format("MOCK-%010d", receipt.getPaymentId()));
    }

    @Override
    public Result cancel(CashReceipt receipt, String reason) {
        log.warn("현금영수증 모의 취소 — 실제 국세청 연동 아님: paymentId={}, approvalNumber={}, reason={}",
                receipt.getPaymentId(), receipt.getApprovalNumber(), reason);
        return Result.ok();
    }
}
