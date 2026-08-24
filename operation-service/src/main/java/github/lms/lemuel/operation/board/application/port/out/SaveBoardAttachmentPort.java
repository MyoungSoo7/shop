package github.lms.lemuel.operation.board.application.port.out;

import github.lms.lemuel.operation.board.domain.BoardAttachment;

public interface SaveBoardAttachmentPort {

    BoardAttachment save(BoardAttachment attachment);

    void delete(Long id);
}
