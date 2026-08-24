package github.lms.lemuel.category.adapter.out.persistence;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ProductEcommerceCategoryId implements Serializable {
    private Long productId;
    private Long categoryId;
}
