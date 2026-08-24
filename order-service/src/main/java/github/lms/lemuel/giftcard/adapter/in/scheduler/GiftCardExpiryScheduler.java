package github.lms.lemuel.giftcard.adapter.in.scheduler;

import github.lms.lemuel.giftcard.application.port.in.ExpireGiftCardsUseCase;
import github.lms.lemuel.giftcard.application.port.in.ExpireGiftCardsUseCase.ExpireGiftCardsCommand;
import github.lms.lemuel.giftcard.application.port.in.ExpireGiftCardsUseCase.ExpireGiftCardsResult;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 기프트카드 소멸 배치.
 *
 * <p><b>ShedLock 이름은 전역 유일해야 한다.</b> 같은 이름을 두 스케줄러가 나눠 쓰면 락 보유 기간
 * 동안 나머지가 조용히 스킵된다(ofDentis 레거시의 실장애 패턴). {@code order-gift-card-expiry} 는
 * 기존 이름들과 겹치지 않는다.
 *
 * <p>포인트 소멸(03:40)과 시각을 어긋나게 잡는다 — 같은 시각에 두 배치가 같은 DB 를 훑을 이유가 없다.
 */
@Component
public class GiftCardExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(GiftCardExpiryScheduler.class);

    private final ExpireGiftCardsUseCase useCase;
    private final int batchSize;

    public GiftCardExpiryScheduler(ExpireGiftCardsUseCase useCase,
                                   @Value("${app.gift-card.expiry.batch-size:500}") int batchSize) {
        this.useCase = useCase;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${app.gift-card.expiry.cron:0 50 3 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "order-gift-card-expiry", lockAtMostFor = "PT30M")
    public void expire() {
        try {
            ExpireGiftCardsResult result = useCase.expire(
                    new ExpireGiftCardsCommand(OffsetDateTime.now(), batchSize, false, "scheduler"));
            if (result.cardCount() > 0) {
                log.info("기프트카드 소멸 배치: cards={}, 소멸액={}",
                        result.cardCount(), result.forfeitedTotal());
            }
        } catch (RuntimeException exception) {
            // 스케줄러 밖으로 예외가 새면 이후 주기가 멈출 수 있다 — 남기되 스레드는 지킨다.
            log.error("기프트카드 소멸 배치 실패 — 다음 주기에 재시도한다", exception);
        }
    }
}
