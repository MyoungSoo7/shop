package github.lms.lemuel.commoncode.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CommonCodeGroupCreateRequest {

    @NotBlank(message = "그룹코드는 필수입니다.")
    private String groupCode;

    @NotBlank(message = "그룹명은 필수입니다.")
    private String name;

    private String description;
}
