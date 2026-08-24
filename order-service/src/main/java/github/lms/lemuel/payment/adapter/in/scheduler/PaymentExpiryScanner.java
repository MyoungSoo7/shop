package github.lms.lemuel.payment.adapter.in.scheduler;

import github.lms.lemuel.payment.application.port.in.ExpirePendingPaymentsUseCase;
import github.lms.lemuel.payment.application.port.in.ExpirePendingPaymentsUseCase.ExpiryReport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 미입금 결제 만료 스캐너.
 *
 * <p>입금 대기(READY)로 기한이 지난 가상계좌·무통장 결제를 만료시키고 그 주문을 취소해 재고를 되돌린다.
 *
 * <p><b>ShedLock 이름은 전역 유일해야 한다</b> — 같은 이름을 두 스케줄러가 공유하면 락 보유 기간 동안
 * 나머지가 조용히 스킵된다(컴파일도 CI 도 못 잡는 실장애 패턴). {@code order-payment-expiry} 는
 * 기존 9개 이름과 겹치지 않는다.
 */
@Component
public class PaymentExpiryScanner {

    private static final Logger log = LoggerFactory.getLogger(PaymentExpiryScanner.class);

    private final ExpirePendingPaymentsUseCase useCase;

    public PaymentExpiryScanner(ExpirePendingPaymentsUseCase useCase) {
        this.useCase = useCase;
    }

    @Scheduled(cron = "${app.payment-expiry.scan-cron:0 20 3 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "order-payment-expiry", lockAtMostFor = "PT20M")
    public void scan() {
        try {
            ExpiryReport report = useCase.expireDue(LocalDateTime.now(), false);
            if (report.failed() > 0) {
                log.warn("미입금 만료 배치에 실패 건 존재 — 운영 확인 필요: failed={}, expired={}",
                        report.failed(), report.expired());
            }
        } catch (RuntimeException e) {
            // 스케줄러 밖으로 예외가 새면 이후 주기가 중단될 수 있다 — 삼키지 말고 남기되 스레드는 지킨다.
            log.error("미입금 만료 배치 실행 실패 — 다음 주기에 재시도한다", e);
        }
    }
}
