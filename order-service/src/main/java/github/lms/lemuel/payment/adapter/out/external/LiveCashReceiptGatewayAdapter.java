package github.lms.lemuel.payment.adapter.out.external;

import github.lms.lemuel.payment.application.port.out.CashReceiptGatewayPort;
import github.lms.lemuel.payment.domain.CashReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 운영용 <b>실</b> 현금영수증 게이트웨이 — {@link CashReceiptApiClient} 왕복을 결과 객체로 옮긴다.
 *
 * <p>{@link MockCashReceiptGatewayAdapter}(모의) 는 {@code @Profile("!prod")} 라 운영에서는 등록되지
 * 않는다. 그 자리를 이 어댑터가 채운다 — 연동 설정이 켜져 있을 때만이고, 꺼져 있으면
 * {@link DisabledCashReceiptGatewayAdapter} 가 대신 들어온다. 배선은
 * {@code CashReceiptGatewayWiringTest} 가 못박는다.
 *
 * <h2>예외를 여기서 멈추는 이유</h2>
 * {@code CashReceiptService} 는 발급 실패를 예외가 아니라 {@code Result.failed} 로 받아 FAILED 로
 * 확정한다. 예외가 서비스까지 올라가면 트랜잭션이 롤백되어 <b>시도한 흔적(REQUESTED 행)까지
 * 사라지고</b>, 대행사 쪽에서 부분 성공했을 경우를 영영 알 수 없게 된다. 그래서 HTTP 왕복에서
 * 나오는 모든 예외는 이 경계에서 결과 객체로 바뀐다.
 *
 * <p>다만 <b>승인번호 없는 성공은 성공으로 치지 않는다</b>. 그대로 넘기면 도메인
 * ({@code markIssued})이 예외를 던져, 결국 위와 같은 롤백이 난다.
 */
public class LiveCashReceiptGatewayAdapter implements CashReceiptGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(LiveCashReceiptGatewayAdapter.class);

    private final CashReceiptApiClient apiClient;

    public LiveCashReceiptGatewayAdapter(CashReceiptApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public Result issue(CashReceipt receipt) {
        try {
            String approvalNumber = apiClient.issue(receipt);
            if (approvalNumber == null || approvalNumber.isBlank()) {
                return Result.failed("대행사가 승인번호를 주지 않았습니다");
            }
            return Result.issued(approvalNumber);
        } catch (RuntimeException e) {
            // 사유에 식별번호 원문이 섞이지 않도록, 로그에도 마스킹된 값만 남긴다.
            log.warn("현금영수증 발급 실패: paymentId={}, 식별번호={}, reason={}",
                    receipt.getPaymentId(), receipt.getIdentifier().masked(), e.toString());
            return Result.failed(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    @Override
    public Result cancel(CashReceipt receipt, String reason) {
        try {
            apiClient.cancel(receipt, reason);
            return Result.ok();
        } catch (RuntimeException e) {
            log.warn("현금영수증 취소 실패: paymentId={}, 승인번호={}, reason={}",
                    receipt.getPaymentId(), receipt.getApprovalNumber(), e.toString());
            return Result.failed(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
