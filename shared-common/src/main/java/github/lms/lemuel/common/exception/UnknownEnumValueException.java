package github.lms.lemuel.common.exception;

/**
 * 문자열을 enum 으로 옮기지 못했다 — 모르는 값이거나 값이 없다.
 *
 * <p>이 예외의 존재 이유는 <b>조용한 기본값</b>을 없애는 것이다. 예전 {@code fromString} 들은
 * 파싱에 실패하면 각자 그럴듯한 값 하나로 떨어뜨렸다({@code UserRole → USER},
 * {@code OrderStatus → CREATED}, {@code MembershipStatus → PENDING},
 * {@code ProductStatus → ACTIVE}). 그 결과 실패는 에러가 아니라 <i>다른 정상 동작</i>으로
 * 보였다 — 관리자 계정이 권한 없는 사용자로, 환불된 주문이 방금 만든 주문으로, 승인된 회원이
 * 승인 대기로 둔갑한다. 어디에도 예외가 남지 않으므로 로그를 뒤져도 나오지 않는다.
 *
 * <p>{@link ErrorCode#INVALID_PARAMETER} 라 요청에서 들어온 값이면 400 으로 나간다. DB 에서
 * 읽은 값이 여기 걸리면 그건 400 이 아니라 <b>데이터가 깨졌다는 신호</b>다 — 응답 코드보다
 * 스택트레이스가 남는다는 사실이 중요하다.
 */
public class UnknownEnumValueException extends BusinessException {

    private final transient String typeName;
    private final transient String rawValue;

    public UnknownEnumValueException(Class<? extends Enum<?>> type, String rawValue) {
        super(ErrorCode.INVALID_PARAMETER,
                "알 수 없는 " + type.getSimpleName() + " 값입니다: " + quote(rawValue));
        this.typeName = type.getSimpleName();
        this.rawValue = rawValue;
    }

    private static String quote(String raw) {
        return raw == null ? "(없음)" : "\"" + raw + "\"";
    }

    public String getTypeName() {
        return typeName;
    }

    /** 파싱에 실패한 원래 문자열. {@code null} 일 수 있다(값 자체가 없었던 경우). */
    public String getRawValue() {
        return rawValue;
    }
}
