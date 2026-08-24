package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.CreateUserUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import github.lms.lemuel.user.application.port.out.PublishUserEventPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.exception.DuplicateEmailException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CreateUserService implements CreateUserUseCase {

    private final LoadUserPort loadUserPort;
    private final SaveUserPort saveUserPort;
    private final PasswordHashPort passwordHashPort;
    private final PublishUserEventPort publishUserEventPort;

    @Override
    public User createUser(CreateUserCommand command) {
        log.info("회원가입 시작: email={}", command.email());

        // 1. 이메일 중복 확인
        if (loadUserPort.findByEmail(command.email()).isPresent()) {
            log.warn("이메일 중복: email={}", command.email());
            throw new DuplicateEmailException(command.email());
        }

        // 2. 비밀번호 해싱
        String hashedPassword = passwordHashPort.hash(command.rawPassword());

        // 3. User 도메인 생성 (도메인 검증 수행)
        User user = User.createWithProfile(
                command.email(),
                hashedPassword,
                command.role(),
                command.name(),
                command.phoneNumber()
        );

        // 3-1. 승인 필요 역할(업체/시공기사)은 승인 대기로 가입
        if (user.requiresApproval()) {
            user.markPending();
            log.info("승인 대기 가입: email={}, role={}", command.email(), user.getRole());
        }

        // 4. 저장
        User savedUser = saveUserPort.save(user);

        // ADR 0020 Phase 3b — settlement user 프로젝션(email) 동기화용 UserRegistered 발행(같은 트랜잭션 Outbox)
        publishUserEventPort.publishUserRegistered(savedUser.getId(), savedUser.getEmail());

        log.info("회원가입 완료: userId={}, email={}", savedUser.getId(), savedUser.getEmail());

        return savedUser;
    }
}
