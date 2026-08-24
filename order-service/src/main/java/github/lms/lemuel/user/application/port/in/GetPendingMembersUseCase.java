package github.lms.lemuel.user.application.port.in;

import github.lms.lemuel.user.domain.User;

import java.util.List;

/**
 * 승인 대기(PENDING) 회원 조회 UseCase (관리자 대시보드).
 */
public interface GetPendingMembersUseCase {

    List<User> getPendingMembers();
}
