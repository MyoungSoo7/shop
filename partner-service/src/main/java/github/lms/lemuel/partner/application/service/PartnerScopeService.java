package github.lms.lemuel.partner.application.service;

import github.lms.lemuel.partner.application.port.dto.PartnerMemberView;
import github.lms.lemuel.partner.application.port.dto.PartnerProfileView;
import github.lms.lemuel.partner.application.port.in.ResolvePartnerScopeUseCase;
import github.lms.lemuel.partner.application.port.in.ViewPartnerProfileUseCase;
import github.lms.lemuel.partner.application.port.out.LoadPartnerScopePort;
import github.lms.lemuel.partner.application.port.out.LoadSellerTierPort;
import github.lms.lemuel.partner.domain.PartnerScope;
import github.lms.lemuel.partner.domain.exception.PartnerScopeNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 인가 스코프 해석 + 콘솔 헤더.
 *
 * <p>이 서비스가 이 모듈의 보안 경계 전부다. 다른 모든 유스케이스는 여기서 나온
 * {@link PartnerScope} 만 받고, 요청에서 온 식별자는 어느 계층에서도 조회 조건이 되지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class PartnerScopeService implements ResolvePartnerScopeUseCase, ViewPartnerProfileUseCase {

    private final LoadPartnerScopePort loadPartnerScopePort;
    private final LoadSellerTierPort loadSellerTierPort;

    public PartnerScopeService(LoadPartnerScopePort loadPartnerScopePort,
                               LoadSellerTierPort loadSellerTierPort) {
        this.loadPartnerScopePort = loadPartnerScopePort;
        this.loadSellerTierPort = loadSellerTierPort;
    }

    @Override
    public PartnerScope resolve(long userId) {
        return loadPartnerScopePort.findByUserId(userId)
                .orElseThrow(() -> new PartnerScopeNotFoundException(userId));
    }

    @Override
    public PartnerProfileView profile(PartnerScope scope) {
        // 등급은 셀러에게만 있다. 셀러가 아닌 조직에 등급을 조회하면 항상 비는데, 그 빈 값이
        // 화면에서는 "등급 미확인" 으로 보여 법인 고객에게 없는 문제를 있는 것처럼 만든다.
        var tier = scope.sellerId() == null
                ? java.util.Optional.<LoadSellerTierPort.TierSnapshot>empty()
                : loadSellerTierPort.findBySellerId(scope.sellerId());

        return new PartnerProfileView(
                scope.organizationId(),
                scope.organizationName(),
                scope.orgType(),
                scope.sellerId(),
                scope.role(),
                scope.sellerId() != null,
                tier.map(LoadSellerTierPort.TierSnapshot::tier).orElse(null),
                tier.map(LoadSellerTierPort.TierSnapshot::effectiveFrom).orElse(null));
    }

    @Override
    public List<PartnerMemberView> members(PartnerScope scope) {
        return loadPartnerScopePort.findActiveMembers(scope.organizationId());
    }
}
