package github.lms.lemuel.addressbook.application.port.out;

import github.lms.lemuel.addressbook.domain.ShippingAddressEntry;

public interface SaveAddressBookPort {

    /** 등록·수정. id 가 없으면 DB 가 발급한다 — 애플리케이션이 {@code MAX(id)+1} 을 계산하지 않는다. */
    ShippingAddressEntry save(ShippingAddressEntry entry);

    void deleteById(Long entryId);

    /**
     * 한 사용자의 기본 배송지를 전부 내린다.
     *
     * <p>{@link #markDefault} 와 짝으로만 쓰며, <b>반드시 이것을 먼저</b> 부른다. 부분 유일 인덱스가
     * 회원당 기본 한 줄만 허용하므로 순서가 뒤집히면 DB 가 그 자리에서 거부한다 — 조용히 통과하지
     * 않는다. 두 문장이 한 트랜잭션 안에 있어야 하는 이유는 <b>사이에서 실패하면 기본이 0개</b>가
     * 되기 때문이고, 그 상태는 제약으로 잡히지 않는다.
     */
    void clearDefault(Long userId);

    /** 한 줄을 기본으로 올린다. {@link #clearDefault} 가 먼저 반영된 뒤에 부른다. */
    void markDefault(Long entryId);
}
