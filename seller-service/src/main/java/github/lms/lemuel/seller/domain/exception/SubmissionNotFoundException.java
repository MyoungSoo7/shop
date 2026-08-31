package github.lms.lemuel.seller.domain.exception;

/**
 * 신청서를 못 찾았다 — <b>없거나, 내 것이 아니거나</b>.
 *
 * <p>두 경우를 구분하지 않는 것이 의도다. "존재하지만 남의 것" 을 403 으로 따로 알려 주면,
 * 그 응답만으로 번호를 훑어 어느 신청서가 실재하는지 알아낼 수 있다. 셀러 콘솔에서 그건 경쟁사가
 * 남의 등록 규모를 세는 경로가 된다. 소유자 검사를 조회 조건에 넣어 둔 것과 같은 이유다.
 */
public class SubmissionNotFoundException extends RuntimeException {

    public SubmissionNotFoundException(long submissionId) {
        super("신청서를 찾을 수 없습니다: submissionId=" + submissionId);
    }
}
