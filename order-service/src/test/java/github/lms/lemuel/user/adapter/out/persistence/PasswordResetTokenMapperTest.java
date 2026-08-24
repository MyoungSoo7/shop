package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.domain.PasswordResetToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PasswordResetToken MapStruct 매퍼(생성 구현체) 회귀 테스트 — 도메인↔엔티티 양방향 매핑 보존.
 */
class PasswordResetTokenMapperTest {

    private final PasswordResetTokenMapper mapper = new PasswordResetTokenMapperImpl();

    @Test
    @DisplayName("toEntity: 도메인 필드가 엔티티로 매핑된다")
    void toEntity() {
        LocalDateTime expiry = LocalDateTime.now().plusMinutes(30);
        PasswordResetToken domain = new PasswordResetToken(1L, 100L, "tok-123", expiry, false,
                LocalDateTime.now());

        PasswordResetTokenJpaEntity entity = mapper.toEntity(domain);

        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getUserId()).isEqualTo(100L);
        assertThat(entity.getToken()).isEqualTo("tok-123");
        assertThat(entity.getExpiryDate()).isEqualTo(expiry);
        assertThat(entity.isUsed()).isFalse();
    }

    @Test
    @DisplayName("toDomain: 엔티티 필드가 도메인으로 매핑된다")
    void toDomain() {
        LocalDateTime expiry = LocalDateTime.now().plusMinutes(30);
        LocalDateTime created = LocalDateTime.now().minusMinutes(5);
        PasswordResetTokenJpaEntity entity =
                new PasswordResetTokenJpaEntity(2L, 200L, "tok-xyz", expiry, true, created);

        PasswordResetToken domain = mapper.toDomain(entity);

        assertThat(domain.getId()).isEqualTo(2L);
        assertThat(domain.getUserId()).isEqualTo(200L);
        assertThat(domain.getToken()).isEqualTo("tok-xyz");
        assertThat(domain.getExpiryDate()).isEqualTo(expiry);
        assertThat(domain.isUsed()).isTrue();
        assertThat(domain.getCreatedAt()).isEqualTo(created);
    }

    @Test
    @DisplayName("null 입력은 null 반환")
    void nullSafe() {
        assertThat(mapper.toEntity(null)).isNull();
        assertThat(mapper.toDomain(null)).isNull();
    }
}
