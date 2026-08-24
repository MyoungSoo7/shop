package github.lms.lemuel.operation.board.domain;

/**
 * 첨부 종류 — 화면에서 그림으로 펼칠 수 있는가.
 *
 * <p>이 값은 <b>업로더의 주장이 아니라 서버가 실제 바이트를 보고</b> 정한다
 * ({@link DetectedFileType#image()}). 확장자를 믿고 IMAGE 로 분류하면 {@code shell.php} 를
 * {@code shell.jpg} 로 바꾼 파일이 이미지 태그로 렌더된다.
 */
public enum BoardAttachmentKind {
    IMAGE,
    FILE
}
