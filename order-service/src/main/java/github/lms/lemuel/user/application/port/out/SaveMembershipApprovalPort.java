package github.lms.lemuel.user.application.port.out;

import github.lms.lemuel.user.domain.MembershipApproval;

public interface SaveMembershipApprovalPort {

    MembershipApproval save(MembershipApproval approval);
}
