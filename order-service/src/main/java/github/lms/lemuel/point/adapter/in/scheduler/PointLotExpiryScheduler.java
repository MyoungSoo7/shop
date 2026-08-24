package github.lms.lemuel.point.adapter.in.scheduler;

import github.lms.lemuel.point.application.port.in.ExpirePointLotsUseCase;
import github.lms.lemuel.point.application.port.in.ExpirePointLotsUseCase.ExpirePointCommand;
import github.lms.lemuel.point.application.port.in.ExpirePointLotsUseCase.ExpirePointResult;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 포인트 소멸 배치 — 유효기간이 지난 로트를 닫고 잔고를 차감한다.
 *
 * <p><b>ShedLock 이름은 전역 유일해야 한다.</b> 참고한 레거시(ofDentis)의 실장애가 정확히 이
 * 지점이었다 — {@code PointScheduler} 의 서로 다른 5개 메서드가 락 이름 2개를 나눠 쓰는 바람에
 * 배치들이 서로를 굶겼다. {@code order-point-lot-expiry} 는 기존 24개 이름과 겹치지 않는다.
 *
 * <p>스케줄러는 실행만 하고 판단하지 않는다 — 무엇을 소멸시킬지는 유스케이스가 정한다.
 */
@Component
public class PointLotExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PointLotExpiryScheduler.class);

    private final ExpirePointLotsUseCase useCase;
    private final int batchSize;

    public PointLotExpiryScheduler(ExpirePointLotsUseCase useCase,
                                   @Value("${app.point.expiry.batch-size:500}") int batchSize) {
        this.useCase = useCase;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${app.point.expiry.cron:0 40 3 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "order-point-lot-expiry", lockAtMostFor = "PT30M")
    public void expire() {
        try {
            ExpirePointResult result = useCase.expire(
                    new ExpirePointCommand(OffsetDateTime.now(), batchSize, false, "scheduler"));
            if (result.lotCount() > 0) {
                log.info("포인트 소멸 배치: lots={}, accounts={}, 소멸액={}",
                        result.lotCount(), result.accountCount(), result.forfeitedTotal());
            }
        } catch (RuntimeException exception) {
            // 스케줄러 밖으로 예외가 새면 이후 주기가 멈출 수 있다 — 남기되 스레드는 지킨다.
            log.error("포인트 소멸 배치 실패 — 다음 주기에 재시도한다", exception);
        }
    }
}
