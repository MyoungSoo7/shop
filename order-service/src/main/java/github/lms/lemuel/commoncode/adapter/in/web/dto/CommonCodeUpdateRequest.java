package github.lms.lemuel.commoncode.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CommonCodeUpdateRequest {

    @NotBlank(message = "코드명(label)은 필수입니다.")
    private String label;

    private int sortOrder = 0;

    private boolean active = true;

    private String extra1;
}
