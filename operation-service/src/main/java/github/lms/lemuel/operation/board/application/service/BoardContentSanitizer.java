package github.lms.lemuel.operation.board.application.service;

import github.lms.lemuel.operation.board.application.port.out.SanitizeHtmlPort;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 본문 정화 지점 — <b>쓰기 경로가 반드시 지나는 한 곳</b>.
 *
 * <p>정화를 저장 시점에 하는 이유는 렌더 시점에 하면 늦기 때문이다. 원문을 그대로 쌓아 두면
 * 오늘의 렌더러가 이스케이프해 준 덕에 안전해 보이지만, 나중에 누군가
 * {@code dangerouslySetInnerHTML} 한 줄을 추가하는 순간 <b>그동안 쌓인 모든 행이 동시에 발화</b>한다.
 * 그 한 줄을 쓰는 사람은 거의 항상 "저장된 건 정화됐겠지"라고 가정한다.
 *
 * <p>사후 정화(백필)로 미룰 수 없는 이유: 이미 쌓인 행을 훑어 다시 쓰는 것은 <b>사용자가 쓴 글을
 * 서버가 임의로 고치는 일</b>이고, 어디까지가 공격이고 어디까지가 정당한 마크업인지 사후에는
 * 판정할 수 없다. 원장 전표를 수정하지 않고 역분개만 하는 것과 같은 이유다 —
 * 쓰기 시점에 세운 규칙만 신뢰할 수 있다.
 *
 * <p><b>MARKDOWN 은 정화하지 않는다.</b> 마크다운 원문을 HTML 정화기에 넣으면 코드 블록 안의
 * {@code <script>} 같은 <b>정당한 예시 코드까지</b> 사라진다. 대신 마크다운 렌더러가 raw HTML 을
 * 끄는 것이 계약이다(프론트 책임). 댓글도 같다 — 댓글은 HTML 렌더 경로가 없는 평문이다.
 */
@Component
@RequiredArgsConstructor
public class BoardContentSanitizer {

    private final SanitizeHtmlPort sanitizeHtmlPort;

    /**
     * 게시판 정책이 요구할 때만 정화한다.
     *
     * <p>"요구하는가"는 도메인({@code definition})이 답하고, "어떻게 정화하는가"는 어댑터가 안다.
     * 이 클래스는 그 둘을 잇기만 한다 — 여기서 형식을 문자열로 비교하기 시작하면 규칙이 둘이 된다.
     */
    public String sanitize(BoardDefinition definition, String content) {
        if (content == null || !definition.getContentPolicy().requiresSanitize()) {
            return content;
        }
        return sanitizeHtmlPort.sanitize(content);
    }
}
