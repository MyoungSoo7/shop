package github.lms.lemuel.operation.board.adapter.out.storage;

import github.lms.lemuel.operation.board.application.port.out.StoreAttachmentPort;
import github.lms.lemuel.operation.board.config.AttachmentProperties;
import github.lms.lemuel.operation.board.domain.exception.BoardAttachmentNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 로컬 파일시스템 첨부 저장소.
 *
 * <p><b>파일명은 전적으로 서버가 만든다</b>(UUID + 판정된 확장자). 업로더가 준 이름은 표시용으로만
 * DB 에 남고 경로에는 한 글자도 들어가지 않는다 — 경로 조작이 성립할 자리를 없애는 가장 확실한
 * 방법은 입력을 정화하는 게 아니라 <b>입력을 쓰지 않는 것</b>이다.
 *
 * <p>그럼에도 저장·조회 직전에 루트 밖으로 나가지 않는지 다시 확인한다. 언젠가 누군가 경로에
 * 사용자 입력을 넣게 되면 그때 이 검사가 마지막 방어선이 된다.
 */
@Slf4j
@Component
public class LocalFileSystemAttachmentStorageAdapter implements StoreAttachmentPort {

    private final Path baseDir;

    public LocalFileSystemAttachmentStorageAdapter(AttachmentProperties properties) {
        this.baseDir = Paths.get(properties.baseDir()).toAbsolutePath().normalize();
    }

    @Override
    public StoredAttachment store(Long boardId, Long postId, String extension, byte[] content) {
        String storedName = UUID.randomUUID() + "." + extension;
        Path relative = Paths.get("board-" + boardId, "post-" + postId, storedName);
        Path target = resolveWithinBase(relative.toString());

        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("첨부 저장에 실패했습니다: " + relative, e);
        }
        return new StoredAttachment(storedName, relative.toString().replace('\\', '/'));
    }

    @Override
    public byte[] read(String storagePath) {
        Path target = resolveWithinBase(storagePath);
        if (!Files.isRegularFile(target)) {
            // 행은 있는데 파일이 없다 — 볼륨 유실이거나 삭제 순서가 깨진 것이다. 500 보다 404 가 정직하다.
            throw new BoardAttachmentNotFoundException("첨부 파일이 없습니다: " + storagePath);
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new UncheckedIOException("첨부 읽기에 실패했습니다: " + storagePath, e);
        }
    }

    @Override
    public void delete(String storagePath) {
        try {
            Files.deleteIfExists(resolveWithinBase(storagePath));
        } catch (IOException e) {
            // 삭제 실패로 호출자의 트랜잭션을 깨지 않는다 — 남은 파일은 청소 대상이지 사고가 아니다.
            log.warn("첨부 파일 삭제 실패(수동 정리 필요): {}", storagePath, e);
        }
    }

    @Override
    public List<StoredFile> listAll() {
        if (!Files.isDirectory(baseDir)) {
            // 아직 아무것도 올라오지 않았다 — 청소할 것도 없다.
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(baseDir)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> new StoredFile(
                            baseDir.relativize(path).toString().replace('\\', '/'), lastModified(path)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("첨부 저장소를 훑지 못했습니다: " + baseDir, e);
        }
    }

    private static Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            // 시각을 못 읽으면 "방금 만들어진 것"으로 취급한다 — 유예 기간에 걸려 지워지지 않는다.
            // 모르는 파일을 지우는 쪽으로 기울면 안 된다.
            return Instant.now();
        }
    }

    private Path resolveWithinBase(String relativePath) {
        Path resolved = baseDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new BoardInvariantViolationException("첨부 경로가 저장소 밖을 가리킵니다: " + relativePath);
        }
        return resolved;
    }
}
