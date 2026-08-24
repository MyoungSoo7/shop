package github.lms.lemuel.commoncode.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CommonCodeCreateRequest {

    @NotBlank(message = "그룹코드는 필수입니다.")
    private String groupCode;

    @NotBlank(message = "코드는 필수입니다.")
    private String code;

    @NotBlank(message = "코드명(label)은 필수입니다.")
    private String label;

    private int sortOrder = 0;

    private String extra1;
}
