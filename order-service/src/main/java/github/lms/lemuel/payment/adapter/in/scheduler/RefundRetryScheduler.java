package github.lms.lemuel.payment.adapter.in.scheduler;

import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.domain.Refund;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PG 환불 실패건 자동 재시도 스케줄러.
 *
 * <p>{@code refunds.status = FAILED} 이면서 {@code next_retry_at} 이 도래한 건을 주기적으로 찾아
 * 원래 멱등 키로 {@link RefundPaymentPort#refundPayment} 를 재호출한다. 재호출은 결제 행 비관 락 +
 * 멱등 키(동일 키 COMPLETED 면 no-op)로 보호되므로 다중 인스턴스가 같은 건을 동시에 집어도 이중 환불되지
 * 않는다(별도 분산 락 불필요). 재시도가 또 실패하면 유스케이스가 {@code retry_count} 를 늘리고 백오프로
 * 다음 시각을 재예약하며, 상한({@link Refund#MAX_RETRIES})에 도달하면 {@code next_retry_at} 이 비워져
 * 이 스케줄러의 대상에서 빠지고 관리자 개입 대상(/admin/refunds?status=FAILED)으로 남는다.
 */
@Component
public class RefundRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefundRetryScheduler.class);

    private final LoadRefundPort loadRefundPort;
    private final RefundPaymentPort refundPaymentPort;

    // 필드 초기값(100)은 단위 테스트(@Value 미주입 컨텍스트)용 안전 기본값 — 런타임엔 @Value 가 덮어쓴다.
    @Value("${refund.retry.batch-limit:100}")
    private int batchLimit = 100;

    public RefundRetryScheduler(LoadRefundPort loadRefundPort, RefundPaymentPort refundPaymentPort) {
        this.loadRefundPort = loadRefundPort;
        this.refundPaymentPort = refundPaymentPort;
    }

    @Scheduled(
            fixedDelayString = "${refund.retry.delay-ms:60000}",
            initialDelayString = "${refund.retry.initial-delay-ms:30000}")
    public void retryFailedRefunds() {
        List<Refund> due = loadRefundPort.findRetryable(LocalDateTime.now());
        if (due.isEmpty()) {
            return;
        }
        int limited = Math.min(due.size(), batchLimit);
        if (due.size() > batchLimit) {
            log.info("환불 재시도 대상 {}건 중 {}건만 이번 주기에 처리(batchLimit)", due.size(), batchLimit);
        }
        log.info("환불 자동 재시도 시작 — 대상 {}건", limited);

        int succeeded = 0;
        int failed = 0;
        for (Refund refund : due.subList(0, limited)) {
            try {
                // 저장된 금액·멱등 키로 재호출 — 같은 키라 이중 환불 없이 이어서 시도된다.
                refundPaymentPort.refundPayment(refund.getPaymentId(), refund.getAmount(), refund.getIdempotencyKey());
                succeeded++;
            } catch (RuntimeException e) {
                // 개별 실패는 유스케이스가 FAILED 재기록(retry_count 증가·백오프)하므로 여기선 삼키고 다음 건 진행.
                failed++;
                log.warn("환불 재시도 실패(다음 주기에 재시도). refundId={}, paymentId={}, retryCount={}, 원인={}",
                        refund.getId(), refund.getPaymentId(), refund.getRetryCount(), e.getMessage());
            }
        }
        log.info("환불 자동 재시도 종료 — 성공 {}건, 실패 {}건", succeeded, failed);
    }
}
