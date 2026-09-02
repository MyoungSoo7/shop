package github.lms.lemuel.order.domain;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 주문 상태가 한 번 바뀐 사실.
 *
 * <p>{@code order_status_history} 는 처음부터 이 여섯 칸을 성실히 적어 왔다. 없던 것은 데이터가 아니라
 * <b>읽는 길</b>이었다 — 조회 포트가 "직전 상태 하나" 만 돌려주고 목록을 돌려주지 않았고, 이력을
 * 보여주는 엔드포인트가 하나도 없었다. 그래서 CS 는 "이 주문 왜 이 상태냐" 를 DB 를 직접 열어야만
 * 답할 수 있었다.
 *
 * <p><b>상태를 문자열 그대로 들고 있는다.</b> {@link OrderStatus} 로 바꿔서 들고 있으면 두 가지 중
 * 하나가 된다 — 모르는 값에 {@link OrderStatus#fromString} 이 던져서 <i>이력 화면 전체가 500</i> 이
 * 되거나, {@link OrderStatus#fromStringOrNull} 이 {@code null} 을 줘서 <i>CS 가 찾던 그 한 줄만
 * 조용히 빈칸</i>이 된다. 둘 다 이 화면의 존재 이유를 깬다. 이력은 "지금 enum 이 아는 값" 이 아니라
 * <b>그때 적힌 값</b>을 보여주는 물건이고, enum 이 필요한 호출자는 {@link #newStatusAsEnum()} 으로
 * 직접 물어보면 된다. 실제로 {@code REFUND_COMPLETED} 처럼 신규 전이가 끊긴 값이 이미 DB 에 있다.
 *
 * @param previousStatus 최초 생성 기록에는 없다({@code null})
 * @param changedBy      사람이면 계정, 배치면 배치 이름이 들어온다
 * @param reason         취소·반품 사유 등. 없을 수 있다
 */
public record OrderStatusChange(Long id,
                                Long orderId,
                                String previousStatus,
                                String newStatus,
                                String changedBy,
                                String reason,
                                LocalDateTime changedAt) {

    /** 지금의 {@link OrderStatus} 로 읽히면 그 값. 안 읽히면 비어 있음 — 던지지 않는다. */
    public Optional<OrderStatus> newStatusAsEnum() {
        return Optional.ofNullable(OrderStatus.fromStringOrNull(newStatus));
    }

    /** 직전 상태를 지금의 {@link OrderStatus} 로. 최초 기록이거나 안 읽히면 비어 있음. */
    public Optional<OrderStatus> previousStatusAsEnum() {
        return Optional.ofNullable(OrderStatus.fromStringOrNull(previousStatus));
    }
}
