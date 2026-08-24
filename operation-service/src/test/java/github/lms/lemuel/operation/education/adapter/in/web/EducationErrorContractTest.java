package github.lms.lemuel.operation.education.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import github.lms.lemuel.OperationServiceApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * education 의 에러 응답이 <b>다른 17개 서비스와 같은 스키마</b>로 나가는지 — 실제 부팅한
 * 프로덕션 컨텍스트에서 검증한다.
 *
 * <p>education 은 스캔 범위를 {@code github.lms.lemuel.operation.education} 으로 한정해서, shared-common 의
 * {@code GlobalExceptionHandler} 가 <b>빈으로 등록되지 않았다</b>. 그래서 상태 코드는 맞는데
 * ({@code 404}/{@code 405}) 본문이 스프링 기본 형태였다 — {@code errorCode} 가 없어 클라이언트가
 * 다른 서비스와 같은 방식으로 파싱할 수 없었다. 2026-08-20 전 서비스 405 점검에서 유일하게
 * 어긋난 서비스로 드러났다.
 *
 * <p>이 테스트를 슬라이스({@code @WebMvcTest} + {@code @Import})로 짜면 안 된다 — 그건 테스트가
 * 스스로 advice 를 꽂아 넣고 통과하는 것이라, 정작 <b>프로덕션 배선</b>이 빠져도 초록이 된다.
 * 여기서 앱 클래스를 통째로 띄우는 이유가 그것이다.
 *
 * <p>인증도 {@code @WithMockUser} 가 아니라 실제 JWT 로 통과한다 — 이 서비스는 인증 실패를
 * (401 이 아니라) 403 으로 돌려주므로, 목 인증이 조용히 새면 "404 를 기대했는데 403" 처럼
 * 원인이 가려진 실패가 난다.
 */
@SpringBootTest(
        classes = OperationServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.datasource.url=jdbc:h2:mem:education-error;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
@AutoConfigureMockMvc
class EducationErrorContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JwtUtil jwtUtil;

    private String adminBearer() {
        return "Bearer " + jwtUtil.generateToken("admin@lemuel.test", "ADMIN", 1L);
    }

    @Test
    @DisplayName("공통 예외 처리기가 프로덕션 컨텍스트에 등록돼 있다")
    void globalExceptionHandlerIsRegistered() {
        assertThat(context.getBeansOfType(GlobalExceptionHandler.class)).isNotEmpty();
    }

    @Test
    @DisplayName("없는 경로는 404 + ENDPOINT_NOT_FOUND — 다른 서비스와 같은 본문 스키마다")
    void unknownPathReturnsCommonSchema() throws Exception {
        mockMvc.perform(get("/admin/education/no-such-endpoint")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.ENDPOINT_NOT_FOUND.code()))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("메서드 불일치는 405 + METHOD_NOT_ALLOWED + Allow 헤더")
    void methodMismatchReturnsCommonSchema() throws Exception {
        mockMvc.perform(delete("/admin/education/courses")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.METHOD_NOT_ALLOWED.code()))
                .andExpect(jsonPath("$.status").value(405));
    }
}
