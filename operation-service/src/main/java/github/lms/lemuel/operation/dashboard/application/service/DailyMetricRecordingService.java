package github.lms.lemuel.operation.dashboard.application.service;

import github.lms.lemuel.operation.dashboard.application.port.in.RecordDailyMetricUseCase;
import github.lms.lemuel.operation.dashboard.application.port.out.UpsertDailyMetricPort;
import github.lms.lemuel.operation.dashboard.domain.DashboardMetric;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 이벤트 → 일별 집계 누적.
 *
 * <p>하는 일은 사실상 하나다 — <b>사건 시각을 어느 날짜 칸에 넣을지 정하는 것</b>. 그 판정을
 * 컨슈머나 SQL 이 아니라 여기서 하는 이유는, 타임존이 걸린 결정이라 한 곳에만 있어야 하기
 * 때문이다. UTC 로 자르면 KST 오전 9시 이전의 주문이 전부 "어제"가 되어, 아침에 대시보드를
 * 여는 운영자에게는 밤새 매출이 사라진 것처럼 보인다.
 */
@Service
public class DailyMetricRecordingService implements RecordDailyMetricUseCase {

    private final UpsertDailyMetricPort upsertPort;
    private final ZoneId zone;

    public DailyMetricRecordingService(UpsertDailyMetricPort upsertPort,
                                       @Value("${app.ops.dashboard.zone:Asia/Seoul}") String zone) {
        this.upsertPort = upsertPort;
        this.zone = ZoneId.of(zone);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(DashboardMetric metric, Instant occurredAt, BigDecimal amount) {
        // MANDATORY — 호출자(컨슈머)의 트랜잭션 안에서만 실행된다. 멱등 마커 저장과 이 누적이
        // 같은 커밋에 묶이지 않으면, 둘 사이에서 죽었을 때 재전송분이 한 번 더 더해지거나
        // (마커만 남았다면) 영영 빠진다. 트랜잭션 없이 호출되면 그 자리에서 실패하는 편이
        // 조용히 어긋난 숫자를 만드는 것보다 낫다.
        upsertPort.accumulate(occurredAt.atZone(zone).toLocalDate(), metric, amount);
    }
}
