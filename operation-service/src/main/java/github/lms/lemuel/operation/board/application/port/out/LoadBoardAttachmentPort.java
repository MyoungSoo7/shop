package github.lms.lemuel.operation.board.application.port.out;

import github.lms.lemuel.operation.board.domain.BoardAttachment;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

public interface LoadBoardAttachmentPort {

    Optional<BoardAttachment> findById(Long id);

    List<BoardAttachment> findByPostId(Long postId);

    int countByPostId(Long postId);

    /**
     * 글마다 대표 이미지(정렬 첫 번째 IMAGE) 하나씩.
     *
     * <p>갤러리 목록이 글 하나당 한 번씩 첨부를 조회하면 한 화면에 20번의 왕복이 생긴다.
     * 목록은 <b>한 번의 질의</b>로 끝나야 한다.
     */
    Map<Long, BoardAttachment> findFirstImageByPostIds(List<Long> postIds);

    /**
     * DB 가 참조하는 저장 경로 전부(원본 + 축소본).
     *
     * <p>고아 청소가 "지워도 되는 파일"을 고르는 기준이다. 축소본을 빠뜨리면 살아 있는 썸네일을
     * 지우게 되므로 두 컬럼을 <b>함께</b> 모은다.
     */
    Set<String> findAllReferencedPaths();
}
