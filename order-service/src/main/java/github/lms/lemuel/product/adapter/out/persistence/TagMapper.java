package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.Tag;
import org.springframework.stereotype.Component;

@Component
public class TagMapper {

    public TagJpaEntity toJpaEntity(Tag tag) {
        if (tag == null) {
            return null;
        }

        return new TagJpaEntity(
                tag.getId(),
                tag.getName(),
                tag.getColor(),
                tag.getCreatedAt()
        );
    }

    public Tag toDomainEntity(TagJpaEntity jpaEntity) {
        if (jpaEntity == null) {
            return null;
        }

        return new Tag(
                jpaEntity.getId(),
                jpaEntity.getName(),
                jpaEntity.getColor(),
                jpaEntity.getCreatedAt()
        );
    }
}
