package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.TagUseCase;
import github.lms.lemuel.product.application.port.out.LoadTagPort;
import github.lms.lemuel.product.application.port.out.SaveTagPort;
import github.lms.lemuel.product.domain.Tag;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagService implements TagUseCase {

    private final SaveTagPort saveTagPort;
    private final LoadTagPort loadTagPort;

    @Override
    @Transactional
    @CacheEvict(value = "tags", allEntries = true)
    public Tag createTag(String name, String color) {
        Tag tag = Tag.create(name, color);
        return saveTagPort.save(tag);
    }

    @Override
    public Tag getTagById(Long id) {
        return loadTagPort.findById(id)
                .orElseThrow(() -> new ProductInvariantViolationException("Tag not found: " + id));
    }

    @Override
    @Cacheable("tags")
    public List<Tag> getAllTags() {
        return loadTagPort.findAll();
    }

    @Override
    @Cacheable(value = "tags", key = "'product:' + #productId")
    public List<Tag> getTagsByProductId(Long productId) {
        return loadTagPort.findByProductId(productId);
    }

    @Override
    @Transactional
    @CacheEvict(value = "tags", allEntries = true)
    public Tag updateTag(Long id, String name, String color) {
        Tag tag = getTagById(id);
        tag.updateInfo(name, color);
        return saveTagPort.save(tag);
    }

    @Override
    @Transactional
    @CacheEvict(value = "tags", allEntries = true)
    public void deleteTag(Long id) {
        saveTagPort.delete(id);
    }

    @Override
    @Transactional
    @CacheEvict(value = "tags", key = "'product:' + #productId")
    public void addTagToProduct(Long productId, Long tagId) {
        // 태그 존재 여부 확인
        getTagById(tagId);
        saveTagPort.addTagToProduct(productId, tagId);
    }

    @Override
    @Transactional
    @CacheEvict(value = "tags", key = "'product:' + #productId")
    public void removeTagFromProduct(Long productId, Long tagId) {
        saveTagPort.removeTagFromProduct(productId, tagId);
    }
}
