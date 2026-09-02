package github.lms.lemuel.batch.application.port.in;

/**
 * 배치 본문 1회 실행의 결말.
 *
 * <p>"돌긴 돌았는데 일부가 실패" 를 표현하기 위해 있다. 이걸 성공으로 적으면 원장이 거짓말을 하고,
 * 예외로 올리면 호출 흐름이 바뀐다 — 그래서 값으로 돌려받는다.
 *
 * @param processedCount 실제 처리(또는 dry-run 시 대상) 건수
 * @param failureNote    널이면 성공. 널이 아니면 처리 건수는 그대로 적되 상태는 FAILED 로 남는다.
 */
public record BatchRunOutcome(int processedCount, String failureNote) {

    public static BatchRunOutcome succeeded(int processedCount) {
        return new BatchRunOutcome(processedCount, null);
    }

    public static BatchRunOutcome partiallyFailed(int processedCount, String failureNote) {
        return new BatchRunOutcome(processedCount, failureNote);
    }

    public boolean isFailure() {
        return failureNote != null;
    }
}
