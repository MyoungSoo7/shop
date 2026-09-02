package github.lms.lemuel.shipping.adapter.in.scheduler;

import github.lms.lemuel.batch.application.BatchRunRecorder;
import github.lms.lemuel.shipping.application.port.in.SafetyNumberUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 만료된 안심번호 회수 스케줄러.
 *
 * <p>회수가 멈추면 풀이 말라 신규 주문이 실번호로 나간다 — 조용히 개인정보 노출이 늘어나는 형태의
 * 고장이라, 실패를 삼키지 않고 로그로 남긴다(다음 주기가 다시 시도한다).
 *
 * <p>실행 결과는 {@code batch_run_history} 에 남는다. <b>재실행 경로는 두지 않았다</b> —
 * 회수 대상은 "지금 시점에 만료된 것 전부" 라 누적적이다. 한 시간을 걸러도 다음 시각이 그 몫까지
 * 집어간다. 과거 날짜로 다시 돌리면 <i>더 적게</i> 회수할 뿐 복구가 아니다. 그래서 원장 기록만 한다
 * — 여기서 필요한 건 "되돌리기" 가 아니라 "멈춘 걸 알아채기" 다.
 */
@Component
public class SafetyNumberReclaimScheduler {

    private static final Logger log = LoggerFactory.getLogger(SafetyNumberReclaimScheduler.class);

    /** 원장 키. 재실행 대상은 아니라 {@code /rerunnable} 목록에는 안 뜬다. */
    public static final String BATCH_NAME = "safety-number-reclaim";

    private final SafetyNumberUseCase useCase;
    private final BatchRunRecorder recorder;
    private final int batchSize;

    public SafetyNumberReclaimScheduler(SafetyNumberUseCase useCase,
                                        BatchRunRecorder recorder,
                                        @Value("${app.shipping.safety-number.reclaim-batch:200}") int batchSize) {
        this.useCase = useCase;
        this.recorder = recorder;
        this.batchSize = batchSize;
    }

    // 락이 필요한 이유: 회수는 상태를 바꾸는 배치이고, 다중 인스턴스 안전을 보장하는 장치(멱등 키·
    // 조건부 UPDATE)가 코드에 명시돼 있지 않다. 같은 배치 창을 두 파드가 집으면 같은 번호 집합을
    // 두 번 처리한다 — 안전한 쪽으로 락을 건다.
    @Scheduled(cron = "${app.shipping.safety-number.reclaim-cron:0 10 * * * *}")
    @SchedulerLock(name = "safety-number-reclaim", lockAtMostFor = "PT30M")
    public void reclaim() {
        try {
            recorder.recordScheduled(BATCH_NAME, () -> {
                int released = useCase.releaseExpired(OffsetDateTime.now(), batchSize);
                if (released > 0) {
                    log.info("안심번호 만료 회수 완료: {} 건", released);
                }
                return released;
            });
        } catch (RuntimeException e) {
            log.error("안심번호 회수 실패 — 풀 고갈 시 실번호가 노출된다", e);
        }
    }
}
