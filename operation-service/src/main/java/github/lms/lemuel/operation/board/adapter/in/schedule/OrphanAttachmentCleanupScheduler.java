package github.lms.lemuel.operation.board.adapter.in.schedule;

import github.lms.lemuel.operation.board.application.port.in.CleanupOrphanAttachmentUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 고아 첨부 청소 배치 — 진입 어댑터(시간이 부르는 입구).
 *
 * <p>이 클래스는 <b>시각을 정하고 결과를 로그로 남기는 일만</b> 한다. 무엇을 지울지는 응용 서비스가
 * 정한다 — 스케줄러에 판단을 두면 수동 실행 경로를 만들 때 그 판단이 복제된다.
 *
 * <p>실패해도 다음 회차가 있으므로 예외를 삼키지 않고 그대로 올린다. 스프링 스케줄러가 로그를
 * 남기고 다음 주기에 다시 돈다 — 여기서 조용히 먹으면 청소가 몇 달간 멈춰 있어도 아무도 모른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.board.attachment.cleanup-enabled", havingValue = "true",
        matchIfMissing = true)
public class OrphanAttachmentCleanupScheduler {

    private final CleanupOrphanAttachmentUseCase cleanupOrphanAttachmentUseCase;

    @Scheduled(cron = "${app.board.attachment.cleanup-cron:0 10 4 * * *}", zone = "Asia/Seoul")
    public void cleanup() {
        var result = cleanupOrphanAttachmentUseCase.cleanupOrphans();
        if (result.deleted() > 0) {
            log.info("고아 첨부 청소 완료: 훑음 {}건, 삭제 {}건", result.scanned(), result.deleted());
        } else {
            log.debug("고아 첨부 청소 완료: 훑음 {}건, 삭제 없음", result.scanned());
        }
    }
}
