package github.lms.lemuel.menu.application.service;

import github.lms.lemuel.menu.application.port.in.MenuUseCase;
import github.lms.lemuel.menu.application.port.out.LoadMenuPort;
import github.lms.lemuel.menu.application.port.out.LoadPermissionCodesPort;
import github.lms.lemuel.menu.application.port.out.SaveMenuPort;
import github.lms.lemuel.menu.domain.Menu;
import github.lms.lemuel.menu.domain.MenuArea;
import github.lms.lemuel.menu.domain.MenuAttributes;
import github.lms.lemuel.menu.domain.MenuType;
import github.lms.lemuel.menu.domain.exception.MenuInvariantViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MenuService implements MenuUseCase {

    private static final Set<String> ADMIN_ONLY_PATHS = Set.of(
            // 화면 URL 이다(API 경로 /admin/payouts/** 가 아니다). 2026-08-21 에 nginx SPA 폴백
            // 밖이라 새로고침이 깨져 /admin/settlement/payouts 로 옮겼다 — 여기를 같이 옮기지
            // 않으면 지급 메뉴에서 ADMIN 잠금이 조용히 풀린다(경로가 안 맞으니 검사에 안 걸린다).
            "/admin/settlement/payouts",
            "/admin/settlement/chargebacks",
            "/admin/settlement/monthly-closing",
            "/admin/settlement/commission-rates",
            "/admin/settlement/dlq"
    );

    private final LoadMenuPort loadMenuPort;
    private final SaveMenuPort saveMenuPort;
    private final LoadPermissionCodesPort loadPermissionCodesPort;

    @Override
    @Transactional(readOnly = true)
    public List<Menu> getMenuTree() {
        List<Menu> all = loadMenuPort.findAll();
        return buildTree(all);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Menu> getVisibleMenuTreeForRole(String role) {
        Set<String> permissions = role == null ? Set.of() : loadPermissionCodesPort.findByRoleCode(role);
        List<Menu> accessible = loadMenuPort.findAll().stream()
                .filter(m -> m.isAccessibleBy(role, permissions))
                .collect(Collectors.toList());
        return pruneEmptyGroups(buildTree(accessible));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Menu> getAllFlat() {
        return loadMenuPort.findAll();
    }

    @Override
    public Menu createMenu(CreateMenuCommand command) {
        Menu menu = Menu.create(
                command.attributes(),
                command.parentId(),
                command.sortOrder(),
                command.visible()
        );
        if (command.parentId() != null) {
            Map<Long, Menu> byId = loadAllById();
            Menu parent = requireParent(byId, command.parentId());
            menu.requireSameAreaAs(parent);
            Menu.requireDepthWithin(depthOf(parent, byId) + 1);
        }
        validateAdminOnlyPolicy(command.attributes());
        Menu saved = saveMenuPort.save(menu);
        log.info("메뉴 생성: id={}, name={}, area={}", saved.getId(), saved.getName(), saved.getArea());
        return saved;
    }

    @Override
    public Menu updateMenu(Long id, UpdateMenuCommand command) {
        Menu menu = loadMenuPort.findById(id)
                .orElseThrow(() -> new MenuInvariantViolationException("메뉴를 찾을 수 없습니다: " + id));
        validateAdminOnlyPolicy(command.attributes());
        validateParentChange(id, command.parentId());
        menu.update(
                command.attributes(),
                command.parentId(),
                command.sortOrder(),
                command.visible(),
                command.active()
        );
        if (command.parentId() != null) {
            Map<Long, Menu> byId = loadAllById();
            Menu parent = requireParent(byId, command.parentId());
            menu.requireSameAreaAs(parent);
            byId.put(id, menu);
            Menu.requireDepthWithin(maxDepthOfSubtree(menu, byId));
        }
        Menu saved = saveMenuPort.save(menu);
        log.info("메뉴 수정: id={}, name={}", saved.getId(), saved.getName());
        return saved;
    }

    /** 시스템·실자금·마감·이벤트 재처리 메뉴는 설정 API에서도 ADMIN 전용을 강제한다. */
    private void validateAdminOnlyPolicy(MenuAttributes attrs) {
        boolean adminOnly = attrs.area() == MenuArea.SYSTEM
                || (attrs.path() != null && attrs.path().startsWith("/admin/system/"))
                || ADMIN_ONLY_PATHS.contains(attrs.path());
        if (!adminOnly) {
            return;
        }
        Set<String> roles = attrs.requiredRole() == null
                ? Set.of()
                : Arrays.stream(attrs.requiredRole().split(","))
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (!roles.equals(Set.of("ADMIN"))) {
            throw new MenuInvariantViolationException(
                    "시스템·민감 운영 메뉴는 ADMIN 역할만 지정할 수 있습니다: " + attrs.path());
        }
    }

    @Override
    public void deleteMenu(Long id) {
        loadMenuPort.findById(id)
                .orElseThrow(() -> new MenuInvariantViolationException("메뉴를 찾을 수 없습니다: " + id));
        if (loadMenuPort.existsByParentId(id)) {
            throw new MenuInvariantViolationException("하위 메뉴가 존재하여 삭제할 수 없습니다. 하위 메뉴를 먼저 삭제하세요.");
        }
        saveMenuPort.deleteById(id);
        log.info("메뉴 삭제: id={}", id);
    }

    @Override
    public List<Menu> reorder(List<ReorderItemCommand> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<Long, Menu> byId = loadAllById();

        // 1) 변경 적용 (메모리 상 도메인 객체 — 검증 실패 시 저장 없이 전체 거부)
        for (ReorderItemCommand item : items) {
            Menu menu = byId.get(item.id());
            if (menu == null) {
                throw new MenuInvariantViolationException("존재하지 않는 메뉴 ID: " + item.id());
            }
            if (item.parentId() != null && !byId.containsKey(item.parentId())) {
                throw new MenuInvariantViolationException("존재하지 않는 부모 메뉴: " + item.parentId());
            }
            if (item.parentId() != null && item.parentId().equals(item.id())) {
                throw new MenuInvariantViolationException("자기 자신을 부모로 지정할 수 없습니다: " + item.id());
            }
            menu.reorder(item.parentId(), item.sortOrder());
        }

        // 2) 변경 반영된 그래프 전체에 대해 순환 참조 검증
        for (Menu menu : byId.values()) {
            Set<Long> visited = new HashSet<>();
            Long cursor = menu.getParentId();
            while (cursor != null) {
                if (cursor.equals(menu.getId()) || !visited.add(cursor)) {
                    throw new MenuInvariantViolationException(
                            "순환 참조가 발생하는 재배치입니다: menuId=" + menu.getId());
                }
                Menu current = byId.get(cursor);
                cursor = current == null ? null : current.getParentId();
            }
        }

        // 3) 순환이 없음을 확인한 뒤에야 깊이·영역을 본다 (깊이 계산이 무한루프에 빠지지 않도록 순서 고정)
        for (ReorderItemCommand item : items) {
            Menu menu = byId.get(item.id());
            if (item.parentId() != null) {
                menu.requireSameAreaAs(byId.get(item.parentId()));
            }
            Menu.requireDepthWithin(maxDepthOfSubtree(menu, byId));
        }

        // 4) 요청에 포함된 메뉴만 저장
        List<Menu> changed = items.stream()
                .map(i -> byId.get(i.id()))
                .distinct()
                .collect(Collectors.toList());
        List<Menu> saved = saveMenuPort.saveAll(changed);
        log.info("메뉴 재배치: count={}", saved.size());
        return saved;
    }

    private Map<Long, Menu> loadAllById() {
        return loadMenuPort.findAll().stream()
                .collect(Collectors.toMap(Menu::getId, m -> m));
    }

    private Menu requireParent(Map<Long, Menu> byId, Long parentId) {
        Menu parent = byId.get(parentId);
        if (parent == null) {
            throw new MenuInvariantViolationException("존재하지 않는 부모 메뉴: " + parentId);
        }
        return parent;
    }

    /** 루트를 1 로 세는 깊이. 순환은 호출 전에 배제돼 있다고 본다. */
    private int depthOf(Menu menu, Map<Long, Menu> byId) {
        int depth = 1;
        Set<Long> visited = new HashSet<>();
        Long cursor = menu.getParentId();
        while (cursor != null && visited.add(cursor)) {
            depth++;
            Menu parent = byId.get(cursor);
            cursor = parent == null ? null : parent.getParentId();
        }
        return depth;
    }

    /**
     * 이 메뉴를 뿌리로 하는 부분트리에서 가장 깊은 노드의 절대 깊이.
     * 노드를 위로 옮기면 자손 전체가 함께 내려가므로, 자기 깊이만 봐서는 초과를 못 잡는다.
     */
    private int maxDepthOfSubtree(Menu menu, Map<Long, Menu> byId) {
        int own = depthOf(menu, byId);
        int deepest = own;
        for (Menu candidate : byId.values()) {
            if (candidate.getId() == null || candidate.getId().equals(menu.getId())) {
                continue;
            }
            if (isDescendantOf(candidate, menu.getId(), byId)) {
                deepest = Math.max(deepest, depthOf(candidate, byId));
            }
        }
        return deepest;
    }

    private boolean isDescendantOf(Menu candidate, Long ancestorId, Map<Long, Menu> byId) {
        Set<Long> visited = new HashSet<>();
        Long cursor = candidate.getParentId();
        while (cursor != null && visited.add(cursor)) {
            if (cursor.equals(ancestorId)) {
                return true;
            }
            Menu parent = byId.get(cursor);
            cursor = parent == null ? null : parent.getParentId();
        }
        return false;
    }

    /**
     * 부모 변경 검증 — 자기 자신/자손을 부모로 지정하면 순환 참조가 생기므로 거부한다.
     */
    private void validateParentChange(Long id, Long newParentId) {
        if (newParentId == null) {
            return;
        }
        if (newParentId.equals(id)) {
            throw new MenuInvariantViolationException("자기 자신을 부모로 지정할 수 없습니다: " + id);
        }
        Map<Long, Menu> byId = loadAllById();
        if (!byId.containsKey(newParentId)) {
            throw new MenuInvariantViolationException("존재하지 않는 부모 메뉴: " + newParentId);
        }
        // 새 부모의 조상 체인에 자신이 있으면 자손을 부모로 지정한 것 → 순환
        Set<Long> visited = new HashSet<>();
        Long cursor = newParentId;
        while (cursor != null) {
            if (cursor.equals(id)) {
                throw new MenuInvariantViolationException(
                        "하위 메뉴를 부모로 지정하면 순환 참조가 발생합니다: " + newParentId);
            }
            if (!visited.add(cursor)) {
                break; // 기존 데이터 이상으로 인한 무한루프 방지
            }
            Menu current = byId.get(cursor);
            cursor = current == null ? null : current.getParentId();
        }
    }

    /**
     * 평면 목록을 sort_order 기준 트리로 조립한다 (메모리 내 재귀).
     *
     * <p>조회 때마다 새 도메인 인스턴스를 받으므로 children 은 매번 비어 있다. 다만 같은 리스트를
     * 두 번 조립하는 실수를 대비해 조립 전에 비워 둔다.
     */
    private List<Menu> buildTree(List<Menu> all) {
        all.forEach(m -> m.replaceChildren(new ArrayList<>()));
        Map<Long, Menu> byId = all.stream()
                .collect(Collectors.toMap(Menu::getId, m -> m));

        List<Menu> roots = new ArrayList<>();
        for (Menu menu : all) {
            if (menu.getParentId() == null) {
                roots.add(menu);
            } else {
                Menu parent = byId.get(menu.getParentId());
                if (parent != null) {
                    parent.addChild(menu);
                } else {
                    // 부모가 없는 고아 메뉴는 루트로 처리
                    roots.add(menu);
                }
            }
        }

        roots.sort(Comparator.comparingInt(Menu::getSortOrder));
        sortChildren(roots);
        return roots;
    }

    /**
     * 자식이 하나도 남지 않은 묶음(GROUP)을 걷어낸다.
     * 권한 필터로 하위가 전부 사라진 묶음을 남기면 눌러도 빈 사이드바만 나오는 죽은 메뉴가 된다.
     */
    private List<Menu> pruneEmptyGroups(List<Menu> nodes) {
        List<Menu> kept = new ArrayList<>();
        for (Menu node : nodes) {
            node.replaceChildren(pruneEmptyGroups(node.getChildren()));
            if (node.getType() == MenuType.GROUP && node.getChildren().isEmpty()) {
                continue;
            }
            kept.add(node);
        }
        return kept;
    }

    private void sortChildren(List<Menu> menus) {
        for (Menu menu : menus) {
            menu.getChildren().sort(Comparator.comparingInt(Menu::getSortOrder));
            sortChildren(menu.getChildren());
        }
    }
}
