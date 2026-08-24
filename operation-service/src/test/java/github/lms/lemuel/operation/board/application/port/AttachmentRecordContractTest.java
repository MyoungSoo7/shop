package github.lms.lemuel.operation.board.application.port;

import github.lms.lemuel.operation.board.application.port.in.BoardAttachmentUseCase.AttachmentDownload;
import github.lms.lemuel.operation.board.application.port.out.GenerateThumbnailPort.Thumbnail;
import github.lms.lemuel.operation.board.domain.BoardAttachment;
import github.lms.lemuel.operation.board.domain.BoardAttachmentKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * byte[] 를 품은 첨부 포트 record 2종의 값 계약.
 *
 * <p>record 기본 구현은 배열을 <b>참조 동일성</b>으로 비교한다 — 같은 파일을 두 번 읽어 만든 두 값이
 * 서로 다른 값이 되는 셈이라 놀랍다. 첨부는 "같은 바이트면 같은 내려받기"가 맞는 의미론이다.
 *
 * <p>{@code toString} 은 파일 바이트를 로그로 흘리지 않는다 — 게시판 첨부는 비밀글의 첨부일 수 있고
 * (권한 판정은 {@code download} 가 한다), 메가바이트 배열을 찍으면 로그가 통째로 오염된다.
 * 같은 계약을 문서함 쪽에서 먼저 세웠다(company-service {@code DocumentRecordContractTest}).
 */
@DisplayName("첨부 포트 record — 배열 값 계약")
class AttachmentRecordContractTest {

    /**
     * equals 의 타입 가드 분기를 덮기 위한 이종 객체.
     *
     * <p>단정문에 문자열 리터럴을 직접 넣으면 "다른 타입끼리 비교하는 단정"으로 잡힌다(S5845).
     * 그 규칙이 노리는 것은 <i>실수로</i> 다른 타입을 비교하는 테스트인데, 여기서는 그게 검증 대상이다.
     * {@code Object} 로 받아 의도를 코드에 드러낸다 — 규칙 회피가 아니라 "일부러 이종을 넣는다"는 선언이다.
     */
    private static final Object FOREIGN_TYPE = "다른 타입";

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-16T09:00:00Z");

    private static BoardAttachment attachment() {
        return BoardAttachment.rehydrate(9L, 5L, 1L, BoardAttachmentKind.IMAGE, "photo.jpg",
                "uuid.jpg", "board-1/post-5/uuid.jpg", "board-1/post-5/thumb.jpg", "image/jpeg", 4, 0, NOW);
    }

    @Test
    @DisplayName("AttachmentDownload: 내용이 같으면 같은 값이다 — 배열 참조가 달라도")
    void attachmentDownload_equalsByContent() {
        BoardAttachment a = attachment();
        AttachmentDownload one = new AttachmentDownload(a, new byte[]{1, 2, 3});
        AttachmentDownload two = new AttachmentDownload(a, new byte[]{1, 2, 3});

        assertThat(one).isEqualTo(two).hasSameHashCodeAs(two);
    }

    @Test
    @DisplayName("AttachmentDownload: 내용이 다르면 다른 값이다")
    void attachmentDownload_differsByContent() {
        BoardAttachment a = attachment();
        AttachmentDownload one = new AttachmentDownload(a, new byte[]{1, 2, 3});

        assertThat(one)
                .isNotEqualTo(new AttachmentDownload(a, new byte[]{9}))
                .isNotEqualTo(new AttachmentDownload(null, new byte[]{1, 2, 3}))
                .isNotEqualTo(null)
                .isNotEqualTo(FOREIGN_TYPE);
    }

    @Test
    @DisplayName("AttachmentDownload: toString 은 바이트가 아니라 길이만 노출한다")
    void attachmentDownload_toStringHidesBytes() {
        String rendered = new AttachmentDownload(attachment(), new byte[]{1, 2, 3}).toString();

        assertThat(rendered).contains("3B").contains("photo.jpg").doesNotContain("[B@");
        assertThat(new AttachmentDownload(attachment(), null).toString()).contains("content=null");
    }

    @Test
    @DisplayName("Thumbnail: 내용 기준 비교 + toString 이 바이트를 감춘다")
    void thumbnail_contract() {
        Thumbnail one = new Thumbnail(new byte[]{7, 7}, "jpg");
        Thumbnail two = new Thumbnail(new byte[]{7, 7}, "jpg");

        assertThat(one).isEqualTo(two).hasSameHashCodeAs(two);
        assertThat(one)
                .isNotEqualTo(new Thumbnail(new byte[]{7}, "jpg"))
                .isNotEqualTo(new Thumbnail(new byte[]{7, 7}, "png"))
                .isNotEqualTo(null)
                .isNotEqualTo(FOREIGN_TYPE);
        assertThat(one.toString()).contains("2B").contains("jpg").doesNotContain("[B@");
        assertThat(new Thumbnail(null, "jpg").toString()).contains("content=null");
    }
}
