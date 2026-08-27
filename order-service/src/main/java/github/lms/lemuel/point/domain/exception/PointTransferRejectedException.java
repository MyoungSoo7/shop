package github.lms.lemuel.point.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 회원 간 포인트 선물 거절 (BusinessException 상속 — ErrorCode 가 HTTP 상태로 번역한다).
 *
 * <p>거절 사유가 셋인데 클래스는 하나다. 사유마다 ErrorCode 가 다르고, 그중 하나
 * ({@link #recipientUnknown()})는 <b>일부러 뭉갠</b> 답이기 때문이다 — "그런 이메일이 없다"와
 * "이름이 다르다"를 갈라 주면, 이름을 아무 값으로나 넣어 응답만 보고 그 이메일로 가입한
 * 회원이 있는지 확인할 수 있다. 기프트카드 코드 등록 거절({@code GIFT_CARD_INVALID_STATE})이
 * 사유를 구분하지 않는 것과 같은 이유다.
 *
 * <p>반대로 자기 자신에게 보내기({@link #self()})는 숨길 것이 없다 — 보내는 이는 자기
 * 이메일을 알고 있으므로 정확한 사유를 말해 주는 편이 낫다.
 */
public class PointTransferRejectedException extends BusinessException {

    private PointTransferRejectedException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /** 이메일·이름으로 받는 이를 특정하지 못했다. 사유를 구분하지 않는다. */
    public static PointTransferRejectedException recipientUnknown() {
        return new PointTransferRejectedException(ErrorCode.POINT_TRANSFER_RECIPIENT_UNKNOWN,
                "받는 분을 확인할 수 없습니다. 이메일과 이름을 다시 확인해 주세요.");
    }

    /** 보내는 이와 받는 이가 같다. */
    public static PointTransferRejectedException self() {
        return new PointTransferRejectedException(ErrorCode.POINT_TRANSFER_SELF,
                "자기 자신에게는 포인트를 선물할 수 없습니다.");
    }

    /** 요청 자체가 형식을 어겼다(빈 식별자, 메시지 길이 초과 등). */
    public static PointTransferRejectedException malformed(String message) {
        return new PointTransferRejectedException(ErrorCode.POINT_INVALID_STATE, message);
    }
}
