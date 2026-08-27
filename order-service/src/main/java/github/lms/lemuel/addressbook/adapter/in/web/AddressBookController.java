package github.lms.lemuel.addressbook.adapter.in.web;

import github.lms.lemuel.addressbook.application.port.in.AddressBookUseCase;
import github.lms.lemuel.addressbook.domain.AddressBook;
import github.lms.lemuel.addressbook.domain.ShippingAddressEntry;
import github.lms.lemuel.web.security.ResourceOwnership;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 배송지 주소록 — {@code /users/{userId}/shipping-addresses}.
 *
 * <p><b>소유권은 경로가 아니라 토큰이 정한다.</b> 모든 메서드가 첫 줄에서
 * {@link ResourceOwnership#requireSelfOrAdmin(Long)} 를 호출한다. 그리고 그 뒤로 배송지 한 줄을
 * 집을 때도 id 로 곧장 찾지 않는다 — 서비스가 <b>이 사용자의 주소록 전체</b>를 읽고 그 안에서
 * 찾으므로, 남의 id 를 넣으면 "주소록에 없는 배송지"가 된다. 대조를 잊을 수 있는 자리를 만들지
 * 않는 편이 잊지 않도록 주의하는 것보다 낫다.
 *
 * <p>기본 배송지 지정이 {@code PUT .../default} 한 번인 것도 의도된 것이다. 레거시는 "전부 내리기"와
 * "하나 올리기"가 서로 다른 요청이라, 화면이 두 번 부르는 사이에 끊기면 기본 배송지가 하나도 없는
 * 상태로 남았다. 그 상태에서 기본 배송지 조회는 아무것도 돌려주지 않는다.
 */
@Tag(name = "ShippingAddressBook", description = "배송지 주소록")
@RestController
@RequestMapping("/users/{userId}/shipping-addresses")
public class AddressBookController {

    private final AddressBookUseCase addressBookUseCase;

    public AddressBookController(AddressBookUseCase addressBookUseCase) {
        this.addressBookUseCase = addressBookUseCase;
    }

    @Operation(summary = "주소록 조회",
            description = "기본 배송지가 맨 위. 주소록이 비어 있지 않으면 기본 배송지는 반드시 하나 있다.")
    @GetMapping
    public ResponseEntity<AddressBookResponse> list(@PathVariable Long userId) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        return ResponseEntity.ok(AddressBookResponse.from(addressBookUseCase.list(userId)));
    }

    @Operation(summary = "기본 배송지 단건 조회",
            description = "주문서 배송지 칸을 미리 채우는 용도. 주소록이 비어 있으면 204 다.")
    @GetMapping("/default")
    public ResponseEntity<AddressResponse> findDefault(@PathVariable Long userId) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        return addressBookUseCase.findDefault(userId)
                .map(entry -> ResponseEntity.ok(AddressResponse.from(entry)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "배송지 등록",
            description = "별칭(label)과 받는 분(recipientName)은 서로 다른 칸이며 둘 다 필수다. "
                    + "첫 배송지는 makeDefault 를 보내지 않아도 기본이 된다.")
    @PostMapping
    public ResponseEntity<AddressResponse> register(@PathVariable Long userId,
                                                    @RequestBody AddressRequest request) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        ShippingAddressEntry saved = addressBookUseCase.register(userId, request.toForm());
        return ResponseEntity.ok(AddressResponse.from(saved));
    }

    @Operation(summary = "배송지 수정",
            description = "makeDefault 가 true 면 기본 지정까지 함께 한다. false 라고 해서 기본을 내리지는 않는다 "
                    + "— 내리기만 하는 동작은 기본을 0개로 만들 수 있어 제공하지 않는다.")
    @PutMapping("/{addressId}")
    public ResponseEntity<AddressResponse> modify(@PathVariable Long userId,
                                                  @PathVariable Long addressId,
                                                  @RequestBody AddressRequest request) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        ShippingAddressEntry saved = addressBookUseCase.modify(userId, addressId, request.toForm());
        return ResponseEntity.ok(AddressResponse.from(saved));
    }

    @Operation(summary = "기본 배송지 지정",
            description = "내리기와 올리기가 한 트랜잭션 안에서 끝난다. 응답은 갱신된 주소록 전체다 — "
                    + "어느 줄이 기본인지는 목록 안에서만 뜻이 있다.")
    @PutMapping("/{addressId}/default")
    public ResponseEntity<AddressBookResponse> setDefault(@PathVariable Long userId,
                                                          @PathVariable Long addressId) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        return ResponseEntity.ok(AddressBookResponse.from(
                addressBookUseCase.setDefault(userId, addressId)));
    }

    @Operation(summary = "배송지 삭제",
            description = "지운 것이 기본이었고 남은 배송지가 있으면 그중 하나가 기본으로 승격한다.")
    @DeleteMapping("/{addressId}")
    public ResponseEntity<AddressBookResponse> remove(@PathVariable Long userId,
                                                      @PathVariable Long addressId) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        return ResponseEntity.ok(AddressBookResponse.from(
                addressBookUseCase.remove(userId, addressId)));
    }

    /**
     * 등록·수정 요청 본문.
     *
     * @param makeDefault 저장과 동시에 기본으로 지정할지. 주소록이 비어 있으면 이 값과 무관하게 기본이 된다
     */
    public record AddressRequest(String label,
                                 String recipientName,
                                 String phone,
                                 String postalCode,
                                 String address1,
                                 String address2,
                                 String deliveryMemo,
                                 boolean makeDefault) {

        AddressBookUseCase.AddressForm toForm() {
            return new AddressBookUseCase.AddressForm(label, recipientName, phone, postalCode,
                    address1, address2, deliveryMemo, makeDefault);
        }
    }

    /**
     * 주소록 응답.
     *
     * @param maxAddresses 보관 상한. 한도 근처에서 미리 알릴 수 있도록 함께 준다
     */
    public record AddressBookResponse(List<AddressResponse> addresses,
                                      int totalCount,
                                      int maxAddresses) {
        static AddressBookResponse from(AddressBook book) {
            return new AddressBookResponse(
                    book.entries().stream().map(AddressResponse::from).toList(),
                    book.size(),
                    AddressBook.MAX_ENTRIES);
        }
    }

    /**
     * 배송지 한 줄.
     *
     * @param label     사용자가 붙인 별칭('집', '회사'). 받는 분 이름과 다른 값이다
     * @param isDefault 기본 배송지 여부
     */
    public record AddressResponse(Long id,
                                  String label,
                                  String recipientName,
                                  String phone,
                                  String postalCode,
                                  String address1,
                                  String address2,
                                  String deliveryMemo,
                                  boolean isDefault,
                                  LocalDateTime createdAt,
                                  LocalDateTime updatedAt) {
        static AddressResponse from(ShippingAddressEntry e) {
            return new AddressResponse(e.id(), e.label(), e.recipientName(), e.phone(),
                    e.postalCode(), e.address1(), e.address2(), e.deliveryMemo(),
                    e.defaultAddress(), e.createdAt(), e.updatedAt());
        }
    }
}
