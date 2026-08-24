package github.lms.lemuel.operation.board.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoardSkinTest {

    @Test
    @DisplayName("GALLERY 만 첨부를 전제한다")
    void onlyGalleryRequiresAttachments() {
        assertThat(BoardSkin.GALLERY.requiresAttachments()).isTrue();
        assertThat(BoardSkin.LIST.requiresAttachments()).isFalse();
        assertThat(BoardSkin.FAQ.requiresAttachments()).isFalse();
        assertThat(BoardSkin.QNA.requiresAttachments()).isFalse();
    }

    @Test
    @DisplayName("QNA 만 댓글을 전제한다")
    void onlyQnaRequiresComments() {
        assertThat(BoardSkin.QNA.requiresComments()).isTrue();
        assertThat(BoardSkin.LIST.requiresComments()).isFalse();
        assertThat(BoardSkin.GALLERY.requiresComments()).isFalse();
        assertThat(BoardSkin.FAQ.requiresComments()).isFalse();
    }
}
