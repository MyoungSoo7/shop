package github.lms.lemuel.product.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

public class DuplicateProductNameException extends BusinessException {

    public DuplicateProductNameException(String productName) {
        super(ErrorCode.DUPLICATE_PRODUCT_NAME, "Product already exists with name: " + productName);
    }
}
