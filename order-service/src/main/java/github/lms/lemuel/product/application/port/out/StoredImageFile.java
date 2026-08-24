package github.lms.lemuel.product.application.port.out;

/**
 * 이미지 저장 결과 메타데이터 — {@link StoreProductImagePort} 의 반환 계약.
 *
 * <p>포트와 함께 응용 계층이 소유한다(인터페이스 소유권 역전). 어댑터가 정의한 타입을
 * 응용 서비스가 받아 쓰면 소유권이 다시 하위로 넘어가므로, 결과 타입도 이쪽에 둔다.
 *
 * @param storedFileName 저장소가 부여한 파일명(원본명이 아니다 — path traversal 방지용 UUID 기반)
 * @param filePath       저장소 내부 경로. 삭제 시 이 값을 그대로 포트에 되돌려준다
 * @param url            외부 노출용 URL
 * @param width          이미지 가로 픽셀. 판독 실패 시 null
 * @param height         이미지 세로 픽셀. 판독 실패 시 null
 * @param checksum       SHA-256 체크섬. 산출 실패 시 null
 */
public record StoredImageFile(
        String storedFileName,
        String filePath,
        String url,
        Integer width,
        Integer height,
        String checksum
) {
}
