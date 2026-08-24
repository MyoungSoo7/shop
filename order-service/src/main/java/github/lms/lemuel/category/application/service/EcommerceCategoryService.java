package github.lms.lemuel.category.application.service;

import github.lms.lemuel.category.application.port.out.LoadEcommerceCategoryPort;
import github.lms.lemuel.category.application.port.out.SaveEcommerceCategoryPort;
import github.lms.lemuel.category.domain.EcommerceCategory;
import github.lms.lemuel.category.domain.exception.CategoryHasChildrenException;
import github.lms.lemuel.category.domain.exception.CategoryInvariantViolationException;
import github.lms.lemuel.category.domain.exception.CategoryHasProductsException;
import github.lms.lemuel.category.domain.exception.CategoryNotFoundException;
import github.lms.lemuel.category.domain.exception.CircularReferenceException;
import github.lms.lemuel.category.domain.exception.DuplicateSlugException;
import github.lms.lemuel.category.util.SlugGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EcommerceCategoryService {

    private final LoadEcommerceCategoryPort loadPort;
    private final SaveEcommerceCategoryPort savePort;
    private final SlugGenerator slugGenerator;

    @Transactional
    @CacheEvict(value = "ecommerce-categories", allEntries = true)
    public EcommerceCategory createCategory(String name, String slug, Long parentId, Integer sortOrder) {
        if (slug == null || slug.trim().isEmpty()) {
            if (parentId != null) {
                EcommerceCategory parent = loadCategory(parentId);
                slug = slugGenerator.generateWithParent(parent.getSlug(), name);
            } else {
                slug = slugGenerator.generate(name);
            }
        }

        if (loadPort.findBySlug(slug).isPresent()) {
            throw new DuplicateSlugException(slug);
        }

        EcommerceCategory category;
        if (parentId == null) {
            category = EcommerceCategory.createRoot(name, slug, sortOrder != null ? sortOrder : 0);
        } else {
            EcommerceCategory parent = loadCategory(parentId);
            if (parent.isDeleted()) {
                throw new CategoryInvariantViolationException("Cannot create category under deleted parent");
            }
            category = EcommerceCategory.createChild(name, slug, parentId, parent.getDepth(),
                    sortOrder != null ? sortOrder : 0);
        }

        EcommerceCategory saved = savePort.save(category);
        savePort.recalculatePaths();
        return saved;
    }

    @Cacheable(value = "ecommerce-categories", key = "'id:' + #id")
    public EcommerceCategory getCategoryById(Long id) {
        return loadCategory(id);
    }

    /**
     * 캐시를 거치지 않는 <b>쓰기 경로 전용</b> 단건 조회.
     *
     * <p>쓰기 메서드가 {@link #getCategoryById(Long)} 을 직접 부르면 스프링 AOP 프록시를 타지 않아
     * 어차피 캐시가 걸리지 않는다(고급편 §14 프록시와 내부 호출). 그 <b>암묵적</b> 우회에 기대는 대신
     * 의도를 코드로 드러낸다 — 수정 대상 엔티티는 캐시 스냅샷이 아니라 <b>DB 현재 상태</b>여야
     * lost update 가 없다. 누군가 "자기호출 버그"로 오해해 자기 자신 주입으로 '고치면' 오히려
     * 낡은 캐시 엔티티를 저장하게 된다.
     */
    private EcommerceCategory loadCategory(Long id) {
        return loadPort.findByIdNotDeleted(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));
    }

    @Cacheable(value = "ecommerce-categories", key = "'slug:' + #slug")
    public EcommerceCategory getCategoryBySlug(String slug) {
        return loadPort.findBySlug(slug)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new CategoryNotFoundException(slug));
    }

    @Cacheable(value = "ecommerce-categories", key = "'tree'")
    public List<EcommerceCategory> getAllCategoriesTree() {
        return buildTree(loadPort.findAllNotDeleted());
    }

    @Cacheable(value = "ecommerce-categories", key = "'tree:active'")
    public List<EcommerceCategory> getActiveCategoriesTree() {
        return buildTree(loadPort.findAllActiveNotDeleted());
    }

    private List<EcommerceCategory> buildTree(List<EcommerceCategory> categories) {
        Map<Long, EcommerceCategory> categoryMap = new HashMap<>();
        List<EcommerceCategory> rootCategories = new ArrayList<>();

        for (EcommerceCategory category : categories) {
            categoryMap.put(category.getId(), category);
        }

        for (EcommerceCategory category : categories) {
            if (category.isRoot()) {
                rootCategories.add(category);
            } else {
                EcommerceCategory parent = categoryMap.get(category.getParentId());
                if (parent != null) {
                    parent.addChild(category);
                }
            }
        }

        return rootCategories;
    }

    @Transactional
    @CacheEvict(value = "ecommerce-categories", allEntries = true)
    public EcommerceCategory updateCategory(Long id, String name, String slug) {
        EcommerceCategory category = loadCategory(id);

        if (slug != null && !slug.equals(category.getSlug())) {
            if (loadPort.findBySlug(slug).isPresent()) {
                throw new DuplicateSlugException(slug);
            }
        }

        category.updateInfo(name, slug);
        return savePort.save(category);
    }

    @Transactional
    @CacheEvict(value = "ecommerce-categories", allEntries = true)
    public EcommerceCategory moveCategory(Long categoryId, Long newParentId) {
        EcommerceCategory category = loadCategory(categoryId);

        validateNoCircularReference(categoryId, newParentId);

        Integer newParentDepth;
        if (newParentId == null) {
            newParentDepth = null;
        } else {
            EcommerceCategory newParent = loadCategory(newParentId);
            newParentDepth = newParent.getDepth();
        }

        category.changeParent(newParentId, newParentDepth != null ? newParentDepth : -1);
        EcommerceCategory moved = savePort.save(category);
        // 옮겨진 노드뿐 아니라 그 아래 전부의 경로가 바뀐다 — 영향 범위를 좁게 잡으면 조용히 어긋난다.
        savePort.recalculatePaths();
        return moved;
    }

    /**
     * 상품수 캐시 재계산. 캐시는 정본이 아니므로(정본은 매핑 테이블) 주기적/수동 갱신으로 맞춘다.
     *
     * @return 값이 달라진 행 수. 0 이면 캐시와 정본이 일치한다.
     */
    public int refreshProductCounts() {
        return savePort.refreshProductCounts();
    }

    private void validateNoCircularReference(Long categoryId, Long newParentId) {
        if (newParentId == null) {
            return;
        }
        if (categoryId.equals(newParentId)) {
            throw new CircularReferenceException(categoryId, newParentId);
        }

        Set<Long> descendants = getDescendantIds(categoryId);
        if (descendants.contains(newParentId)) {
            throw new CircularReferenceException(
                    String.format("Cannot move category %d to parent %d: would create circular reference",
                            categoryId, newParentId));
        }
    }

    /**
     * 주어진 카테고리의 모든 하위(자손) id 집합을 반환한다.
     *
     * <p>전체 카테고리를 <b>한 번만</b> 로드해 {@code parentId -> children} 인접 맵을 구성한 뒤,
     * 그 위에서 {@link ArrayDeque} 기반 <b>BFS</b> 로 자손을 순회한다. 노드마다 DB 를 조회하던
     * 기존 재귀 방식의 N+1 쿼리를 제거한다. 이미 방문한 노드는 큐에 다시 넣지 않아
     * (방어적으로) 데이터 사이클에도 무한 루프하지 않는다.
     */
    private Set<Long> getDescendantIds(Long categoryId) {
        Map<Long, List<EcommerceCategory>> childrenByParent = new HashMap<>();
        for (EcommerceCategory category : loadPort.findAllNotDeleted()) {
            if (category.getParentId() != null) {
                childrenByParent
                        .computeIfAbsent(category.getParentId(), k -> new ArrayList<>())
                        .add(category);
            }
        }

        Set<Long> descendants = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(categoryId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            for (EcommerceCategory child : childrenByParent.getOrDefault(current, List.of())) {
                Long childId = child.getId();
                if (descendants.add(childId)) { // 처음 본 노드만 큐에 추가 (중복/사이클 방어)
                    queue.add(childId);
                }
            }
        }
        return descendants;
    }

    @Transactional
    @CacheEvict(value = "ecommerce-categories", allEntries = true)
    public EcommerceCategory changeSortOrder(Long id, Integer sortOrder) {
        EcommerceCategory category = loadCategory(id);
        category.changeSortOrder(sortOrder);
        return savePort.save(category);
    }

    @Transactional
    @CacheEvict(value = "ecommerce-categories", allEntries = true)
    public EcommerceCategory activateCategory(Long id) {
        EcommerceCategory category = loadCategory(id);
        category.activate();
        return savePort.save(category);
    }

    @Transactional
    @CacheEvict(value = "ecommerce-categories", allEntries = true)
    public EcommerceCategory deactivateCategory(Long id) {
        EcommerceCategory category = loadCategory(id);
        category.deactivate();
        return savePort.save(category);
    }

    @Transactional
    @CacheEvict(value = "ecommerce-categories", allEntries = true)
    public void deleteCategory(Long id) {
        EcommerceCategory category = loadCategory(id);

        long childCount = loadPort.countChildrenByParentId(id);
        if (childCount > 0) {
            throw new CategoryHasChildrenException(id, childCount);
        }

        if (loadPort.hasProducts(id)) {
            throw new CategoryHasProductsException(id);
        }

        category.softDelete();
        savePort.save(category);
        savePort.recalculatePaths();
    }
}
