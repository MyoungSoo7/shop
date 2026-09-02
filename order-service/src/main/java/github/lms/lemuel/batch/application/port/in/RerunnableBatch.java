package github.lms.lemuel.batch.application.port.in;

import java.time.LocalDate;

/**
 * 날짜를 지정해 다시 돌릴 수 있는 배치.
 *
 * <p>레거시(ssgb2e-quartz)의 {@code SettlementRerunController} + {@code SettlementTargetDateResolver}
 * 짝에 해당한다. 구현하지 않은 배치는 재실행 대상이 아니라는 뜻이며, 그 판단은 각 배치가 스스로 한다 —
 * 예컨대 파티션 생성은 "며칠분" 이라는 개념 자체가 없다.
 *
 * <p>구현체는 스케줄 경로와 <b>같은 코드</b>를 타야 한다. 재실행 전용 경로를 따로 만들면 둘이 갈라지고,
 * 갈라진 사실은 사고가 나기 전까지 안 드러난다.
 */
public interface RerunnableBatch {

    /** 원장에 남는 이름. {@code batch_run_history.batch_name} 과 재실행 API 의 키가 같은 값이다. */
    String batchName();

    /** 사람이 읽을 설명 — 운영자가 무엇을 다시 돌리는지 알아야 한다. */
    String description();

    /**
     * 지정한 날짜분을 다시 처리한다.
     *
     * @param dryRun 참이면 대상만 세고 상태를 바꾸지 않는다. 지원하지 않는 배치는 무시할 수 있으며,
     *               무시한다면 {@link #supportsDryRun()} 이 거짓이어야 한다.
     * @return 처리 건수와 부분 실패 여부. 원장 적재는 <b>호출자({@code BatchRerunService})가</b> 한다 —
     *         구현체가 스스로 적으면 재실행 1회가 원장에 두 줄로 남는다.
     */
    BatchRunOutcome rerun(LocalDate targetDate, boolean dryRun);

    /** dry-run 을 실제로 지원하는가. 거짓인데 dryRun 을 요청하면 API 가 거절한다. */
    default boolean supportsDryRun() {
        return true;
    }
}
