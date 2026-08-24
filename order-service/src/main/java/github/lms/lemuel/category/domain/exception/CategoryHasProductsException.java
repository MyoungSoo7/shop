package github.lms.lemuel.category.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

public class CategoryHasProductsException extends BusinessException {
    public CategoryHasProductsException(Long categoryId) {
        super(ErrorCode.CATEGORY_HAS_PRODUCTS,
                String.format("Cannot delete category %d: has associated products", categoryId));
    }
}
