package github.lms.lemuel.product.application.service;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import github.lms.lemuel.product.application.port.out.LoadProductImagePort;
import github.lms.lemuel.product.application.port.out.SaveProductImagePort;
import github.lms.lemuel.product.application.port.out.StoreProductImagePort;
import github.lms.lemuel.product.application.port.out.StoredImageFile;
import github.lms.lemuel.product.domain.ImageUpload;
import github.lms.lemuel.product.domain.ProductImage;
import github.lms.lemuel.product.domain.exception.ImageStorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductImageServiceTest {

    @Mock LoadProductImagePort loadPort;
    @Mock SaveProductImagePort savePort;
    @Mock StoreProductImagePort storagePort;
    @InjectMocks ProductImageService service;

    private ProductImage image(Long id, Long productId) {
        ProductImage i = ProductImage.create(productId, "a.jpg", "stored.jpg", "/p", "/u",
                "image/jpeg", 1024L, 100, 100, 0);
        i.assignId(id);
        return i;
    }

    @Test @DisplayName("setPrimaryImage - 기존 대표 해제 + 새 대표 지정")
    void setPrimary_swaps() {
        ProductImage current = image(1L, 10L);
        current.markAsPrimary();
        ProductImage target = image(2L, 10L);
        when(loadPort.findByIdNotDeleted(2L)).thenReturn(Optional.of(target));
        when(loadPort.findPrimaryImageByProductId(10L)).thenReturn(Optional.of(current));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProductImage result = service.setPrimaryImage(10L, 2L);

        assertThat(result.getIsPrimary()).isTrue();
        assertThat(current.getIsPrimary()).isFalse();
        verify(savePort, times(2)).save(any());
    }

    @Test @DisplayName("setPrimaryImage - 이미지가 다른 상품이면 예외")
    void setPrimary_wrongProduct() {
        ProductImage img = image(1L, 99L);
        when(loadPort.findByIdNotDeleted(1L)).thenReturn(Optional.of(img));

        assertThatThrownBy(() -> service.setPrimaryImage(10L, 1L))
                .isInstanceOf(ProductInvariantViolationException.class);
    }

    @Test @DisplayName("getProductImages - 포트에 위임")
    void getProductImages() {
        ProductImage a = image(1L, 10L);
        ProductImage b = image(2L, 10L);
        when(loadPort.findByProductIdNotDeleted(10L)).thenReturn(List.of(a, b));

        List<ProductImage> result = service.getProductImages(10L);

        assertThat(result).hasSize(2);
    }

    @Test @DisplayName("getPrimaryImage - 없으면 null")
    void getPrimary_null() {
        when(loadPort.findPrimaryImageByProductId(10L)).thenReturn(Optional.empty());
        assertThat(service.getPrimaryImage(10L)).isNull();
    }

    @Test @DisplayName("getPrimaryImageUrl - 없으면 null")
    void getPrimaryUrl_null() {
        when(loadPort.findPrimaryImageByProductId(10L)).thenReturn(Optional.empty());
        assertThat(service.getPrimaryImageUrl(10L)).isNull();
    }

    @Test @DisplayName("getPrimaryImageUrl - 있으면 url 반환")
    void getPrimaryUrl_present() {
        ProductImage i = image(1L, 10L);
        when(loadPort.findPrimaryImageByProductId(10L)).thenReturn(Optional.of(i));
        assertThat(service.getPrimaryImageUrl(10L)).isEqualTo("/u");
    }

    @Test @DisplayName("reorderImages - 각 이미지에 순서 반영")
    void reorder() {
        ProductImage a = image(1L, 10L);
        ProductImage b = image(2L, 10L);
        when(loadPort.findByIdNotDeleted(1L)).thenReturn(Optional.of(a));
        when(loadPort.findByIdNotDeleted(2L)).thenReturn(Optional.of(b));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reorderImages(10L, List.of(2L, 1L));

        assertThat(b.getOrderIndex()).isZero();
        assertThat(a.getOrderIndex()).isEqualTo(1);
    }

    @Test @DisplayName("deleteImage - 소유 상품 불일치 시 예외")
    void deleteImage_wrongProduct() {
        ProductImage img = image(1L, 99L);
        when(loadPort.findByIdNotDeleted(1L)).thenReturn(Optional.of(img));

        assertThatThrownBy(() -> service.deleteImage(10L, 1L))
                .isInstanceOf(ProductInvariantViolationException.class);
        verify(storagePort, never()).delete(any());
    }

    @Test @DisplayName("deleteImage - 대표였다면 다른 이미지를 대표로 승격")
    void deleteImage_promotesNext() {
        ProductImage deleted = image(1L, 10L);
        deleted.markAsPrimary();
        ProductImage remaining = image(2L, 10L);
        when(loadPort.findByIdNotDeleted(1L)).thenReturn(Optional.of(deleted));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadPort.findByProductIdNotDeleted(10L)).thenReturn(List.of(remaining));

        service.deleteImage(10L, 1L);

        assertThat(remaining.getIsPrimary()).isTrue();
        verify(storagePort).delete("/p");
    }

    private ImageUpload upload(String contentType, long size, String name) {
        return ImageUpload.of(name, contentType, size, new byte[]{1, 2, 3});
    }

    @Test @DisplayName("uploadImages - 첫 이미지는 대표 이미지로 자동 승격")
    void upload_firstBecomesPrimary() {
        ImageUpload up = upload("image/jpeg", 1024L, "a.jpg");
        when(loadPort.countByProductIdNotDeleted(10L)).thenReturn(0L);
        when(storagePort.store(10L, up)).thenReturn(
                new StoredImageFile("s.jpg", "/p/s.jpg", "/u/s.jpg", 100, 100, "chk"));
        ProductImage[] holder = new ProductImage[1];
        when(savePort.save(any())).thenAnswer(inv -> {
            ProductImage p = inv.getArgument(0);
            if (p.getId() == null) p.assignId(1L);
            holder[0] = p;
            return p;
        });
        when(loadPort.findByIdNotDeleted(1L)).thenAnswer(inv -> Optional.of(holder[0]));
        when(loadPort.findPrimaryImageByProductId(10L)).thenReturn(Optional.empty());

        List<ProductImage> result = service.uploadImages(10L, List.of(up));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getChecksum()).isEqualTo("chk");
        assertThat(result.get(0).getIsPrimary()).isTrue();
        verify(storagePort).store(10L, up);
    }

    @Test @DisplayName("uploadImages - 기존 이미지가 있으면 대표 승격 없이 뒤 순번으로 추가")
    void upload_appendsAfterExisting() {
        ImageUpload up = upload("image/png", 2048L, "b.png");
        when(loadPort.countByProductIdNotDeleted(10L)).thenReturn(3L);
        when(storagePort.store(10L, up)).thenReturn(
                new StoredImageFile("s.png", "/p/s.png", "/u/s.png", 50, 50, "c2"));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<ProductImage> result = service.uploadImages(10L, List.of(up));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOrderIndex()).isEqualTo(3);
        verify(loadPort, never()).findPrimaryImageByProductId(anyLong());
    }

    @Test @DisplayName("uploadImages - 저장 실패(ImageStorageException)는 그대로 전파되고 DB 저장 안 함")
    void upload_storageFailurePropagates() {
        ImageUpload up = upload("image/jpeg", 1024L, "a.jpg");
        when(loadPort.countByProductIdNotDeleted(10L)).thenReturn(0L);
        when(storagePort.store(10L, up))
                .thenThrow(new ImageStorageException("이미지 저장 실패", new IOException("disk full")));

        assertThatThrownBy(() -> service.uploadImages(10L, List.of(up)))
                .isInstanceOf(ImageStorageException.class)
                .hasRootCauseInstanceOf(IOException.class);
        verify(savePort, never()).save(any());
    }

    @Test @DisplayName("uploadImages - 여러 건은 순번이 이어진다")
    void upload_multipleKeepsOrder() {
        ImageUpload first = upload("image/jpeg", 1024L, "a.jpg");
        ImageUpload second = upload("image/png", 2048L, "b.png");
        when(loadPort.countByProductIdNotDeleted(10L)).thenReturn(1L);
        when(storagePort.store(10L, first)).thenReturn(
                new StoredImageFile("s1.jpg", "/p/1", "/u/1", 10, 10, "c1"));
        when(storagePort.store(10L, second)).thenReturn(
                new StoredImageFile("s2.png", "/p/2", "/u/2", 20, 20, "c2"));
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<ProductImage> result = service.uploadImages(10L, List.of(first, second));

        assertThat(result).extracting(ProductImage::getOrderIndex).containsExactly(1, 2);
    }
}
