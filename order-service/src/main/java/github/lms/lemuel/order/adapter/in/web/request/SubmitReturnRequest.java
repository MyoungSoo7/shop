package github.lms.lemuel.order.adapter.in.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 반품·교환·취소 신청 본문.
 *
 * <p>계좌 3 칸이 전부 선택값인 이유: 카드 결제는 계좌가 필요 없고, 무통장·가상계좌는 필요하다.
 * 어느 쪽인지는 화면이 아니라 결제 정보가 정한다({@code LoadOrderRefundRoutePort}). 여기서
 * {@code @NotBlank} 를 걸면 카드 주문의 반품이 계좌 없이는 접수되지 않는다.
 *
 * <p>{@code userId} 가 없는 것도 같은 이유다 — 신청자는 본문이 아니라 토큰이 정한다.
 */
public record SubmitReturnRequest(
        @NotBlank(message = "신청 유형은 필수입니다")
        String type,

        @NotBlank(message = "사유 코드는 필수입니다")
        @Size(max = 40, message = "사유 코드는 40자를 넘을 수 없습니다")
        String reasonCode,

        @Size(max = 500, message = "사유 상세는 500자를 넘을 수 없습니다")
        String reasonDetail,

        @Size(max = 20) String refundBankCode,
        @Size(max = 60) String refundAccountNumber,
        @Size(max = 60) String refundAccountHolder) {
}
