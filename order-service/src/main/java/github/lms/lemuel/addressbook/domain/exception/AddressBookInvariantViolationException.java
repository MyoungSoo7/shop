package github.lms.lemuel.addressbook.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 배송지 주소록 불변식 위반 — 필수 항목 누락, 별칭 길이 초과, 보관 한도 초과, 기본 배송지 규칙 위반.
 *
 * <p>{@code IllegalArgumentException} 을 쓰지 않는 것은 OO 게이트 때문만이 아니라, "무엇이
 * 어긋났는가"를 타입으로 말해야 웹 계층이 메시지 문자열을 뜯어보지 않고 응답을 정할 수 있어서다.
 */
public class AddressBookInvariantViolationException extends BusinessException {

    public AddressBookInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
