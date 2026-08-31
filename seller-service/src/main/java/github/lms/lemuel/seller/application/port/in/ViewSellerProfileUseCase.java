package github.lms.lemuel.seller.application.port.in;

import github.lms.lemuel.seller.application.port.dto.SellerMemberView;
import github.lms.lemuel.seller.application.port.dto.SellerProfileView;
import github.lms.lemuel.seller.domain.SellerScope;

import java.util.List;

/** 콘솔 헤더(내 조직·내 역할·내가 할 수 있는 일)와 구성원 목록. */
public interface ViewSellerProfileUseCase {

    SellerProfileView profile(SellerScope scope);

    /** 같은 조직의 활성 구성원. 조직 밖 사람은 애초에 {@link SellerScope} 를 못 얻는다. */
    List<SellerMemberView> members(SellerScope scope);
}
