package github.lms.lemuel.common.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * exception 패키지 단위 검증 — ErrorCode/ErrorResponse/BusinessException 값 객체와
 * GlobalExceptionHandler 의 예외→응답 매핑을 Spring MVC 부팅 없이 직접 호출로 커버한다.
 */
class ExceptionHandlingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ─── ErrorCode ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ErrorCode: 모든 상수가 상태/코드/기본메시지를 제공한다")
    void errorCodeAccessors() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.status()).isNotNull();
            assertThat(code.code()).isEqualTo(code.name());
            assertThat(code.defaultMessage()).isNotBlank();
        }
        assertThat(ErrorCode.INVALID_ARGUMENT.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.ORDER_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.INTERNAL_ERROR.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ─── ErrorResponse ────────────────────────────────────────────────────────

    @Test
    @DisplayName("ErrorResponse: 3종 팩토리가 상태/코드/필드를 채운다")
    void errorResponseFactories() {
        ErrorResponse basic = ErrorResponse.of(HttpStatus.NOT_FOUND, "X", "msg");
        assertThat(basic.status()).isEqualTo(404);
        assertThat(basic.error()).isEqualTo("Not Found");
        assertThat(basic.errorCode()).isEqualTo("X");
        assertThat(basic.message()).isEqualTo("msg");
        assertThat(basic.details()).isNull();
        assertThat(basic.errors()).isNull();
        assertThat(basic.timestamp()).isNotNull();

        ErrorResponse withDetails = ErrorResponse.of(HttpStatus.CONFLICT, "Y", "m", Map.of("stock", 3));
        assertThat(withDetails.details()).containsEntry("stock", 3);

        ErrorResponse validation = ErrorResponse.validation(
                HttpStatus.BAD_REQUEST, "Z", "bad", Map.of("field", "required"));
        assertThat(validation.errors()).containsEntry("field", "required");
    }

    // ─── BusinessException ───────────────────────────────────────────────────

    @Test
    @DisplayName("BusinessException: 4개 생성자 모두 코드/메시지/details 를 보존한다")
    void businessExceptionConstructors() {
        BusinessException e1 = new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        assertThat(e1.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND);
        assertThat(e1.getMessage()).isEqualTo(ErrorCode.ORDER_NOT_FOUND.defaultMessage());
        assertThat(e1.getDetails()).isNull();

        BusinessException e2 = new BusinessException(ErrorCode.INVALID_ARGUMENT, "커스텀");
        assertThat(e2.getMessage()).isEqualTo("커스텀");

        BusinessException e3 = new BusinessException(ErrorCode.INSUFFICIENT_STOCK, "부족", Map.of("need", 5));
        assertThat(e3.getDetails()).containsEntry("need", 5);

        Throwable cause = new RuntimeException("root");
        BusinessException e4 = new BusinessException(ErrorCode.REFUND_ERROR, null, cause);
        assertThat(e4.getMessage()).isEqualTo(ErrorCode.REFUND_ERROR.defaultMessage());
        assertThat(e4.getCause()).isSameAs(cause);
        assertThat(e4.getDetails()).isNull();
    }

    // ─── GlobalExceptionHandler ──────────────────────────────────────────────

    @Test
    @DisplayName("handleBusiness: 4xx 코드는 상태와 코드/메시지를 그대로 전달")
    void handleBusiness4xx() {
        ResponseEntity<ErrorResponse> res =
                handler.handleBusiness(new BusinessException(ErrorCode.ORDER_NOT_FOUND, "없음", Map.of("id", 1)));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().errorCode()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(res.getBody().message()).isEqualTo("없음");
        assertThat(res.getBody().details()).containsEntry("id", 1);
    }

    @Test
    @DisplayName("handleBusiness: 5xx 코드도 매핑된다(스택트레이스 로깅 경로)")
    void handleBusiness5xx() {
        ResponseEntity<ErrorResponse> res =
                handler.handleBusiness(new BusinessException(ErrorCode.REFUND_ERROR));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().errorCode()).isEqualTo("REFUND_ERROR");
    }

    @Test
    @DisplayName("handleMethodArgumentNotValid: 필드 오류 맵과 결합 메시지 생성")
    void handleMethodArgumentNotValid() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "obj");
        binding.addError(new FieldError("obj", "name", "이름 필수"));
        binding.addError(new FieldError("obj", "age", "나이 필수"));
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(binding);

        ResponseEntity<ErrorResponse> res = handler.handleMethodArgumentNotValid(ex);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().message()).contains("이름 필수").contains("나이 필수");
        assertThat(res.getBody().errors()).containsEntry("name", "이름 필수").containsEntry("age", "나이 필수");
    }

    @Test
    @DisplayName("handleConstraintViolation: 경로에서 필드명만 추출해 메시지 생성")
    void handleConstraintViolation() {
        ConstraintViolation<?> v = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("method.arg0.amount");
        when(v.getPropertyPath()).thenReturn(path);
        when(v.getMessage()).thenReturn("0보다 커야 함");
        ConstraintViolationException ex = new ConstraintViolationException(Set.of(v));

        ResponseEntity<ErrorResponse> res = handler.handleConstraintViolation(ex);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().message()).isEqualTo("amount: 0보다 커야 함");
        assertThat(res.getBody().errorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER.code());
    }

    @Test
    @DisplayName("handleMissingServletRequestParameter: 파라미터명 required 메시지")
    void handleMissingParam() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("page", "int");
        ResponseEntity<ErrorResponse> res = handler.handleMissingServletRequestParameter(ex);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().message()).isEqualTo("page parameter is required");
    }

    @Test
    @DisplayName("handleIllegalArgument / handleIllegalState → 400")
    void handleIllegalArgAndState() {
        ResponseEntity<ErrorResponse> arg = handler.handleIllegalArgument(new IllegalArgumentException("잘못"));
        assertThat(arg.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(arg.getBody().errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT.code());

        ResponseEntity<ErrorResponse> state = handler.handleIllegalState(new IllegalStateException("상태"));
        assertThat(state.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(state.getBody().errorCode()).isEqualTo(ErrorCode.INVALID_STATE.code());
    }

    @Test
    @DisplayName("handleNoResourceFound: 없는 경로 → 404 ENDPOINT_NOT_FOUND (500 아님)")
    void handleNoResourceFound() {
        // (httpMethod, requestPath, resourcePath) — 운영에서 실제로 터진 조합 그대로
        NoResourceFoundException ex =
                new NoResourceFoundException(HttpMethod.GET, "/actuator/health", "actuator/health");
        assertThat(ex.getResourcePath()).isEqualTo("actuator/health");

        ResponseEntity<ErrorResponse> res = handler.handleNoResourceFound(ex);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().errorCode()).isEqualTo(ErrorCode.ENDPOINT_NOT_FOUND.code());
        assertThat(res.getBody().message()).isEqualTo(ErrorCode.ENDPOINT_NOT_FOUND.defaultMessage());
    }

    @Test
    @DisplayName("디스패치가 고르는 핸들러도 404 다 — catch-all(500)이 다시 가로채지 못한다")
    void noResourceFoundIsNotSwallowedByCatchAll() throws Exception {
        NoResourceFoundException ex =
                new NoResourceFoundException(HttpMethod.GET, "/actuator/health", "actuator/health");

        // 핸들러를 직접 부르는 테스트는 @ExceptionHandler 매핑이 사라져도 통과한다 — 실제 회귀는
        // "누가 이 예외를 잡는가" 에서 나므로, 런타임과 같은 선택기로 고른 뒤 그 결과를 검증한다.
        Method resolved = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class).resolveMethod(ex);
        assertThat(resolved).isNotNull();

        @SuppressWarnings("unchecked")
        ResponseEntity<ErrorResponse> res = (ResponseEntity<ErrorResponse>) resolved.invoke(handler, ex);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().errorCode()).isEqualTo(ErrorCode.ENDPOINT_NOT_FOUND.code());
    }

    @Test
    @DisplayName("지원하지 않는 메서드는 405 다 — Allow 헤더로 허용 메서드를 알린다")
    void methodNotSupportedMapsTo405() throws Exception {
        // 2026-08-19 실측: POST 전용인 /api/organizations 에 GET 을 치면 500 이 나왔다.
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("GET", List.of("POST"));

        Method resolved = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class).resolveMethod(ex);
        assertThat(resolved).isNotNull();

        @SuppressWarnings("unchecked")
        ResponseEntity<ErrorResponse> res = (ResponseEntity<ErrorResponse>) resolved.invoke(handler, ex);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.getBody().errorCode()).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED.code());
        // 405 는 Allow 헤더가 규격 필수다(RFC 9110) — 없으면 클라이언트가 무엇을 써야 할지 모른다.
        assertThat(res.getHeaders().getAllow()).containsExactly(HttpMethod.POST);
    }

    @Test
    @DisplayName("ENDPOINT_NOT_FOUND 는 도메인 XXX_NOT_FOUND 와 구분되는 별도 코드다")
    void endpointNotFoundIsDistinctFromDomainNotFound() {
        assertThat(ErrorCode.ENDPOINT_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.ENDPOINT_NOT_FOUND).isNotEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("handleMaxUploadSizeExceeded: 업로드 초과 → 413 PAYLOAD_TOO_LARGE (500 아님)")
    void handleMaxUploadSizeExceeded() throws Exception {
        // 서블릿이 멀티파트를 끊는 예외라 컨트롤러도 도메인도 실행되지 않는다 — 도메인 크기 검증
        // (order-service ImageUpload.MAX_SIZE_BYTES)으로는 잡을 수 없고, 이 매핑이 유일한 방어선이다.
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(5L * 1024 * 1024);

        // 직접 호출은 @ExceptionHandler 매핑이 사라져도 통과한다. 실제 회귀("catch-all 이 500 으로
        // 가져감")를 잡으려면 런타임과 같은 선택기로 고른 메서드를 검증해야 한다.
        Method resolved = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class).resolveMethod(ex);
        assertThat(resolved).isNotNull();

        @SuppressWarnings("unchecked")
        ResponseEntity<ErrorResponse> res = (ResponseEntity<ErrorResponse>) resolved.invoke(handler, ex);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(res.getBody().errorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE.code());
        assertThat(res.getBody().status()).isEqualTo(413);
    }

    @Test
    @DisplayName("PAYLOAD_TOO_LARGE 는 400 이 아니라 413 이다 — 요청이 틀린 게 아니라 크다")
    void payloadTooLargeIs413NotBadRequest() {
        assertThat(ErrorCode.PAYLOAD_TOO_LARGE.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(ErrorCode.PAYLOAD_TOO_LARGE.status()).isNotEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("handleException: 예상치 못한 예외 → 500 기본 메시지")
    void handleGenericException() {
        ResponseEntity<ErrorResponse> res = handler.handleException(new RuntimeException("boom"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.code());
        assertThat(res.getBody().message()).isEqualTo(ErrorCode.INTERNAL_ERROR.defaultMessage());
    }
}
