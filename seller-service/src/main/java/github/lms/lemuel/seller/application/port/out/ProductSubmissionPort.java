package github.lms.lemuel.seller.application.port.out;

import github.lms.lemuel.seller.domain.ProductSubmission;
import github.lms.lemuel.seller.domain.SubmissionStatus;

import java.util.List;
import java.util.Optional;

/**
 * 상품 등록 신청서 원장 — 이 서비스가 <b>유일하게 소유한</b> 두 테이블 중 하나.
 *
 * <p>조회 메서드가 둘로 갈린 것이 요점이다. {@link #load(long, long)} 는 셀러 화면용이라
 * {@code sellerId} 를 반드시 받고, {@link #loadAny(long)} 는 운영자·이벤트 처리용이라 받지
 * 않는다. 하나로 합치고 "sellerId 가 null 이면 전체" 로 두면, 셀러 경로에서 null 이 흘러들어간
 * 날 남의 신청서가 열린다 — 그리고 그 코드는 컴파일도 되고 테스트도 통과한다.
 */
public interface ProductSubmissionPort {

    /** 내 셀러의 신청서만. 소유자가 아니면 {@code Optional.empty()} — 404 와 403 을 구분하지 않는다. */
    Optional<ProductSubmission> load(long submissionId, long sellerId);

    /** 소유자 검사 없이. 호출 지점은 운영자 심사와 {@code product.registered} 회신 둘뿐이다. */
    Optional<ProductSubmission> loadAny(long submissionId);

    /** 신규면 ID 가 채워진 사본을, 수정이면 저장된 사본을 돌려준다. */
    ProductSubmission save(ProductSubmission submission);

    long countBySeller(long sellerId, SubmissionStatus status);

    List<ProductSubmission> findBySeller(long sellerId, SubmissionStatus status, int limit, long offset);

    long countPending();

    List<ProductSubmission> findPending(int limit, long offset);
}
