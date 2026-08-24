package github.lms.lemuel.menu.adapter.out.persistence;

import github.lms.lemuel.menu.application.port.out.LoadMenuPort;
import github.lms.lemuel.menu.application.port.out.SaveMenuPort;
import github.lms.lemuel.menu.domain.Menu;
import github.lms.lemuel.menu.domain.MenuAttributes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class MenuPersistenceAdapter implements LoadMenuPort, SaveMenuPort {

    private final SpringDataMenuJpaRepository menuRepository;

    @Override
    public List<Menu> findAll() {
        return menuRepository.findAllByOrderBySortOrderAsc().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Menu> findById(Long id) {
        return menuRepository.findById(id).map(this::toDomain);
    }

    @Override
    public boolean existsByParentId(Long parentId) {
        return menuRepository.existsByParentId(parentId);
    }

    @Override
    public Menu save(Menu menu) {
        MenuJpaEntity entity = toEntity(menu);
        MenuJpaEntity saved = menuRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public List<Menu> saveAll(List<Menu> menus) {
        List<MenuJpaEntity> entities = menus.stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
        return menuRepository.saveAll(entities).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteById(Long id) {
        menuRepository.deleteById(id);
    }

    private MenuJpaEntity toEntity(Menu domain) {
        MenuJpaEntity entity = new MenuJpaEntity();
        entity.setId(domain.getId());
        entity.setParentId(domain.getParentId());
        entity.setName(domain.getName());
        entity.setShortName(domain.getShortName());
        entity.setPath(domain.getPath());
        entity.setIcon(domain.getIcon());
        entity.setDescription(domain.getDescription());
        entity.setArea(domain.getArea());
        entity.setMenuType(domain.getType());
        entity.setSortOrder(domain.getSortOrder());
        entity.setRequiredRole(domain.getRequiredRole());
        entity.setRequiredPermission(domain.getRequiredPermission());
        entity.setVisible(domain.isVisible());
        entity.setActive(domain.isActive());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    private Menu toDomain(MenuJpaEntity entity) {
        return Menu.rehydrate(
                entity.getId(),
                entity.getParentId(),
                new MenuAttributes(
                        entity.getName(),
                        entity.getShortName(),
                        entity.getPath(),
                        entity.getIcon(),
                        entity.getDescription(),
                        entity.getArea(),
                        entity.getMenuType(),
                        entity.getRequiredRole(),
                        entity.getRequiredPermission()),
                entity.getSortOrder(),
                entity.isVisible(),
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
