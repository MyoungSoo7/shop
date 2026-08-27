package github.lms.lemuel.point.adapter.in.web;

import github.lms.lemuel.point.application.port.in.TransferPointUseCase;
import github.lms.lemuel.web.security.ResourceOwnership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 회원 간 포인트 선물.
 *
 * <p>경로에도 본문에도 <b>보내는 이의 식별자가 없다</b>. 주체는 JWT 에서만 파생한다 — 요청이
 * 보내는 이를 지정할 수 있으면 그것이 곧 남의 포인트를 꺼내는 수단이다.
 *
 * <p>받는 이는 이메일과 이름을 <b>둘 다</b> 맞춰야 한다. 이메일 하나만 받으면 오타 한 글자가 곧
 * 모르는 사람에게 돈을 보내는 일이 된다. 실패 사유는 하나로만 돌려준다 — "그런 이메일 없음"과
 * "이름이 다름"을 갈라 주면 응답만으로 남의 계정 존재를 확인할 수 있다.
 */
@Tag(name = "Point", description = "회원 간 포인트 선물")
@RestController
@RequestMapping("/api/points/transfers")
public class PointTransferController {

    private static final int DEFAULT_HISTORY_LIMIT = 20;

    private final TransferPointUseCase transferPointUseCase;

    public PointTransferController(TransferPointUseCase transferPointUseCase) {
        this.transferPointUseCase = transferPointUseCase;
    }

    @Operation(summary = "포인트 선물하기",
            description = "받는 분의 이메일과 이름이 모두 일치할 때만 보낸다. "
                    + "같은 requestId 로 다시 부르면 첫 결과를 그대로 돌려준다(중복 송금 없음).")
    @PostMapping
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        long senderUserId = ResourceOwnership.callerUserId(
                SecurityContextHolder.getContext().getAuthentication());
        TransferPointUseCase.TransferPointResult result = transferPointUseCase.transfer(
                new TransferPointUseCase.TransferPointCommand(senderUserId, request.requestId(),
                        request.recipientEmail(), request.recipientName(),
                        request.amount(), request.message()));
        return ResponseEntity.ok(TransferResponse.from(result));
    }

    @Operation(summary = "내 선물 이력",
            description = "보낸 것과 받은 것을 최신순으로 섞어 준다. outgoing 이 방향이다.")
    @GetMapping
    public ResponseEntity<List<HistoryResponse>> history(
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_HISTORY_LIMIT) int limit) {
        long userId = ResourceOwnership.callerUserId(
                SecurityContextHolder.getContext().getAuthentication());
        List<HistoryResponse> entries = transferPointUseCase.history(userId, limit).stream()
                .map(HistoryResponse::from)
                .toList();
        return ResponseEntity.ok(entries);
    }

    public record TransferRequest(
            @NotBlank(message = "요청 식별자가 필요합니다")
            @Size(max = 64, message = "요청 식별자는 64자를 넘을 수 없습니다")
            String requestId,

            @NotBlank(message = "받는 분의 이메일을 입력해 주세요")
            @Email(message = "이메일 형식이 올바르지 않습니다")
            String recipientEmail,

            @NotBlank(message = "받는 분의 이름을 입력해 주세요")
            @Size(max = 50, message = "이름은 50자를 넘을 수 없습니다")
            String recipientName,

            @NotNull(message = "보낼 포인트를 입력해 주세요")
            BigDecimal amount,

            @Size(max = 200, message = "메시지는 200자를 넘을 수 없습니다")
            String message) {
    }

    /**
     * @param alreadyProcessed 같은 requestId 로 이미 처리된 건인지. 화면이 "또 보냈나?"를 묻지 않고
     *                         그대로 성공으로 그릴 수 있게 한다
     */
    public record TransferResponse(String transferNo, String recipientEmail, String recipientName,
                                   BigDecimal amount, BigDecimal remainingBalance,
                                   OffsetDateTime transferredAt, boolean alreadyProcessed) {

        static TransferResponse from(TransferPointUseCase.TransferPointResult result) {
            return new TransferResponse(result.transferNo(), result.recipientMaskedEmail(),
                    result.recipientName(), result.amount(), result.remainingBalance(),
                    result.transferredAt(), result.alreadyProcessed());
        }
    }

    public record HistoryResponse(String transferNo, boolean outgoing, String counterpartName,
                                  BigDecimal amount, String message, OffsetDateTime transferredAt) {

        static HistoryResponse from(TransferPointUseCase.PointTransferHistoryEntry entry) {
            return new HistoryResponse(entry.transferNo(), entry.outgoing(), entry.counterpartName(),
                    entry.amount(), entry.message(), entry.transferredAt());
        }
    }
}
