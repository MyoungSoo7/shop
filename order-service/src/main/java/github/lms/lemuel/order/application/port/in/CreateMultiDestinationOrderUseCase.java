package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.ShippingAddressSnapshot;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import java.util.List;

/**
 * 여러 곳 배송 — 한 번의 결제로 서로 다른 주소에 나눠 보낸다.
 *
 * <p><b>주문 하나를 N 곳으로 쪼개지 않는다.</b> 배송지마다 주문을 하나씩 만들고, 그 주문들을
 * 묶음 id 로 묶는다. 배송(shipment)이 주문과 1:1 이라는 기존 불변식을 그대로 두기 위해서다 —
 * 그 자리를 1:N 으로 바꾸면 운송장 일괄 등록·배송 지연 스캐너·반품 회수처럼 "주문의 배송"
 * 이라는 전제 위에 서 있는 코드가 전부 흔들린다.
 *
 * <p>금액은 배송지마다 <b>그 배송지에 담긴 라인에서</b> 나온다. 원본(ssg-front)의 같은 기능은
 * 장바구니 총액을 배송지 수만큼 곱해서 더했고 — 상품값도 배송비도 — 정작 같은 경로의 재고
 * 차감은 장바구니 수량 그대로였다. 돈은 N 배인데 빠지는 재고는 1 배였다는 뜻이다. 여기서는
 * 배송지가 자기 라인을 들고 있으므로 금액도 재고도 그 라인 하나에서만 나온다.
 *
 * <p>전부 한 트랜잭션이다. 세 곳 중 한 곳이 품절이면 세 주문이 다 없던 일이 된다. 성공한
 * 만큼만 남기면 사용자는 자기가 무엇을 샀는지 결제 화면을 떠난 뒤에야 알게 된다.
 */
public interface CreateMultiDestinationOrderUseCase {

    /** 한 번에 보낼 수 있는 배송지 수 상한. */
    int MAX_DESTINATIONS = 20;

    Result create(Command command);

    /**
     * @param consent        주문 시점 동의. 배송지마다 주문이 하나씩 생기므로 <b>주문 수만큼</b>
     *                       기록된다 — 동의는 주문에 붙는 근거라 묶음에 한 번만 남기면 개별 주문의
     *                       근거가 비게 된다
     * @param idempotencyKey 없으면 멱등 보호 없이 그대로 만든다(하위 호환). 있으면 재요청이
     *                       <b>같은 묶음 전체</b>를 돌려준다
     */
    record Command(Long userId,
                   List<Destination> destinations,
                   CreateMultiItemOrderUseCase.ConsentSubmission consent,
                   String idempotencyKey) {
        public Command {
            if (userId == null) {
                throw new OrderInvariantViolationException("userId 필수");
            }
            if (destinations == null || destinations.size() < 2) {
                // 한 곳뿐이면 이 경로로 올 이유가 없다. 통과시키면 형제 없는 주문 한 건이 묶음
                // 표시를 달게 되고, 화면은 "여러 곳" 이라 말하면서 한 곳을 보여 준다.
                throw new OrderInvariantViolationException("여러 곳 배송은 배송지가 둘 이상이어야 합니다");
            }
            if (destinations.size() > MAX_DESTINATIONS) {
                // 상한이 있는 이유는 화면이 아니라 트랜잭션이다. 배송지 하나가 곧 주문 하나이고
                // 전부 한 트랜잭션이라, 배송지 수만큼의 상품 행이 커밋까지 잠긴 채로 있는다.
                throw new OrderInvariantViolationException(
                        "배송지는 한 번에 " + MAX_DESTINATIONS + " 곳까지입니다");
            }
            destinations = List.copyOf(destinations);
        }
    }

    /**
     * 배송지 한 곳과 그곳으로 갈 라인들.
     *
     * <p>같은 상품을 두 배송지에 보내는 것은 각 배송지가 그 상품 라인을 따로 들고 있는 것으로
     * 표현된다 — 수량은 각자의 것이고, 재고도 각자만큼 빠진다.
     */
    record Destination(ShippingAddressSnapshot shippingAddress,
                       List<CreateMultiItemOrderUseCase.Line> lines) {
        public Destination {
            if (shippingAddress == null) {
                throw new OrderInvariantViolationException("배송지 필수");
            }
            if (lines == null || lines.isEmpty()) {
                // 라인 없는 배송지는 "아무것도 안 보낼 주소"다. 통과시키면 금액 0 원짜리 주문이
                // 하나 생기고, 그 주문에도 배송이 붙어 운영자의 배송 큐에 뜬다.
                throw new OrderInvariantViolationException("배송지마다 상품이 하나 이상 있어야 합니다");
            }
            lines = List.copyOf(lines);
        }
    }

    /**
     * @param destinationGroupId 만들어진 주문들이 공유하는 묶음 id
     * @param orders             배송지 순서대로의 주문들. 요청의 배송지 순서와 같다 —
     *                           화면이 "두 번째 주소" 로 부르는 것이 두 번째 주문이어야 한다
     */
    record Result(String destinationGroupId, List<Order> orders) {
    }
}
