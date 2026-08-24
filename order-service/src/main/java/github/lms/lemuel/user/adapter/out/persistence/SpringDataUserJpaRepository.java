package github.lms.lemuel.user.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository (충돌 방지: UserRepository 대신 SpringDataUserJpaRepository)
 */
public interface SpringDataUserJpaRepository extends JpaRepository<UserJpaEntity, Long> {

    Optional<UserJpaEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    List<UserJpaEntity> findByMembershipStatusOrderByCreatedAtAsc(String membershipStatus);
}
