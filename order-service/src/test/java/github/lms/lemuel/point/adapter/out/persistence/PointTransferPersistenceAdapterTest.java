package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointTransfer;
import github.lms.lemuel.point.domain.exception.PointInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 포인트 선물 영속 어댑터 회귀 테스트 (Mockito, 실 DB 미접속). */
@ExtendWith(MockitoExtension.class)
@DisplayName("포인트 선물 영속 어댑터")
class PointTransferPersistenceAdapterTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-08-28T10:00:00+09:00");

    @Mock PointTransferRepository repository;
    @InjectMocks PointTransferPersistenceAdapter adapter;

    private static PointTransfer newTransfer() {
        return PointTransfer.create("PT20260828-00000001", "req-1", 10L, 20L,
                new BigDecimal("1000"), "고마워", T0);
    }

    @Test
    @DisplayName("저장하면 생성된 식별자를 도메인에 붙여 준다")
    void assignsGeneratedId() {
        when(repository.save(any())).thenAnswer(invocation -> {
            PointTransferJpaEntity entity = invocation.getArgument(0);
            // 저장 후 식별자가 채워진 엔티티를 흉내 낸다.
            return PointTransferJpaEntity.from(PointTransfer.rehydrate(77L,
                    "PT20260828-00000001", "req-1", 10L, 20L,
                    new BigDecimal("1000"), "고마워", T0));
        });

        PointTransfer saved = adapter.save(newTransfer());

        assertThat(saved.getId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("이미 저장된 선물은 다시 저장하지 않는다 — 기록은 만들어진 뒤 바뀌지 않는다")
    void refusesResave() {
        PointTransfer persisted = PointTransfer.rehydrate(5L, "PT20260828-00000001", "req-1",
                10L, 20L, new BigDecimal("1000"), null, T0);

        assertThatThrownBy(() -> adapter.save(persisted))
                .isInstanceOf(PointInvariantViolationException.class);
    }

    @Test
    @DisplayName("선물 번호는 시퀀스 값에 날짜를 붙여 만든다 — MAX+1 채번을 쓰지 않는다")
    void formatsTransferNoFromSequence() {
        when(repository.nextTransferSequence()).thenReturn(42L);

        String no = adapter.nextTransferNo();

        assertThat(no).matches("PT\\d{8}-\\d{8}");
        assertThat(no).endsWith("-00000042");
    }

    @Test
    @DisplayName("멱등 조회는 보내는 이와 요청 식별자를 함께 묻는다")
    void looksUpBySenderAndRequestId() {
        when(repository.findBySenderUserIdAndRequestId(10L, "req-1"))
                .thenReturn(Optional.of(PointTransferJpaEntity.from(
                        PointTransfer.rehydrate(5L, "PT20260828-00000001", "req-1", 10L, 20L,
                                new BigDecimal("1000"), null, T0))));

        assertThat(adapter.findBySenderAndRequestId(10L, "req-1"))
                .get()
                .extracting(PointTransfer::getTransferNo)
                .isEqualTo("PT20260828-00000001");
    }

    @Test
    @DisplayName("빈 요청 식별자로는 조회하지 않는다")
    void skipsBlankLookup() {
        assertThat(adapter.findBySenderAndRequestId(10L, "  ")).isEmpty();
        assertThat(adapter.findBySenderAndRequestId(null, "req-1")).isEmpty();
    }

    @Test
    @DisplayName("이력 조회는 요청한 건수만큼만 첫 페이지를 읽는다")
    void readsFirstPage() {
        when(repository.findByParticipant(org.mockito.ArgumentMatchers.eq(10L), any(Pageable.class)))
                .thenReturn(List.of());

        adapter.findByParticipant(10L, 20);

        verify(repository).findByParticipant(10L, PageRequest.of(0, 20));
    }
}
