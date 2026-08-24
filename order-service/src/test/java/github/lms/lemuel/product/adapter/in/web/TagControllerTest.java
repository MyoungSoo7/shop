package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.product.application.port.in.TagUseCase;
import github.lms.lemuel.product.domain.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TagController.class)
@AutoConfigureMockMvc(addFilters = false)
class TagControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean TagUseCase tagUseCase;

    private static Tag tag(Long id, String name) {
        Tag t = Tag.create(name, "#FF0000");
        t.assignId(id);
        return t;
    }

    @Test
    @DisplayName("POST /api/tags creates tag")
    void createTag() throws Exception {
        when(tagUseCase.createTag("신상", "#FF0000")).thenReturn(tag(1L, "신상"));

        mockMvc.perform(post("/api/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"신상","color":"#FF0000"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("신상"));
    }

    @Test
    @DisplayName("GET /api/tags/{id} returns tag")
    void getTag() throws Exception {
        when(tagUseCase.getTagById(1L)).thenReturn(tag(1L, "신상"));

        mockMvc.perform(get("/api/tags/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("GET /api/tags returns all tags")
    void getAllTags() throws Exception {
        when(tagUseCase.getAllTags()).thenReturn(List.of(tag(1L, "신상")));

        mockMvc.perform(get("/api/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("신상"));
    }

    @Test
    @DisplayName("GET /api/tags/product/{productId} returns tags for product")
    void getTagsByProduct() throws Exception {
        when(tagUseCase.getTagsByProductId(5L)).thenReturn(List.of(tag(1L, "신상")));

        mockMvc.perform(get("/api/tags/product/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    @DisplayName("PUT /api/tags/{id} updates tag")
    void updateTag() throws Exception {
        when(tagUseCase.updateTag(1L, "베스트", "#00FF00")).thenReturn(tag(1L, "베스트"));

        mockMvc.perform(put("/api/tags/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"베스트","color":"#00FF00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("베스트"));
    }

    @Test
    @DisplayName("DELETE /api/tags/{id} deletes tag")
    void deleteTag() throws Exception {
        mockMvc.perform(delete("/api/tags/1"))
                .andExpect(status().isNoContent());

        verify(tagUseCase).deleteTag(1L);
    }

    @Test
    @DisplayName("POST /api/tags/product/{productId}/tag/{tagId} attaches tag")
    void addTagToProduct() throws Exception {
        mockMvc.perform(post("/api/tags/product/5/tag/1"))
                .andExpect(status().isOk());

        verify(tagUseCase).addTagToProduct(5L, 1L);
    }

    @Test
    @DisplayName("DELETE /api/tags/product/{productId}/tag/{tagId} detaches tag")
    void removeTagFromProduct() throws Exception {
        mockMvc.perform(delete("/api/tags/product/5/tag/1"))
                .andExpect(status().isOk());

        verify(tagUseCase).removeTagFromProduct(5L, 1L);
    }
}
