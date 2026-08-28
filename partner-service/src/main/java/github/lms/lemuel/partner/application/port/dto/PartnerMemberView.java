package github.lms.lemuel.partner.application.port.dto;

import github.lms.lemuel.partner.domain.MemberRole;

import java.time.OffsetDateTime;

/**
 * 같은 조직의 구성원 한 명.
 *
 * <p>이름·이메일이 없는 것은 누락이 아니다 — {@code organization.member_joined} 가 숫자
 * {@code userId} 만 싣는다. 없는 값을 다른 서비스에 물어 채우면 그 순간 서비스 간 동기 호출이
 * 생기고(리포 전체 불변식 위반), 여기에 개인정보가 들어오면 지금 없는 마스킹 통제가 필요해진다.
 */
public record PartnerMemberView(
        long membershipId,
        long userId,
        MemberRole role,
        OffsetDateTime joinedAt) {
}
