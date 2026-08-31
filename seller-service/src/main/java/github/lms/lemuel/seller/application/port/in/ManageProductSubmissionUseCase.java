package github.lms.lemuel.seller.application.port.in;

import github.lms.lemuel.seller.application.port.dto.SubmissionView;
import github.lms.lemuel.seller.domain.ProductContent;
import github.lms.lemuel.seller.domain.SellerScope;
import github.lms.lemuel.seller.domain.SubmissionType;

/**
 * 셀러가 자기 상품을 <b>직접 등록</b>하는 경로 — 이 서비스의 존재 이유.
 *
 * <p>레퍼런스(ssgb2e-outbackoffice)에서는 등록 화면의 저장 버튼 하나가 곧 심사 대기였다.
 * 그래서 쓰다 만 신청서가 심사 큐에 섞였고, 운영자는 그걸 반려하고, 셀러는 처음부터 다시
 * 등록했다. 여기서는 작성({@link #create})과 제출({@link #submit})을 갈라 두었다 — 갈라 두면
 * 큐에 있는 것은 전부 "봐 달라고 낸 것" 이 된다.
 *
 * <p><b>세 메서드 모두 {@link SellerScope} 를 받는다.</b> {@code sellerId} 를 인자로 받는
 * 시그니처를 두지 않는 이유는 {@link SellerScope} 클래스 주석에 있다 — 요약하면, 그 인자가
 * 요청 본문에서 채워지는 코드는 리뷰를 통과하기 때문이다.
 */
public interface ManageProductSubmissionUseCase {

    /**
     * 신청서를 작성한다(DRAFT). 아직 아무 데도 나가지 않는다.
     *
     * <p>제출과 달리 STAFF 도 할 수 있다. 초안 작성까지 막으면 조직 안에서 실무자가 아무것도
     * 못 하게 되고, 그러면 관리자 계정을 공유하게 된다 — 통제가 오히려 사라진다.
     */
    SubmissionView create(SellerScope scope, long userId, SubmissionType type,
                          Long baseProductId, ProductContent content);

    /** 내용을 고친다. 작성 중(DRAFT)이거나 반려(REJECTED)된 건만 고칠 수 있다. */
    SubmissionView update(SellerScope scope, long submissionId, ProductContent content);

    /**
     * 심사에 올린다. <b>여기서만 역할 검사가 걸린다</b>({@code MemberRole.canSubmit()}).
     *
     * <p>화면에서 제출 버튼을 감추는 것으로는 부족하다 — API 는 화면 없이도 호출된다.
     */
    SubmissionView submit(SellerScope scope, long submissionId);
}
