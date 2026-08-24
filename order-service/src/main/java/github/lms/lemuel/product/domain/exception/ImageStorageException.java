package github.lms.lemuel.product.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 이미지 저장소(파일시스템·오브젝트 스토리지) 작업 실패 — <b>저장 기술에 독립적인</b> 추상 예외.
 *
 * <p>{@code StoreProductImagePort} 의 계약이다. 로컬 파일시스템 어댑터는 {@code IOException} 을,
 * 다른 어댑터는 각자의 SDK 예외를 던지겠지만 포트를 넘는 순간 전부 이 타입으로 <b>번역</b>된다.
 * 덕분에 응용 서비스는 {@code throws IOException} 을 시그니처에 달지 않고, 저장 기술을 바꿔도
 * 예외 처리 코드가 따라 바뀌지 않는다.
 *
 * <p>원인 예외는 항상 {@code cause} 로 보존한다 — 삼키지 않는다.
 */
public class ImageStorageException extends BusinessException {

    public ImageStorageException(String message, Throwable cause) {
        super(ErrorCode.IMAGE_STORAGE_FAILED, message, cause);
    }
}
