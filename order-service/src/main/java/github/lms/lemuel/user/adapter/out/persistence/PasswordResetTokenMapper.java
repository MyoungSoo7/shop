package github.lms.lemuel.user.adapter.out.persistence;

import github.lms.lemuel.user.domain.PasswordResetToken;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface PasswordResetTokenMapper {

    /**
     * Entity → Domain 복원. 도메인이 setter 를 봉인하고 no-arg(랜덤 토큰 생성) + 전체 생성자 두 경로를
     * 가지므로, MapStruct 자동 매핑이 no-arg 를 골라 필드를 못 채우는 것을 막기 위해 전체 생성자로 직접 복원한다.
     */
    default PasswordResetToken toDomain(PasswordResetTokenJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return new PasswordResetToken(
                entity.getId(),
                entity.getUserId(),
                entity.getToken(),
                entity.getExpiryDate(),
                entity.isUsed(),
                entity.getCreatedAt());
    }

    PasswordResetTokenJpaEntity toEntity(PasswordResetToken domain);
}
