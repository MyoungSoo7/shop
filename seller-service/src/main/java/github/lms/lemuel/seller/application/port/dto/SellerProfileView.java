package github.lms.lemuel.seller.application.port.dto;

import github.lms.lemuel.seller.domain.MemberRole;
import github.lms.lemuel.seller.domain.OrgType;

/**
 * 콘솔 상단에 "내가 누구로, 무엇을 할 수 있는 자격으로 보고 있는가" 를 적기 위한 값.
 *
 * <p>{@code canSubmit} 을 서버가 계산해 내려 주는 이유는, 화면이 역할 문자열을 보고 스스로
 * 판단하면 규칙이 두 벌이 되기 때문이다. 두 벌이 되면 언제나 한쪽이 먼저 낡는다. 다만 이 값은
 * <b>버튼을 감추기 위한 것일 뿐</b>이고, 실제 차단은 서버가 매 요청마다 다시 한다 —
 * 화면이 보내는 어떤 값도 권한 근거가 되지 않는다.
 */
public record SellerProfileView(
        long organizationId,
        String organizationName,
        OrgType orgType,
        Long sellerId,
        MemberRole myRole,
        boolean canSubmit) {
}
