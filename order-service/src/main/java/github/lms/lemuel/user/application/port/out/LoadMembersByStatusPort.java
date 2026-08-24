package github.lms.lemuel.user.application.port.out;

import github.lms.lemuel.user.domain.MembershipStatus;
import github.lms.lemuel.user.domain.User;

import java.util.List;

public interface LoadMembersByStatusPort {

    List<User> findByMembershipStatus(MembershipStatus status);
}
