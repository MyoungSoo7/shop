package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

/**
 * 표시 이름 → 기계 코드 변환 규칙.
 *
 * <p>레거시 표시명에서 카탈로그를 만들 때와, 그 카탈로그를 다시 찾아갈 때 <b>같은 규칙</b>을 써야 한다.
 * 두 곳이 각자 변환하면 백필은 {@code "메인-색상"} 을 만들고 조회는 {@code "메인 색상"} 을 찾는
 * 어긋남이 생긴다 — 그래서 규칙을 도메인 한 곳에 둔다.
 *
 * <p>내부 공백만 하이픈으로 접고 그 외에는 손대지 않는다. 길이 초과는 <b>자르지 않고 거부</b>한다:
 * 잘라내면 서로 다른 두 이름이 한 코드로 합쳐져 다른 옵션이 같은 값이 되는 조용한 오염이 생긴다.
 */
public final class OptionCode {

    public static final int MAX_LENGTH = 50;

    private OptionCode() {
    }

    public static String fromDisplayName(String name, String what) {
        if (name == null || name.isBlank()) {
            throw new ProductInvariantViolationException(what + "이 비어 있습니다");
        }
        String code = name.trim().replaceAll("\\s+", "-");
        if (code.length() > MAX_LENGTH) {
            throw new ProductInvariantViolationException(
                    what + "이 코드 길이 " + MAX_LENGTH + " 자를 넘습니다(자동 축약하지 않음): " + name);
        }
        return code;
    }
}
