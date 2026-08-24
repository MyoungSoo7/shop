package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;

/**
 * 작성자 — 식별자(대조용)와 표시명(화면용)을 함께 든 값.
 *
 * <p><b>표시명은 마스킹된 스냅샷이다.</b> 이 플랫폼의 JWT 에는 닉네임이 없고 이메일(sub)뿐인데,
 * 원문 이메일을 board DB 에 적으면 PII 가 서비스 하나 더 넓은 범위로 퍼진다. 게시판이 필요한
 * 것은 "누가 썼는지 사람이 알아볼 수 있는 라벨"이지 연락처가 아니다.
 *
 * <p>소유권 대조(수정·삭제 권한)는 표시명이 아니라 {@link #userId()} 로 한다 — 마스킹은 표시
 * 계층의 손실이지 신원의 손실이 아니다.
 *
 * <p>스냅샷인 이유: 나중에 사용자가 이메일을 바꿔도 그때 쓴 글의 작성자 라벨은 그대로다.
 * 게시판에서는 그게 오히려 올바른 의미론이고(당시의 그 사람), 덕분에 user 프로젝션이 필요 없다.
 */
public record BoardAuthor(Long userId, String displayName) {

    public BoardAuthor {
        if (userId == null) {
            throw new BoardInvariantViolationException("작성자 식별자는 필수입니다.");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new BoardInvariantViolationException("작성자 표시명은 필수입니다.");
        }
    }

    /**
     * JWT 주체(이메일)에서 표시명을 파생한다.
     *
     * <p>{@code admin@lemuel.local} → {@code ad***}. 두 글자 이하 로컬파트는 첫 글자만 남긴다.
     * 도메인 부분은 통째로 버린다 — 사내 도메인 목록도 정보다.
     */
    public static BoardAuthor fromSubject(Long userId, String subject) {
        if (subject == null || subject.isBlank()) {
            throw new BoardInvariantViolationException("작성자 주체(subject)는 필수입니다.");
        }
        String local = subject.trim();
        int at = local.indexOf('@');
        if (at > 0) {
            local = local.substring(0, at);
        }
        String masked = local.length() <= 2
                ? local.charAt(0) + "*"
                : local.substring(0, 2) + "***";
        return new BoardAuthor(userId, masked);
    }
}
