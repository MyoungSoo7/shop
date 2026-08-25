package github.lms.lemuel.user.application.port.out;

import github.lms.lemuel.user.application.port.in.SearchOperatorsUseCase.OperatorSummary;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 운영자 계정 콘솔 조회 포트.
 *
 * <p>{@link SearchMembersPort} 와 같은 규약: 시각 경계는 서비스가 이미 계산해 넘기고 어댑터는
 * 다시 해석하지 않는다. "몇 일 이상 미사용"이 몇 시 몇 분인지는 정책이지 SQL 이 아니다.
 *
 * <p>{@code now} 를 조건에 함께 넘기는 이유: 잠금 판정({@code locked_until > now})의 기준 시각이
 * 서비스와 DB 두 곳에서 따로 정해지면 같은 요청 안에서도 목록의 잠김 표시와 건수가 어긋난다.
 * 기준 시각은 하나여야 하고, 그 하나는 {@code Clock} 을 가진 서비스가 정한다.
 */
public interface SearchOperatorsPort {

    /** 조건에 맞는 운영자 계정을 한 페이지 조회한다. */
    List<OperatorSummary> search(OperatorCriteria criteria, int page, int size);

    /** 같은 조건의 총 인원. */
    long count(OperatorCriteria criteria);

    /**
     * 정규화된 조회 조건. 값이 null 이면 그 조건은 적용하지 않는다.
     *
     * @param roles          조회 대상 역할(비어 있을 수 없다 — 비면 전 회원 조회가 된다)
     * @param keyword        이메일·이름 부분일치
     * @param lockedOnly     지금 잠긴 계정만
     * @param idleBefore     마지막 로그인이 이 시각 이전인 계정만
     * @param neverLoggedIn  한 번도 로그인하지 않은 계정만
     * @param now            잠금 판정 기준 시각
     */
    record OperatorCriteria(
            List<String> roles,
            String keyword,
            boolean lockedOnly,
            LocalDateTime idleBefore,
            boolean neverLoggedIn,
            LocalDateTime now) {
    }
}
