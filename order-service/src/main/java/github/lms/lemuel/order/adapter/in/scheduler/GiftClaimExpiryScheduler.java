package github.lms.lemuel.order.adapter.in.scheduler;

import github.lms.lemuel.order.application.port.in.ClaimGiftUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 기한이 지난 선물 링크를 닫는 배치.
 *
 * <p><b>이 배치가 만료를 결정하지는 않는다.</b> 판정은 언제나 {@code expiresAt} 과 현재 시각의
 * 비교로 도메인이 하고(그래서 배치가 멈춰도 기한 지난 링크는 즉시 거절된다), 여기서 하는 일은
 * 그 사실을 상태로 남겨 운영 화면과 통계가 열린 건만 세게 하는 것이다. 반대로 만들면 —
 * 배치가 EXPIRED 로 바꿔 줘야 만료인 구조 — 배치가 하루 밀리는 날 링크가 하루 더 살아 있다.
 *
 * <p><b>ShedLock 이름은 전역 유일해야 한다.</b> 같은 이름을 두 스케줄러가 나눠 쓰면 락 보유 기간
 * 동안 나머지가 조용히 스킵된다. {@code order-gift-claim-expiry} 는 기존 이름들과 겹치지 않는다.
 *
 * <p>시각은 포인트 소멸(03:40)·기프트카드 소멸(03:50)과 어긋나게 04:00 으로 잡는다.
 */
@Component
public class GiftClaimExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(GiftClaimExpiryScheduler.class);

    private final ClaimGiftUseCase claimGiftUseCase;
    private final int batchSize;

    public GiftClaimExpiryScheduler(ClaimGiftUseCase claimGiftUseCase,
                                    @Value("${app.gift.expiry.batch-size:500}") int batchSize) {
        this.claimGiftUseCase = claimGiftUseCase;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${app.gift.expiry.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "order-gift-claim-expiry", lockAtMostFor = "PT30M")
    public void expire() {
        try {
            int closed = claimGiftUseCase.expireOverdue(LocalDateTime.now(), batchSize);
            if (closed > 0) {
                log.info("선물 링크 소멸 배치: {}건", closed);
            }
        } catch (RuntimeException exception) {
            // 스케줄러 밖으로 예외가 새면 이후 주기가 멈출 수 있다 — 남기되 스레드는 지킨다.
            log.error("선물 링크 소멸 배치 실패 — 다음 주기에 재시도한다", exception);
        }
    }
}
