package github.lms.lemuel.seller.application.port.in;

import github.lms.lemuel.seller.application.port.dto.SubmissionPage;
import github.lms.lemuel.seller.application.port.dto.SubmissionQuery;
import github.lms.lemuel.seller.application.port.dto.SubmissionView;
import github.lms.lemuel.seller.domain.SellerScope;

import java.util.Optional;

/** 신청서 조회 — 셀러가 보는 자기 목록과, 운영자가 보는 심사 대기열. */
public interface ViewProductSubmissionUseCase {

    /** 내 신청서 목록. 조회 대상 셀러는 {@link SellerScope} 가 정한다. */
    SubmissionPage mine(SellerScope scope, SubmissionQuery query);

    /**
     * 내 신청서 한 건.
     *
     * <p>"불러온 뒤 소유자를 검사" 가 아니라 <b>처음부터 내 셀러로 필터</b>한다. 남의 신청번호를
     * 넣으면 존재 자체가 드러나지 않고 그냥 비어서 돌아온다 — 검사를 빠뜨릴 자리가 없다.
     */
    Optional<SubmissionView> mine(SellerScope scope, long submissionId);

    /**
     * 운영자 심사 대기열 — 제출된 순서대로.
     *
     * <p>여기에는 {@link SellerScope} 가 없다. 운영자는 특정 셀러에 속하지 않기 때문이다.
     * 대신 이 경로는 <b>{@code ROLE_ADMIN} 이 아니면 시큐리티 설정에서 막힌다</b>
     * ({@code /api/seller/admin/**}). 인가 근거가 스코프가 아니라 권한이라는 점이 다르므로,
     * 이 메서드를 셀러용 컨트롤러에서 부르지 않도록 주의할 것.
     */
    SubmissionPage pending(SubmissionQuery query);
}
