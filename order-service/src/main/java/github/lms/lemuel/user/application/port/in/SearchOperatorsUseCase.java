package github.lms.lemuel.user.application.port.in;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 운영자 계정 콘솔 조회 유스케이스.
 *
 * <p><b>회원 콘솔({@link SearchMembersUseCase})과 왜 따로인가</b>: 보는 축이 다르다.
 * 회원 콘솔은 "이 사람을 찾는다"가 목적이라 이메일·이름·연락처·승인상태를 보여 준다.
 * 운영자 콘솔은 <b>계정 위생</b>이 목적이라 마지막 로그인·실패 누적·잠금·비밀번호 기준 시각을
 * 본다. {@code MemberSummary} 에는 그 네 값이 하나도 없고, 거기 얹으면 전 회원 목록이
 * 로그인 보안 상태를 함께 실어 나른다 — 필요 없는 곳까지 넓어진다.
 *
 * <p><b>대상은 ADMIN · MANAGER 로 고정</b>이다. 이 콘솔이 답해야 하는 질문("권한을 가진 계정 중
 * 오래 안 쓴 것 / 지금 잠긴 것")은 권한 없는 계정에는 성립하지 않는다. 일반 회원까지 열면
 * 전 회원 로그인 상태 조회가 되어 버린다.
 */
public interface SearchOperatorsUseCase {

    /** 조건에 맞는 운영자 계정을 조회한다. 기본 정렬은 마지막 로그인이 오래된 순. */
    OperatorPage search(OperatorQuery query);

    /** 같은 조건의 CSV 내보내기 목록(상한 있음). */
    OperatorExport export(OperatorQuery query);

    /**
     * 조회 조건.
     *
     * @param keyword    이메일·이름 부분일치(대소문자 무시). 공백/null 이면 미적용
     * @param role       ADMIN 또는 MANAGER 로만 좁힌다. null 이면 둘 다
     * @param lockedOnly true 면 지금 잠긴 계정만
     * @param idleDays   마지막 로그인이 이 일수보다 오래된 계정만. null 이면 미적용
     * @param neverLoggedIn true 면 한 번도 로그인한 적 없는 계정만
     */
    record OperatorQuery(
            String keyword,
            String role,
            boolean lockedOnly,
            Integer idleDays,
            boolean neverLoggedIn,
            int page,
            int size) {
    }

    /** 한 페이지. */
    record OperatorPage(
            List<OperatorSummary> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    /**
     * 운영자 계정 한 줄.
     *
     * <p>{@code locked} 는 조회 시각 기준으로 서버가 판정해 담는다. {@code lockedUntil} 만 주고
     * 화면이 "미래면 잠김"을 계산하게 두면 서버와 브라우저의 시계·시간대가 갈라지는 만큼
     * 판정이 갈라진다 — 잠긴 계정이 안 잠긴 것으로 보이는 쪽이 특히 나쁘다.
     *
     * <p>연락처는 담지 않는다. 이 화면의 용도(계정 위생)에 필요 없고, 목록에 실리는 순간
     * CSV 를 타고 밖으로 나간다.
     */
    record OperatorSummary(
            Long id,
            String email,
            String name,
            String role,
            boolean active,
            LocalDateTime lastLoginAt,
            int failedLoginAttempts,
            LocalDateTime lockedUntil,
            boolean locked,
            LocalDateTime passwordChangedAt,
            LocalDateTime createdAt) {
    }

    /**
     * 내보내기 결과.
     *
     * @param truncated 상한에 걸려 잘렸는지 — 화면은 반드시 알려야 한다
     */
    record OperatorExport(List<OperatorSummary> rows, boolean truncated, long totalElements) {
    }
}
