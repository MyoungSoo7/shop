package github.lms.lemuel.partner.adapter.in.web;

import github.lms.lemuel.partner.application.port.dto.PartnerMemberView;
import github.lms.lemuel.partner.application.port.dto.PartnerProfileView;
import github.lms.lemuel.partner.application.port.in.ResolvePartnerScopeUseCase;
import github.lms.lemuel.partner.application.port.in.ViewPartnerProfileUseCase;
import github.lms.lemuel.partner.domain.PartnerScope;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 파트너 자기 정보 — 화면이 제일 먼저 부르는 두 개.
 *
 * <p>{@code /me} 가 없으면 화면은 자기가 어느 조직인지, 매출을 볼 수 있는 조직인지조차 모른다.
 * 특히 {@code salesAvailable=false}(셀러로 연결되지 않은 기업 회원)를 화면이 알아야
 * "데이터 없음" 이 아니라 "판매 조직이 아닙니다" 를 보여줄 수 있다 — 그 둘은 완전히 다른
 * 상태인데 화면에서는 똑같이 빈 표로 보인다.
 */
@RestController
@RequestMapping("/api/partner")
@RequiredArgsConstructor
public class PartnerProfileController {

    private final ResolvePartnerScopeUseCase resolveScope;
    private final ViewPartnerProfileUseCase viewProfile;

    @GetMapping("/me")
    public PartnerProfileView me() {
        return viewProfile.profile(scope());
    }

    /** 같은 조직의 활성 구성원. 조직 번호를 인자로 받지 않는 이유는 {@link CurrentPartnerUser} 참조. */
    @GetMapping("/members")
    public List<PartnerMemberView> members() {
        return viewProfile.members(scope());
    }

    private PartnerScope scope() {
        return resolveScope.resolve(CurrentPartnerUser.requireUserId());
    }
}
