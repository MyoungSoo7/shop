package github.lms.lemuel.rbac.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "role_permissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RolePermissionJpaEntity {

    @EmbeddedId
    private RolePermissionId id;
}
