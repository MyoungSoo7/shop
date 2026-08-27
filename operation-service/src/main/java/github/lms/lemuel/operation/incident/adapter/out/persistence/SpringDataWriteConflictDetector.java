package github.lms.lemuel.operation.incident.adapter.out.persistence;

import github.lms.lemuel.operation.incident.application.port.out.WriteConflictDetector;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 스프링 데이터/JPA 기준의 동시 쓰기 충돌 판정.
 *
 * <ul>
 *   <li>{@link DataIntegrityViolationException} — 이중 INSERT 가 {@code uq_incident_active} 를 위반.</li>
 *   <li>{@link OptimisticLockingFailureException} — 겹친 refire 가 {@code @Version} 충돌.</li>
 * </ul>
 *
 * <p>두 예외 모두 <b>커밋 시점</b>에 트랜잭션 프록시가 던진다. 그래서 리포지터리 호출을
 * 감싸는 방식으로는 잡을 수 없고, 예외를 받은 쪽이 이 판정을 물어보는 형태가 된다.
 */
// 빈 이름을 명시한다 — anomaly 에 같은 이름의 클래스가 있어 기본 빈 이름(단순명 기준)이 충돌한다.
// 타입은 서로 다르므로 주입은 문제없고, 이름만 부딪힌다.
@Component("incidentWriteConflictDetector")
public class SpringDataWriteConflictDetector implements WriteConflictDetector {

    @Override
    public boolean isWriteConflict(RuntimeException exception) {
        return exception instanceof DataIntegrityViolationException
                || exception instanceof OptimisticLockingFailureException;
    }
}
