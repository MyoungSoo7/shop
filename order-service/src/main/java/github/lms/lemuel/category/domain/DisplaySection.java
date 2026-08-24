package github.lms.lemuel.category.domain;

import github.lms.lemuel.category.domain.exception.CategoryInvariantViolationException;
import github.lms.lemuel.category.domain.exception.InvalidCategoryStateException;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 진열 편성 — "무엇을, 언제, 어떤 순서로 보여줄 것인가".
 *
 * <p>분류(카테고리)와 다른 축이다. 분류는 상품의 성질이라 시간이 없지만, 진열은 <b>기간</b>이 있다.
 * 선행 사례는 이 둘을 한 테이블에 섞어(노출 플래그·진열 순번을 카테고리 행에) 카테고리를 진열
 * 속성으로 오염시켰다. 여기서는 편성을 별도 애그리거트로 분리한다.
 *
 * <p>노출 여부는 저장된 플래그가 아니라 {@link #isVisibleAt(LocalDateTime)} 이 <b>계산</b>한다 —
 * 기간이 지났는데 플래그가 남아 노출되는 종류의 사고를 구조적으로 없앤다.
 */
public final class DisplaySection {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,49}$");
    private static final int MAX_NAME_LENGTH = 200;

    private Long id;
    private final String code;
    private String name;
    private final DisplaySectionKind kind;
    private Long categoryId;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private int sortOrder;
    private boolean active;

    private DisplaySection(Long id, String code, String name, DisplaySectionKind kind, Long categoryId,
                           LocalDateTime startsAt, LocalDateTime endsAt, int sortOrder, boolean active) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.kind = kind;
        this.categoryId = categoryId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    public static DisplaySection create(String code, String name, DisplaySectionKind kind,
                                        Long categoryId, LocalDateTime startsAt, LocalDateTime endsAt,
                                        int sortOrder) {
        validateCode(code);
        validateName(name);
        Objects.requireNonNull(kind, "kind");
        validatePeriod(startsAt, endsAt);
        validateSortOrder(sortOrder);
        if (kind.requiresCategory() && categoryId == null) {
            throw new CategoryInvariantViolationException(
                    kind + " 편성은 대상 카테고리가 필요합니다");
        }
        return new DisplaySection(null, code, name.trim(), kind,
                kind.requiresCategory() ? categoryId : null, startsAt, endsAt, sortOrder, true);
    }

    public static DisplaySection rehydrate(Long id, String code, String name, DisplaySectionKind kind,
                                           Long categoryId, LocalDateTime startsAt, LocalDateTime endsAt,
                                           int sortOrder, boolean active) {
        return new DisplaySection(id, code, name, kind, categoryId, startsAt, endsAt, sortOrder, active);
    }

    private static void validateCode(String code) {
        if (code == null || code.isBlank()) {
            throw new CategoryInvariantViolationException("편성 코드는 필수입니다");
        }
        if (!CODE.matcher(code).matches()) {
            throw new CategoryInvariantViolationException(
                    "편성 코드는 대문자로 시작하는 2~50자 영문 대문자·숫자·밑줄이어야 합니다: " + code);
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new CategoryInvariantViolationException("편성 이름은 필수입니다");
        }
        if (name.trim().length() > MAX_NAME_LENGTH) {
            throw new CategoryInvariantViolationException(
                    "편성 이름은 " + MAX_NAME_LENGTH + "자를 넘을 수 없습니다");
        }
    }

    private static void validatePeriod(LocalDateTime startsAt, LocalDateTime endsAt) {
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new CategoryInvariantViolationException("종료 시각은 시작 시각보다 뒤여야 합니다");
        }
    }

    private static void validateSortOrder(int sortOrder) {
        if (sortOrder < 0) {
            throw new CategoryInvariantViolationException("정렬 순서는 0 이상이어야 합니다");
        }
    }

    /**
     * 주어진 시점에 노출되는가. 활성 플래그 <b>그리고</b> 기간 조건을 모두 만족해야 한다.
     * 시작·종료가 비어 있으면 그 방향은 열려 있다(상시 진열).
     */
    public boolean isVisibleAt(LocalDateTime at) {
        Objects.requireNonNull(at, "at");
        if (!active) {
            return false;
        }
        if (startsAt != null && at.isBefore(startsAt)) {
            return false;
        }
        return endsAt == null || at.isBefore(endsAt);
    }

    /** 기간을 다시 잡는다. 이미 끝난 편성을 되살리는 정상 경로이기도 하다. */
    public void reschedule(LocalDateTime newStartsAt, LocalDateTime newEndsAt) {
        validatePeriod(newStartsAt, newEndsAt);
        this.startsAt = newStartsAt;
        this.endsAt = newEndsAt;
    }

    public void rename(String newName) {
        validateName(newName);
        this.name = newName.trim();
    }

    public void changeSortOrder(int newSortOrder) {
        validateSortOrder(newSortOrder);
        this.sortOrder = newSortOrder;
    }

    /** 대상 카테고리 변경 — CATEGORY_BEST 가 아닌 편성에는 허용하지 않는다. */
    public void retarget(Long newCategoryId) {
        if (!kind.requiresCategory()) {
            throw new InvalidCategoryStateException(kind + " 편성은 카테고리를 지목하지 않습니다");
        }
        if (newCategoryId == null) {
            throw new CategoryInvariantViolationException("대상 카테고리는 필수입니다");
        }
        this.categoryId = newCategoryId;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public void assignId(Long newId) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1 회만 부여 가능");
        }
        this.id = newId;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public DisplaySectionKind getKind() { return kind; }
    public Long getCategoryId() { return categoryId; }
    public LocalDateTime getStartsAt() { return startsAt; }
    public LocalDateTime getEndsAt() { return endsAt; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
}
