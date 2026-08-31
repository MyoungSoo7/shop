package github.lms.lemuel.seller.domain.exception;

import github.lms.lemuel.seller.domain.MemberRole;

/**
 * 조직 소속은 맞는데 그 행위를 할 역할이 아니다 (STAFF 가 제출·송장 등록을 시도).
 *
 * <p>메시지에 현재 역할을 담는다. "권한이 없습니다" 만 나오면 STAFF 는 자기 계정이 잘못된
 * 줄 알고 재로그인을 반복한다 — 무엇이 부족한지 알아야 조직 관리자에게 요청할 수 있다.
 */
public class InsufficientSellerRoleException extends RuntimeException {

    public InsufficientSellerRoleException(MemberRole role) {
        super("현재 역할(" + role + ")로는 제출·송장 등록을 할 수 없습니다. 조직 관리자(OWNER/MANAGER)에게 요청하세요.");
    }
}
