package github.lms.lemuel.category.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

public class DuplicateSlugException extends BusinessException {
    public DuplicateSlugException(String slug) {
        super(ErrorCode.DUPLICATE_SLUG, "Category slug already exists: " + slug);
    }
}
