package github.lms.lemuel.operation.anomaly.application.port.out;

/**
 * 실패한 쓰기가 <b>동시 경쟁</b>이었는지 알아보는 계약.
 *
 * <p>유스케이스가 알아야 하는 것은 "재시도하면 수렴하는 충돌인가" 뿐이고,
 * <b>무엇이 충돌인지는 저장소 기술이 정한다</b>. 그래서 판정을 어댑터로 내보낸다.
 *
 * <p>incident 기능에도 같은 이름의 포트가 있다. 일부러 합치지 않았다 —
 * 공용 타입으로 올리면 기능 간 코드 결합이 생기고, 그건 이 모듈이 줄이려는 바로 그 결합이다.
 * (5줄짜리 중복이 기능 경계보다 싸다.)
 */
public interface WriteConflictDetector {

    /**
     * @param exception 쓰기 시도가 던진 예외
     * @return 동시 경쟁으로 인한 충돌이라 <b>재시도할 가치가 있으면</b> true
     */
    boolean isWriteConflict(RuntimeException exception);
}
