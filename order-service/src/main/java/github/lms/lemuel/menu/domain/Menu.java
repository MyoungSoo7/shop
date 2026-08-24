package github.lms.lemuel.menu.domain;

import github.lms.lemuel.menu.domain.exception.MenuInvariantViolationException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Menu Domain Entity (순수 POJO)
 *
 * <p>화면 네비게이션의 정본. 라우트(코드)는 "갈 수 있는 곳"이고 이 트리는 "보여 줄 곳"이다.
 * 둘의 정합은 빌드 게이트가 대조한다.
 */
public class Menu {

    /**
     * 트리 최대 깊이(루트=1). 1=상단 네비, 2=사이드바 항목, 3=사이드바 그룹 안 항목까지가 셸이
     * 그릴 수 있는 전부다. 더 깊은 트리는 데이터로는 만들 수 있어도 화면에 나오지 않으므로 막는다.
     */
    public static final int MAX_DEPTH = 3;

    private Long id;
    private Long parentId;
    private String name;
    private String shortName;
    private String path;
    private String icon;
    private String description;
    private MenuArea area;
    private MenuType type;
    private int sortOrder;
    private String requiredRole;
    private String requiredPermission;
    private boolean visible;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 트리 조립용 (DB 컬럼 아님)
    private List<Menu> children = new ArrayList<>();

    private Menu() {
        this.visible = true;
        this.active = true;
        this.sortOrder = 0;
        this.type = MenuType.ITEM;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public static Menu create(MenuAttributes attrs, Long parentId, int sortOrder, boolean visible) {
        validate(attrs);
        Menu menu = new Menu();
        menu.applyAttributes(attrs);
        menu.parentId = parentId;
        menu.sortOrder = sortOrder;
        menu.visible = visible;
        return menu;
    }

    public void update(MenuAttributes attrs, Long parentId, int sortOrder,
                       boolean visible, boolean active) {
        validate(attrs);
        applyAttributes(attrs);
        this.parentId = parentId;
        this.sortOrder = sortOrder;
        this.visible = visible;
        this.active = active;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 영속 레코드 복원 팩토리. 어댑터가 no-arg + setter 대신 이 경로로만 도메인을 재구성한다.
     *
     * <p>복원은 검증하지 않는다 — 이미 저장된 행을 되살리는 경로에서 예외를 던지면 낡은 데이터
     * 한 줄이 트리 전체 조회를 막는다. 유입 시점(create/update)에서 막는 것이 정본이다.
     */
    public static Menu rehydrate(Long id, Long parentId, MenuAttributes attrs, int sortOrder,
                                 boolean visible, boolean active,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {
        Menu menu = new Menu();
        menu.id = id;
        menu.parentId = parentId;
        menu.applyAttributes(attrs);
        menu.sortOrder = sortOrder;
        menu.visible = visible;
        menu.active = active;
        menu.createdAt = createdAt;
        menu.updatedAt = updatedAt;
        return menu;
    }

    private void applyAttributes(MenuAttributes attrs) {
        this.name = attrs.name() == null ? null : attrs.name().trim();
        this.shortName = blankToNull(attrs.shortName());
        this.path = blankToNull(attrs.path());
        this.icon = attrs.icon();
        this.description = attrs.description();
        this.area = attrs.area();
        this.type = attrs.type() == null ? MenuType.ITEM : attrs.type();
        this.requiredRole = blankToNull(attrs.requiredRole());
        this.requiredPermission = blankToNull(attrs.requiredPermission());
    }

    private static void validate(MenuAttributes attrs) {
        if (attrs == null) {
            throw new MenuInvariantViolationException("메뉴 속성은 필수입니다.");
        }
        if (attrs.name() == null || attrs.name().isBlank()) {
            throw new MenuInvariantViolationException("메뉴 이름은 필수입니다.");
        }
        if (attrs.area() == null) {
            throw new MenuInvariantViolationException("메뉴 영역(area)은 필수입니다.");
        }
        MenuType type = attrs.type() == null ? MenuType.ITEM : attrs.type();
        boolean hasPath = attrs.path() != null && !attrs.path().isBlank();
        if (type.requiresPath() && !hasPath) {
            throw new MenuInvariantViolationException("링크 메뉴(" + type + ")는 경로가 필수입니다: " + attrs.name());
        }
        if (!type.requiresPath() && hasPath) {
            throw new MenuInvariantViolationException("구분선은 경로를 가질 수 없습니다: " + attrs.name());
        }
        if (hasPath && !attrs.path().startsWith("/")) {
            throw new MenuInvariantViolationException("메뉴 경로는 '/' 로 시작해야 합니다: " + attrs.path());
        }
        validateRoles(attrs.requiredRole());
    }

    private static void validateRoles(String csv) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        for (String token : csv.split(",", -1)) {
            if (token.isBlank()) {
                throw new MenuInvariantViolationException("역할 목록에 빈 항목이 있습니다: " + csv);
            }
        }
    }

    /**
     * 깊이 검증 — 트리 문맥이 있어야 알 수 있으므로 조립하는 쪽(application)이 호출한다.
     * 규칙 자체는 도메인이 소유한다.
     *
     * @param depth 루트를 1 로 세는 깊이
     */
    public static void requireDepthWithin(int depth) {
        if (depth > MAX_DEPTH) {
            throw new MenuInvariantViolationException(
                    "메뉴 깊이는 최대 " + MAX_DEPTH + " 단계입니다. 요청 깊이=" + depth);
        }
    }

    /** 자식은 부모와 같은 영역에 속해야 한다 — 한 사이드바에 남의 영역 항목이 섞이는 것을 막는다. */
    public void requireSameAreaAs(Menu parent) {
        if (parent != null && parent.area != this.area) {
            throw new MenuInvariantViolationException(
                    "하위 메뉴는 부모와 같은 영역이어야 합니다. 부모=" + parent.area + ", 자신=" + this.area);
        }
    }

    /** 접근 허용 역할 집합. 비어 있으면 역할 제한이 없다는 뜻이다. */
    public Set<String> allowedRoles() {
        if (requiredRole == null) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        Arrays.stream(requiredRole.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .forEach(roles::add);
        return Collections.unmodifiableSet(roles);
    }

    /**
     * 이 메뉴를 해당 주체에게 보여 줄 수 있는가.
     *
     * @param role        호출자 역할(미인증이면 null)
     * @param permissions 호출자 권한 코드 집합(null 이면 빈 집합으로 본다)
     */
    public boolean isAccessibleBy(String role, Set<String> permissions) {
        if (!visible || !active) {
            return false;
        }
        Set<String> allowed = allowedRoles();
        if (!allowed.isEmpty()) {
            if (role == null || !allowed.contains(role.toUpperCase(Locale.ROOT))) {
                return false;
            }
        }
        if (requiredPermission != null) {
            return permissions != null && permissions.contains(requiredPermission);
        }
        return true;
    }

    /** 상단 네비에 쓰는 짧은 라벨 — 없으면 이름을 그대로 쓴다. */
    public String displayLabel() {
        return shortName != null ? shortName : name;
    }

    public void addChild(Menu child) {
        this.children.add(child);
    }

    /**
     * 트리 재정렬 시 부모/정렬순서 재배치(setter 대체). updatedAt 갱신까지 한 동작으로 묶는다.
     */
    public void reorder(Long parentId, int sortOrder) {
        this.parentId = parentId;
        this.sortOrder = sortOrder;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 트리 조립용 children 교체(DB 컬럼 아님, 조회 시 조립).
     */
    public void replaceChildren(List<Menu> children) {
        this.children = children != null ? children : new ArrayList<>();
    }

    /** DB 부여 PK 주입(setter 대체). */
    public void assignId(Long id) { this.id = id; }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // Getters
    public Long getId() { return id; }

    public Long getParentId() { return parentId; }

    public String getName() { return name; }

    public String getShortName() { return shortName; }

    public String getPath() { return path; }

    public String getIcon() { return icon; }

    public String getDescription() { return description; }

    public MenuArea getArea() { return area; }

    public MenuType getType() { return type; }

    public int getSortOrder() { return sortOrder; }

    public String getRequiredRole() { return requiredRole; }

    public String getRequiredPermission() { return requiredPermission; }

    public boolean isVisible() { return visible; }

    public boolean isActive() { return active; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public List<Menu> getChildren() { return children; }
}
