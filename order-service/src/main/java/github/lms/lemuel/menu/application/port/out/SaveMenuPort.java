package github.lms.lemuel.menu.application.port.out;

import github.lms.lemuel.menu.domain.Menu;

import java.util.List;

public interface SaveMenuPort {
    Menu save(Menu menu);
    List<Menu> saveAll(List<Menu> menus);
    void deleteById(Long id);
}
