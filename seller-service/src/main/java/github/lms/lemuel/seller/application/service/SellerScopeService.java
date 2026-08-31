package github.lms.lemuel.seller.application.service;

import github.lms.lemuel.seller.application.port.dto.SellerMemberView;
import github.lms.lemuel.seller.application.port.dto.SellerProfileView;
import github.lms.lemuel.seller.application.port.in.ResolveSellerScopeUseCase;
import github.lms.lemuel.seller.application.port.in.ViewSellerProfileUseCase;
import github.lms.lemuel.seller.application.port.out.LoadSellerScopePort;
import github.lms.lemuel.seller.domain.SellerScope;
import github.lms.lemuel.seller.domain.exception.SellerScopeNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 인가 스코프 해석 + 콘솔 헤더.
 *
 * <p>이 서비스가 이 모듈의 보안 경계 전부다. 다른 모든 유스케이스는 여기서 나온
 * {@link SellerScope} 만 받고, 요청에서 온 식별자는 어느 계층에서도 조회 조건이 되지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class SellerScopeService implements ResolveSellerScopeUseCase, ViewSellerProfileUseCase {

    private final LoadSellerScopePort loadSellerScopePort;

    public SellerScopeService(LoadSellerScopePort loadSellerScopePort) {
        this.loadSellerScopePort = loadSellerScopePort;
    }

    @Override
    public SellerScope resolve(long userId) {
        return loadSellerScopePort.findByUserId(userId)
                .orElseThrow(() -> new SellerScopeNotFoundException(userId));
    }

    @Override
    public SellerProfileView profile(SellerScope scope) {
        // canSubmit 은 셀러인지와 역할을 함께 본다. 둘을 화면에서 조합하게 두면 같은 규칙이 두
        // 벌이 되고, 법인 조직(셀러 아님)에서 STAFF 가 아니라는 이유로 등록 버튼이 켜진다.
        boolean canSubmit = scope.sellerId() != null && scope.role().canSubmit();

        return new SellerProfileView(
                scope.organizationId(),
                scope.organizationName(),
                scope.orgType(),
                scope.sellerId(),
                scope.role(),
                canSubmit);
    }

    @Override
    public List<SellerMemberView> members(SellerScope scope) {
        return loadSellerScopePort.findActiveMembers(scope.organizationId());
    }
}
