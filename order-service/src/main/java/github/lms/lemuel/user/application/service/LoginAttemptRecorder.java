package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.domain.LoginSecurityPolicy;
import github.lms.lemuel.user.domain.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 로그인 시도 결과를 <b>독립 트랜잭션</b>으로 확정하는 협력자.
 *
 * <p>왜 별도 빈인가: 실패 카운터는 곧이어 던지는 예외와 같은 트랜잭션에 있으면 안 된다. 같은 tx 라면
 * {@code save()} 뒤에 예외가 올라가는 순간 롤백되어 <b>카운터가 영원히 0 에 머무는</b> 잠금이 된다 —
 * 코드는 있는데 잠기지 않는, 테스트로도 잘 안 잡히는 종류의 결함이다. 그래서 커밋 시점을 분리한다.
 *
 * <p>같은 클래스 안의 private 메서드에 {@code @Transactional} 을 달아 self-invocation 으로 부르면
 * 프록시를 거치지 않아 트랜잭션이 아예 적용되지 않는다. 그래서 <b>별도 빈</b>이어야 한다.
 */
@Component
public class LoginAttemptRecorder {

    private final SaveUserPort saveUserPort;

    public LoginAttemptRecorder(SaveUserPort saveUserPort) {
        this.saveUserPort = saveUserPort;
    }

    /**
     * 비밀번호 검증 실패 1 건을 확정한다. 임계에 닿으면 이 커밋으로 잠금이 걸린다.
     *
     * <p>반환값이 없다 — 잠금 여부는 <b>인자로 받은 도메인 객체가 이미 반영</b>하고 있으므로
     * 호출자는 그것을 보면 된다. 저장 결과를 되받아 판정하면 어댑터 반환값에 의존하게 되어
     * "저장은 됐지만 반환은 null" 같은 구현 차이가 잠금 판정을 조용히 무너뜨린다.
     */
    @Transactional
    public void recordFailure(User user, LoginSecurityPolicy policy, LocalDateTime now) {
        user.recordLoginFailure(policy, now);
        saveUserPort.save(user);
    }

    /** 로그인 성공을 확정한다 — 실패 누적과 잠금을 지운다. */
    @Transactional
    public void recordSuccess(User user, LocalDateTime now) {
        user.recordLoginSuccess(now);
        saveUserPort.save(user);
    }
}
