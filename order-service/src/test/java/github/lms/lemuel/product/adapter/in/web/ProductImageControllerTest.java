package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.product.application.service.ProductImageService;
import github.lms.lemuel.product.domain.ImageUpload;
import github.lms.lemuel.product.domain.ProductImage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProductImageController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProductImageControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean ProductImageService imageService;

    private static ProductImage image(Long id, Long productId) {
        ProductImage i = ProductImage.create(productId, "a.jpg", "stored.jpg", "/p", "/u",
                "image/jpeg", 1024L, 100, 100, 0);
        i.assignId(id);
        return i;
    }

    @Test
    @DisplayName("POST /admin/products/{id}/images uploads images")
    void uploadImages() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "a.jpg", "image/jpeg", "data".getBytes());
        when(imageService.uploadImages(eq(1L), any())).thenReturn(List.of(image(10L, 1L)));

        mockMvc.perform(multipart("/admin/products/1/images").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].id").value(10));
    }

    @Test
    @DisplayName("업로드 - MultipartFile 은 web 어댑터에서 ImageUpload 로 변환되어 서비스로 넘어간다")
    void convertsMultipartToDomainUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "a.jpg", "image/jpeg", "data".getBytes());
        when(imageService.uploadImages(eq(1L), any())).thenReturn(List.of(image(10L, 1L)));

        mockMvc.perform(multipart("/admin/products/1/images").file(file))
                .andExpect(status().isCreated());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ImageUpload>> captor = ArgumentCaptor.forClass(List.class);
        verify(imageService).uploadImages(eq(1L), captor.capture());
        ImageUpload converted = captor.getValue().get(0);
        assertThat(converted.getOriginalFilename()).isEqualTo("a.jpg");
        assertThat(converted.getContentType()).isEqualTo("image/jpeg");
        assertThat(converted.getSizeBytes()).isEqualTo(4L);
        assertThat(converted.getContent()).isEqualTo("data".getBytes());
    }

    @Test
    @DisplayName("업로드 - 허용되지 않은 형식은 도메인 정책이 막고 400 (서비스 미호출)")
    void rejectsDisallowedTypeAtBoundary() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "a.gif", "image/gif", "data".getBytes());

        mockMvc.perform(multipart("/admin/products/1/images").file(file))
                .andExpect(status().isBadRequest());

        verify(imageService, never()).uploadImages(any(), any());
    }

    @Test
    @DisplayName("업로드 - 5MB 초과는 도메인 정책이 막고 400 (서비스 미호출)")
    void rejectsOversizeAtBoundary() throws Exception {
        byte[] tooBig = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("files", "big.jpg", "image/jpeg", tooBig);

        mockMvc.perform(multipart("/admin/products/1/images").file(file))
                .andExpect(status().isBadRequest());

        verify(imageService, never()).uploadImages(any(), any());
    }

    @Test
    @DisplayName("GET /admin/products/{id}/images returns all images")
    void getImages() throws Exception {
        when(imageService.getProductImages(1L)).thenReturn(List.of(image(10L, 1L)));

        mockMvc.perform(get("/admin/products/1/images"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10));
    }

    @Test
    @DisplayName("PATCH /admin/products/{id}/images/{imageId}/primary sets primary")
    void setPrimaryImage() throws Exception {
        ProductImage img = image(10L, 1L);
        img.markAsPrimary();
        when(imageService.setPrimaryImage(1L, 10L)).thenReturn(img);

        mockMvc.perform(patch("/admin/products/1/images/10/primary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPrimary").value(true));
    }

    @Test
    @DisplayName("PATCH /admin/products/{id}/images/reorder reorders images")
    void reorderImages() throws Exception {
        when(imageService.reorderImages(eq(1L), any())).thenReturn(List.of(image(10L, 1L), image(11L, 1L)));

        mockMvc.perform(patch("/admin/products/1/images/reorder")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"imageIds":[11,10]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("DELETE /admin/products/{id}/images/{imageId} deletes image")
    void deleteImage() throws Exception {
        mockMvc.perform(delete("/admin/products/1/images/10"))
                .andExpect(status().isNoContent());

        verify(imageService).deleteImage(1L, 10L);
    }
}
