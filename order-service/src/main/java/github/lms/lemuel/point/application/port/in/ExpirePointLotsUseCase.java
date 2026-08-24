package github.lms.lemuel.point.application.port.in;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 포인트 소멸(유효기간 만료) 유스케이스.
 *
 * <p>{@code dryRun} 을 기본값으로 두는 이유는 ofDentis 레거시의 대량 관리 작업 교훈이다
 * (P0-3 dry-run 프로토콜) — 고객 재산을 지우는 배치는 "무엇이 지워질지"를 먼저 보여 준 뒤 실행한다.
 */
public interface ExpirePointLotsUseCase {

    record ExpirePointCommand(OffsetDateTime at, int batchSize, boolean dryRun, String actor) {
    }

    record ExpirePointResult(int lotCount, int accountCount, BigDecimal forfeitedTotal, boolean dryRun) {
    }

    ExpirePointResult expire(ExpirePointCommand command);
}
