package github.lms.lemuel.addressbook.application.port.out;

import github.lms.lemuel.addressbook.domain.ShippingAddressEntry;

import java.util.List;

public interface LoadAddressBookPort {

    /**
     * 한 사용자의 배송지 전부 — 기본 먼저, 그 다음 최근 등록 순.
     *
     * <p>단건 조회({@code findById})는 <b>일부러 두지 않았다.</b> 주소록은 한 사람 것이 최대 서른
     * 줄이라 통째로 읽는 비용이 낮고, 그렇게 읽으면 "남의 줄을 id 로 집어 오는" 경로가 구조적으로
     * 사라진다. 소유자 대조를 잊을 수 있는 자리를 없애는 편이 잊지 않도록 주의하는 것보다 낫다.
     */
    List<ShippingAddressEntry> findByUserId(Long userId);
}
