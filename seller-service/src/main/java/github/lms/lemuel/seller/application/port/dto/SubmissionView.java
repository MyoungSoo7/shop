package github.lms.lemuel.seller.application.port.dto;

import github.lms.lemuel.seller.domain.ProductContent;
import github.lms.lemuel.seller.domain.ProductSubmission;
import github.lms.lemuel.seller.domain.SubmissionStatus;
import github.lms.lemuel.seller.domain.SubmissionType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 상품 등록 신청서 한 건 — 셀러 화면과 운영자 심사 화면이 함께 쓴다.
 *
 * @param awaitingCatalog 승인은 났는데 상품번호가 아직 안 돌아온 상태. 화면은 이걸 "승인됨" 과
 *                        다르게 그려야 한다 — 같게 그리면 등록이 실패해 영영 상품이 안 생긴 건과
 *                        방금 승인돼 몇 초 뒤 생길 건이 화면에서 구분되지 않는다.
 */
public record SubmissionView(
        long submissionId,
        long sellerId,
        SubmissionType type,
        Long baseProductId,
        String name,
        String description,
        BigDecimal price,
        int stock,
        String category,
        String imageUrl,
        boolean displayVisible,
        SubmissionStatus status,
        String rejectReason,
        Long productId,
        boolean awaitingCatalog,
        long createdByUserId,
        Long decidedByUserId,
        OffsetDateTime submittedAt,
        OffsetDateTime decidedAt) {

    public static SubmissionView of(ProductSubmission submission) {
        ProductContent content = submission.content();
        return new SubmissionView(
                submission.requireSubmissionId(),
                submission.sellerId(),
                submission.type(),
                submission.baseProductId(),
                content.name(),
                content.description(),
                content.price(),
                content.stock(),
                content.category(),
                content.imageUrl(),
                content.displayVisible(),
                submission.status(),
                submission.rejectReason(),
                submission.productId(),
                submission.awaitingCatalog(),
                submission.createdByUserId(),
                submission.decidedByUserId(),
                submission.submittedAt(),
                submission.decidedAt());
    }
}
