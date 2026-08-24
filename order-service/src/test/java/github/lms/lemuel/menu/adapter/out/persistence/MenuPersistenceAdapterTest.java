package github.lms.lemuel.menu.adapter.out.persistence;

import github.lms.lemuel.menu.domain.Menu;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 메뉴 영속 어댑터 매핑/위임 회귀 테스트 (Mockito, 실 DB 미접속).
 */
@ExtendWith(MockitoExtension.class)
class MenuPersistenceAdapterTest {

    @Mock SpringDataMenuJpaRepository menuRepository;
    @InjectMocks MenuPersistenceAdapter adapter;

    private MenuJpaEntity entity() {
        MenuJpaEntity e = new MenuJpaEntity();
        e.setId(5L);
        e.setParentId(1L);
        e.setName("상품관리");
        e.setPath("/admin/products");
        e.setIcon("box");
        e.setSortOrder(2);
        e.setRequiredRole("ADMIN");
        e.setVisible(true);
        e.setActive(true);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return e;
    }

    @Test
    @DisplayName("findAll: 정렬 조회 결과가 도메인으로 매핑된다")
    void findAll() {
        when(menuRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(entity()));

        List<Menu> menus = adapter.findAll();

        assertThat(menus).hasSize(1);
        Menu m = menus.get(0);
        assertThat(m.getId()).isEqualTo(5L);
        assertThat(m.getParentId()).isEqualTo(1L);
        assertThat(m.getName()).isEqualTo("상품관리");
        assertThat(m.getPath()).isEqualTo("/admin/products");
        assertThat(m.getIcon()).isEqualTo("box");
        assertThat(m.getSortOrder()).isEqualTo(2);
        assertThat(m.getRequiredRole()).isEqualTo("ADMIN");
        assertThat(m.isVisible()).isTrue();
        assertThat(m.isActive()).isTrue();
    }

    @Test
    @DisplayName("findById: 존재 시 도메인 매핑")
    void findById_present() {
        when(menuRepository.findById(5L)).thenReturn(Optional.of(entity()));
        assertThat(adapter.findById(5L)).isPresent();
    }

    @Test
    @DisplayName("findById: 미존재 시 empty")
    void findById_empty() {
        when(menuRepository.findById(99L)).thenReturn(Optional.empty());
        assertThat(adapter.findById(99L)).isEmpty();
    }

    @Test
    @DisplayName("existsByParentId: 리포지토리 위임")
    void existsByParentId() {
        when(menuRepository.existsByParentId(1L)).thenReturn(true);
        assertThat(adapter.existsByParentId(1L)).isTrue();
    }

    @Test
    @DisplayName("save: 도메인→엔티티→저장→도메인 왕복이 필드를 보존한다")
    void save() {
        when(menuRepository.save(any(MenuJpaEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Menu menu = Menu.create(new github.lms.lemuel.menu.domain.MenuAttributes(
                "주문관리", null, "/admin/orders", "cart", null,
                github.lms.lemuel.menu.domain.MenuArea.BACKOFFICE,
                github.lms.lemuel.menu.domain.MenuType.ITEM, "ADMIN", null), 2L, 4, true);
        Menu saved = adapter.save(menu);

        assertThat(saved.getName()).isEqualTo("주문관리");
        assertThat(saved.getPath()).isEqualTo("/admin/orders");
        assertThat(saved.getParentId()).isEqualTo(2L);
        assertThat(saved.getSortOrder()).isEqualTo(4);
        verify(menuRepository).save(any(MenuJpaEntity.class));
    }

    @Test
    @DisplayName("deleteById: 리포지토리 위임")
    void deleteById() {
        adapter.deleteById(5L);
        verify(menuRepository).deleteById(5L);
    }
}
