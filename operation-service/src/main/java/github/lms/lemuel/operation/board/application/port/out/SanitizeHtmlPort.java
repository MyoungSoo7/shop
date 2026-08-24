package github.lms.lemuel.operation.board.application.port.out;

/**
 * HTML 본문 정화 — 실행 가능한 마크업을 걷어내고 표시용 태그만 남긴다.
 *
 * <p><b>왜 도메인이 아니라 포트인가</b>: 무엇이 위험한 태그인지는 라이브러리와 브라우저 동작에
 * 달린 <b>바깥 세상의 지식</b>이고, 새 우회 기법이 나오면 구현이 바뀐다. 도메인이 아는 것은
 * "이 게시판이 정화를 요구하는가"({@code BoardContentPolicy.requiresSanitize})까지다 —
 * <b>판단은 도메인, 수행은 어댑터</b>.
 */
public interface SanitizeHtmlPort {

    /**
     * 정화된 HTML 을 돌려준다. 입력이 null 이면 null.
     *
     * <p>구현은 <b>화이트리스트 방식</b>이어야 한다. "위험한 것을 지운다"는 블랙리스트는 새 우회
     * 기법이 나올 때마다 뚫린다 — 허용할 것만 남기고 나머지를 버리는 방향이라야 한다.
     */
    String sanitize(String html);
}
