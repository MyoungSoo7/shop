package github.lms.lemuel.user.adapter.in.web;

import github.lms.lemuel.user.adapter.in.web.request.LoginRequest;
import github.lms.lemuel.user.adapter.in.web.response.LoginResponse;
import github.lms.lemuel.user.application.port.in.DemoLoginUseCase;
import github.lms.lemuel.user.application.port.in.LoginUseCase;
import github.lms.lemuel.user.domain.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Authentication API Controller.
 *
 * - POST /auth/login         : 정식 로그인 (이메일/비밀번호)
 * - POST /auth/dev/auto-login: 데모 자동로그인 (lemuel.demo.enabled=true 일 때)
 * - POST /auth/dev/guest     : 게스트 토큰 (lemuel.demo.enabled=true 일 때)
 */
@Tag(name = "Auth", description = "로그인/인증 API")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final LoginUseCase loginUseCase;
    private final DemoLoginUseCase demoLoginUseCase;

    @Value("${lemuel.demo.enabled:false}")
    private boolean demoEnabled;

    @Operation(summary = "로그인", description = "이메일/비밀번호로 로그인하여 JWT 토큰을 발급한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그인 성공 및 토큰 발급"),
            @ApiResponse(responseCode = "401", description = "인증 실패 (이메일/비밀번호 불일치)")
    })
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginUseCase.LoginResult result = loginUseCase.login(
                new LoginUseCase.LoginCommand(request.getEmail(), request.getPassword())
        );
        return ResponseEntity.ok(LoginResponse.from(result));
    }

    @Operation(summary = "데모 자동로그인",
            description = "지정한 역할(USER/MANAGER/ADMIN)의 데모 계정으로 즉시 로그인. " +
                          "lemuel.demo.enabled=true 일 때만 허용.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "토큰 발급"),
            @ApiResponse(responseCode = "404", description = "데모 모드 비활성")
    })
    @PostMapping("/dev/auto-login")
    public ResponseEntity<LoginResponse> autoLogin(
            @Parameter(description = "USER | MANAGER | ADMIN", example = "USER")
            @RequestParam(defaultValue = "USER") String role) {
        ensureDemoEnabled();
        LoginUseCase.LoginResult result = demoLoginUseCase.autoLogin(UserRole.fromString(role));
        return ResponseEntity.ok(LoginResponse.from(result));
    }

    @Operation(summary = "게스트 둘러보기 토큰",
            description = "DB 계정 없이 GUEST 역할 JWT 만 발급. 읽기 전용 화면 둘러보기 용도. " +
                          "lemuel.demo.enabled=true 일 때만 허용.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "게스트 토큰 발급"),
            @ApiResponse(responseCode = "404", description = "데모 모드 비활성")
    })
    @PostMapping("/dev/guest")
    public ResponseEntity<LoginResponse> guestLogin() {
        ensureDemoEnabled();
        LoginUseCase.LoginResult result = demoLoginUseCase.guestLogin();
        return ResponseEntity.ok(LoginResponse.from(result));
    }

    private void ensureDemoEnabled() {
        if (!demoEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo endpoints disabled");
        }
    }
}
