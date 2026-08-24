package github.lms.lemuel.order.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class OrderExceptionTest {

    @Test @DisplayName("OrderNotFoundException: 문자열 생성자")
    void orderNotFoundException_stringConstructor() {
        var ex = new OrderNotFoundException("주문을 찾을 수 없습니다");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("주문을 찾을 수 없습니다");
    }

    @Test @DisplayName("OrderNotFoundException: Long 생성자")
    void orderNotFoundException_longConstructor() {
        var ex = new OrderNotFoundException(123L);
        assertThat(ex.getMessage()).isEqualTo("Order not found with id: 123");
    }

    @Test @DisplayName("UserNotExistsException: 사용자 ID가 메시지에 포함된다")
    void userNotExistsException_message() {
        var ex = new UserNotExistsException(7L);
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("존재하지 않는 사용자입니다: 7");
    }

    @Test @DisplayName("UserNotExistsException: cause가 올바르게 전달된다")
    void userNotExistsException_withCause() {
        var cause = new IllegalStateException("원본 예외");
        var ex = new UserNotExistsException(10L, cause);
        assertThat(ex.getMessage()).isEqualTo("존재하지 않는 사용자입니다: 10");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
