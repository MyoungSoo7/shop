package github.lms.lemuel.user.adapter.in.web;

import github.lms.lemuel.user.adapter.in.web.request.CreateUserRequest;
import github.lms.lemuel.user.adapter.in.web.request.PasswordResetRequestDto;
import github.lms.lemuel.user.adapter.in.web.request.ResetPasswordDto;
import github.lms.lemuel.user.adapter.in.web.response.UserResponse;
import github.lms.lemuel.user.application.port.in.CreateUserUseCase;
import github.lms.lemuel.user.application.port.in.GetUserUseCase;
import github.lms.lemuel.user.application.port.in.PasswordResetUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.exception.InvalidCredentialsException;
import github.lms.lemuel.user.domain.exception.UserInvariantViolationException;
import github.lms.lemuel.user.domain.exception.UserNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * User API Controller
 */
@Tag(name = "User", description = "회원 가입/조회/비밀번호 재설정 API")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final CreateUserUseCase createUserUseCase;
    private final GetUserUseCase getUserUseCase;
    private final PasswordResetUseCase passwordResetUseCase;
    private final LoadUserPort loadUserPort;
    private final SaveUserPort saveUserPort;
    private final PasswordHashPort passwordHashPort;

    @Operation(summary = "회원 가입", description = "이메일/비밀번호/역할로 회원을 생성한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "가입 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "409", description = "이메일 중복")
    })
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        User user = createUserUseCase.createUser(
                new CreateUserUseCase.CreateUserCommand(
                        request.getEmail(),
                        request.getPassword(),
                        request.getRole(),
                        request.getName(),
                        request.getPhoneNumber())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @Operation(summary = "내 정보 조회", description = "JWT의 이메일을 기준으로 로그인 사용자의 프로필을 조회한다.")
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getMe() {
        return ResponseEntity.ok(UserResponse.from(currentUser()));
    }

    @Operation(summary = "내 정보 수정", description = "이름/휴대폰 번호를 수정한다. 이메일은 변경하지 않는다.")
    @PatchMapping("/me")
    public ResponseEntity<UserResponse> updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        User user = currentUser();
        user.updateProfile(request.name(), request.phoneNumber());
        return ResponseEntity.ok(UserResponse.from(saveUserPort.save(user)));
    }

    @Operation(summary = "비밀번호 변경", description = "현재 비밀번호 확인 후 새 비밀번호로 변경한다.")
    @PatchMapping("/me/password")
    public ResponseEntity<Map<String, String>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        User user = currentUser();
        if (!passwordHashPort.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Current password does not match");
        }
        if (passwordHashPort.matches(request.newPassword(), user.getPasswordHash())) {
            throw new UserInvariantViolationException("New password must be different from current password");
        }
        user.updatePassword(passwordHashPort.hash(request.newPassword()));
        saveUserPort.save(user);
        return ResponseEntity.ok(Map.of("message", "비밀번호가 변경되었습니다."));
    }

    @Operation(summary = "회원 탈퇴", description = "비밀번호 재확인 후 사용자를 비활성화한다.")
    @DeleteMapping("/me")
    public ResponseEntity<Map<String, String>> withdraw(@Valid @RequestBody WithdrawRequest request) {
        User user = currentUser();
        if (!passwordHashPort.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Password does not match");
        }
        user.deactivate();
        saveUserPort.save(user);
        return ResponseEntity.ok(Map.of("message", "회원 탈퇴 처리가 완료되었습니다."));
    }

    @Operation(summary = "회원 단건 조회", description = "ID로 회원을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음")
    })
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(
            @Parameter(description = "회원 ID", required = true) @PathVariable Long id) {
        User user = getUserUseCase.getUserById(id);
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @Operation(summary = "전체 회원 조회 (관리자)", description = "시스템의 모든 회원을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "권한 없음")
    })
    @GetMapping("/admin/all")
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        List<UserResponse> users = getUserUseCase.getAllUsers()
                .stream()
                .map(UserResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    /**
     * 비밀번호 재설정 요청 (이메일 발송)
     * POST /users/password-reset/request
     */
    @Operation(summary = "비밀번호 재설정 요청",
            description = "지정한 이메일로 비밀번호 재설정 링크(토큰)를 발송한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "재설정 메일 발송"),
            @ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음")
    })
    @PostMapping("/password-reset/request")
    public ResponseEntity<Map<String, String>> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequestDto request) {
        passwordResetUseCase.requestPasswordReset(request.email());
        return ResponseEntity.ok(Map.of(
                "message", "비밀번호 재설정 이메일이 발송되었습니다.",
                "email", request.email()
        ));
    }

    /**
     * 비밀번호 재설정 (토큰 검증 후 비밀번호 변경)
     * POST /users/password-reset/confirm
     */
    @Operation(summary = "비밀번호 재설정 확정",
            description = "재설정 토큰과 새 비밀번호로 비밀번호를 변경한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "변경 성공"),
            @ApiResponse(responseCode = "400", description = "토큰 만료 또는 무효")
    })
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody ResetPasswordDto request) {
        passwordResetUseCase.resetPassword(
                new PasswordResetUseCase.ResetPasswordCommand(request.token(), request.newPassword())
        );
        return ResponseEntity.ok(Map.of(
                "message", "비밀번호가 성공적으로 변경되었습니다."
        ));
    }

    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new InvalidCredentialsException("Authentication required");
        }
        return loadUserPort.findByEmail(authentication.getName())
                .orElseThrow(() -> new UserNotFoundException(authentication.getName()));
    }

    public record UpdateProfileRequest(
            @jakarta.validation.constraints.Size(max = 100) String name,
            @jakarta.validation.constraints.Pattern(regexp = "^[0-9+\\-() ]{8,30}$") String phoneNumber
    ) {}

    public record ChangePasswordRequest(
            @jakarta.validation.constraints.NotBlank String currentPassword,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(min = 8, max = 100) String newPassword
    ) {}

    public record WithdrawRequest(@jakarta.validation.constraints.NotBlank String password) {}
}
