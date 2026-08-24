package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TagMapperTest {

    private final TagMapper mapper = new TagMapper();

    @Test
    @DisplayName("toJpaEntity: null 이면 null")
    void toJpaEntity_null() {
        assertThat(mapper.toJpaEntity(null)).isNull();
    }

    @Test
    @DisplayName("toDomainEntity: null 이면 null")
    void toDomainEntity_null() {
        assertThat(mapper.toDomainEntity(null)).isNull();
    }

    @Test
    @DisplayName("도메인 -> 엔티티 -> 도메인 왕복 시 전 필드 보존")
    void roundTrip() {
        LocalDateTime now = LocalDateTime.now();
        Tag tag = new Tag(1L, "신상", "#FF0000", now);

        TagJpaEntity entity = mapper.toJpaEntity(tag);
        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getName()).isEqualTo("신상");
        assertThat(entity.getColor()).isEqualTo("#FF0000");

        Tag restored = mapper.toDomainEntity(entity);
        assertThat(restored.getId()).isEqualTo(1L);
        assertThat(restored.getName()).isEqualTo("신상");
        assertThat(restored.getColor()).isEqualTo("#FF0000");
        assertThat(restored.getCreatedAt()).isEqualTo(now);
    }
}
