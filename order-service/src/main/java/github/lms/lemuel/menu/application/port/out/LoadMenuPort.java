package github.lms.lemuel.menu.application.port.out;

import github.lms.lemuel.menu.domain.Menu;

import java.util.List;
import java.util.Optional;

public interface LoadMenuPort {
    List<Menu> findAll();
    Optional<Menu> findById(Long id);
    boolean existsByParentId(Long parentId);
}
