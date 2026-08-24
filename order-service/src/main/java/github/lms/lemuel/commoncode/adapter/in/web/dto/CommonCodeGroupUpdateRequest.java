package github.lms.lemuel.commoncode.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CommonCodeGroupUpdateRequest {

    @NotBlank(message = "그룹명은 필수입니다.")
    private String name;

    private String description;

    private boolean active = true;
}
