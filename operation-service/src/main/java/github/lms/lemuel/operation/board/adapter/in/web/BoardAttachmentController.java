package github.lms.lemuel.operation.board.adapter.in.web;

import github.lms.lemuel.operation.board.adapter.in.web.dto.BoardAttachmentResponse;
import github.lms.lemuel.operation.board.application.port.in.BoardAttachmentUseCase;
import github.lms.lemuel.operation.board.domain.BoardAttachment;
import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 첨부 API.
 *
 * <p>다운로드 응답의 세 헤더가 이 컨트롤러의 핵심이다:
 * <ul>
 *   <li><b>Content-Type</b> — 서버가 매직바이트로 판정한 값. 업로드 요청이 준 값을 쓰면
 *       업로더가 응답 헤더를 정하는 셈이 된다.</li>
 *   <li><b>Content-Disposition</b> — 이미지만 {@code inline}, 나머지는 {@code attachment}.
 *       PDF·ZIP 을 inline 으로 열어 주면 같은 오리진에서 문서로 해석될 여지가 생긴다.</li>
 *   <li><b>X-Content-Type-Options: nosniff</b> — 브라우저가 우리 판정을 무시하고 내용으로
 *       타입을 추측하는 것을 막는다. 위 둘을 지켜도 이게 없으면 추측이 이긴다.</li>
 * </ul>
 */
@Tag(name = "Board Attachment", description = "게시글 첨부 업로드·다운로드")
@RestController
@RequestMapping("/api/boards/{boardKey}")
@RequiredArgsConstructor
public class BoardAttachmentController {

    private final BoardAttachmentUseCase boardAttachmentUseCase;

    @Operation(summary = "첨부 목록")
    @GetMapping("/posts/{postId}/attachments")
    public ResponseEntity<List<BoardAttachmentResponse>> list(@PathVariable String boardKey,
                                                              @PathVariable Long postId) {
        List<BoardAttachmentResponse> attachments =
                boardAttachmentUseCase.listByPost(boardKey, postId, CurrentActor.resolve()).stream()
                        .map(attachment -> BoardAttachmentResponse.from(attachment, boardKey))
                        .toList();
        return ResponseEntity.ok(attachments);
    }

    @Operation(summary = "첨부 업로드",
            description = "형식은 파일 내용으로 판정한다. 확장자만 바꾼 파일·형식 미상은 400.")
    @PostMapping("/posts/{postId}/attachments")
    public ResponseEntity<BoardAttachmentResponse> upload(@PathVariable String boardKey,
                                                          @PathVariable Long postId,
                                                          @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BoardInvariantViolationException("첨부할 파일이 없습니다.");
        }
        BoardAttachment attachment = boardAttachmentUseCase.upload(
                boardKey, postId, CurrentActor.resolve(), file.getOriginalFilename(), readBytes(file));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BoardAttachmentResponse.from(attachment, boardKey));
    }

    @Operation(summary = "첨부 다운로드", description = "볼 수 없는 글의 첨부는 404.")
    @GetMapping("/attachments/{attachmentId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String boardKey, @PathVariable Long attachmentId) {
        var download = boardAttachmentUseCase.download(boardKey, attachmentId, CurrentActor.resolve());
        BoardAttachment attachment = download.attachment();

        ContentDisposition disposition = (attachment.allowsInlineDisposition()
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                // 원본 파일명은 UTF-8 로 인코딩해 실어 보낸다(RFC 5987) — 한글 파일명이 깨지지 않게.
                .filename(attachment.getOriginalName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(download.content());
    }

    @Operation(summary = "첨부 축소본", description = "목록용. 축소본이 없으면 원본으로 떨어진다.")
    @GetMapping("/attachments/{attachmentId}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable String boardKey, @PathVariable Long attachmentId) {
        var download = boardAttachmentUseCase.downloadThumbnail(boardKey, attachmentId, CurrentActor.resolve());
        BoardAttachment attachment = download.attachment();

        // 축소본은 항상 PNG 로 만든다(생성기 규약). 원본으로 떨어진 경우에만 판정된 타입을 쓴다.
        MediaType contentType = attachment.hasThumbnail()
                ? MediaType.IMAGE_PNG
                : MediaType.parseMediaType(attachment.getContentType());

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(attachment.getOriginalName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                // 축소본은 내용이 바뀌지 않는다(첨부는 수정되지 않고 지웠다 다시 올린다).
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .body(download.content());
    }

    @Operation(summary = "첨부 삭제", description = "글을 고칠 수 있는 사람만 — 첨부는 글의 일부다.")
    @DeleteMapping("/attachments/{attachmentId}")
    public ResponseEntity<Void> delete(@PathVariable String boardKey, @PathVariable Long attachmentId) {
        boardAttachmentUseCase.delete(boardKey, attachmentId, CurrentActor.resolve());
        return ResponseEntity.noContent().build();
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 파일을 읽지 못했습니다.", e);
        }
    }
}
