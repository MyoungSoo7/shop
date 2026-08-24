package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 옵션 축의 표준 값 (빨강·L·256GB …).
 *
 * <p>값 이름의 <b>단일 진실원</b>이다. SKU 와 조합 매핑은 이 행의 id 로만 묶이므로,
 * 이름을 바꿔도 재고·주문·조합이 흔들리지 않는다 — 문자열이 조인키였을 때 이름 변경이
 * options_json·option_name·주문 스냅샷 세 곳을 어긋나게 하던 문제가 여기서 사라진다.
 */
public final class OptionAxisValue {

    private static final Pattern CODE = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{0,49}$");
    private static final Pattern SWATCH_HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final int MAX_NAME_LENGTH = 100;

    private Long id;
    private final Long axisId;
    private final String code;
    private String name;
    private String swatchHex;
    private int sortOrder;
    private boolean active;

    private OptionAxisValue(Long id, Long axisId, String code, String name,
                            String swatchHex, int sortOrder, boolean active) {
        this.id = id;
        this.axisId = axisId;
        this.code = code;
        this.name = name;
        this.swatchHex = swatchHex;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    public static OptionAxisValue create(Long axisId, String code, String name,
                                         String swatchHex, int sortOrder) {
        Objects.requireNonNull(axisId, "axisId");
        validateCode(code);
        validateName(name);
        validateSwatchHex(swatchHex);
        validateSortOrder(sortOrder);
        return new OptionAxisValue(null, axisId, code, name.trim(),
                normalizeBlank(swatchHex), sortOrder, true);
    }

    public static OptionAxisValue rehydrate(Long id, Long axisId, String code, String name,
                                            String swatchHex, int sortOrder, boolean active) {
        return new OptionAxisValue(id, axisId, code, name, swatchHex, sortOrder, active);
    }

    private static String normalizeBlank(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static void validateCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ProductInvariantViolationException("옵션 값 코드는 필수입니다");
        }
        if (!CODE.matcher(code).matches()) {
            throw new ProductInvariantViolationException(
                    "옵션 값 코드에 공백/':'/'/' 를 쓸 수 없고 50자를 넘을 수 없습니다: " + code);
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ProductInvariantViolationException("옵션 값 이름은 필수입니다");
        }
        if (name.trim().length() > MAX_NAME_LENGTH) {
            throw new ProductInvariantViolationException(
                    "옵션 값 이름은 " + MAX_NAME_LENGTH + "자를 넘을 수 없습니다");
        }
    }

    private static void validateSwatchHex(String swatchHex) {
        if (swatchHex == null || swatchHex.isBlank()) {
            return;
        }
        if (!SWATCH_HEX.matcher(swatchHex.trim()).matches()) {
            throw new ProductInvariantViolationException(
                    "표시색은 #RRGGBB 형식이어야 합니다: " + swatchHex);
        }
    }

    private static void validateSortOrder(int sortOrder) {
        if (sortOrder < 0) {
            throw new ProductInvariantViolationException("정렬 순서는 0 이상이어야 합니다");
        }
    }

    public void rename(String newName) {
        validateName(newName);
        this.name = newName.trim();
    }

    public void changeSwatchHex(String newSwatchHex) {
        validateSwatchHex(newSwatchHex);
        this.swatchHex = normalizeBlank(newSwatchHex);
    }

    public void changeSortOrder(int newSortOrder) {
        validateSortOrder(newSortOrder);
        this.sortOrder = newSortOrder;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    /**
     * 이 값이 주어진 축의 표현 방식을 만족하는지. SWATCH 축인데 표시색이 없으면 화면이 빈 칩을 그린다 —
     * 축을 모르는 값 단독으로는 판정할 수 없어 축을 인자로 받는다.
     */
    public boolean satisfies(OptionAxis axis) {
        Objects.requireNonNull(axis, "axis");
        if (!axis.getId().equals(axisId)) {
            throw new ProductInvariantViolationException(
                    "다른 축의 값입니다: axisId=" + axisId + ", 검사축=" + axis.getId());
        }
        return !axis.requiresSwatch() || swatchHex != null;
    }

    public void assignId(Long newId) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1 회만 부여 가능");
        }
        this.id = newId;
    }

    public Long getId() { return id; }
    public Long getAxisId() { return axisId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getSwatchHex() { return swatchHex; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
}
