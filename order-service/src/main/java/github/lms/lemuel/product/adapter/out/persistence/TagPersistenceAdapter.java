package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.application.port.out.LoadTagPort;
import github.lms.lemuel.product.application.port.out.SaveTagPort;
import github.lms.lemuel.product.domain.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TagPersistenceAdapter implements SaveTagPort, LoadTagPort {

    private final SpringDataTagRepository tagRepository;
    private final SpringDataProductTagRepository productTagRepository;
    private final TagMapper tagMapper;

    @Override
    public Tag save(Tag tag) {
        TagJpaEntity jpaEntity = tagMapper.toJpaEntity(tag);
        TagJpaEntity saved = tagRepository.save(jpaEntity);
        return tagMapper.toDomainEntity(saved);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        tagRepository.deleteById(id);
    }

    @Override
    public void addTagToProduct(Long productId, Long tagId) {
        ProductTagJpaEntity productTag = new ProductTagJpaEntity(productId, tagId, LocalDateTime.now());
        productTagRepository.save(productTag);
    }

    @Override
    @Transactional
    public void removeTagFromProduct(Long productId, Long tagId) {
        productTagRepository.deleteByProductIdAndTagId(productId, tagId);
    }

    @Override
    @Transactional
    public void removeAllTagsFromProduct(Long productId) {
        productTagRepository.deleteByProductId(productId);
    }

    @Override
    public Optional<Tag> findById(Long id) {
        return tagRepository.findById(id)
                .map(tagMapper::toDomainEntity);
    }

    @Override
    public Optional<Tag> findByName(String name) {
        return tagRepository.findByName(name)
                .map(tagMapper::toDomainEntity);
    }

    @Override
    public List<Tag> findAll() {
        return tagRepository.findAll().stream()
                .map(tagMapper::toDomainEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<Tag> findByProductId(Long productId) {
        return tagRepository.findByProductId(productId).stream()
                .map(tagMapper::toDomainEntity)
                .collect(Collectors.toList());
    }
}
