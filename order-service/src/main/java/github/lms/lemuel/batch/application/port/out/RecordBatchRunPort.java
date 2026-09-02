package github.lms.lemuel.batch.application.port.out;

import java.time.LocalDate;

/**
 * 배치 실행 원장 적재 포트.
 *
 * <p>구현은 <b>반드시 별도 트랜잭션</b>이어야 한다. 배치 본문과 같은 트랜잭션에 얹으면
 * 본문이 롤백될 때 "실패했다"는 기록까지 같이 사라진다 — 정확히 남겨야 할 그 한 건이 사라진다.
 */
public interface RecordBatchRunPort {

    /**
     * 실행 시작을 적는다.
     *
     * @return 적재된 행의 id. 적재에 실패하면 {@code null} — 호출자는 원장 실패로 배치를 멈추지 않는다.
     */
    Long begin(String batchName, String runId, LocalDate targetDate, String triggeredBy);

    void succeed(long id, int processedCount);

    /**
     * 실행 실패를 적는다.
     *
     * @param processedCount 실패 <b>전까지</b> 처리한 건수. 모르면 {@code null} — 본문이 예외로 튄
     *     경우가 그렇다. 부분 실패(결과 객체로 돌아온 경우)는 아는 값이므로 반드시 넘긴다.
     *     여기를 항상 비워 두면 원장은 "실패했다"만 알고 <b>"그래서 몇 건은 됐나"</b>에 답하지
     *     못한다 — 재실행 범위를 정할 때 필요한 게 정확히 그 숫자다.
     */
    void fail(long id, Integer processedCount, String errorMessage);
}
