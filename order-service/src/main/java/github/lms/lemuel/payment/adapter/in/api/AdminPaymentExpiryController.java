package github.lms.lemuel.payment.adapter.in.api;

import github.lms.lemuel.payment.application.port.in.ExpirePendingPaymentsUseCase;
import github.lms.lemuel.payment.application.port.in.ExpirePendingPaymentsUseCase.ExpiryReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 관리자 미입금 만료 콘솔.
 *
 * <pre>
 *   POST /admin/payment-expiry/run                 → 미리보기(무변경)
 *   POST /admin/payment-expiry/run?dryRun=false    → 실제 만료 실행
 * </pre>
 *
 * <p>돈·재고가 걸린 배치라 <b>미리보기가 기본값</b>이다 — 파라미터를 빠뜨린 호출이 실행이 되어선 안 된다.
 * 스케줄러가 이미 매일 돌지만, 운영자가 임시로 대상을 확인하거나(장애 후) 즉시 소진할 때 쓴다.
 *
 * <p>권한은 SecurityConfig 의 {@code /admin/payment-expiry/**} 매처(ADMIN)로 제한된다.
 */
@RestController
@RequestMapping("/admin/payment-expiry")
public class AdminPaymentExpiryController {

    private final ExpirePendingPaymentsUseCase useCase;

    public AdminPaymentExpiryController(ExpirePendingPaymentsUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/run")
    public ResponseEntity<ExpiryReport> run(
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
        return ResponseEntity.ok(useCase.expireDue(LocalDateTime.now(), dryRun));
    }
}
