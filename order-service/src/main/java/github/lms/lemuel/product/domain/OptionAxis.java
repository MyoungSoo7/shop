package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 표준 옵션 축 (색상·사이즈·용량 …). 상품 간 재사용되는 카탈로그 엔티티.
 *
 * <p>왜 상품별이 아니라 전역인가: 축이 상품마다 따로 존재하면 "사이즈"가 판매자마다 제각각이 되어
 * 파셋 검색·표준화·통계가 성립하지 않는다. 상품은 이 축을 <b>채택</b>할 뿐이며,
 * 채택 사실은 {@link ProductOptionAxis} 가 들고 있다.
 *
 * <p>{@link #getCode()} 는 기계 식별자다. 공백과 {@code :} {@code /} 를 금지하는데, 이 두 글자가
 * 표시용 규약({@code "색상:빨강/사이즈:L"})의 구분자라 코드에 섞이면 라벨이 모호해진다.
 */
public final class OptionAxis {

    /** 유니코드 문자/숫자로 시작하고, 이어서 문자·숫자·{@code _}·{@code -} 만. 최대 50자. */
    private static final Pattern CODE = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{0,49}$");
    private static final int MAX_NAME_LENGTH = 100;

    private Long id;
    private final String code;
    private String name;
    private OptionInputType inputType;
    private boolean active;

    private OptionAxis(Long id, String code, String name, OptionInputType inputType, boolean active) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.inputType = inputType;
        this.active = active;
    }

    public static OptionAxis create(String code, String name, OptionInputType inputType) {
        validateCode(code);
        validateName(name);
        Objects.requireNonNull(inputType, "inputType");
        return new OptionAxis(null, code, name.trim(), inputType, true);
    }

    public static OptionAxis rehydrate(Long id, String code, String name,
                                       OptionInputType inputType, boolean active) {
        return new OptionAxis(id, code, name, inputType, active);
    }

    private static void validateCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ProductInvariantViolationException("옵션 축 코드는 필수입니다");
        }
        if (!CODE.matcher(code).matches()) {
            throw new ProductInvariantViolationException(
                    "옵션 축 코드에 공백/':'/'/' 를 쓸 수 없고 50자를 넘을 수 없습니다: " + code);
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ProductInvariantViolationException("옵션 축 이름은 필수입니다");
        }
        if (name.trim().length() > MAX_NAME_LENGTH) {
            throw new ProductInvariantViolationException(
                    "옵션 축 이름은 " + MAX_NAME_LENGTH + "자를 넘을 수 없습니다");
        }
    }

    /** 표시 이름 변경. 코드는 불변이므로 이 변경은 SKU·매핑에 영향을 주지 않는다. */
    public void rename(String newName) {
        validateName(newName);
        this.name = newName.trim();
    }

    public void changeInputType(OptionInputType newType) {
        this.inputType = Objects.requireNonNull(newType, "inputType");
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    /** 이 축의 값이 표시색을 가져야 하는가. */
    public boolean requiresSwatch() {
        return inputType.requiresSwatch();
    }

    /** DB 부여 PK 주입(1 회만). */
    public void assignId(Long newId) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1 회만 부여 가능");
        }
        this.id = newId;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public OptionInputType getInputType() { return inputType; }
    public boolean isActive() { return active; }
}
