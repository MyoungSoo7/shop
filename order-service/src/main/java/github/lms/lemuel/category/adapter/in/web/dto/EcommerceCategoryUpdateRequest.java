package github.lms.lemuel.category.adapter.in.web.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EcommerceCategoryUpdateRequest {
    private String name;
    private String slug;
}
