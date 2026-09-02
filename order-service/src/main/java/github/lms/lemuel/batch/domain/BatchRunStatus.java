package github.lms.lemuel.batch.domain;

/**
 * 배치 1회 실행의 결말.
 *
 * <p>{@code RUNNING} 행이 남아 있다는 것은 "성공"도 "실패"도 아니라 <b>끝을 못 봤다</b>는 뜻이다 —
 * 파드가 중간에 죽었거나 ShedLock 의 {@code lockAtMostFor} 를 넘겨 락이 풀린 경우가 여기 해당한다.
 * 이 상태를 따로 두는 이유가 그거다. 성공/실패 두 값만 두면 <i>실행이 사라진 사고</i>가
 * 아무 흔적도 남기지 않는다.
 */
public enum BatchRunStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
}
