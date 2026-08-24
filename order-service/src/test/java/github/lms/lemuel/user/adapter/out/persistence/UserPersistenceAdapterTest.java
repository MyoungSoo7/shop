package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.domain.MembershipStatus;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * User 영속 어댑터 + 수동 매퍼(UserPersistenceMapper) 회귀 테스트.
 *
 * <p>매퍼 구현체를 사용해 도메인↔엔티티 매핑(role/active/membershipStatus 변환 포함)과
 * 어댑터의 리포지토리 위임을 함께 검증한다(실 DB 미접속).
 */
@ExtendWith(MockitoExtension.class)
class UserPersistenceAdapterTest {

    @Mock SpringDataUserJpaRepository repository;
    private final UserPersistenceMapper mapper = new UserPersistenceMapper();

    private UserPersistenceAdapter adapter() {
        return new UserPersistenceAdapter(repository, mapper);
    }

    private UserJpaEntity entity(long id, String email) {
        UserJpaEntity e = new UserJpaEntity();
        e.setId(id);
        e.setEmail(email);
        e.setPassword("hashed");
        e.setRole("ADMIN");
        e.setName("관리자");
        e.setPhoneNumber("010-1111-2222");
        e.setActive(true);
        e.setMembershipStatus("APPROVED");
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return e;
    }

    @Test
    @DisplayName("findById: 엔티티가 도메인으로 매핑된다 (role/membership 표현식 포함)")
    void findById() {
        when(repository.findById(1L)).thenReturn(Optional.of(entity(1L, "a@b.com")));

        User user = adapter().findById(1L).orElseThrow();

        assertThat(user.getEmail()).isEqualTo("a@b.com");
        assertThat(user.getPasswordHash()).isEqualTo("hashed");
        assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(user.isActive()).isTrue();
        assertThat(user.getMembershipStatus()).isEqualTo(MembershipStatus.APPROVED);
    }

    @Test
    @DisplayName("findById: 미존재 시 empty")
    void findById_empty() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThat(adapter().findById(99L)).isEmpty();
    }

    @Test
    @DisplayName("findAll: 목록 매핑")
    void findAll() {
        when(repository.findAll()).thenReturn(List.of(entity(1L, "a@b.com"), entity(2L, "c@d.com")));
        assertThat(adapter().findAll()).hasSize(2);
    }

    @Test
    @DisplayName("findByEmail: 이메일 조회 매핑")
    void findByEmail() {
        when(repository.findByEmail("a@b.com")).thenReturn(Optional.of(entity(1L, "a@b.com")));
        assertThat(adapter().findByEmail("a@b.com")).isPresent();
    }

    @Test
    @DisplayName("save: 도메인→엔티티→저장→도메인 왕복이 필드를 보존한다")
    void save() {
        when(repository.save(any(UserJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = User.createWithProfile("new@b.com", "hash", UserRole.MANAGER, "매니저", "010-3333-4444");
        User saved = adapter().save(user);

        assertThat(saved.getEmail()).isEqualTo("new@b.com");
        assertThat(saved.getRole()).isEqualTo(UserRole.MANAGER);
        assertThat(saved.getName()).isEqualTo("매니저");
        assertThat(saved.getMembershipStatus()).isEqualTo(MembershipStatus.APPROVED);
    }

    @Test
    @DisplayName("existsByEmail: 리포지토리 위임")
    void existsByEmail() {
        when(repository.existsByEmail("a@b.com")).thenReturn(true);
        assertThat(adapter().existsByEmail("a@b.com")).isTrue();
    }

    @Test
    @DisplayName("findByMembershipStatus: 상태명으로 조회 후 매핑")
    void findByMembershipStatus() {
        UserJpaEntity pending = entity(3L, "p@b.com");
        pending.setMembershipStatus("PENDING");
        when(repository.findByMembershipStatusOrderByCreatedAtAsc("PENDING"))
                .thenReturn(List.of(pending));

        List<User> users = adapter().findByMembershipStatus(MembershipStatus.PENDING);

        assertThat(users).hasSize(1);
        assertThat(users.get(0).getMembershipStatus()).isEqualTo(MembershipStatus.PENDING);
    }
}
