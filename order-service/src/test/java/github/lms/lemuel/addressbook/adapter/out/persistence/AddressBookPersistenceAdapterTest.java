package github.lms.lemuel.addressbook.adapter.out.persistence;

import github.lms.lemuel.addressbook.domain.ShippingAddressEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 배송지 주소록 영속 어댑터 회귀 테스트 (Mockito, 실 DB 미접속). */
@ExtendWith(MockitoExtension.class)
@DisplayName("배송지 주소록 영속 어댑터")
class AddressBookPersistenceAdapterTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 27, 10, 0, 0);

    @Mock SpringDataShippingAddressBookRepository repository;
    @InjectMocks AddressBookPersistenceAdapter adapter;

    private static ShippingAddressBookJpaEntity entity(Long id, String label, boolean isDefault) {
        return new ShippingAddressBookJpaEntity(id, 7L, label, "홍길동", "010-1234-5678",
                "06236", "서울 강남구 테헤란로 1", "301호", "문 앞에", isDefault, T0, T0);
    }

    @Test
    @DisplayName("목록은 기본 먼저·최근 순으로 읽어 도메인으로 매핑한다")
    void loadsDefaultFirst() {
        when(repository.findByUserIdOrderByDefaultAddressDescCreatedAtDesc(7L))
                .thenReturn(List.of(entity(1L, "집", true), entity(2L, "회사", false)));

        List<ShippingAddressEntry> entries = adapter.findByUserId(7L);

        assertThat(entries).extracting(ShippingAddressEntry::id).containsExactly(1L, 2L);
        assertThat(entries.get(0).defaultAddress()).isTrue();
        assertThat(entries.get(1).defaultAddress()).isFalse();
    }

    @Test
    @DisplayName("모든 칸이 왕복해도 값이 바뀌지 않는다 — 별칭과 받는 사람 이름이 각자 남는다")
    void mapsEveryFieldBothWays() {
        when(repository.findByUserIdOrderByDefaultAddressDescCreatedAtDesc(7L))
                .thenReturn(List.of(entity(1L, "회사", true)));

        ShippingAddressEntry e = adapter.findByUserId(7L).get(0);

        assertThat(e.userId()).isEqualTo(7L);
        assertThat(e.label()).isEqualTo("회사");
        assertThat(e.recipientName()).isEqualTo("홍길동");
        assertThat(e.phone()).isEqualTo("010-1234-5678");
        assertThat(e.postalCode()).isEqualTo("06236");
        assertThat(e.address1()).isEqualTo("서울 강남구 테헤란로 1");
        assertThat(e.address2()).isEqualTo("301호");
        assertThat(e.deliveryMemo()).isEqualTo("문 앞에");
        assertThat(e.createdAt()).isEqualTo(T0);
        assertThat(e.updatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("저장은 도메인의 값을 그대로 엔티티에 옮긴다")
    void savePassesFieldsThrough() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShippingAddressEntry saved = adapter.save(new ShippingAddressEntry(null, 7L, "집", "김철수",
                "010-9999-9999", "13529", "경기 성남시", null, null, true, null, null));

        ArgumentCaptor<ShippingAddressBookJpaEntity> captor =
                ArgumentCaptor.forClass(ShippingAddressBookJpaEntity.class);
        verify(repository).save(captor.capture());
        ShippingAddressBookJpaEntity passed = captor.getValue();

        assertThat(passed.getId()).isNull();       // id 는 DB 가 발급한다
        assertThat(passed.getLabel()).isEqualTo("집");
        assertThat(passed.getRecipientName()).isEqualTo("김철수");
        assertThat(passed.isDefaultAddress()).isTrue();
        assertThat(passed.getAddress2()).isNull();
        assertThat(saved.label()).isEqualTo("집");
    }

    @Test
    @DisplayName("기본 내리기·올리기는 벌크 UPDATE 로 저장소에 그대로 위임한다")
    void defaultFlagUsesBulkUpdates() {
        // 엔티티를 더럽혀 두면 실제 UPDATE 순서를 Hibernate 가 정한다. 부르는 순서가 곧 실행
        // 순서여야 부분 유일 인덱스에 걸리지 않으므로, 어댑터는 두 문장을 그대로 넘긴다.
        adapter.clearDefault(7L);
        adapter.markDefault(2L);

        verify(repository).clearDefault(7L);
        verify(repository).markDefault(2L);
    }

    @Test
    @DisplayName("삭제는 id 로 위임한다")
    void deleteDelegates() {
        adapter.deleteById(3L);

        verify(repository).deleteById(3L);
    }

    @Test
    @DisplayName("주소록이 비면 빈 목록이다")
    void emptyBookIsEmptyList() {
        when(repository.findByUserIdOrderByDefaultAddressDescCreatedAtDesc(7L))
                .thenReturn(List.of());

        assertThat(adapter.findByUserId(7L)).isEmpty();
    }
}
