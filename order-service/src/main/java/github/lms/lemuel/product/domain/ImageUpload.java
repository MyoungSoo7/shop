package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import java.util.Set;

/**
 * 업로드하려는 이미지 원본 — 형식·크기 정책을 스스로 판정하는 도메인 값 오브젝트.
 *
 * <p>이전에는 허용 MIME 목록과 5MB 상한이 {@code FileStorageService}(파일시스템 어댑터 성격의
 * 응용 서비스)에 있었고, 응용 서비스가 {@code isValidImageType(...)}/{@code isValidFileSize(...)} 를
 * 순서대로 호출해 판정했다. 정책이 저장 기술 쪽에 얹혀 있어 저장소를 S3 로 바꾸면 정책도 따라
 * 움직이는 구조였다. 판정을 이 값 오브젝트로 끌어와 <b>저장 기술과 무관하게</b> 강제한다.
 *
 * <p>생성 시점에 불변식을 통과하지 못하면 객체 자체가 만들어지지 않는다 — 검증되지 않은
 * {@code ImageUpload} 는 존재할 수 없으므로 하위 계층은 재검증하지 않아도 된다.
 *
 * <p>{@code MultipartFile} 같은 웹 기술 타입은 이 경계 밖(web 어댑터)에 남는다.
 */
public final class ImageUpload {

    /** 허용 MIME 타입 — jpeg/jpg/png/webp 만 받는다(gif·svg 등은 불허). */
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/jpg", "image/png", "image/webp");

    /** 파일 1건 상한 5MB. 경계값(정확히 5MB)은 허용이다. */
    private static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;

    private final String originalFilename;
    private final String contentType;
    private final long sizeBytes;
    private final byte[] content;

    private ImageUpload(String originalFilename, String contentType, long sizeBytes, byte[] content) {
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.content = content;
    }

    /**
     * 정책을 통과한 업로드 원본을 만든다.
     *
     * @throws ProductInvariantViolationException 허용되지 않은 형식이거나 크기 상한을 벗어난 경우
     */
    public static ImageUpload of(String originalFilename, String contentType, long sizeBytes, byte[] content) {
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ProductInvariantViolationException("Invalid image type: " + contentType);
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_SIZE_BYTES) {
            throw new ProductInvariantViolationException("File size exceeds 5MB limit");
        }
        return new ImageUpload(originalFilename, contentType, sizeBytes,
                content == null ? new byte[0] : content.clone());
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    /** 원본 바이트의 방어적 복사본 — 외부에서 내부 배열을 변조할 수 없다. */
    public byte[] getContent() {
        return content.clone();
    }

    /** 저장 파일명에 쓸 확장자(점 포함). 원본 파일명에 확장자가 없으면 빈 문자열. */
    public String extension() {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return "";
        }
        return originalFilename.substring(originalFilename.lastIndexOf("."));
    }
}
