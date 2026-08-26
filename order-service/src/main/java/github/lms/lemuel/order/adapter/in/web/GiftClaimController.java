package github.lms.lemuel.order.adapter.in.web;

import github.lms.lemuel.order.application.port.in.ClaimGiftUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 선물 받기 — <b>받는 사람</b> 쪽 경로. 로그인하지 않은 사람이 호출한다.
 *
 * <pre>
 *   GET  /gift-claims/{token}              → 무엇을 받는지 (금액은 없다)
 *   POST /gift-claims/{token}/code         → 인증번호 발송
 *   POST /gift-claims/{token}/verify       → 인증번호 확인
 *   POST /gift-claims/{token}/address      → 배송지 제출 (여기서 배송이 시작된다)
 * </pre>
 *
 * <p><b>경로를 {@code /orders} 아래에 두지 않았다.</b> 그 아래는 전부 인증이 필요한 영역이라,
 * 여기 한 갈래를 열려면 {@code permitAll} 이 {@code /orders/**} 안쪽을 가리키게 된다. 접두사부터
 * 갈라 두면 "어디까지 열려 있는가"를 경로만 보고 알 수 있다.
 *
 * <p><b>인가가 토큰 하나에 걸려 있다.</b> 그래서 나가는 값이 최소한이고(금액 없음, 번호는 마스킹),
 * 주소를 낼 수 있으려면 링크만으로는 부족하도록 본인확인을 한 단계 더 둔다 — 링크가 새면
 * 링크를 주운 사람이 배송지를 바꿀 수 있게 되기 때문이다.
 */
@Tag(name = "Gift Claim", description = "선물 받기 (받는 사람 · 비로그인)")
@Validated
@RestController
@RequestMapping("/gift-claims")
@RequiredArgsConstructor
public class GiftClaimController {

    private final ClaimGiftUseCase claimGiftUseCase;

    @Operation(summary = "선물 정보 조회",
            description = "받는 사람 화면의 재료. 상품명과 수량만 나가고 금액은 담지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공(만료·종단 상태도 200 — actionable 로 구분)"),
            @ApiResponse(responseCode = "404", description = "없거나 폐기된 링크(둘을 구분해 주지 않는다)")
    })
    @GetMapping("/{token}")
    public ResponseEntity<ClaimGiftUseCase.GiftView> view(@PathVariable String token) {
        return ResponseEntity.ok(claimGiftUseCase.view(token));
    }

    @Operation(summary = "인증번호 발송",
            description = "선물에 적힌 받는 사람 번호로 6자리를 보낸다. 이전 번호는 즉시 무효.")
    @PostMapping("/{token}/code")
    public ResponseEntity<Void> requestCode(@PathVariable String token) {
        claimGiftUseCase.requestVerificationCode(token);
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "인증번호 확인",
            description = "틀리면 400 과 함께 남은 시도 횟수가 온다. 다 쓰면 번호를 다시 받아야 한다.")
    @PostMapping("/{token}/verify")
    public ResponseEntity<Void> verify(@PathVariable String token,
                                       @Valid @RequestBody VerifyRequest request) {
        claimGiftUseCase.verify(token, request.code());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "배송지 제출",
            description = "본인확인을 마친 뒤에만 된다. 이 호출로 주문에 배송지가 붙고 배송이 만들어진다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "제출 완료 — 배송 시작"),
            @ApiResponse(responseCode = "400", description = "본인확인 전이거나 이미 끝난 선물")
    })
    @PostMapping("/{token}/address")
    public ResponseEntity<Void> submitAddress(@PathVariable String token,
                                              @Valid @RequestBody GiftAddressRequest request) {
        claimGiftUseCase.submitAddress(token, request.toSubmission());
        return ResponseEntity.noContent().build();
    }

    // ───────── 요청 ─────────

    public record VerifyRequest(
            @NotBlank @Pattern(regexp = "\\d{6}", message = "인증번호는 6자리 숫자입니다") String code) {}

    /**
     * 받는 사람이 내는 배송지.
     *
     * <p>일반 주문의 {@code ShippingAddressRequest} 와 달리 {@code recipientName} 이 <b>선택</b>이다.
     * 화면에 이미 자기 이름이 적혀 있어 다시 받는 것이 어색하고, 비우면 선물에 적힌 이름을 쓴다.
     * 사무실 등 다른 이름으로 받고 싶은 사람만 채우면 된다.
     */
    public record GiftAddressRequest(
            @Size(max = 100) String recipientName,
            @NotBlank @Size(max = 40) String phone,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Size(max = 255) String address1,
            @Size(max = 255) String address2,
            @Size(max = 255) String deliveryMemo) {

        ClaimGiftUseCase.AddressSubmission toSubmission() {
            // 이름이 비었을 때 무엇으로 채우는지는 응용 계층이 정한다 — 여기서는 그대로 넘긴다.
            return new ClaimGiftUseCase.AddressSubmission(
                    recipientName, phone, postalCode, address1, address2, deliveryMemo);
        }
    }
}
