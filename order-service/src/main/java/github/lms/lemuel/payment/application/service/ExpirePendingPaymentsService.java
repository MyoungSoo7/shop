package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.in.ExpirePendingPaymentsUseCase;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentExpiryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 미입금 결제 자동 만료 배치.
 *
 * <p>입금 대기(READY)로 기한이 지난 결제를 만료시키고 그 주문을 취소해 재고를 되돌린다. 미처리 시
 * 결제는 READY, 주문은 CREATED 로 영구 잔류하며 재고를 붙잡고 주문 통계를 왜곡한다.
 *
 * <p><b>트랜잭션 없음(의도)</b> — 이 클래스는 후보를 훑기만 하고, 실제 상태 변경은
 * {@link PaymentExpiryProcessor} 가 건별 독립 트랜잭션에서 수행한다. 한 건의 실패가 나머지를 막지 않는다.
 *
 * <p><b>실패를 삼키지 않는다</b> — 개별 실패는 건별로 잡되 {@code failed} 카운터와 WARN 로그로 드러낸다.
 * 실패 건은 상태가 READY 그대로라 다음 주기가 다시 집는다(멱등).
 */
@Service
public class ExpirePendingPaymentsService implements ExpirePendingPaymentsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpirePendingPaymentsService.class);

    private final LoadPaymentPort loadPaymentPort;
    private final PaymentExpiryProcessor processor;
    private final Duration ttl;
    private final int batchLimit;

    public ExpirePendingPaymentsService(
            LoadPaymentPort loadPaymentPort,
            PaymentExpiryProcessor processor,
            @Value("${app.payment-expiry.ttl:48h}") Duration ttl,
            @Value("${app.payment-expiry.batch-limit:200}") int batchLimit) {
        this.loadPaymentPort = loadPaymentPort;
        this.processor = processor;
        this.ttl = ttl;
        this.batchLimit = batchLimit;
    }

    @Override
    public ExpiryReport expireDue(LocalDateTime now, boolean dryRun) {
        LocalDateTime cutoff = now.minus(ttl);
        List<PaymentDomain> candidates = loadPaymentPort.findPendingCreatedBefore(cutoff, batchLimit);

        int expired = 0;
        int skipped = 0;
        int failed = 0;

        for (PaymentDomain payment : candidates) {
            // 쿼리는 시각만 거르므로 수단·경계 판정은 도메인 정책이 다시 본다(모르는 수단은 만료 대상 아님).
            // 결제 기준 판정을 쓴다 — 수단 문자열만 보면 "SPLIT:CARD" 뒤의 가상계좌 텐더를 놓친다.
            if (!PaymentExpiryPolicy.isExpired(payment, ttl, now)) {
                skipped++;
                continue;
            }
            if (dryRun) {
                expired++;
                continue;
            }
            try {
                processor.expireAndCancelOrder(payment.getId());
                expired++;
            } catch (RuntimeException e) {
                // 다음 주기가 다시 집는다 — 여기서 멈추면 뒤 건들이 통째로 밀린다.
                failed++;
                log.warn("미입금 만료 실패: paymentId={}, orderId={}, 사유={}",
                        payment.getId(), payment.getOrderId(), e.toString());
            }
        }

        ExpiryReport report = new ExpiryReport(candidates.size(), expired, skipped, failed, dryRun);
        if (!candidates.isEmpty()) {
            log.info("미입금 만료 배치{}: 조회={}, 만료={}, 제외={}, 실패={}",
                    dryRun ? "(dryRun)" : "", report.scanned(), report.expired(),
                    report.skipped(), report.failed());
        }
        return report;
    }
}
