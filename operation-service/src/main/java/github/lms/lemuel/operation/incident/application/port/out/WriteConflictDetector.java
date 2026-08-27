package github.lms.lemuel.operation.incident.application.port.out;

/**
 * 실패한 쓰기가 <b>동시 경쟁</b>이었는지 알아보는 계약.
 *
 * <p>유스케이스가 알아야 하는 것은 "재시도하면 수렴하는 충돌인가" 뿐이고,
 * <b>무엇이 충돌인지는 저장소 기술이 정한다</b>. 그래서 판정을 어댑터로 내보낸다.
 * (JPA 라면 uq 위반·낙관적 락, 다른 저장소라면 다른 신호일 것이다.)
 *
 * <p>이 포트가 없던 시절 {@code IngestAlertService} 는
 * {@code org.springframework.dao.DataIntegrityViolationException} 을 직접 catch 했다.
 * 유스케이스에 "우리 저장소는 스프링 데이터다" 가 박혀 있었다는 뜻이다.
 */
public interface WriteConflictDetector {

    /**
     * @param exception 쓰기 시도가 던진 예외
     * @return 동시 경쟁으로 인한 충돌이라 <b>재시도할 가치가 있으면</b> true
     */
    boolean isWriteConflict(RuntimeException exception);
}
