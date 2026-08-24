package github.lms.lemuel.operation.board.application.port.in;

/**
 * 고아 첨부 파일 청소 — DB 가 참조하지 않는 파일을 지운다.
 *
 * <p>고아는 정상 운영에서도 생긴다: 삭제 시 파일 삭제만 실패하거나(경고 로그만 남긴다), DB 기록
 * 실패 후 보상 삭제까지 실패하거나, 볼륨을 옮기다 어긋나거나. 하나하나는 드물지만 <b>아무도 치우지
 * 않으면 단조 증가</b>한다.
 */
public interface CleanupOrphanAttachmentUseCase {

    /**
     * 한 바퀴 돌고 결과를 돌려준다.
     *
     * @return 훑은 파일 수와 지운 파일 수
     */
    CleanupResult cleanupOrphans();

    record CleanupResult(int scanned, int deleted) {
    }
}
