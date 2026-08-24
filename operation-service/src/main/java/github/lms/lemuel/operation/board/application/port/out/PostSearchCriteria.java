package github.lms.lemuel.operation.board.application.port.out;

/**
 * 게시글 목록 조회 조건.
 *
 * <p><b>가시성을 조건으로 내려보내는 이유</b>: 페이지를 읽어 온 뒤 자바에서 걸러 내면 총건수와
 * 페이지 크기가 어긋난다(10건 요청 → 비밀글 3건 제외 → 7건 반환, 총건수는 여전히 전체).
 * 무엇이 보이는지는 도메인이 정하지만, 그 판정을 <b>조건으로 번역</b>해 질의에 싣는 것은
 * 응용 계층의 일이다.
 *
 * @param includeHidden    숨김 글까지 볼 수 있는가(운영 역할)
 * @param includeAllSecret 남의 비밀글까지 볼 수 있는가(운영 역할)
 * @param viewerId         비밀글 소유 판정에 쓰는 주체 식별자. 미인증이면 null
 */
public record PostSearchCriteria(
        Long boardId,
        String categoryCode,
        String keyword,
        boolean includeHidden,
        boolean includeAllSecret,
        Long viewerId) {
}
