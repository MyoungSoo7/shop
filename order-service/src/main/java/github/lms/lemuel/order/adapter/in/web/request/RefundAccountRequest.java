package github.lms.lemuel.order.adapter.in.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 환불 계좌 등록·정정 본문 — 세 칸이 모두 채워져야 한다(반쪽 계좌로는 송금할 수 없다). */
public record RefundAccountRequest(
        @NotBlank(message = "은행 코드는 필수입니다")
        @Size(max = 20, message = "은행 코드는 20자를 넘을 수 없습니다")
        String bankCode,

        @NotBlank(message = "계좌 번호는 필수입니다")
        @Size(max = 60, message = "계좌 번호는 60자를 넘을 수 없습니다")
        String accountNumber,

        @NotBlank(message = "예금주는 필수입니다")
        @Size(max = 60, message = "예금주는 60자를 넘을 수 없습니다")
        String holderName) {
}
