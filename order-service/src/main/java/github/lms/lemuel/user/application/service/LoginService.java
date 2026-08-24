package github.lms.lemuel.user.application.service;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.user.application.port.in.LoginUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import github.lms.lemuel.user.application.port.out.TokenProviderPort;
import github.lms.lemuel.user.domain.LoginSecurityPolicy;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.exception.AccountLockedException;
import github.lms.lemuel.user.domain.exception.InvalidCredentialsException;
import github.lms.lemuel.user.domain.exception.PasswordExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 로그인 서비스 — 자격 검증 + 무차별 대입 잠금 + 비밀번호 사용 기한.
 *
 * <p><b>검사 순서가 곧 보안 설계다.</b>
 * <ol>
 *   <li><b>잠금 먼저</b> — 잠긴 계정은 비밀번호를 대조조차 하지 않는다. 대조부터 하면 잠긴 동안에도
 *       "맞다/틀리다"가 상태 변화로 새어 나가 잠금이 사실상 무력해진다.</li>
 *   <li><b>비밀번호 대조</b> — 실패는 {@link LoginAttemptRecorder} 가 <b>독립 트랜잭션</b>으로 즉시
 *       확정한다. 예외와 같은 tx 에 두면 롤백되어 카운터가 영원히 0 에 머문다.</li>
 *   <li><b>비밀번호 기한</b> — 대조에 성공한 다음에만 본다. 먼저 보면 "이 이메일은 실재하고 비밀번호가
 *       오래됐다"가 비밀번호를 모르는 사람에게도 새어 나가는 계정 열거 통로가 된다.</li>
 * </ol>
 *
 * <p><b>계정이 없을 때</b>는 아무것도 기록하지 않고 {@link InvalidCredentialsException} 을 던진다 —
 * 없는 이메일에는 잠금을 저장할 곳이 없고, 바깥에서 보이는 응답도 실패 하나로 같아야 한다.
 *
 * <p>레거시 커머스(ssgb2e-front {@code LoginServiceImpl.selectLogin})와 다른 점: 저쪽 잠금은 관리자
 * 개입 전까지 <b>영구</b>였다. 영구 잠금은 남의 이메일만 알면 서비스 거부를 걸 수 있어(계정 잠금 공격),
 * 여기서는 기한부 잠금(기본 30 분)으로 바꿨다.
 */
@Service
public class LoginService implements LoginUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final LoadUserPort loadUserPort;
    private final LoginAttemptRecorder attemptRecorder;
    private final PasswordHashPort passwordHashPort;
    private final TokenProviderPort tokenProviderPort;
    /** 판정 시각의 단일 소스(KST). 도메인에는 값으로만 넘어간다 — 시간 규칙을 테스트에서 재현하기 위해. */
    private final Clock clock;
    private final LoginSecurityPolicy policy;

    /**
     * 스프링 조립용 생성자.
     *
     * <p>{@code @Autowired} 를 반드시 남긴다 — 생성자가 둘이면 스프링은 어느 쪽으로 만들지 정하지
     * 못하고 기본 생성자를 찾다가 {@code No default constructor found} 로 <b>컨텍스트 기동 자체가
     * 깨진다</b>. 슬라이스 테스트는 이 빈을 만들지 않아 통과하므로, 전체 컨텍스트가 뜨는 곳에서만
     * 드러나는 종류의 사고다.
     */
    @Autowired
    public LoginService(LoadUserPort loadUserPort,
                        LoginAttemptRecorder attemptRecorder,
                        PasswordHashPort passwordHashPort,
                        TokenProviderPort tokenProviderPort,
                        Clock clock,
                        @Value("${app.security.login.max-failed-attempts:5}") int maxFailedAttempts,
                        @Value("${app.security.login.lock-minutes:30}") long lockMinutes,
                        @Value("${app.security.login.password-max-age-days:90}") long passwordMaxAgeDays) {
        this(loadUserPort, attemptRecorder, passwordHashPort, tokenProviderPort, clock,
                new LoginSecurityPolicy(maxFailedAttempts, Duration.ofMinutes(lockMinutes),
                        Duration.ofDays(passwordMaxAgeDays)));
    }

    /** 테스트·조립 전용 생성자 — 정책을 그대로 주입한다. */
    public LoginService(LoadUserPort loadUserPort,
                        LoginAttemptRecorder attemptRecorder,
                        PasswordHashPort passwordHashPort,
                        TokenProviderPort tokenProviderPort,
                        Clock clock,
                        LoginSecurityPolicy policy) {
        this.loadUserPort = loadUserPort;
        this.attemptRecorder = attemptRecorder;
        this.passwordHashPort = passwordHashPort;
        this.tokenProviderPort = tokenProviderPort;
        this.clock = clock;
        this.policy = policy;
    }

    @Override
    @Auditable(
            action = AuditAction.LOGIN_SUCCESS,
            failureAction = "LOGIN_FAILED",
            resourceType = "User",
            resourceId = "#p0.email()",
            detail = "{'email': #p0.email(), 'role': #result == null ? null : #result.role()}"
    )
    public LoginResult login(LoginCommand command) {
        LocalDateTime now = LocalDateTime.now(clock);

        User user = loadUserPort.findByEmail(command.email())
                .orElseThrow(InvalidCredentialsException::new);

        // 1) 잠금 — 비밀번호를 보기 전에 막는다.
        if (user.isLocked(now)) {
            log.warn("잠긴 계정 로그인 시도: email={}, until={}", command.email(), user.lockedUntil());
            throw new AccountLockedException(user.lockedUntil());
        }

        // 2) 비밀번호 대조 — 실패는 독립 트랜잭션으로 그 자리에서 확정한다.
        if (!passwordHashPort.matches(command.rawPassword(), user.getPasswordHash())) {
            attemptRecorder.recordFailure(user, policy, now);
            if (user.isLocked(now)) {
                log.warn("연속 실패로 계정 잠금: email={}, until={}", command.email(), user.lockedUntil());
                throw new AccountLockedException(user.lockedUntil());
            }
            throw new InvalidCredentialsException();
        }

        // 3) 비밀번호 사용 기한 — 대조 성공 이후에만.
        if (user.isPasswordExpired(policy, now)) {
            log.info("비밀번호 사용 기한 초과: email={}", command.email());
            throw new PasswordExpiredException(policy.passwordMaxAge().toDays());
        }

        attemptRecorder.recordSuccess(user, now);

        String token = tokenProviderPort.generateToken(user.getEmail(), user.getRole().name(), user.getId());

        return new LoginResult(token, user.getEmail(), user.getRole().name());
    }
}
