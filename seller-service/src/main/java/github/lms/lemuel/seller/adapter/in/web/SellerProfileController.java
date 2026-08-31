package github.lms.lemuel.seller.adapter.in.web;

import github.lms.lemuel.seller.application.port.dto.SellerMemberView;
import github.lms.lemuel.seller.application.port.dto.SellerProfileView;
import github.lms.lemuel.seller.application.port.in.ResolveSellerScopeUseCase;
import github.lms.lemuel.seller.application.port.in.ViewSellerProfileUseCase;
import github.lms.lemuel.seller.domain.SellerScope;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 셀러 자기 정보 — 화면이 제일 먼저 부르는 두 개.
 *
 * <p>{@code /profile} 이 없으면 화면은 자기가 어느 조직인지, <b>제출 버튼을 그려도 되는지</b>조차
 * 모른다. 특히 {@code canSubmit=false}(STAFF)를 화면이 알아야 눌러도 403 이 나는 버튼을 안 그린다.
 * 다만 그건 표시용일 뿐이고 실제 차단은 매 요청마다 서버가 다시 한다.
 */
@RestController
@RequestMapping("/api/seller")
@RequiredArgsConstructor
public class SellerProfileController {

    private final ResolveSellerScopeUseCase resolveScope;
    private final ViewSellerProfileUseCase viewProfile;

    @GetMapping("/profile")
    public SellerProfileView profile() {
        return viewProfile.profile(scope());
    }

    /** 같은 조직의 활성 구성원. 조직 번호를 인자로 받지 않는 이유는 {@link CurrentSellerUser} 참조. */
    @GetMapping("/members")
    public List<SellerMemberView> members() {
        return viewProfile.members(scope());
    }

    private SellerScope scope() {
        return resolveScope.resolve(CurrentSellerUser.requireUserId());
    }
}
