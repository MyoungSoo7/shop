package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.domain.GiftClaim;
import github.lms.lemuel.order.domain.GiftClaimStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GiftClaimPersistenceAdapter — 선물 수령 영속화")
class GiftClaimPersistenceAdapterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 27, 10, 0);

    private SpringDataGiftClaimRepository repository;
    private GiftClaimPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        repository = mock(SpringDataGiftClaimRepository.class);
        adapter = new GiftClaimPersistenceAdapter(repository);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static GiftClaim claim() {
        return GiftClaim.open(100L, 7L, "김수령", "010-1234-5678", "생일 축하해",
                "tokenhash", NOW, NOW.plusDays(14));
    }

    @Nested
    @DisplayName("매핑")
    class Mapping {

        @Test
        @DisplayName("도메인 → 엔티티 → 도메인 왕복에서 칸이 하나도 새지 않는다")
        void roundTrip() {
            GiftClaim origin = GiftClaim.restore(5L, 100L, 7L, "김수령", "010-1234-5678", "생일 축하해",
                    "tokenhash", GiftClaimStatus.VERIFIED,
                    "codehash", NOW.plusMinutes(5), 3,
                    NOW.plusDays(14), NOW, NOW.plusMinutes(1), null, NOW.plusMinutes(1));

            GiftClaim back = GiftClaimJpaEntity.fromDomain(origin).toDomain();

            assertThat(back).usingRecursiveComparison().isEqualTo(origin);
        }

        @Test
        @DisplayName("상태는 이름으로 담긴다 — 순서로 담으면 상수를 끼워 넣는 순간 옛 행의 뜻이 바뀐다")
        void statusStoredAsName() {
            GiftClaim stored = adapter.save(claim());
            assertThat(stored.getStatus()).isEqualTo(GiftClaimStatus.PENDING);

            ArgumentCaptor<GiftClaimJpaEntity> captor =
                    ArgumentCaptor.forClass(GiftClaimJpaEntity.class);
            verify(repository).save(captor.capture());
            assertThat(GiftClaimStatus.fromString("PENDING")).isEqualTo(GiftClaimStatus.PENDING);
            assertThat(captor.getValue().toDomain().getStatus()).isEqualTo(GiftClaimStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("저장")
    class Saving {

        @Test
        @DisplayName("새 레코드는 조회 없이 바로 만든다")
        void insertSkipsLookup() {
            adapter.save(claim());
            verify(repository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("식별자가 있으면 로드 후 덮어쓴다 — merge 는 매핑에서 빠진 칸을 null 로 되돌린다")
        void updateLoadsFirst() {
            GiftClaim existing = GiftClaim.restore(5L, 100L, 7L, "김수령", "010-1234-5678", null,
                    "tokenhash", GiftClaimStatus.PENDING, null, null, 0,
                    NOW.plusDays(14), NOW, null, null, NOW);
            GiftClaimJpaEntity row = GiftClaimJpaEntity.fromDomain(existing);
            when(repository.findById(5L)).thenReturn(Optional.of(row));

            existing.rotateToken("newhash", NOW.plusMinutes(1));
            GiftClaim saved = adapter.save(existing);

            verify(repository).findById(5L);
            assertThat(saved.getId()).isEqualTo(5L);
            assertThat(saved.getTokenHash()).isEqualTo("newhash");
        }
    }

    @Nested
    @DisplayName("조회")
    class Loading {

        @Test
        @DisplayName("빈 토큰 해시·null 주문번호는 저장소까지 가지 않는다")
        void nullsShortCircuit() {
            assertThat(adapter.findByTokenHash(null)).isEmpty();
            assertThat(adapter.findByTokenHash("  ")).isEmpty();
            assertThat(adapter.findByOrderId(null)).isEmpty();
            verify(repository, never()).findByTokenHash(any());
            verify(repository, never()).findByOrderId(any());
        }

        @Test
        @DisplayName("소멸 대상 조회는 상한에서 잘린다 — 설정 실수가 한 번에 테이블을 훑지 않게")
        void clampsLimit() {
            when(repository.findExpirable(any(), any())).thenReturn(List.of());

            adapter.findExpirable(NOW, 999_999);
            adapter.findExpirable(NOW, 0);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(repository, times(2)).findExpirable(any(), captor.capture());
            assertThat(captor.getAllValues()).extracting(Pageable::getPageSize)
                    .containsExactly(1000, 1);
        }
    }
}
