package github.lms.lemuel.operation.board.application.port.out;

import java.util.Optional;

/**
 * 목록용 축소본 생성.
 *
 * <p><b>실패가 정상 경로다.</b> 반환이 {@code Optional} 인 것은 실수가 아니다 — JDK 의 ImageIO 는
 * WEBP 을 읽지 못하고, 손상된 이미지도 들어온다. 그때 업로드 전체를 실패시키면 <b>썸네일이라는
 * 부가 기능이 본 기능(첨부)을 죽이는</b> 셈이 된다. 축소본이 없으면 원본을 내려 줄 뿐이다.
 */
public interface GenerateThumbnailPort {

    /**
     * 긴 변을 {@code maxEdge} 로 줄인 이미지를 만든다. 만들 수 없으면 비어 있는 값.
     *
     * @param extension 서버가 판정한 확장자(요청이 주장한 값이 아니다)
     */
    Optional<Thumbnail> generate(byte[] source, String extension, int maxEdge);

    /**
     * 축소본 1건. {@code content} 가 배열이라 record 기본 구현은 <b>참조 동일성</b>으로 비교하고
     * {@code toString()} 은 {@code [B@1a2b3c} 를 찍는다 — 둘 다 놀라운 동작이라 재정의한다.
     * {@code toString()} 은 이미지 바이트 대신 길이만 남긴다(로그 오염 방지).
     */
    record Thumbnail(byte[] content, String extension) {

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Thumbnail other)) {
                return false;
            }
            return java.util.Arrays.equals(content, other.content)
                    && java.util.Objects.equals(extension, other.extension);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Arrays.hashCode(content) + java.util.Objects.hashCode(extension);
        }

        @Override
        public String toString() {
            return "Thumbnail[content=" + (content == null ? "null" : content.length + "B")
                    + ", extension=" + extension + "]";
        }
    }
}
