package github.lms.lemuel.user.adapter.in.web;

import github.lms.lemuel.common.web.csv.CsvResponse;
import github.lms.lemuel.user.application.port.in.ChangeUserRoleUseCase;
import github.lms.lemuel.user.application.port.in.ChangeUserRoleUseCase.RoleChangeResult;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberExport;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberPage;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberQuery;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberStatusCount;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberSummary;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.adapter.in.web.response.UserResponse;
import github.lms.lemuel.user.domain.MembershipStatus;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.time.LocalDate;

/**
 * 회원 관리 콘솔.
 *
 * <pre>
 *   GET   /admin/members                 → 조건 검색(가입 최신순 페이지)
 *   GET   /admin/members/status-counts    → 같은 조건의 승인 상태별 인원
 *   GET   /admin/members/export           → 같은 조건의 CSV(감사 기록 남김)
 *   PATCH /admin/members/{userId}/role    → 역할 변경(사유 필수)
 * </pre>
 *
 * <p><b>왜 필요한가</b>: 승인·반려·정지·복구 조작은 {@code /memberships/**} 에 이미 있었지만
 * 대상을 <b>찾는 방법</b>이 없었다. 목록이라곤 {@code /users/admin/all}(전 회원 무페이징)과
 * {@code /memberships/pending}(PENDING 만)뿐이라, "정지된 그 사람"이나 "이 번호로 가입한
 * 사람"을 찾을 수 없었다.
 *
 * <p><b>상태 전이 조작을 여기 두지 않는 이유</b>: 승인·정지는 {@code MembershipApprovalService}
 * 가 이미 소유하고 {@code membership_approvals} 이력까지 남긴다. 같은 조작을 두 표면에 두면
 * 언젠가 한쪽만 고쳐져 이력이 갈라진다. 이 컨트롤러는 <b>조회와 역할 변경</b>만 맡고,
 * 화면은 상태 전이를 기존 {@code /memberships/**} 로 보낸다.
 *
 * <p>권한은 SecurityConfig 의 {@code /admin/members/**} 매처(ADMIN)로 제한된다. 이 설정에는
 * 포괄 {@code /admin/**} 매처가 없으므로 명시하지 않으면 {@code anyRequest().authenticated()}
 * 로 새어 일반 사용자가 전 회원 개인정보를 읽게 된다.
 */
@Tag(name = "Admin Member", description = "회원 검색 · 역할 변경")
@RestController
@RequestMapping("/admin/members")
@RequiredArgsConstructor
public class AdminMemberController {

    private final SearchMembersUseCase searchMembersUseCase;
    private final ChangeUserRoleUseCase changeUserRoleUseCase;
    private final LoadUserPort loadUserPort;

    @GetMapping
    @Operation(summary = "회원 검색", description = "키워드·역할·승인상태·활성여부·가입일로 좁혀 최신 가입순으로 조회한다")
    public ResponseEntity<MemberPage> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return ResponseEntity.ok(searchMembersUseCase.search(
                toQuery(keyword, role, status, active, joinedFrom, joinedTo, page, size)));
    }

    @GetMapping("/status-counts")
    @Operation(summary = "승인 상태별 인원", description = "'지금 승인 대기가 몇 명인가'는 목록을 세어 알 일이 아니다")
    public ResponseEntity<List<MemberStatusCount>> statusCounts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo) {

        // 상태별 집계에 상태 필터를 걸면 고른 상태 하나만 남아 집계의 의미가 사라진다.
        return ResponseEntity.ok(searchMembersUseCase.countByStatus(
                toQuery(keyword, role, null, active, joinedFrom, joinedTo, 0, 1)));
    }

    /**
     * 필터 드롭다운이 쓸 역할·상태 목록. 서버 enum 이 정본이라 화면에 하드코딩하지 않는다.
     */
    @GetMapping("/enums")
    @Operation(summary = "역할 · 승인상태 목록", description = "필터 드롭다운용")
    public ResponseEntity<MemberEnums> enums() {
        return ResponseEntity.ok(new MemberEnums(
                Arrays.stream(UserRole.values()).map(Enum::name).toList(),
                Arrays.stream(MembershipStatus.values()).map(Enum::name).toList()));
    }

    /**
     * 같은 조건의 CSV.
     *
     * <p>이 호출은 <b>감사에 남는다</b>({@code MEMBER_LIST_EXPORTED}). 목록을 보는 것과 PII 를
     * 파일로 가져가는 것은 감사에서 같은 무게가 아니다 — 파일은 우리 통제를 벗어난다.
     */
    @GetMapping("/export")
    @Operation(summary = "회원 CSV", description = "화면과 같은 조건으로 최대 5000행. 이 조작은 감사 로그에 남는다")
    public ResponseEntity<ByteArrayResource> export(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo) {

        MemberExport exported = searchMembersUseCase.export(
                toQuery(keyword, role, status, active, joinedFrom, joinedTo, 0, 1));

        ResponseEntity<ByteArrayResource> csv = CsvResponse.of(
                "members",
                List.of("ID", "이메일", "이름", "연락처", "역할", "승인상태", "활성", "가입일시"),
                exported.rows(),
                AdminMemberController::toCells);

        return ResponseEntity.status(csv.getStatusCode())
                .headers(csv.getHeaders())
                .header("X-Export-Truncated", String.valueOf(exported.truncated()))
                .header("X-Export-Total", String.valueOf(exported.totalElements()))
                .body(csv.getBody());
    }

    @PatchMapping("/{userId}/role")
    @Operation(summary = "역할 변경", description = "사유 필수. 자기 자신은 바꿀 수 없다")
    public ResponseEntity<UserResponse> changeRole(@PathVariable Long userId,
                                                   @Valid @RequestBody ChangeRoleRequest request) {
        RoleChangeResult result = changeUserRoleUseCase.changeRole(
                userId, request.role(), request.reason(), currentUserId());
        return ResponseEntity.ok(UserResponse.from(result.user()));
    }

    /**
     * JWT 주체에서 조작자 ID 를 꺼낸다.
     *
     * <p>요청 본문으로 받지 않는 이유: 조작자를 요청이 정하게 두면 감사 로그의 "누가"가
     * 위조 가능해진다 — 그 순간 기록은 증거가 아니라 자기 신고가 된다.
     *
     * <p><b>주체는 이메일이다</b>({@code JwtAuthenticationFilter} 가 {@code claims.getSubject()} 를
     * principal 로 쓴다). 숫자로 파싱하려 들면 항상 실패해 조작자를 영영 알 수 없고, 그러면
     * 자기 자신 역할 변경 차단이 <b>조용히 무력화</b>된다 — 통과하는 테스트만으로는 드러나지
     * 않는 종류의 구멍이다. 그래서 이메일로 조회한다({@code MembershipController} 와 같은 규약).
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

    private static List<String> toCells(MemberSummary row) {
        return List.of(
                Objects.toString(row.id(), ""),
                Objects.toString(row.email(), ""),
                Objects.toString(row.name(), ""),
                Objects.toString(row.phoneNumber(), ""),
                Objects.toString(row.role(), ""),
                Objects.toString(row.membershipStatus(), ""),
                row.active() ? "Y" : "N",
                Objects.toString(row.createdAt(), ""));
    }

    /**
     * 문자열 필터를 enum 으로 옮긴다.
     *
     * <p>모르는 이름은 <b>필터 미적용</b>으로 흘린다. {@code UserRole.fromString} 처럼 USER 로
     * 기본값을 주면 오타 하나가 "USER 만 조회"로 조용히 바뀌어, 운영자는 찾는 사람이 없다고
     * 결론짓는다. 조건을 빼는 편이 넓게 보여 줄 뿐 거짓말은 하지 않는다.
     */
    private static MemberQuery toQuery(String keyword, String role, String status, Boolean active,
                                       LocalDate joinedFrom, LocalDate joinedTo, int page, int size) {
        return new MemberQuery(keyword,
                parseEnum(UserRole.class, role),
                parseEnum(MembershipStatus.class, status),
                active, joinedFrom, joinedTo, page, size);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 필터 드롭다운 목록. */
    public record MemberEnums(List<String> roles, List<String> membershipStatuses) {
    }

    /** 역할 변경 요청. 사유는 비워 둘 수 없다 — 근거 없는 권한 변경은 감사에서 설명되지 않는다. */
    public record ChangeRoleRequest(@NotNull UserRole role, @NotBlank String reason) {
    }
}
