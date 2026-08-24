package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 게시판 접근 정책 — 행위 4종(읽기·쓰기·댓글·운영)에 대한 <b>역할 allowlist</b>.
 *
 * <p><b>왜 permission 코드가 아니라 역할인가</b>: 권한 코드는 order-service 의 {@code permissions}
 * 테이블에 산다. 게시판이 그 코드로 인가를 판정하면 board-service 가 order DB 를 읽어야 하고,
 * 그 순간 DB-per-service 경계가 무너진다. 역할은 JWT 클레임({@code role})에 이미 실려 오므로
 * 어떤 외부 조회도 없이 판정이 끝난다 (docs/plan/board-service.md §3).
 *
 * <p><b>빈 집합의 의미는 행위마다 다르다</b>:
 * <ul>
 *   <li>읽기 — 비어 있으면 <b>공개</b>다. 비로그인 방문자도 읽는다(공지사항).</li>
 *   <li>쓰기·댓글·운영 — 비어 있을 수 없다. 익명 쓰기는 스팸 벡터라 정책적으로 지원하지 않는다.
 *       "아무나 쓰게" 하고 싶다면 {@code USER} 를 명시적으로 넣어야 한다.</li>
 * </ul>
 * 이 비대칭을 문서가 아니라 조립 시점 검사로 강제한다.
 *
 * <p>역할 문자열을 enum 으로 봉인하지 않는 이유: 역할은 RBAC 테이블의 데이터이고 운영 중
 * 늘어난다(`roles` 는 builtin 3종 + 관리자 생성분). 여기서 화이트리스트를 들면 새 역할이
 * 생길 때마다 board-service 를 배포해야 한다.
 */
public final class BoardAccessPolicy {

    private final Set<String> readRoles;
    private final Set<String> writeRoles;
    private final Set<String> commentRoles;
    private final Set<String> manageRoles;

    private BoardAccessPolicy(Set<String> readRoles, Set<String> writeRoles,
                              Set<String> commentRoles, Set<String> manageRoles) {
        this.readRoles = readRoles;
        this.writeRoles = writeRoles;
        this.commentRoles = commentRoles;
        this.manageRoles = manageRoles;
    }

    public static BoardAccessPolicy of(Collection<String> readRoles, Collection<String> writeRoles,
                                       Collection<String> commentRoles, Collection<String> manageRoles) {
        Set<String> write = normalizeRequired(writeRoles, "쓰기");
        Set<String> comment = normalizeRequired(commentRoles, "댓글");
        Set<String> manage = normalizeRequired(manageRoles, "운영");
        return new BoardAccessPolicy(normalize(readRoles), write, comment, manage);
    }

    /**
     * 영속 레코드 복원 — 저장된 값은 이미 검증을 통과한 것이므로 재검증하지 않는다.
     *
     * <p>재검증하면 정책이 강화됐을 때 <b>기존 게시판을 읽는 것만으로 예외</b>가 난다. 조회가
     * 죽으면 관리자는 그 게시판을 고칠 수단조차 잃는다.
     */
    public static BoardAccessPolicy rehydrate(Collection<String> readRoles, Collection<String> writeRoles,
                                              Collection<String> commentRoles, Collection<String> manageRoles) {
        return new BoardAccessPolicy(normalize(readRoles), normalize(writeRoles),
                normalize(commentRoles), normalize(manageRoles));
    }

    private static Set<String> normalizeRequired(Collection<String> roles, String label) {
        Set<String> normalized = normalize(roles);
        if (normalized.isEmpty()) {
            throw new BoardInvariantViolationException(
                    label + " 허용 역할은 최소 하나가 필요합니다. 익명 " + label + "은 지원하지 않습니다.");
        }
        return normalized;
    }

    private static Set<String> normalize(Collection<String> roles) {
        if (roles == null) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            normalized.add(role.trim().toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(normalized);
    }

    /** 읽기 허용 역할이 비어 있으면 공개 게시판이다(비로그인 포함). */
    public boolean isPublicRead() {
        return readRoles.isEmpty();
    }

    public boolean canRead(String role) {
        return isPublicRead() || matches(readRoles, role);
    }

    public boolean canWrite(String role) {
        return matches(writeRoles, role);
    }

    public boolean canComment(String role) {
        return matches(commentRoles, role);
    }

    public boolean canManage(String role) {
        return matches(manageRoles, role);
    }

    private static boolean matches(Set<String> allowed, String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        return allowed.contains(role.trim().toUpperCase(Locale.ROOT));
    }

    public Set<String> readRoles() {
        return readRoles;
    }

    public Set<String> writeRoles() {
        return writeRoles;
    }

    public Set<String> commentRoles() {
        return commentRoles;
    }

    public Set<String> manageRoles() {
        return manageRoles;
    }
}
