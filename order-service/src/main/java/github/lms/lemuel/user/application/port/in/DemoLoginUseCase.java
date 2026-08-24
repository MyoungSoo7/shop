package github.lms.lemuel.user.application.port.in;

import github.lms.lemuel.user.domain.UserRole;

/**
 * 데모/자동로그인 UseCase.
 *
 * 운영 차단: {@code lemuel.demo.enabled=false} 시 컨트롤러 단에서 404 처리.
 * MVP / 데모 / 면접 시연 / Cloudflare Tunnel 공개 데모 용도.
 */
public interface DemoLoginUseCase {

    /**
     * 지정 역할의 데모 사용자로 자동 로그인.
     * 데모 사용자가 없으면 즉시 생성 (멱등) → JWT 발급.
     */
    LoginUseCase.LoginResult autoLogin(UserRole role);

    /**
     * 게스트 토큰 발급. DB 사용자 생성하지 않음.
     * role=GUEST 클레임만 가진 단기 JWT — 읽기 전용 화면 둘러보기 용도.
     */
    LoginUseCase.LoginResult guestLogin();
}
