package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.ApproveMembershipUseCase;
import github.lms.lemuel.user.application.port.in.GetPendingMembersUseCase;
import github.lms.lemuel.user.application.port.out.LoadMembersByStatusPort;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PublishUserEventPort;
import github.lms.lemuel.user.application.port.out.SaveMembershipApprovalPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.domain.MembershipAction;
import github.lms.lemuel.user.domain.MembershipApproval;
import github.lms.lemuel.user.domain.MembershipStatus;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Consumer;

/**
 * 회원 승인 워크플로 서비스.
 *
 * 도메인의 상태 전이 메서드를 호출해 회원 상태를 바꾸고, 같은 트랜잭션에서
 * 감사 이력({@link MembershipApproval})을 기록한다. 잘못된 전이는 도메인이
 * IllegalStateException 으로 차단하므로 회원 상태/이력 모두 롤백된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MembershipApprovalService implements ApproveMembershipUseCase, GetPendingMembersUseCase {

    private final LoadUserPort loadUserPort;
    private final SaveUserPort saveUserPort;
    private final LoadMembersByStatusPort loadMembersByStatusPort;
    private final SaveMembershipApprovalPort saveMembershipApprovalPort;
    private final PublishUserEventPort publishUserEventPort;

    @Override
    @Transactional
    public User approve(Long userId, Long processedBy) {
        return process(userId, User::approveMembership, MembershipAction.APPROVE, null, processedBy);
    }

    @Override
    @Transactional
    public User reject(Long userId, String reason, Long processedBy) {
        return process(userId, User::rejectMembership, MembershipAction.REJECT, reason, processedBy);
    }

    @Override
    @Transactional
    public User suspend(Long userId, String reason, Long processedBy) {
        return process(userId, User::suspendMembership, MembershipAction.SUSPEND, reason, processedBy);
    }

    @Override
    @Transactional
    public User reinstate(Long userId, Long processedBy) {
        return process(userId, User::reinstateMembership, MembershipAction.REINSTATE, null, processedBy);
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> getPendingMembers() {
        return loadMembersByStatusPort.findByMembershipStatus(MembershipStatus.PENDING);
    }

    private User process(Long userId, Consumer<User> transition, MembershipAction action,
                         String reason, Long processedBy) {
        User user = loadUserPort.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        transition.accept(user);                       // 도메인 상태 전이 (가드 포함)
        User saved = saveUserPort.save(user);

        saveMembershipApprovalPort.save(
                new MembershipApproval(userId, action, reason, processedBy));

        // 같은 트랜잭션에서 outbox 발행 — reservation-service 등이 기사 프로젝션을 동기화한다.
        publishUserEventPort.publishMembershipChanged(
                saved.getId(),
                saved.getRole().name(),
                saved.getMembershipStatus().name(),
                saved.isActive());

        log.info("회원 승인 처리: userId={}, action={}, status={}, processedBy={}",
                userId, action, saved.getMembershipStatus(), processedBy);
        return saved;
    }
}
