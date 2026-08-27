package github.lms.lemuel.addressbook.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpringDataShippingAddressBookRepository
        extends JpaRepository<ShippingAddressBookJpaEntity, Long> {

    /** 기본 배송지가 맨 위, 그 다음 최근 등록 순. {@code idx_shipping_address_book_user} 가 받는다. */
    List<ShippingAddressBookJpaEntity> findByUserIdOrderByDefaultAddressDescCreatedAtDesc(Long userId);

    /**
     * 이 사용자의 기본 배송지를 전부 내린다.
     *
     * <p>영속성 컨텍스트를 거치지 않는 <b>벌크 UPDATE</b> 다. 이렇게 쓰는 이유는 실행 순서 때문이다 —
     * 엔티티를 하나씩 더럽혀 두면 실제 UPDATE 가 나가는 순서를 Hibernate 가 정하고, 내리기보다
     * 올리기가 먼저 나가면 부분 유일 인덱스에 걸려 요청 전체가 실패한다. 여기서는 이 문장이
     * <b>먼저</b> 나가는 것이 보장돼야 한다.
     *
     * <p>{@code flushAutomatically} 는 앞선 변경을 먼저 내보내고, {@code clearAutomatically} 는
     * 벌크 UPDATE 가 건드린 행의 낡은 사본이 컨텍스트에 남지 않게 한다(남으면 이후 조회가 여전히
     * "기본"이라고 답한다).
     *
     * @return 실제로 내려간 행 수. 정상 상태라면 0 또는 1 이다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ShippingAddressBookJpaEntity e set e.defaultAddress = false, "
            + "e.updatedAt = CURRENT_TIMESTAMP "
            + "where e.userId = :userId and e.defaultAddress = true")
    int clearDefault(@Param("userId") Long userId);

    /**
     * 한 줄을 기본으로 올린다. {@link #clearDefault} 가 먼저 반영된 뒤에만 부른다.
     *
     * @return 올라간 행 수. 0 이면 그 id 가 없다는 뜻이다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ShippingAddressBookJpaEntity e set e.defaultAddress = true, "
            + "e.updatedAt = CURRENT_TIMESTAMP where e.id = :id")
    int markDefault(@Param("id") Long id);
}
