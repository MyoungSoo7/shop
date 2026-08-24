package github.lms.lemuel.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    @DisplayName("ok() 는 data·error 가 모두 비어있는 성공 응답")
    void okEmpty() {
        ApiResponse<String> res = ApiResponse.ok();

        assertThat(res.getData()).isNull();
        assertThat(res.getError()).isNull();
    }

    @Test
    @DisplayName("ok(data) 는 페이로드를 담고 error 는 비운다")
    void okWithData() {
        ApiResponse<String> res = ApiResponse.ok("payload");

        assertThat(res.getData()).isEqualTo("payload");
        assertThat(res.getError()).isNull();
    }

    @Test
    @DisplayName("fail() 은 상태코드를 보존하고 Error 본문을 채운다")
    void fail() {
        ResponseEntity<ApiResponse<Object>> res =
                ApiResponse.fail(HttpStatus.BAD_REQUEST, "E001", "잘못된 요청");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiResponse<Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getData()).isNull();
        assertThat(body.getError()).isEqualTo(ApiResponse.Error.of("E001", "잘못된 요청"));
        assertThat(body.getError().errorCode()).isEqualTo("E001");
        assertThat(body.getError().errorMessage()).isEqualTo("잘못된 요청");
    }
}
