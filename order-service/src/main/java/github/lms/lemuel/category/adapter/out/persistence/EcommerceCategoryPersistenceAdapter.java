package github.lms.lemuel.category.adapter.out.persistence;

import github.lms.lemuel.category.application.port.out.LoadCategoryCountDriftPort;
import github.lms.lemuel.category.application.port.out.LoadEcommerceCategoryPort;
import github.lms.lemuel.category.application.port.out.SaveEcommerceCategoryPort;
import github.lms.lemuel.category.domain.EcommerceCategory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class EcommerceCategoryPersistenceAdapter
        implements LoadEcommerceCategoryPort, SaveEcommerceCategoryPort, LoadCategoryCountDriftPort {

    private final SpringDataEcommerceCategoryRepository repository;
    private final EcommerceCategoryMapper mapper;

    public EcommerceCategoryPersistenceAdapter(SpringDataEcommerceCategoryRepository repository,
                                               EcommerceCategoryMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Optional<EcommerceCategory> findByIdNotDeleted(Long id) {
        return repository.findByIdNotDeleted(id).map(mapper::toDomainEntity);
    }

    @Override
    public Optional<EcommerceCategory> findBySlug(String slug) {
        return repository.findBySlug(slug).map(mapper::toDomainEntity);
    }

    @Override
    public List<EcommerceCategory> findAllNotDeleted() {
        return repository.findAllNotDeleted().stream()
                .map(mapper::toDomainEntity).collect(Collectors.toList());
    }

    @Override
    public List<EcommerceCategory> findAllActiveNotDeleted() {
        return repository.findAllActiveNotDeleted().stream()
                .map(mapper::toDomainEntity).collect(Collectors.toList());
    }

    @Override
    public List<EcommerceCategory> findByParentId(Long parentId) {
        return repository.findByParentId(parentId).stream()
                .map(mapper::toDomainEntity).collect(Collectors.toList());
    }

    @Override
    public long countChildrenByParentId(Long parentId) {
        return repository.countChildrenByParentId(parentId);
    }

    @Override
    public boolean hasProducts(Long categoryId) {
        return repository.hasProducts(categoryId);
    }

    @Override
    public EcommerceCategory save(EcommerceCategory category) {
        EcommerceCategoryJpaEntity saved = repository.save(mapper.toJpaEntity(category));
        return mapper.toDomainEntity(saved);
    }

    @Override
    public int recalculatePaths() {
        return repository.recalculatePaths();
    }

    @Override
    public int refreshProductCounts() {
        return repository.refreshProductCounts();
    }

    @Override
    public long countDrifts() {
        return repository.countProductCountDrifts();
    }

    /**
     * 네이티브 조회라 컬럼 순서로 읽는다. 숫자는 드라이버·집계 함수에 따라 Integer/Long/BigInteger 로
     * 오므로 {@link Number} 로 받아 넓힌다 — 캐스팅을 좁게 잡으면 환경에 따라서만 터진다.
     */
    @Override
    public List<RawCountDrift> findDrifts(int limit) {
        return repository.findProductCountDrifts(limit).stream()
                .map(row -> new RawCountDrift(
                        ((Number) row[0]).longValue(),
                        (String) row[1],
                        (String) row[2],
                        ((Number) row[3]).longValue(),
                        ((Number) row[4]).longValue()))
                .collect(Collectors.toList());
    }
}
