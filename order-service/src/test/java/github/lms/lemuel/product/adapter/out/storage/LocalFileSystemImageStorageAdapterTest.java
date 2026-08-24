package github.lms.lemuel.product.adapter.out.storage;

import github.lms.lemuel.product.application.port.out.StoredImageFile;
import github.lms.lemuel.product.domain.ImageUpload;
import github.lms.lemuel.product.domain.exception.ImageStorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 저장 어댑터 검증 — 기술 예외가 포트 경계를 넘지 않고 {@link ImageStorageException} 으로
 * 번역되는지, 부가 정보(크기·체크섬) 실패가 업로드 자체를 깨뜨리지 않는지 확인한다.
 */
class LocalFileSystemImageStorageAdapterTest {

    @TempDir
    Path tempDir;

    private LocalFileSystemImageStorageAdapter adapter() {
        return new LocalFileSystemImageStorageAdapter(tempDir.toString(), "/assets");
    }

    private ImageUpload jpeg(byte[] content) {
        return ImageUpload.of("photo.jpg", "image/jpeg", content.length, content);
    }

    @Test
    @DisplayName("저장 - 파일이 실제로 쓰이고 UUID 파일명·URL·체크섬을 돌려준다")
    void storesFile() throws IOException {
        byte[] content = "hello".getBytes();

        StoredImageFile stored = adapter().store(7L, jpeg(content));

        assertThat(Paths.get(stored.filePath())).exists();
        assertThat(Files.readAllBytes(Paths.get(stored.filePath()))).isEqualTo(content);
        assertThat(stored.storedFileName()).endsWith(".jpg").isNotEqualTo("photo.jpg");
        assertThat(stored.url()).isEqualTo("/assets/products/7/" + stored.storedFileName());
        // "hello" 의 SHA-256
        assertThat(stored.checksum())
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    @DisplayName("저장 - 이미지로 판독되지 않는 바이트여도 업로드는 성공하고 width/height 만 null")
    void unreadableImageStillStores() {
        StoredImageFile stored = adapter().store(7L, jpeg("not-an-image".getBytes()));

        assertThat(Paths.get(stored.filePath())).exists();
        assertThat(stored.width()).isNull();
        assertThat(stored.height()).isNull();
        assertThat(stored.checksum()).isNotNull();
    }

    @Test
    @DisplayName("저장 - 쓰기가 불가능하면 ImageStorageException 으로 번역하고 원인을 보존한다")
    void translatesWriteFailure() throws IOException {
        // uploadDir 자리에 '파일' 을 놓으면 하위 디렉터리 생성이 실패한다.
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "x");
        var adapter = new LocalFileSystemImageStorageAdapter(blocker.toString(), "/assets");

        assertThatThrownBy(() -> adapter.store(7L, jpeg("hello".getBytes())))
                .isInstanceOf(ImageStorageException.class)
                .hasCauseInstanceOf(IOException.class)
                .hasMessageContaining("productId=7");
    }

    @Test
    @DisplayName("삭제 - 저장된 파일을 지운다")
    void deletesFile() {
        StoredImageFile stored = adapter().store(7L, jpeg("hello".getBytes()));

        adapter().delete(stored.filePath());

        assertThat(Paths.get(stored.filePath())).doesNotExist();
    }

    @Test
    @DisplayName("삭제 - 이미 없는 경로는 조용히 성공한다(멱등)")
    void deleteIsIdempotent() {
        assertThatCode(() -> adapter().delete(tempDir.resolve("missing.jpg").toString()))
                .doesNotThrowAnyException();
    }
}
