package github.lms.lemuel.user.application.port.in;

import github.lms.lemuel.user.domain.MembershipStatus;
import github.lms.lemuel.user.domain.UserRole;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 회원 관리 콘솔 조회 유스케이스.
 *
 * <p><b>왜 필요한가</b>: 승인·정지·복구 API 는 이미 있었지만({@code /memberships/**}), 운영자가
 * 볼 수 있는 목록은 {@code /users/admin/all} 하나뿐이었다. 이 API 는 <b>전 회원을 페이징 없이
 * 한 번에</b> 돌려준다 — 회원이 늘면 응답이 커지다 어느 날 터지고, 그 전까지는 "이름으로
 * 찾아 주세요" 같은 문의에 답할 방법이 없다. 승인 대기 목록({@code /memberships/pending})도
 * PENDING 만 보여 주므로 "정지된 그 사람"을 찾을 수 없다.
 *
 * <p><b>조회 축</b>은 인덱스와 맞춘다 — {@code (membership_status, created_at)}, {@code (is_active)},
 * {@code email}. 키워드는 이메일·이름·연락처를 함께 훑는 편의 검색이라 인덱스를 타지 않으므로,
 * 다른 조건과 함께 쓰이는 것을 전제로 한다.
 */
public interface SearchMembersUseCase {

    /** 조건에 맞는 회원을 최신 가입순 페이지로 조회한다. */
    MemberPage search(MemberQuery query);

    /**
     * 같은 조건의 승인 상태별 인원.
     *
     * <p>목록보다 이게 먼저 필요하다 — "지금 승인 대기가 몇 명인가"는 목록을 넘겨 보며 세는
     * 것이 아니라 한눈에 보여야 하는 숫자다.
     */
    List<MemberStatusCount> countByStatus(MemberQuery query);

    /** 같은 조건의 내보내기용 목록(상한 있음). */
    MemberExport export(MemberQuery query);

    /**
     * 조회 조건.
     *
     * @param keyword    이메일·이름·연락처 부분일치(대소문자 무시). 공백/null 이면 미적용
     * @param role       역할 정확일치. null 이면 미적용
     * @param status     승인 상태 정확일치. null 이면 미적용
     * @param active     계정 활성 여부. null 이면 미적용(탈퇴 회원도 함께 본다)
     * @param joinedFrom 가입일 시작(포함). null 이면 미적용
     * @param joinedTo   가입일 종료(포함 — 그날 끝까지). null 이면 미적용
     */
    record MemberQuery(
            String keyword,
            UserRole role,
            MembershipStatus status,
            Boolean active,
            LocalDate joinedFrom,
            LocalDate joinedTo,
            int page,
            int size) {
    }

    /** 한 페이지. */
    record MemberPage(
            List<MemberSummary> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    /**
     * 목록 한 줄.
     *
     * <p>비밀번호 해시는 <b>담지 않는다</b>. 화면이 쓰지 않는 값을 응답에 실으면 언젠가 로그·
     * 캐시·CSV 어딘가로 새어 나간다.
     */
    record MemberSummary(
            Long id,
            String email,
            String name,
            String phoneNumber,
            String role,
            String membershipStatus,
            boolean active,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 승인 상태별 인원. */
    record MemberStatusCount(String membershipStatus, long count) {
    }

    /**
     * 내보내기 결과.
     *
     * @param truncated 상한에 걸려 잘렸는지 — 화면은 반드시 알려야 한다
     */
    record MemberExport(List<MemberSummary> rows, boolean truncated, long totalElements) {
    }
}
