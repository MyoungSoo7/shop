package github.lms.lemuel.operation.board.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * 첨부 바이너리 저장소.
 *
 * <p>시그니처에 {@code MultipartFile} 도 {@code Path} 도 없다 — 웹 기술과 저장 기술은 각각
 * 어댑터 안에 갇힌다. 나중에 로컬 디스크를 S3 로 바꾸는 일은 이 인터페이스 바깥에서 끝나야 한다.
 *
 * <p>product 도메인의 이미지 저장 어댑터를 재사용하지 않는 이유: 도메인 간 어댑터 공유는
 * 헥사고날 위반이고, 첨부의 이름 규칙·경로 구조·보존 정책은 게시판이 정한다.
 */
public interface StoreAttachmentPort {

    /**
     * 저장하고 위치를 돌려준다. <b>파일명은 서버가 만든다</b> — 업로더가 준 이름은 쓰지 않는다.
     */
    StoredAttachment store(Long boardId, Long postId, String extension, byte[] content);

    byte[] read(String storagePath);

    /** 이미 없으면 성공으로 간주한다(멱등). */
    void delete(String storagePath);

    /**
     * 저장소에 실제로 있는 파일 전부 — 고아 청소가 DB 와 대조하려면 저장소 쪽 사실이 필요하다.
     *
     * <p>규모 전제: 게시판 첨부는 수만 건 수준이라 전량 나열이 감당된다. 그 이상으로 커지면
     * 날짜 파티션 단위 스캔으로 바꿔야 한다.
     */
    List<StoredFile> listAll();

    record StoredAttachment(String storedName, String storagePath) {
    }

    record StoredFile(String storagePath, Instant lastModified) {
    }
}
