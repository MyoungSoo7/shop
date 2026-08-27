package github.lms.lemuel.addressbook.adapter.out.persistence;

import github.lms.lemuel.addressbook.application.port.out.LoadAddressBookPort;
import github.lms.lemuel.addressbook.application.port.out.SaveAddressBookPort;
import github.lms.lemuel.addressbook.domain.ShippingAddressEntry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 배송지 주소록 영속성 어댑터 (JPA/PostgreSQL).
 *
 * <p>기본 배송지를 옮기는 두 문장의 <b>순서</b>가 이 클래스의 유일한 어려움이다. 부분 유일 인덱스는
 * 문장 단위로 즉시 검사되므로 "올리기"가 "내리기"보다 먼저 나가면 그 자리에서 거부된다. 엔티티를
 * 더럽혀 두고 커밋에 맡기면 그 순서를 Hibernate 가 정하기 때문에, 두 동작 모두 벌크 UPDATE 로
 * 두어 부르는 순서가 곧 실행 순서가 되게 했다.
 *
 * <p>삭제 뒤 승격도 같은 문제였다. 지워질 기본 행이 아직 DB 에 남아 있는 상태에서 다른 행을 올리면
 * 역시 인덱스에 걸린다. {@code markDefault} 에 걸어 둔 {@code flushAutomatically} 가 밀린 DELETE 를
 * 먼저 내보내므로 순서가 성립한다.
 */
@Component
public class AddressBookPersistenceAdapter implements LoadAddressBookPort, SaveAddressBookPort {

    private final SpringDataShippingAddressBookRepository repository;

    public AddressBookPersistenceAdapter(SpringDataShippingAddressBookRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ShippingAddressEntry> findByUserId(Long userId) {
        return repository.findByUserIdOrderByDefaultAddressDescCreatedAtDesc(userId).stream()
                .map(AddressBookPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public ShippingAddressEntry save(ShippingAddressEntry entry) {
        return toDomain(repository.save(toEntity(entry)));
    }

    @Override
    @Transactional
    public void deleteById(Long entryId) {
        repository.deleteById(entryId);
    }

    @Override
    @Transactional
    public void clearDefault(Long userId) {
        repository.clearDefault(userId);
    }

    @Override
    @Transactional
    public void markDefault(Long entryId) {
        repository.markDefault(entryId);
    }

    private static ShippingAddressBookJpaEntity toEntity(ShippingAddressEntry e) {
        return new ShippingAddressBookJpaEntity(e.id(), e.userId(), e.label(), e.recipientName(),
                e.phone(), e.postalCode(), e.address1(), e.address2(), e.deliveryMemo(),
                e.defaultAddress(), e.createdAt(), e.updatedAt());
    }

    private static ShippingAddressEntry toDomain(ShippingAddressBookJpaEntity e) {
        return new ShippingAddressEntry(e.getId(), e.getUserId(), e.getLabel(), e.getRecipientName(),
                e.getPhone(), e.getPostalCode(), e.getAddress1(), e.getAddress2(),
                e.getDeliveryMemo(), e.isDefaultAddress(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
