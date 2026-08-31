package github.lms.lemuel.seller.application.service;

import github.lms.lemuel.seller.application.port.dto.SubmissionView;
import github.lms.lemuel.seller.application.port.in.ReviewProductSubmissionUseCase;
import github.lms.lemuel.seller.application.port.out.ProductSubmissionPort;
import github.lms.lemuel.seller.application.port.out.PublishSellerEventPort;
import github.lms.lemuel.seller.domain.ProductSubmission;
import github.lms.lemuel.seller.domain.exception.SubmissionNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * 운영자 심사 — 승인·반려.
 *
 * <p>승인은 <b>상태 저장과 이벤트 기록이 같은 트랜잭션</b>이어야 한다. 나뉘면 둘 중 하나만 남는
 * 창이 생기고, 그 창의 두 결과가 정반대로 나쁘다. 상태만 남으면 승인된 신청서에 상품이 영영 안
 * 생기고, 이벤트만 남으면 심사되지 않은 상품이 몰에 걸린다. Transactional Outbox 는 이 문제를
 * 해결하려고 있는 것이고, 여기가 그 패턴이 실제로 필요한 지점이다.
 *
 * <p>반려는 발행하지 않는다. 바깥에서 할 일이 없기 때문이다 — 셀러가 콘솔에서 사유를 보고 고쳐
 * 다시 낸다. 알림이 필요해지면 그때 토픽을 하나 더 만들지, 지금 빈 이벤트를 미리 깔아 두지
 * 않는다(아무도 구독하지 않는 토픽은 계약처럼 보이지만 계약이 아니다).
 */
@Service
@Transactional(readOnly = true)
public class SubmissionReviewService implements ReviewProductSubmissionUseCase {

    private final ProductSubmissionPort submissionPort;
    private final PublishSellerEventPort publishPort;
    private final Clock clock;

    public SubmissionReviewService(ProductSubmissionPort submissionPort,
                                   PublishSellerEventPort publishPort,
                                   Clock clock) {
        this.submissionPort = submissionPort;
        this.publishPort = publishPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SubmissionView approve(long submissionId, long operatorUserId) {
        ProductSubmission approved = load(submissionId).approve(operatorUserId, OffsetDateTime.now(clock));
        ProductSubmission saved = submissionPort.save(approved);
        publishPort.productApproved(saved);
        return SubmissionView.of(saved);
    }

    @Override
    @Transactional
    public SubmissionView reject(long submissionId, long operatorUserId, String reason) {
        ProductSubmission rejected = load(submissionId).reject(operatorUserId, reason, OffsetDateTime.now(clock));
        return SubmissionView.of(submissionPort.save(rejected));
    }

    private ProductSubmission load(long submissionId) {
        // 여기는 loadAny 다 — 운영자는 셀러 스코프가 없다. 그래서 이 두 메서드의 유일한 방어는
        // 웹 계층의 ROLE_ADMIN 이고, 그 사실을 잊지 않도록 이 주석을 남긴다.
        return submissionPort.loadAny(submissionId)
                .orElseThrow(() -> new SubmissionNotFoundException(submissionId));
    }
}
