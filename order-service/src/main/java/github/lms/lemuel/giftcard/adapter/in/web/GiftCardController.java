package github.lms.lemuel.giftcard.adapter.in.web;

import github.lms.lemuel.giftcard.application.port.in.RegisterGiftCardUseCase;
import github.lms.lemuel.giftcard.application.port.in.RegisterGiftCardUseCase.RegisterGiftCardCommand;
import github.lms.lemuel.giftcard.application.port.in.RegisterGiftCardUseCase.RegisterGiftCardResult;
import github.lms.lemuel.giftcard.application.service.UseGiftCardService;
import github.lms.lemuel.web.security.ResourceOwnership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 내 기프트카드 — 등록과 잔액 조회.
 *
 * <p>경로에 userId 를 두지 않는다. 등록 주체도 조회 주체도 언제나 JWT 에서 파생한다 — 요청 본문의
 * userId 를 믿으면 코드를 아는 사람이 남의 계정으로 등록시킬 수 있다.
 *
 * <p>등록 엔드포인트는 코드 무차별 대입의 표적이라 {@code RateLimitFilter} 의
 * {@code giftcard-redeem} 정책이 걸려 있다. 코드 공간이 넓어도 속도 제한이 없으면 봇은 계속 두드린다.
 */
@Tag(name = "Gift Card", description = "내 기프트카드")
@RestController
@RequestMapping("/api/gift-cards")
public class GiftCardController {

    private final RegisterGiftCardUseCase registerGiftCardUseCase;
    private final UseGiftCardService useGiftCardService;

    public GiftCardController(RegisterGiftCardUseCase registerGiftCardUseCase,
                              UseGiftCardService useGiftCardService) {
        this.registerGiftCardUseCase = registerGiftCardUseCase;
        this.useGiftCardService = useGiftCardService;
    }

    @Operation(summary = "기프트카드 등록",
            description = "코드를 입력해 내 계정에 귀속시킨다. 실패 사유는 구분해 알려 주지 않는다 "
                    + "— 구분하면 유효한 코드가 존재한다는 정보를 흘리게 된다.")
    @PostMapping("/redeem")
    public ResponseEntity<RegisterGiftCardResult> redeem(@Valid @RequestBody RedeemRequest request) {
        long userId = ResourceOwnership.callerUserId(
                SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.ok(registerGiftCardUseCase.register(
                new RegisterGiftCardCommand(request.code(), userId, "user:" + userId)));
    }

    @Operation(summary = "내 기프트카드 잔액",
            description = "결제 화면이 '상품권으로 얼마까지 낼 수 있나'를 물을 때 쓴다. 없으면 0.")
    @GetMapping("/me/balance")
    public ResponseEntity<GiftCardBalanceResponse> myBalance() {
        long userId = ResourceOwnership.callerUserId(
                SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.ok(new GiftCardBalanceResponse(
                userId, useGiftCardService.spendableBalance(userId)));
    }

    public record RedeemRequest(@NotBlank String code) {
    }

    public record GiftCardBalanceResponse(Long userId, BigDecimal available) {
    }
}
