package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.Tag;

import java.util.List;

public interface TagUseCase {
    Tag createTag(String name, String color);
    Tag getTagById(Long id);
    List<Tag> getAllTags();
    List<Tag> getTagsByProductId(Long productId);
    Tag updateTag(Long id, String name, String color);
    void deleteTag(Long id);
    void addTagToProduct(Long productId, Long tagId);
    void removeTagFromProduct(Long productId, Long tagId);
}
