package github.lms.lemuel.partner.application.port.in;

import github.lms.lemuel.partner.application.port.dto.PartnerMemberView;
import github.lms.lemuel.partner.application.port.dto.PartnerProfileView;
import github.lms.lemuel.partner.domain.PartnerScope;

import java.util.List;

/** 콘솔 헤더(내 조직·등급)와 구성원 목록. */
public interface ViewPartnerProfileUseCase {

    PartnerProfileView profile(PartnerScope scope);

    /** 같은 조직의 활성 구성원. 조직 밖 사람은 애초에 {@link PartnerScope} 를 못 얻는다. */
    List<PartnerMemberView> members(PartnerScope scope);
}
