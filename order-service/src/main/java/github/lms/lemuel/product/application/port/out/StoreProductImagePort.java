package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.ImageUpload;
import github.lms.lemuel.product.domain.exception.ImageStorageException;

/**
 * 상품 이미지 바이너리 저장소 — 응용 계층이 소유하는 아웃바운드 포트.
 *
 * <p>이전에는 {@code ProductImageService} 가 구현 클래스({@code FileStorageService})를 직접
 * 참조했다. 저장소를 로컬 파일시스템에서 S3 로 바꾸려면 응용 서비스 코드가 따라 바뀌는 구조 —
 * 상위 모듈이 하위 모듈에 의존하고 있었다. 인터페이스를 <b>사용하는 쪽(application)</b> 에 두어
 * 의존 방향을 역전시킨다: 어댑터가 이 포트를 구현하러 올라온다.
 *
 * <p>시그니처에 {@code MultipartFile} 도 {@code IOException} 도 없다 — 웹 기술과 저장 기술은
 * 각각 web 어댑터와 out 어댑터 안에 갇힌다.
 */
public interface StoreProductImagePort {

    /**
     * 업로드 원본을 저장하고 저장 결과 메타데이터를 돌려준다.
     *
     * @throws ImageStorageException 저장에 실패한 경우(원인 예외 보존)
     */
    StoredImageFile store(Long productId, ImageUpload upload);

    /**
     * 저장된 파일을 삭제한다. 이미 없으면 성공으로 간주한다(멱등).
     *
     * @throws ImageStorageException 삭제에 실패한 경우
     */
    void delete(String filePath);
}
