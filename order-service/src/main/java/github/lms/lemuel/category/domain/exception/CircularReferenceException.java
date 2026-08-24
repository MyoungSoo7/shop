package github.lms.lemuel.category.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

public class CircularReferenceException extends BusinessException {
    public CircularReferenceException(Long categoryId, Long parentId) {
        super(ErrorCode.CIRCULAR_REFERENCE,
                String.format("Circular reference detected: category %d cannot have parent %d (would create a cycle)", categoryId, parentId));
    }

    public CircularReferenceException(String message) {
        super(ErrorCode.CIRCULAR_REFERENCE, message);
    }
}
