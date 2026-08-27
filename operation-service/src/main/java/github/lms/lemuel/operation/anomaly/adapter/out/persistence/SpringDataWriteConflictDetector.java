package github.lms.lemuel.operation.anomaly.adapter.out.persistence;

import github.lms.lemuel.operation.anomaly.application.port.out.WriteConflictDetector;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 스프링 데이터/JPA 기준의 동시 쓰기 충돌 판정.
 *
 * <p>이상 인시던트 반영이 겹치면 이중 INSERT 는 unique 위반으로,
 * 겹친 refire 는 {@code @Version} 충돌로 나타난다. 둘 다 <b>커밋 시점</b>에
 * 트랜잭션 프록시가 던지므로 리포지터리 호출을 감싸는 방식으로는 잡히지 않는다.
 */
// 빈 이름을 명시한다 — incident 에 같은 이름의 클래스가 있어 기본 빈 이름(단순명 기준)이 충돌한다.
// 타입은 서로 다르므로 주입은 문제없고, 이름만 부딪힌다.
@Component("anomalyWriteConflictDetector")
public class SpringDataWriteConflictDetector implements WriteConflictDetector {

    @Override
    public boolean isWriteConflict(RuntimeException exception) {
        return exception instanceof DataIntegrityViolationException
                || exception instanceof OptimisticLockingFailureException;
    }
}
