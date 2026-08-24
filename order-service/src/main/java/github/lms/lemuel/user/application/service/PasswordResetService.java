package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.PasswordResetUseCase;
import github.lms.lemuel.user.application.port.out.*;
import github.lms.lemuel.user.domain.PasswordResetToken;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.exception.InvalidPasswordResetTokenException;
import github.lms.lemuel.user.domain.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PasswordResetService implements PasswordResetUseCase {

    private final LoadUserPort loadUserPort;
    private final SaveUserPort saveUserPort;
    private final SavePasswordResetTokenPort savePasswordResetTokenPort;
    private final SendEmailPort sendEmailPort;
    private final PasswordHashPort passwordHashPort;
    /** 토큰 발급·만료 판정의 시간 소스 — KST({@code TimeConfig}). 도메인에 시각을 값으로 넘긴다. */
    private final Clock clock;

    @Value("${app.password-reset.token-expiry-minutes}")
    private int tokenExpiryMinutes;

    @Override
    public void requestPasswordReset(String email) {
        log.info("비밀번호 재설정 요청: email={}", email);

        // 1. 사용자 조회 (보안: 사용자가 없어도 동일하게 처리)
        var userOptional = loadUserPort.findByEmail(email);

        if (userOptional.isEmpty()) {
            // 보안: 계정 존재 여부를 노출하지 않기 위해 로그만 남기고 정상 처리된 것처럼 반환
            log.warn("존재하지 않는 이메일로 비밀번호 재설정 요청: email={}", email);
            return; // 사용자에게는 성공 응답을 보냄
        }

        User user = userOptional.get();

        // 2. 기존 유효한 토큰이 있는지 확인 (있으면 재사용)
        var existingToken = savePasswordResetTokenPort.findValidTokenByUserId(user.getId());

        PasswordResetToken resetToken;
        if (existingToken.isPresent()) {
            log.info("기존 유효한 토큰 재사용: userId={}", user.getId());
            resetToken = existingToken.get();
        } else {
            // 3. 새 토큰 생성
            resetToken = PasswordResetToken.create(user.getId(), tokenExpiryMinutes, LocalDateTime.now(clock));
            resetToken = savePasswordResetTokenPort.save(resetToken);
            log.info("새 비밀번호 재설정 토큰 생성: userId={}, tokenId={}", user.getId(), resetToken.getId());
        }

        // 4. 이메일 발송
        try {
            sendEmailPort.sendPasswordResetEmail(email, resetToken.getToken());
            log.info("비밀번호 재설정 이메일 발송 완료: email={}", email);
        } catch (Exception e) {
            log.error("비밀번호 재설정 이메일 발송 실패: email={}", email, e);
            // 보안: 이메일 발송 실패도 사용자에게는 성공으로 처리 (내부 로그만 남김)
            // 운영 환경에서는 별도 모니터링/알림 시스템으로 처리
        }
    }

    @Override
    public void resetPassword(ResetPasswordCommand command) {
        log.info("비밀번호 재설정 시도: token={}", command.token().substring(0, 8) + "...");

        // 1. 토큰 조회 및 검증
        PasswordResetToken resetToken = savePasswordResetTokenPort.findByToken(command.token())
                .orElseThrow(() -> new InvalidPasswordResetTokenException("유효하지 않은 토큰입니다."));

        LocalDateTime now = LocalDateTime.now(clock);
        if (!resetToken.isValid(now)) {
            log.warn("만료되거나 사용된 토큰: tokenId={}, used={}, expired={}",
                    resetToken.getId(), resetToken.isUsed(), resetToken.isExpired(now));
            throw new InvalidPasswordResetTokenException();
        }

        // 2. 사용자 조회
        User user = loadUserPort.findById(resetToken.getUserId())
                .orElseThrow(() -> new UserNotFoundException(resetToken.getUserId()));

        // 3. 비밀번호 해싱 및 업데이트
        String hashedPassword = passwordHashPort.hash(command.newPassword());
        user.updatePassword(hashedPassword);
        saveUserPort.save(user);

        // 4. 토큰 사용 처리
        resetToken.markAsUsed();
        savePasswordResetTokenPort.save(resetToken);

        log.info("비밀번호 재설정 완료: userId={}", user.getId());
    }
}
