package github.lms.lemuel.partner.adapter.out.persistence;

import github.lms.lemuel.partner.application.port.dto.PartnerMemberView;
import github.lms.lemuel.partner.application.port.out.LoadPartnerScopePort;
import github.lms.lemuel.partner.application.port.out.PartnerDirectoryProjectionPort;
import github.lms.lemuel.partner.domain.MemberRole;
import github.lms.lemuel.partner.domain.OrgType;
import github.lms.lemuel.partner.domain.PartnerScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 조직·구성원 프로젝션의 적재와, 로그인 사용자 → {@link PartnerScope} 해석. */
@Slf4j
@Component
@RequiredArgsConstructor
class PartnerDirectoryPersistenceAdapter implements LoadPartnerScopePort, PartnerDirectoryProjectionPort {

    private final PartnerJpaRepository partnerRepository;
    private final PartnerMemberJpaRepository memberRepository;

    @Override
    public Optional<PartnerScope> findByUserId(long userId) {
        List<Object[]> rows = memberRepository.findScopeRows(userId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() > 1) {
            // 조직 전환 UI 가 없으므로 가장 먼저 가입한 조직을 쓴다. 조용히 고르지 않고 남기는
            // 이유는, 이 상태에서 파트너가 보는 매출이 "자기가 기대한 조직" 이 아닐 수 있어서다.
            log.warn("사용자 {} 가 {}개 조직에 속해 있다 — 가장 먼저 가입한 조직으로 고정한다", userId, rows.size());
        }
        return Optional.of(toScope(rows.get(0)));
    }

    @Override
    public List<PartnerMemberView> findActiveMembers(long organizationId) {
        return memberRepository.findActiveMemberRows(organizationId).stream()
                .map(row -> new PartnerMemberView(
                        Rows.longAt(row, 0),
                        Rows.longAt(row, 1),
                        MemberRole.valueOf(Rows.stringAt(row, 2)),
                        Rows.offsetDateTimeAt(row, 3)))
                .toList();
    }

    @Override
    public void upsertOrganization(long organizationId, String name, OrgType type, String externalRef,
                                   Long sellerId, long ownerUserId) {
        partnerRepository.upsert(organizationId, name, type.name(), externalRef, sellerId, ownerUserId);
    }

    @Override
    public void upsertMembership(long membershipId, long organizationId, long userId, MemberRole role) {
        memberRepository.upsert(membershipId, organizationId, userId, role.name());
    }

    @Override
    public void markRemoved(long membershipId) {
        int updated = memberRepository.markRemoved(membershipId);
        if (updated == 0) {
            // 가입 이벤트보다 탈퇴 이벤트가 먼저 온 경우다. 여기서 예외를 던지면 컨슈머가
            // 재시도 끝에 DLT 로 보내는데, 그래도 가입 이벤트는 오지 않는다 — 순서 문제이지
            // 실패가 아니다. 다만 가입이 나중에 도착하면 이 사람은 ACTIVE 로 되살아난다.
            log.warn("탈퇴 대상 membership {} 이 없다 — 가입 이벤트가 아직 도착하지 않았다", membershipId);
        }
    }

    @Override
    public void changeRole(long membershipId, MemberRole newRole) {
        int updated = memberRepository.changeRole(membershipId, newRole.name());
        if (updated == 0) {
            log.warn("역할 변경 대상 membership {} 이 없다 — 가입 이벤트가 아직 도착하지 않았다", membershipId);
        }
    }

    private static PartnerScope toScope(Object[] row) {
        return new PartnerScope(
                Rows.longAt(row, 0),
                Rows.stringAt(row, 1),
                OrgType.valueOf(Rows.stringAt(row, 2)),
                Rows.nullableLongAt(row, 3),
                MemberRole.valueOf(Rows.stringAt(row, 4)));
    }
}
