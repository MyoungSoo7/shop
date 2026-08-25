package github.lms.lemuel.user.adapter.in.web;

import github.lms.lemuel.common.web.csv.CsvResponse;
import github.lms.lemuel.common.web.csv.ExportScope;
import github.lms.lemuel.user.application.port.in.SearchOperatorsUseCase;
import github.lms.lemuel.user.application.port.in.SearchOperatorsUseCase.OperatorExport;
import github.lms.lemuel.user.application.port.in.SearchOperatorsUseCase.OperatorPage;
import github.lms.lemuel.user.application.port.in.SearchOperatorsUseCase.OperatorQuery;
import github.lms.lemuel.user.application.port.in.SearchOperatorsUseCase.OperatorSummary;
import github.lms.lemuel.user.application.port.in.UnlockAccountUseCase;
import github.lms.lemuel.user.application.port.in.UnlockAccountUseCase.UnlockResult;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 운영자 계정 콘솔.
 *
 * <pre>
 *   GET  /admin/operators                  → 권한 계정 목록(마지막 로그인 오래된 순)
 *   GET  /admin/operators/export           → 같은 조건의 CSV(감사 기록 남김)
 *   POST /admin/operators/{userId}/unlock  → 잠금 해제(사유 필수, 감사 기록 남김)
 * </pre>
 *
 * <p><b>왜 필요한가</b>: 회원 콘솔({@code /admin/members})은 "이 사람을 찾는다"에 맞춰져 있어
 * 로그인 보안 상태를 하나도 보여 주지 않는다. 그래서 운영자는 <b>지금 잠긴 계정이 무엇인지</b>도,
 * <b>권한을 가진 채 몇 달째 안 쓰이는 계정이 무엇인지</b>도 물어볼 방법이 없었다. 후자가 특히
 * 문제인데, 방치된 관리자 계정은 탈취돼도 아무도 이상하다고 느끼지 못하는 계정이다.
 *
 * <p><b>계정 생성·삭제를 두지 않는 이유</b>: 이 시스템에서 관리자는 별도 테이블의 레코드가
 * 아니라 {@code role} 을 가진 {@code users} 행이다. 여기에 계정 CRUD 를 두면 신원 저장소가
 * 둘로 갈라진다. 권한 부여·회수는 이미 {@code PATCH /admin/members/{userId}/role} 이 소유하고
 * 사유와 변경 전 역할까지 감사에 남기므로, 이 콘솔은 <b>조회와 잠금 해제</b>만 맡는다.
 *
 * <p>권한은 SecurityConfig 의 {@code /admin/operators/**} 매처(ADMIN)로 제한된다. 이 설정에는
 * 포괄 {@code /admin/**} 매처가 없으므로 명시하지 않으면 {@code anyRequest().authenticated()}
 * 로 새어, 로그인만 한 사용자가 <b>관리자 계정 명부와 각 계정의 방치 기간</b>을 읽게 된다.
 * MANAGER 에게도 열지 않는다 — 이 목록은 권한 상승 표적 목록이기도 하다.
 */
@Tag(name = "Admin Operator", description = "운영자 계정 위생 · 잠금 해제")
@RestController
@RequestMapping("/admin/operators")
@RequiredArgsConstructor
public class AdminOperatorController {

    private final SearchOperatorsUseCase searchOperatorsUseCase;
    private final UnlockAccountUseCase unlockAccountUseCase;
    private final LoadUserPort loadUserPort;

    @GetMapping
    @Operation(summary = "운영자 계정 목록",
            description = "ADMIN·MANAGER 계정을 마지막 로그인이 오래된 순으로. 한 번도 로그인하지 않은 계정이 맨 위")
    public ResponseEntity<OperatorPage> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "false") boolean lockedOnly,
            @RequestParam(required = false) Integer idleDays,
            @RequestParam(defaultValue = "false") boolean neverLoggedIn,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return ResponseEntity.ok(searchOperatorsUseCase.search(
                new OperatorQuery(keyword, role, lockedOnly, idleDays, neverLoggedIn, page, size)));
    }

    /**
     * 같은 조건의 CSV.
     *
     * <p>이 호출은 <b>감사에 남는다</b>({@code OPERATOR_LIST_EXPORTED}). 회원 명부보다 무겁게 보는
     * 이유는, 이 파일이 "어느 권한 계정이 아무에게도 관찰되지 않는가"를 정리해 둔 문서이기 때문이다.
     */
    @GetMapping("/export")
    @Operation(summary = "운영자 계정 CSV", description = "화면과 같은 조건으로 최대 5000행. 이 조작은 감사 로그에 남는다")
    public ResponseEntity<ByteArrayResource> export(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "false") boolean lockedOnly,
            @RequestParam(required = false) Integer idleDays,
            @RequestParam(defaultValue = "false") boolean neverLoggedIn) {

        OperatorExport exported = searchOperatorsUseCase.export(
                new OperatorQuery(keyword, role, lockedOnly, idleDays, neverLoggedIn, 0, 1));

        return CsvResponse.of(
                "operators",
                List.of("ID", "이메일", "이름", "역할", "활성", "마지막로그인", "연속실패", "잠금해제예정",
                        "잠김", "비밀번호변경", "가입일시"),
                exported.rows(),
                AdminOperatorController::toCells,
                ExportScope.of(exported.totalElements(), exported.truncated()));
    }

    /**
     * 잠금 해제.
     *
     * <p>{@code POST} 인 이유: 이것은 자원의 표현을 고치는 것이 아니라 <b>사건</b>이다. 사유가
     * 함께 기록돼야 하고 같은 계정에 두 번 일어날 수 있다.
     */
    @PostMapping("/{userId}/unlock")
    @Operation(summary = "계정 잠금 해제",
            description = "사유 필수. 자기 자신은 풀 수 없다. 이미 풀린 계정에도 성공으로 응답한다(멱등)")
    public ResponseEntity<UnlockResponse> unlock(@PathVariable Long userId,
                                                 @Valid @RequestBody UnlockRequest request) {
        UnlockResult result = unlockAccountUseCase.unlock(userId, request.reason(), currentUserId());
        return ResponseEntity.ok(new UnlockResponse(
                result.user().getId(),
                result.user().getEmail(),
                result.wasLocked(),
                result.previousLockedUntil(),
                result.previousFailedAttempts()));
    }

    /**
     * JWT 주체에서 조작자 ID 를 꺼낸다.
     *
     * <p>요청 본문으로 받지 않는 이유는 {@link AdminMemberController} 와 같다 — 조작자를 요청이
     * 정하게 두면 감사 로그의 "누가"가 위조 가능해진다. 주체는 <b>이메일</b>이므로
     * ({@code JwtAuthenticationFilter} 가 {@code claims.getSubject()} 를 principal 로 쓴다)
     * 숫자 파싱이 아니라 이메일 조회로 얻는다. 여기서 null 로 떨어지면 "자기 자신 잠금 해제 금지"가
     * 조용히 무력화된다.
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        return loadUserPort.findByEmail(authentication.getName())
                .map(User::getId)
                .orElse(null);
    }

    private static List<String> toCells(OperatorSummary row) {
        return List.of(
                Objects.toString(row.id(), ""),
                Objects.toString(row.email(), ""),
                Objects.toString(row.name(), ""),
                Objects.toString(row.role(), ""),
                row.active() ? "Y" : "N",
                // 빈 칸은 "한 번도 로그인하지 않음"이다. "-" 같은 기호로 채우면 값이 있는 것처럼 읽힌다.
                Objects.toString(row.lastLoginAt(), ""),
                String.valueOf(row.failedLoginAttempts()),
                Objects.toString(row.lockedUntil(), ""),
                row.locked() ? "Y" : "N",
                Objects.toString(row.passwordChangedAt(), ""),
                Objects.toString(row.createdAt(), ""));
    }

    /** 잠금 해제 요청. 사유는 비워 둘 수 없다 — 잠금 해제는 공격 대응을 한 단계 되돌리는 조작이다. */
    public record UnlockRequest(@NotBlank String reason) {
    }

    /**
     * 잠금 해제 결과.
     *
     * <p>해제 <b>직전</b> 상태를 함께 돌려준다. 화면이 "5회 실패로 잠겨 있던 계정을 풀었다"와
     * "이미 풀려 있었다"를 구분해 보여 줄 수 있어야 운영자가 자기 조작의 의미를 안다.
     */
    public record UnlockResponse(
            Long userId,
            String email,
            boolean wasLocked,
            LocalDateTime previousLockedUntil,
            int previousFailedAttempts) {
    }
}
