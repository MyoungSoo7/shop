package github.lms.lemuel.review.adapter.in.web.dto;

import github.lms.lemuel.review.domain.Review;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ReviewResponse {

    private final Long id;
    private final Long productId;
    private final Long userId;
    private final int rating;
    private final String content;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public ReviewResponse(Review domain) {
        this.id        = domain.getId();
        this.productId = domain.getProductId();
        this.userId    = domain.getUserId();
        this.rating    = domain.getRating();
        this.content   = domain.getContent();
        this.createdAt = domain.getCreatedAt();
        this.updatedAt = domain.getUpdatedAt();
    }
}