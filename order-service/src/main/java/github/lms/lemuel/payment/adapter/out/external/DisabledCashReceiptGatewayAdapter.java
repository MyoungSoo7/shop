package github.lms.lemuel.payment.adapter.out.external;

import github.lms.lemuel.payment.application.port.out.CashReceiptGatewayPort;
import github.lms.lemuel.payment.domain.CashReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 현금영수증 대행 연동이 구성되지 않은 운영 환경의 자리 채우기.
 *
 * <p>대행 계약이 아직 없는 배포도 있다. 그때 기동을 막아 버리면 부수 기능 하나 때문에 커머스
 * 전체가 못 뜬다. 그렇다고 {@link MockCashReceiptGatewayAdapter} 를 올릴 수도 없다 — 모의는 언제나
 * "발급 성공"이라, 고객 화면에는 발급 완료로 뜨는데 국세청에는 아무것도 없는 상태가 조용히 쌓인다.
 *
 * <p>그래서 이 어댑터는 <b>명시적으로 실패</b>한다. 영수증은 FAILED 로 사유와 함께 남고, 자리는
 * 비어 있어(부분 UNIQUE 인덱스가 활성 건만 잡는다) 연동을 붙인 뒤 재신청할 수 있다.
 * 발급되지 않은 것을 발급됐다고 기록하는 쪽이, 실패가 눈에 보이는 쪽보다 언제나 위험하다.
 */
@Component
@Profile("prod")
@ConditionalOnProperty(name = "app.cash-receipt.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledCashReceiptGatewayAdapter implements CashReceiptGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(DisabledCashReceiptGatewayAdapter.class);

    private static final String REASON =
            "현금영수증 대행 연동이 구성되지 않았습니다(app.cash-receipt.enabled=false)";

    @Override
    public Result issue(CashReceipt receipt) {
        log.warn("현금영수증 발급 불가 — 연동 미구성: paymentId={}, amount={}",
                receipt.getPaymentId(), receipt.getTotalAmount());
        return Result.failed(REASON);
    }

    @Override
    public Result cancel(CashReceipt receipt, String reason) {
        log.warn("현금영수증 취소 불가 — 연동 미구성: paymentId={}", receipt.getPaymentId());
        return Result.failed(REASON);
    }
}
