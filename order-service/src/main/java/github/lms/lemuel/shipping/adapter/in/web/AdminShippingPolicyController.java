package github.lms.lemuel.shipping.adapter.in.web;

import github.lms.lemuel.shipping.application.port.in.ManageSellerShippingPolicyUseCase;
import github.lms.lemuel.shipping.domain.SellerShippingPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 셀러 배송비 정책 운영 콘솔.
 *
 * <p>인가: {@code SecurityConfig} 의 {@code /admin/shipping-policies/**} → ADMIN.
 * 이 설정은 경로를 하나씩 열거하는 방식이라 포괄 {@code /admin/**} 매처가 없다 — 여기에 등록하지
 * 않으면 {@code anyRequest().authenticated()} 로 새어 일반 사용자가 배송비를 바꿀 수 있다.
 */
@Tag(name = "Admin Shipping Policy", description = "셀러 배송비 정책(기본배송비·무료배송 임계) 운영")
@RestController
@RequestMapping("/admin/shipping-policies")
public class AdminShippingPolicyController {

    private final ManageSellerShippingPolicyUseCase useCase;

    public AdminShippingPolicyController(ManageSellerShippingPolicyUseCase useCase) {
        this.useCase = useCase;
    }

    @Operation(summary = "셀러 배송비 정책 등록·변경",
            description = "freeThreshold 를 비우면 무료배송 조건 없음(항상 부과), 0 이면 항상 무료.")
    @PutMapping("/{sellerId}")
    public ResponseEntity<PolicyResponse> upsert(@PathVariable Long sellerId,
                                                 @RequestBody PolicyRequest request) {
        SellerShippingPolicy saved =
                useCase.upsert(sellerId, request.baseFee(), request.freeThreshold());
        return ResponseEntity.ok(PolicyResponse.from(saved));
    }

    @Operation(summary = "셀러 배송비 정책 목록",
            description = "등록된 정책 전체(셀러 ID 오름차순). 정책이 없는 셀러는 애초에 행이 없어 목록에 나오지 않으며, "
                    + "그 셀러의 주문에는 기본배송비가 부과되지 않는다.")
    @GetMapping
    public ResponseEntity<List<PolicyResponse>> list() {
        return ResponseEntity.ok(useCase.findAll().stream().map(PolicyResponse::from).toList());
    }

    @Operation(summary = "셀러 배송비 정책 조회")
    @GetMapping("/{sellerId}")
    public ResponseEntity<PolicyResponse> find(@PathVariable Long sellerId) {
        return useCase.find(sellerId)
                .map(PolicyResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    public record PolicyRequest(
            @NotNull @PositiveOrZero BigDecimal baseFee,
            @PositiveOrZero BigDecimal freeThreshold) {
    }

    public record PolicyResponse(Long sellerId, BigDecimal baseFee, BigDecimal freeThreshold) {
        static PolicyResponse from(SellerShippingPolicy policy) {
            return new PolicyResponse(policy.getSellerId(), policy.getBaseFee(), policy.getFreeThreshold());
        }
    }
}
