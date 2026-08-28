package github.lms.lemuel.partner.application.port.dto;

import github.lms.lemuel.partner.domain.MemberRole;
import github.lms.lemuel.partner.domain.OrgType;
import github.lms.lemuel.partner.domain.SellerTier;

import java.time.LocalDate;

/**
 * 콘솔 상단에 "내가 누구로 보고 있는가" 를 적기 위한 값.
 *
 * <p>{@code salesAvailable} 이 별도로 있는 이유는, 매출이 0 인 것과 매출 개념이 없는 것을 화면이
 * 구분해야 하기 때문이다. 둘 다 빈 표로 그리면 법인 고객은 데이터가 유실됐다고 읽는다.
 *
 * @param currentTier 현재 셀러 등급. 등급 이벤트가 아직 안 왔으면 null(=미확인)이며, 이는
 *                    "NORMAL" 과 다르다 — 모르는 것을 기본값으로 채우면 화면이 거짓을 말한다.
 */
public record PartnerProfileView(
        long organizationId,
        String organizationName,
        OrgType orgType,
        Long sellerId,
        MemberRole myRole,
        boolean salesAvailable,
        SellerTier currentTier,
        LocalDate tierEffectiveFrom) {
}
