package github.lms.lemuel.common.pdf;

import java.io.IOException;

/**
 * Ghostscript 실행 중 발생하는 예외.
 *
 * <p>발생 원인:
 * <ul>
 *   <li>gs 바이너리가 설치되지 않음 (Docker 이미지 문제)</li>
 *   <li>입력 파일 형식 오류</li>
 *   <li>처리 타임아웃</li>
 *   <li>디스크 공간 부족</li>
 * </ul>
 */
public class GhostscriptException extends IOException {

    public GhostscriptException(String message) {
        super(message);
    }

    public GhostscriptException(String message, Throwable cause) {
        super(message, cause);
    }
}
