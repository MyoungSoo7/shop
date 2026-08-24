package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.out.LoadProductImagePort;
import github.lms.lemuel.product.application.port.out.SaveProductImagePort;
import github.lms.lemuel.product.application.port.out.StoreProductImagePort;
import github.lms.lemuel.product.application.port.out.StoredImageFile;
import github.lms.lemuel.product.domain.ImageUpload;
import github.lms.lemuel.product.domain.ProductImage;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductImageService {

    private final LoadProductImagePort loadPort;
    private final SaveProductImagePort savePort;
    private final StoreProductImagePort storagePort;

    /**
     * 이미지 다중 업로드.
     *
     * <p>형식·크기 정책은 {@link ImageUpload} 생성 시점에 이미 강제되었다 — 여기서 재검증하지 않는다.
     * 저장 실패는 포트가 {@code ImageStorageException} 으로 번역해 올리므로 이 메서드에는
     * {@code try-catch} 도 {@code throws} 도 없다.
     */
    @Transactional
    public List<ProductImage> uploadImages(Long productId, List<ImageUpload> uploads) {
        List<ProductImage> images = new ArrayList<>();

        long currentCount = loadPort.countByProductIdNotDeleted(productId);
        int orderIndex = (int) currentCount;

        for (ImageUpload upload : uploads) {
            StoredImageFile stored = storagePort.store(productId, upload);

            ProductImage image = ProductImage.create(
                    productId,
                    upload.getOriginalFilename(),
                    stored.storedFileName(),
                    stored.filePath(),
                    stored.url(),
                    upload.getContentType(),
                    upload.getSizeBytes(),
                    stored.width(),
                    stored.height(),
                    orderIndex++
            );
            image.assignChecksum(stored.checksum());

            images.add(savePort.save(image));
        }

        if (currentCount == 0 && !images.isEmpty()) {
            setPrimaryImage(productId, images.get(0).getId());
        }

        return images;
    }

    @Transactional
    public ProductImage setPrimaryImage(Long productId, Long imageId) {
        ProductImage image = getImageById(imageId);

        if (!image.getProductId().equals(productId)) {
            throw new ProductInvariantViolationException("Image does not belong to product");
        }

        loadPort.findPrimaryImageByProductId(productId).ifPresent(current -> {
            current.unmarkAsPrimary();
            savePort.save(current);
        });

        image.markAsPrimary();
        return savePort.save(image);
    }

    @Transactional
    public List<ProductImage> reorderImages(Long productId, List<Long> imageIds) {
        List<ProductImage> images = new ArrayList<>();

        for (int i = 0; i < imageIds.size(); i++) {
            Long imageId = imageIds.get(i);
            ProductImage image = getImageById(imageId);

            if (!image.getProductId().equals(productId)) {
                throw new ProductInvariantViolationException("Image does not belong to product");
            }

            image.changeOrder(i);
            images.add(savePort.save(image));
        }

        return images;
    }

    @Transactional
    public void deleteImage(Long productId, Long imageId) {
        ProductImage image = getImageById(imageId);

        if (!image.getProductId().equals(productId)) {
            throw new ProductInvariantViolationException("Image does not belong to product");
        }

        boolean wasPrimary = image.getIsPrimary();

        image.softDelete();
        savePort.save(image);

        storagePort.delete(image.getFilePath());

        if (wasPrimary) {
            List<ProductImage> remaining = loadPort.findByProductIdNotDeleted(productId);
            if (!remaining.isEmpty()) {
                ProductImage newPrimary = remaining.get(0);
                newPrimary.markAsPrimary();
                savePort.save(newPrimary);
            }
        }
    }

    public List<ProductImage> getProductImages(Long productId) {
        return loadPort.findByProductIdNotDeleted(productId);
    }

    public ProductImage getPrimaryImage(Long productId) {
        return loadPort.findPrimaryImageByProductId(productId).orElse(null);
    }

    public String getPrimaryImageUrl(Long productId) {
        ProductImage primary = getPrimaryImage(productId);
        return primary != null ? primary.getUrl() : null;
    }

    private ProductImage getImageById(Long imageId) {
        return loadPort.findByIdNotDeleted(imageId)
                .orElseThrow(() -> new ProductInvariantViolationException("Image not found: " + imageId));
    }
}
