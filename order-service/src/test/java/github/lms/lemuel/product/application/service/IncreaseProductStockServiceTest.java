package github.lms.lemuel.product.application.service;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import github.lms.lemuel.product.application.port.out.SaveProductPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncreaseProductStockServiceTest {

    @Mock SaveProductPort savePort;
    @InjectMocks IncreaseProductStockService service;

    @Test @DisplayName("원복 성공: 포트에 위임하고 true 반환")
    void increase_success() {
        when(savePort.increaseStock(10L, 3)).thenReturn(1);
        assertThat(service.increase(10L, 3)).isTrue();
        verify(savePort).increaseStock(10L, 3);
    }

    @Test @DisplayName("단종·미존재로 영향 행 0 이면 예외 없이 false")
    void increase_skipped_whenNoRowAffected() {
        when(savePort.increaseStock(10L, 3)).thenReturn(0);
        assertThat(service.increase(10L, 3)).isFalse();
    }

    @Test @DisplayName("수량 0 이하는 예외 — 포트 미호출")
    void increase_rejectsNonPositive() {
        assertThatThrownBy(() -> service.increase(10L, 0))
                .isInstanceOf(ProductInvariantViolationException.class);
        verifyNoInteractions(savePort);
    }
}
