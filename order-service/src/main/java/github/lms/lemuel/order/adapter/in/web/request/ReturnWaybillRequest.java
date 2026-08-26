package github.lms.lemuel.order.adapter.in.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 회수 송장 / 교환 재배송 송장 본문 — 두 곳이 같은 모양이라 하나를 쓴다. */
public record ReturnWaybillRequest(
        @NotBlank(message = "택배사는 필수입니다")
        @Size(max = 40, message = "택배사는 40자를 넘을 수 없습니다")
        String carrier,

        @NotBlank(message = "송장 번호는 필수입니다")
        @Size(max = 60, message = "송장 번호는 60자를 넘을 수 없습니다")
        String trackingNumber) {
}
