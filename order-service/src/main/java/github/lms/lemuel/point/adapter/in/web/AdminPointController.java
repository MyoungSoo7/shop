package github.lms.lemuel.point.adapter.in.web;

import github.lms.lemuel.point.application.port.in.ExpirePointLotsUseCase;
import github.lms.lemuel.point.application.port.in.ExpirePointLotsUseCase.ExpirePointCommand;
import github.lms.lemuel.point.application.port.in.ExpirePointLotsUseCase.ExpirePointResult;
import github.lms.lemuel.point.application.port.in.DeductPointUseCase;
import github.lms.lemuel.point.application.port.in.DeductPointUseCase.DeductPointCommand;
import github.lms.lemuel.point.application.port.in.DeductPointUseCase.DeductPointResult;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase.GrantPointCommand;
import github.lms.lemuel.point.application.port.in.GrantPointUseCase.GrantPointResult;
import github.lms.lemuel.point.application.port.in.ManagePointEarnPolicyUseCase;
import github.lms.lemuel.point.application.port.in.ManagePointEarnPolicyUseCase.ClosePolicyCommand;
import github.lms.lemuel.point.application.port.in.ManagePointEarnPolicyUseCase.RegisterPolicyCommand;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.ExpiringLotView;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointAccountDetail;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointConsoleSummary;
import github.lms.lemuel.point.application.port.in.QueryPointConsoleUseCase.PointEarnPolicyView;
import github.lms.lemuel.point.domain.PointEarnPolicy;
import github.lms.lemuel.point.domain.PointEarnScope;
import github.lms.lemuel.point.domain.PointLotOrigin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 관리자 포인트 콘솔.
 *
 * <pre>
 *   POST /admin/points/grants           → 수기 지급(사유 필수)
 *   POST /admin/points/deductions       → 수기 차감(사유 필수) — 지급의 역방향
 *   POST /admin/points/expiry/run       → 소멸 미리보기(무변경)
 *   POST /admin/points/expiry/run?dryRun=false → 실제 소멸 실행
 *
 *   GET  /admin/points/summary          → 전체 3자 대조 + 소멸 예정 규모
 *   GET  /admin/points/accounts/{userId} → 계정 상세(3자 대조 + 로트·원장 내역)
 *   GET  /admin/points/policies         → 적립률 정책 이력
 *   POST /admin/points/policies         → 적립률 정책 등록(소급 금지)
 *   POST /admin/points/policies/{id}/close → 적립률 정책 종료일 지정
 *   GET  /admin/points/expiring         → 소멸 예정 로트
 * </pre>
 *
 * <p>정책은 <b>고치지 않는다</b>. 등록과 종료 두 조작뿐이고, 요율 변경은 "현재 정책 종료 →
 * 신규 등록" 2단계다(ADR 0032). 표가 곧 이력이라 과거 값을 덮으면 그때 왜 그 요율로
 * 적립됐는지 설명할 수 없게 된다.
 *
 * <p>조회 4종이 뒤에 붙은 이유: 앞의 쓰기 둘은 <b>되돌리기 어려운 조작</b>인데, 그 전에
 * "지금 이 계정이 얼마이고 왜 그런가"를 볼 방법이 없었다. 지급·소멸 버튼과 같은 화면에서
 * 근거를 확인할 수 있어야 조작이 안전해진다.
 *
 * <p>고객 재산을 지우는 배치라 <b>미리보기가 기본값</b>이다 — 파라미터를 빠뜨린 호출이 실행이 되어선
 * 안 된다({@code /admin/payment-expiry} 와 같은 규약).
 *
 * <p>수기 지급은 <b>사유(memo)를 필수</b>로 받는다. 근거 없이 포인트가 생기면 나중에 "왜 이 돈이
 * 여기 있나"에 답할 수 없고, 그 순간 원장은 설명력을 잃는다.
 *
 * <p>권한은 SecurityConfig 의 {@code /admin/points/**} 매처(ADMIN)로 제한된다. 이 설정에는
 * 포괄 {@code /admin/**} 매처가 <b>없다</b> — 경로별 열거 방식이라, 명시하지 않으면
 * {@code anyRequest().authenticated()} 로 새어 일반 사용자도 호출할 수 있다.
 */
@Tag(name = "Admin Point", description = "포인트 수기 지급·소멸 운영")
@RestController
@RequestMapping("/admin/points")
public class AdminPointController {

    private final GrantPointUseCase grantPointUseCase;
    private final ExpirePointLotsUseCase expirePointLotsUseCase;
    private final QueryPointConsoleUseCase queryPointConsoleUseCase;
    private final DeductPointUseCase deductPointUseCase;
    private final ManagePointEarnPolicyUseCase managePointEarnPolicyUseCase;
    private final github.lms.lemuel.point.application.port.in.ManagePointUsageLimitUseCase managePointUsageLimitUseCase;

    public AdminPointController(GrantPointUseCase grantPointUseCase,
                                ExpirePointLotsUseCase expirePointLotsUseCase,
                                QueryPointConsoleUseCase queryPointConsoleUseCase,
                                DeductPointUseCase deductPointUseCase,
                                ManagePointEarnPolicyUseCase managePointEarnPolicyUseCase,
                                github.lms.lemuel.point.application.port.in.ManagePointUsageLimitUseCase managePointUsageLimitUseCase) {
        this.managePointUsageLimitUseCase = managePointUsageLimitUseCase;
        this.grantPointUseCase = grantPointUseCase;
        this.expirePointLotsUseCase = expirePointLotsUseCase;
        this.queryPointConsoleUseCase = queryPointConsoleUseCase;
        this.deductPointUseCase = deductPointUseCase;
        this.managePointEarnPolicyUseCase = managePointEarnPolicyUseCase;
    }

    @Operation(summary = "포인트 원장 전체 요약",
            description = "잔고 총액·ACTIVE 로트 합계·원장 누계의 3자 대조와 소멸 예정 규모. "
                    + "driftedAccountCount 가 0 이 아니면 잔고만 움직이고 기록이 빠진 계정이 있다는 뜻이다.")
    @GetMapping("/summary")
    public ResponseEntity<PointConsoleSummary> summary(
            @RequestParam(name = "withinDays", defaultValue = "30") int withinDays) {
        return ResponseEntity.ok(queryPointConsoleUseCase.summary(withinDays));
    }

    @Operation(summary = "계정 상세",
            description = "잔고 3필드 + 3자 대조 + 최근 로트·원장 내역. 포인트를 한 번도 쓴 적 없는 "
                    + "사용자는 계정 자체가 없어 404 다(잔액 0 인 계정과 구분된다).")
    @GetMapping("/accounts/{userId}")
    public ResponseEntity<PointAccountDetail> account(@PathVariable Long userId) {
        return queryPointConsoleUseCase.account(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "적립률 정책 이력",
            description = "종료된 행도 함께 돌려준다 — 과거 적립이 왜 그 요율이었는지 설명해야 하므로. "
                    + "표가 비어 있으면 적립률 0(무행동 착지)이다.")
    @GetMapping("/policies")
    public ResponseEntity<List<PointEarnPolicyView>> policies() {
        return ResponseEntity.ok(queryPointConsoleUseCase.policies());
    }

    @Operation(summary = "소멸 예정 로트",
            description = "지정 일수 안에 만료되는 ACTIVE 로트를 만료 임박 순으로. 무기한 로트는 대상이 아니다.")
    @GetMapping("/expiring")
    public ResponseEntity<List<ExpiringLotView>> expiring(
            @RequestParam(name = "withinDays", defaultValue = "30") int withinDays,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(queryPointConsoleUseCase.expiringLots(withinDays, limit));
    }

    @Operation(summary = "포인트 수기 지급",
            description = "CS 보상 등으로 운영자가 직접 지급한다. 사유는 필수이며 원장 메모로 보존된다.")
    @PostMapping("/grants")
    public ResponseEntity<GrantPointResult> grant(@Valid @RequestBody ManualGrantRequest request) {
        OffsetDateTime expiresAt = request.validityDays() == null
                ? null
                : OffsetDateTime.now().plusDays(request.validityDays());
        GrantPointResult result = grantPointUseCase.grant(new GrantPointCommand(
                request.userId(), request.amount(), PointLotOrigin.MANUAL_GRANT,
                "MANUAL", request.referenceId(), expiresAt, actor(), request.reason()));
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "포인트 수기 차감",
            description = "오지급·부정 적립을 거둬들인다. 지급과 대칭으로 사유·멱등 키가 필수이며, "
                    + "잔액을 넘는 차감은 422(잔액 부족)로 거절된다. 정지 계정에서도 차감된다.")
    @PostMapping("/deductions")
    public ResponseEntity<DeductPointResult> deduct(@Valid @RequestBody ManualDeductRequest request) {
        return ResponseEntity.ok(deductPointUseCase.deduct(new DeductPointCommand(
                request.userId(), request.amount(), request.referenceId(),
                request.reason(), actor())));
    }

    @Operation(summary = "적립률 정책 등록",
            description = "행을 고치지 않고 새 행을 넣는다(ADR 0032). 소급 발효는 400, "
                    + "같은 범위에 기간이 겹치면 409 — 현재 정책의 종료일을 먼저 지정해야 한다.")
    @PostMapping("/policies")
    public ResponseEntity<PointEarnPolicy> registerPolicy(
            @Valid @RequestBody RegisterPolicyRequest request) {
        return ResponseEntity.ok(managePointEarnPolicyUseCase.register(
                new RegisterPolicyCommand(request.scope(), request.scopeKey(), request.earnRate(),
                        request.validityDays(), request.effectiveFrom(), request.effectiveTo(),
                        request.reason(), actor(),
                        request.roundingUnit() == null ? 1 : request.roundingUnit(),
                        request.rounding()),
                LocalDate.now()));
    }

    @Operation(summary = "적립률 정책 종료",
            description = "종료일을 지정해 자리를 비운다. 반열림이라 그날부터는 적용되지 않는다. "
                    + "과거 날짜로는 끊을 수 없다 — 그 구간 적립은 이미 일어났다.")
    @PostMapping("/policies/{policyId}/close")
    public ResponseEntity<PointEarnPolicy> closePolicy(
            @PathVariable Long policyId,
            @Valid @RequestBody ClosePolicyRequest request) {
        return managePointEarnPolicyUseCase
                .close(new ClosePolicyCommand(policyId, request.effectiveTo(), actor()), LocalDate.now())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "포인트 소멸 실행",
            description = "기본은 미리보기(dryRun=true). 실제 소멸은 dryRun=false 를 명시해야 한다.")
    @PostMapping("/expiry/run")
    public ResponseEntity<ExpirePointResult> runExpiry(
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun,
            @RequestParam(name = "batchSize", defaultValue = "500") int batchSize) {
        return ResponseEntity.ok(expirePointLotsUseCase.expire(
                new ExpirePointCommand(OffsetDateTime.now(), batchSize, dryRun, actor())));
    }

    /** 감사 주체 — 누가 지급·소멸을 실행했는지 원장에 남긴다. */
    @Operation(summary = "포인트 사용 상한 조회",
            description = "주문당 포인트 사용 상한(NONE 상한없음 / FIXED_AMOUNT 정액 / ORDER_RATIO 결제액 비율).")
    @GetMapping("/usage-limit")
    public ResponseEntity<github.lms.lemuel.point.domain.PointUsageLimit> usageLimit() {
        return ResponseEntity.ok(managePointUsageLimitUseCase.current());
    }

    @Operation(summary = "포인트 사용 상한 변경",
            description = "FIXED_AMOUNT 는 limitAmount, ORDER_RATIO 는 limitRatioPercent(0~100)가 필요하다. "
                    + "정액 0 은 '포인트 사용 금지'이며 NONE(상한 없음)과 다른 의미다.")
    @org.springframework.web.bind.annotation.PutMapping("/usage-limit")
    public ResponseEntity<github.lms.lemuel.point.domain.PointUsageLimit> updateUsageLimit(
            @Valid @RequestBody UsageLimitRequest request) {
        return ResponseEntity.ok(managePointUsageLimitUseCase.update(
                request.type(), request.limitAmount(), request.limitRatioPercent(), actor()));
    }

    /** @param type 상한 유형. 유형이 요구하지 않는 값은 무시된다 */
    public record UsageLimitRequest(
            @NotNull github.lms.lemuel.point.domain.PointUsageLimitType type,
            BigDecimal limitAmount,
            BigDecimal limitRatioPercent) {
    }

    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "admin" : "admin:" + authentication.getName();
    }

    /**
     * @param referenceId  멱등 키 — 같은 값으로 두 번 호출해도 한 번만 지급된다(원장 자연키)
     * @param validityDays null 이면 무기한
     */
    public record ManualGrantRequest(
            @NotNull Long userId,
            @NotNull @Positive BigDecimal amount,
            @NotBlank String referenceId,
            @NotBlank String reason,
            Integer validityDays) {
    }

    /**
     * 수기 차감 요청 — 지급과 같은 형태로 받는다.
     *
     * <p>사유를 {@code @NotBlank} 로 잠그는 이유는 지급 쪽보다 오히려 강하다: 고객 재산이
     * 줄어든 근거를 나중에 대지 못하면 그 차감은 방어할 수 없는 조작이 된다.
     */
    public record ManualDeductRequest(
            @NotNull Long userId,
            @NotNull @Positive BigDecimal amount,
            @NotBlank String referenceId,
            @NotBlank String reason) {
    }

    /**
     * 적립률 정책 등록 요청.
     *
     * @param scopeKey     GLOBAL 은 관례상 {@code "-"}, GRADE·CATEGORY 는 등급명·카테고리 코드
     * @param effectiveTo  null 이면 무기한 — 다음 정책을 넣을 때 종료일을 지정하게 된다
     */
    public record RegisterPolicyRequest(
            @NotNull PointEarnScope scope,
            @NotBlank String scopeKey,
            @NotNull BigDecimal earnRate,
            @Positive int validityDays,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank String reason,
            Integer roundingUnit,
            github.lms.lemuel.point.domain.PointEarnRounding rounding) {
    }

    /** @param effectiveTo 이 날짜부터 적용하지 않는다(반열림). 오늘 이상이어야 한다 */
    public record ClosePolicyRequest(@NotNull LocalDate effectiveTo) {
    }
}
