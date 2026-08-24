package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;

import java.util.Locale;

/**
 * 본문·부가기능 정책 — 글쓰기 표면이 무엇을 허용하는가.
 *
 * <p>분류(카테고리)는 <b>새 테이블을 만들지 않고</b> order-service 공통코드 그룹을 문자열로
 * 가리킨다({@code BOARD_CAT_NOTICE} 등). cross-DB FK 는 불가능하고 필요하지도 않다 —
 * 분류는 표시용 라벨이지 회계 값이 아니다. 그룹이 사라지면 라벨이 코드값으로 떨어지는 정도의
 * 열화만 생긴다(docs/plan/board-service.md §4).
 */
public final class BoardContentPolicy {

    private final BoardContentFormat contentFormat;
    private final boolean commentsEnabled;
    private final boolean secretEnabled;
    private final String categoryGroupCode;

    private BoardContentPolicy(BoardContentFormat contentFormat, boolean commentsEnabled,
                               boolean secretEnabled, String categoryGroupCode) {
        this.contentFormat = contentFormat;
        this.commentsEnabled = commentsEnabled;
        this.secretEnabled = secretEnabled;
        this.categoryGroupCode = categoryGroupCode;
    }

    public static BoardContentPolicy of(BoardContentFormat contentFormat, boolean commentsEnabled,
                                        boolean secretEnabled, String categoryGroupCode) {
        if (contentFormat == null) {
            throw new BoardInvariantViolationException("본문 형식은 필수입니다.");
        }
        return new BoardContentPolicy(contentFormat, commentsEnabled, secretEnabled,
                normalizeGroupCode(categoryGroupCode));
    }

    /** 영속 레코드 복원 — 저장값 재검증 금지(사유는 {@link BoardAccessPolicy#rehydrate} 와 동일). */
    public static BoardContentPolicy rehydrate(BoardContentFormat contentFormat, boolean commentsEnabled,
                                               boolean secretEnabled, String categoryGroupCode) {
        return new BoardContentPolicy(contentFormat, commentsEnabled, secretEnabled, categoryGroupCode);
    }

    private static String normalizeGroupCode(String categoryGroupCode) {
        if (categoryGroupCode == null || categoryGroupCode.isBlank()) {
            return null;
        }
        // 공통코드 그룹은 저쪽에서 대문자로 정규화돼 저장된다(CommonCodeGroup.create). 같은 규칙으로 접어야
        // 사람이 소문자로 입력한 값이 조용히 매칭 실패하지 않는다.
        String normalized = categoryGroupCode.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{1,40}")) {
            throw new BoardInvariantViolationException("분류 코드그룹 형식이 올바르지 않습니다: " + categoryGroupCode);
        }
        return normalized;
    }

    public BoardContentFormat contentFormat() {
        return contentFormat;
    }

    public boolean isCommentsEnabled() {
        return commentsEnabled;
    }

    public boolean isSecretEnabled() {
        return secretEnabled;
    }

    public String categoryGroupCode() {
        return categoryGroupCode;
    }

    public boolean hasCategoryGroup() {
        return categoryGroupCode != null;
    }

    /** 본문이 저장 전 sanitize 를 요구하는가(HTML 게시판). Phase 3 업로드 경로가 이 판단을 쓴다. */
    public boolean requiresSanitize() {
        return contentFormat.requiresSanitize();
    }
}
