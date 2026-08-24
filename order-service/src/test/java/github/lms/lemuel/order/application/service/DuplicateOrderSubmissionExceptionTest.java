package github.lms.lemuel.order.application.service;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateOrderSubmissionExceptionTest {

    @Test @DisplayName("멱등 키를 메시지에 담고 DUPLICATE_ORDER_SUBMISSION 코드를 보존")
    void carriesKeyAndErrorCode() {
        DuplicateOrderSubmissionException ex = new DuplicateOrderSubmissionException("idem-key-123");

        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_ORDER_SUBMISSION);
        assertThat(ex.getMessage()).contains("idem-key-123");
    }
}
