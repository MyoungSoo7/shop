package github.lms.lemuel.coupon.adapter.in.web;

import github.lms.lemuel.coupon.adapter.in.web.dto.*;
import github.lms.lemuel.coupon.application.port.in.CouponUseCase;
import github.lms.lemuel.coupon.domain.Coupon;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "Coupon", description = "쿠폰 생성/조회/검증/사용 API")
@Validated
@RestController
@RequestMapping("/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponUseCase couponUseCase;

    /**
     * 쿠폰 생성 (관리자)
     * POST /coupons
     */
    @Operation(summary = "쿠폰 생성", description = "관리자용 쿠폰 생성 API. 할인 유형/할인값/최소 주문 금액/만료일을 설정한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
    @PostMapping
    public ResponseEntity<CouponResponse> createCoupon(@Valid @RequestBody CouponCreateRequest request) {
        Coupon coupon = couponUseCase.createCoupon(new CouponUseCase.CreateCouponCommand(
                request.getCode(),
                request.getType(),
                request.getDiscountValue(),
                request.getMinOrderAmount(),
                request.getMaxDiscountAmount(),
                request.getMaxUses(),
                request.getTargetType(),
                request.getTargetId(),
                request.getStartsAt(),
                request.getExpiresAt()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(CouponResponse.from(coupon));
    }

    /**
     * 전체 쿠폰 목록 조회 (관리자)
     * GET /coupons
     */
    @Operation(summary = "전체 쿠폰 목록 조회", description = "등록된 모든 쿠폰을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ResponseEntity<List<CouponResponse>> getAllCoupons() {
        List<CouponResponse> coupons = couponUseCase.getAllCoupons().stream()
                .map(CouponResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(coupons);
    }

    @Operation(summary = "사용 가능 쿠폰 조회", description = "주문 금액과 상품/카테고리 기준으로 사용 가능한 쿠폰을 계산한다.")
    @GetMapping("/available")
    public ResponseEntity<List<CouponValidateResponse>> getAvailableCoupons(
            @RequestParam Long userId,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long categoryId) {
        List<CouponValidateResponse> responses = couponUseCase
                .getAvailableCoupons(userId, amount, productId, categoryId)
                .stream()
                .map(r -> new CouponValidateResponse(
                        r.valid(),
                        r.message(),
                        r.discountAmount(),
                        r.finalAmount()))
                .toList();
        return ResponseEntity.ok(responses);
    }

    /**
     * 쿠폰 유효성 검증
     * GET /coupons/{code}/validate?userId=&amount=
     */
    @Operation(summary = "쿠폰 유효성 검증", description = "쿠폰 코드와 사용자/주문 금액을 기반으로 사용 가능 여부 및 할인액을 계산한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검증 결과"),
            @ApiResponse(responseCode = "404", description = "쿠폰을 찾을 수 없음")
    })
    @GetMapping("/{code}/validate")
    public ResponseEntity<CouponValidateResponse> validateCoupon(
            @Parameter(description = "쿠폰 코드", required = true) @PathVariable String code,
            @Parameter(description = "사용자 ID", required = true) @RequestParam @Positive(message = "userId는 양수여야 합니다") Long userId,
            @Parameter(description = "주문 총액", required = true) @RequestParam @Positive(message = "주문 금액은 0보다 커야 합니다") BigDecimal amount
    ) {
        CouponUseCase.ValidateResult result = couponUseCase.validateCoupon(code, userId, amount);
        return ResponseEntity.ok(new CouponValidateResponse(
                result.valid(),
                result.message(),
                result.discountAmount(),
                result.finalAmount()
        ));
    }

    /**
     * 쿠폰 사용 처리
     * POST /coupons/{code}/use
     */
    @Operation(summary = "쿠폰 사용 처리", description = "특정 주문에 쿠폰 사용 처리를 기록한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "사용 처리 성공"),
            @ApiResponse(responseCode = "400", description = "사용 불가능한 쿠폰"),
            @ApiResponse(responseCode = "404", description = "쿠폰을 찾을 수 없음")
    })
    @PostMapping("/{code}/use")
    public ResponseEntity<Void> useCoupon(
            @Parameter(description = "쿠폰 코드", required = true) @PathVariable String code,
            @Valid @RequestBody CouponUseRequest request
    ) {
        couponUseCase.useCoupon(code, request.getUserId(), request.getOrderId());
        return ResponseEntity.ok().build();
    }
}
