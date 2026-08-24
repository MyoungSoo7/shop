package github.lms.lemuel.operation.board.domain;

/**
 * 게시글 본문 형식.
 *
 * <p>형식이 곧 위험도다 — {@code HTML} 만 사용자 입력이 브라우저에서 실행 가능한 마크업이 되므로
 * 저장 전 sanitize 가 필요하다(Phase 3). 그 판단을 컨트롤러가 문자열 비교로 하지 않도록
 * 도메인이 {@link #requiresSanitize()} 로 답한다.
 */
public enum BoardContentFormat {
    /** 평문 — 개행만 보존해 렌더 */
    TEXT,
    /** 마크다운 — 렌더는 프론트가, 원문은 그대로 저장 */
    MARKDOWN,
    /** 리치 에디터 HTML — 저장 전 서버측 sanitize 필수 */
    HTML;

    public boolean requiresSanitize() {
        return this == HTML;
    }
}
