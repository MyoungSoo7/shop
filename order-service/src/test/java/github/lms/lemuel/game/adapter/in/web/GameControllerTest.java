package github.lms.lemuel.game.adapter.in.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class GameControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 실제 템플릿 엔진 없이 standalone 모드 — suffix 를 달아 순환 뷰 경로 이슈 회피
        InternalResourceViewResolver viewResolver = new InternalResourceViewResolver();
        viewResolver.setPrefix("/WEB-INF/views/");
        viewResolver.setSuffix(".html");

        mockMvc = MockMvcBuilders.standaloneSetup(new GameController())
                .setViewResolvers(viewResolver)
                .build();
    }

    @Test @DisplayName("GET /games/baduk — baduk 뷰 이름을 반환한다")
    void baduk_returnsView() throws Exception {
        mockMvc.perform(get("/games/baduk"))
                .andExpect(status().isOk())
                .andExpect(view().name("baduk"));
    }

    @Test @DisplayName("GET /games/omok — omok 뷰 이름을 반환한다")
    void omok_returnsView() throws Exception {
        mockMvc.perform(get("/games/omok"))
                .andExpect(status().isOk())
                .andExpect(view().name("omok"));
    }
}
