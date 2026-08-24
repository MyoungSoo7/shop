package github.lms.lemuel.point.adapter.in.web;

import github.lms.lemuel.point.application.service.UsePointService;
import github.lms.lemuel.web.security.ResourceOwnership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 내 포인트 조회.
 *
 * <p>경로에 userId 를 두지 않는다 — 식별자를 요청에서 받으면 남의 잔액을 조회할 수 있다(IDOR).
 * 주체는 언제나 JWT 에서 파생한다.
 */
@Tag(name = "Point", description = "내 포인트")
@RestController
@RequestMapping("/api/points")
public class PointController {

    private final UsePointService usePointService;

    public PointController(UsePointService usePointService) {
        this.usePointService = usePointService;
    }

    @Operation(summary = "내 포인트 잔액",
            description = "결제 화면이 '포인트로 얼마까지 낼 수 있나'를 물을 때 쓴다. 계정이 없으면 0.")
    @GetMapping("/me")
    public ResponseEntity<PointBalanceResponse> myBalance() {
        long userId = ResourceOwnership.callerUserId(
                SecurityContextHolder.getContext().getAuthentication());
        BigDecimal available = usePointService.availableBalance(userId);
        return ResponseEntity.ok(new PointBalanceResponse(userId, available));
    }

    public record PointBalanceResponse(Long userId, BigDecimal available) {
    }
}
