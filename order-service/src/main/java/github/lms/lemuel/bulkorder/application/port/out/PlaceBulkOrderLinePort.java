package github.lms.lemuel.bulkorder.application.port.out;

/**
 * 검증을 통과한 행 1줄을 실주문으로 바꾸는 아웃바운드 포트.
 *
 * <p>대량주문 컨텍스트는 "무엇을 검증했는가"만 알고, 주문 생성·재고 차감·배송지 등록이 어떻게
 * 일어나는지는 모른다. 그 지식은 order/shipping 이 이미 갖고 있고, 여기서 다시 구현하면
 * 같은 규칙(재고 조건부 차감·금액 권위)이 두 벌이 되어 어긋난다.
 *
 * @param productId 상품
 * @param quantity  수량
 * @param recipientName 수령인
 * @param phone     수령인 연락처
 * @param postalCode 우편번호
 * @param address1  기본 주소
 * @param address2  상세 주소(선택)
 * @param memo      배송 메모(선택)
 */
public interface PlaceBulkOrderLinePort {

    /** @return 생성된 주문 id */
    Long place(Long buyerUserId, Line line);

    record Line(Long productId, int quantity, String recipientName, String phone,
                String postalCode, String address1, String address2, String memo) { }
}
