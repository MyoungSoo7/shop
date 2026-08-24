package github.lms.lemuel.user.application.port.out;

import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberStatusCount;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase.MemberSummary;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 회원 콘솔 조회 포트.
 *
 * <p>기간은 이미 정규화된 {@link LocalDateTime} 반개구간({@code joinedFrom} 이상
 * {@code joinedToExclusive} 미만)으로 받는다. "종료일 포함"을 어떻게 해석할지는 정책이라
 * 서비스가 정하고, 어댑터는 경계 계산을 다시 하지 않는다.
 */
public interface SearchMembersPort {

    /** 조건에 맞는 회원을 가입 최신순으로 한 페이지 조회한다. */
    List<MemberSummary> search(MemberCriteria criteria, int page, int size);

    /** 같은 조건의 총 인원. */
    long count(MemberCriteria criteria);

    /** 같은 조건의 승인 상태별 인원. */
    List<MemberStatusCount> countByStatus(MemberCriteria criteria);

    /** 정규화된 조회 조건. 값이 null 이면 그 조건은 적용하지 않는다. */
    record MemberCriteria(
            String keyword,
            String role,
            String membershipStatus,
            Boolean active,
            LocalDateTime joinedFrom,
            LocalDateTime joinedToExclusive) {
    }
}
