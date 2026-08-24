package github.lms.lemuel.category.adapter.in.web;

import github.lms.lemuel.category.application.port.in.CheckCategoryCountIntegrityUseCase;
import github.lms.lemuel.category.application.service.EcommerceCategoryService;
import github.lms.lemuel.category.domain.EcommerceCategory;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        PublicEcommerceCategoryController.class,
        AdminEcommerceCategoryController.class
})
@AutoConfigureMockMvc(addFilters = false)
class EcommerceCategoryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean EcommerceCategoryService categoryService;
    /** 관리 컨트롤러가 정합 점검 유스케이스를 함께 받는다 — 슬라이스가 뜨려면 여기도 채워야 한다. */
    @MockitoBean CheckCategoryCountIntegrityUseCase countIntegrityUseCase;

    @Test
    @DisplayName("GET /categories returns active category tree")
    void getActiveCategories() throws Exception {
        EcommerceCategory root = category(1L, "Books", "books");
        root.addChild(category(2L, "Fiction", "fiction"));
        when(categoryService.getActiveCategoriesTree()).thenReturn(List.of(root));

        mockMvc.perform(get("/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("books"))
                .andExpect(jsonPath("$[0].children[0].slug").value("fiction"));
    }

    @Test
    @DisplayName("GET /categories/{slug} returns category without children")
    void getCategoryBySlug() throws Exception {
        when(categoryService.getCategoryBySlug("books")).thenReturn(category(1L, "Books", "books"));

        mockMvc.perform(get("/categories/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Books"))
                .andExpect(jsonPath("$.children.length()").value(0));
    }

    @Test
    @DisplayName("POST /admin/categories creates category")
    void createCategory() throws Exception {
        when(categoryService.createCategory(eq("Games"), eq("games"), any(), eq(3)))
                .thenReturn(category(10L, "Games", "games"));

        mockMvc.perform(post("/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Games","slug":"games","sortOrder":3}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.slug").value("games"));
    }

    @Test
    @DisplayName("DELETE /admin/categories/{id} returns no content")
    void deleteCategory() throws Exception {
        mockMvc.perform(delete("/admin/categories/10"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /admin/categories/{id} propagates domain validation as 400")
    void deleteCategoryFailure() throws Exception {
        doThrow(new IllegalArgumentException("missing category"))
                .when(categoryService).deleteCategory(999L);

        mockMvc.perform(delete("/admin/categories/999"))
                .andExpect(status().isBadRequest());
    }

    private static EcommerceCategory category(Long id, String name, String slug) {
        EcommerceCategory category = EcommerceCategory.createRoot(name, slug, 0);
        category.assignId(id);
        return category;
    }
}
