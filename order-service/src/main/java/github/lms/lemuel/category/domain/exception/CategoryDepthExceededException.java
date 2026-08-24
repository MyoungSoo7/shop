package github.lms.lemuel.category.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

public class CategoryDepthExceededException extends BusinessException {
    public CategoryDepthExceededException(int attemptedDepth, int maxDepth) {
        super(ErrorCode.CATEGORY_DEPTH_EXCEEDED,
                String.format("Category depth %d exceeds maximum allowed depth of %d", attemptedDepth, maxDepth));
    }

    public CategoryDepthExceededException(String message) {
        super(ErrorCode.CATEGORY_DEPTH_EXCEEDED, message);
    }
}
