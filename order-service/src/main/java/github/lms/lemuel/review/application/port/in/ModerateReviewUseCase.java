package github.lms.lemuel.review.application.port.in;

import github.lms.lemuel.review.domain.Review;

/**
 * 리뷰 노출 관리(블라인드) 유스케이스.
 *
 * <p><b>왜 필요한가</b>: 지금까지 리뷰를 내리는 방법은 <b>작성자 본인의 삭제</b>뿐이었다. 욕설·
 * 개인정보 노출·경쟁사 도배가 올라와도 운영자가 할 수 있는 일은 DB 를 직접 손대는 것뿐이었고,
 * 그렇게 지운 글은 근거도 복구 경로도 남지 않는다.
 */
public interface ModerateReviewUseCase {

    /**
     * 리뷰를 블라인드한다(원문 보존, 노출만 차단).
     *
     * @param reason  사유(필수) — 작성자 이의 제기와 감사 양쪽에 필요하다
     * @param actorId 조작한 관리자
     */
    Review hide(Long reviewId, String reason, Long actorId);

    /** 블라인드를 해제한다. */
    Review restore(Long reviewId, Long actorId);
}
