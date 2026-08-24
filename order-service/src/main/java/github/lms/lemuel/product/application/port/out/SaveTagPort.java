package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.Tag;

public interface SaveTagPort {
    Tag save(Tag tag);
    void delete(Long id);
    void addTagToProduct(Long productId, Long tagId);
    void removeTagFromProduct(Long productId, Long tagId);
    void removeAllTagsFromProduct(Long productId);
}
