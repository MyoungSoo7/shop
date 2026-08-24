package github.lms.lemuel.giftcard.adapter.in.web;

import github.lms.lemuel.giftcard.application.port.in.ExpireGiftCardsUseCase;
import github.lms.lemuel.giftcard.application.port.in.ExpireGiftCardsUseCase.ExpireGiftCardsCommand;
import github.lms.lemuel.giftcard.application.port.in.ExpireGiftCardsUseCase.ExpireGiftCardsResult;
import github.lms.lemuel.giftcard.application.port.in.IssueGiftCardsUseCase;
import github.lms.lemuel.giftcard.application.port.in.IssueGiftCardsUseCase.IssueGiftCardsCommand;
import github.lms.lemuel.giftcard.application.port.in.IssueGiftCardsUseCase.IssuedGiftCard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 관리자 기프트카드 콘솔.
 *
 * <pre>
 *   POST /admin/gift-cards/issue          → 발행(응답에 평문 코드 — 다시 볼 수 없다)
 *   POST /admin/gift-cards/expiry/run     → 소멸 미리보기(무변경)
 *   POST /admin/gift-cards/expiry/run?dryRun=false → 실제 소멸 실행
 * </pre>
 *
 * <p><b>발행 응답이 평문 코드를 담는 유일한 순간이다.</b> 저장된 것은 해시뿐이라 이후 어떤 조회로도
 * 코드를 다시 얻을 수 없다 — 응답을 놓치면 그 카드는 배포할 수 없다.
 *
 * <p>고객 재산을 지우는 소멸 배치는 미리보기가 기본값이다({@code /admin/payment-expiry} 와 같은 규약).
 *
 * <p>권한은 SecurityConfig 의 {@code /admin/gift-cards/**} 매처(ADMIN)로 제한된다. 이 설정에는
 * 포괄 {@code /admin/**} 매처가 <b>없다</b> — 명시하지 않으면 인증만으로 통과한다.
 */
@Tag(name = "Admin Gift Card", description = "기프트카드 발행·소멸 운영")
@RestController
@RequestMapping("/admin/gift-cards")
public class AdminGiftCardController {

    private final IssueGiftCardsUseCase issueGiftCardsUseCase;
    private final ExpireGiftCardsUseCase expireGiftCardsUseCase;

    public AdminGiftCardController(IssueGiftCardsUseCase issueGiftCardsUseCase,
                                   ExpireGiftCardsUseCase expireGiftCardsUseCase) {
        this.issueGiftCardsUseCase = issueGiftCardsUseCase;
        this.expireGiftCardsUseCase = expireGiftCardsUseCase;
    }

    @Operation(summary = "기프트카드 발행",
            description = "권면가·장수·유효기간으로 카드를 찍는다. 응답의 code 는 이 한 번만 볼 수 있다.")
    @PostMapping("/issue")
    public ResponseEntity<List<IssuedGiftCard>> issue(@Valid @RequestBody IssueRequest request) {
        return ResponseEntity.ok(issueGiftCardsUseCase.issue(new IssueGiftCardsCommand(
                request.quantity(), request.faceAmount(), request.validityDays(),
                request.activate(), actor(), request.memo())));
    }

    @Operation(summary = "기프트카드 소멸 실행",
            description = "기본은 미리보기(dryRun=true). 실제 소멸은 dryRun=false 를 명시해야 한다.")
    @PostMapping("/expiry/run")
    public ResponseEntity<ExpireGiftCardsResult> runExpiry(
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun,
            @RequestParam(name = "batchSize", defaultValue = "500") int batchSize) {
        return ResponseEntity.ok(expireGiftCardsUseCase.expire(
                new ExpireGiftCardsCommand(OffsetDateTime.now(), batchSize, dryRun, actor())));
    }

    /** 감사 주체 — 누가 발행·소멸을 실행했는지 카드와 원장에 남긴다. */
    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "admin" : "admin:" + authentication.getName();
    }

    /**
     * @param activate true 면 발행 즉시 등록 가능 상태가 된다. 배포 직전에 활성화하려면 false 로 두고
     *                 별도 절차를 거친다(유출된 코드가 곧 잔액이 되지 않게).
     */
    public record IssueRequest(
            @Min(1) int quantity,
            @NotNull @Positive BigDecimal faceAmount,
            @Min(1) int validityDays,
            boolean activate,
            String memo) {
    }
}
