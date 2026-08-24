package github.lms.lemuel.payment.adapter.out.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 결제 승인 멱등 매핑 어댑터 단위 테스트.
 *
 * <p>이 어댑터가 지키는 계약은 두 줄이다 — <b>있는 키는 결제 ID 로 풀어 주고</b>, <b>쓰기는 반드시
 * INSERT 여야 한다</b>. 두 번째가 핵심이다: {@code JpaRepository.save()} 는 @Id 가 이미 있으면
 * merge(UPDATE)로 동작해 중복 키에서 제약 위반이 <b>나지 않는다</b>. 그러면 동시 중복 승인이
 * 조용히 덮어쓰기로 끝나고 최종 1건 보장이 무너진다. 그래서 여기서 네이티브 insert 위임을 못박는다.
 *
 * <p>엔티티는 쓰기 API 가 없는(생성은 DB DEFAULT + 네이티브 INSERT) 읽기 전용 매핑이라,
 * 픽스처는 리플렉션으로 세운다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentIdempotencyPersistenceAdapterTest {

    @Mock SpringDataPaymentIdempotencyRepository repository;

    private PaymentIdempotencyPersistenceAdapter adapter() {
        return new PaymentIdempotencyPersistenceAdapter(repository);
    }

    private static PaymentIdempotencyJpaEntity entity(String key, Long paymentId, LocalDateTime createdAt) {
        PaymentIdempotencyJpaEntity e = new PaymentIdempotencyJpaEntity();
        set(e, "idempotencyKey", key);
        set(e, "paymentId", paymentId);
        set(e, "createdAt", createdAt);
        return e;
    }

    private static void set(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("픽스처 구성 실패: " + fieldName, e);
        }
    }

    @Test
    @DisplayName("findPaymentId: 이미 처리된 키면 그때 만든 결제 ID 를 돌려준다")
    void findPaymentId_found() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 22, 11, 0);
        PaymentIdempotencyJpaEntity stored = entity("idem-1", 55L, createdAt);
        when(repository.findById("idem-1")).thenReturn(Optional.of(stored));

        assertThat(adapter().findPaymentId("idem-1")).contains(55L);

        // 엔티티가 어댑터에 넘겨주는 값들 — 매핑이 이 세 필드에 의존한다.
        assertThat(stored.getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(stored.getPaymentId()).isEqualTo(55L);
        assertThat(stored.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("findPaymentId: 처음 보는 키면 empty — 호출자는 이때만 실제 승인으로 넘어간다")
    void findPaymentId_notFound() {
        when(repository.findById("new-key")).thenReturn(Optional.empty());

        assertThat(adapter().findPaymentId("new-key")).isEmpty();
    }

    @Test
    @DisplayName("save: save() 가 아니라 네이티브 INSERT 에 위임한다 — merge 로 새면 중복이 제약을 안 건드린다")
    void save_delegatesToNativeInsert() {
        adapter().save("idem-1", 55L);

        verify(repository).insert("idem-1", 55L);
        verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }
}
