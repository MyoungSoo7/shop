package github.lms.lemuel.product.adapter.out.storage;

import github.lms.lemuel.product.application.port.out.StoreProductImagePort;
import github.lms.lemuel.product.application.port.out.StoredImageFile;
import github.lms.lemuel.product.domain.ImageUpload;
import github.lms.lemuel.product.domain.exception.ImageStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 로컬 파일시스템 이미지 저장 어댑터 — {@link StoreProductImagePort} 의 구현.
 *
 * <p>기술 예외({@code IOException} 등)는 <b>이 경계를 넘지 않는다</b>. 전부
 * {@link ImageStorageException} 으로 번역하며 원인 예외를 보존한다.
 *
 * <p>이미지 크기 판독과 체크섬 산출은 <b>부가 정보</b>다 — 실패해도 업로드 자체는 성공시키되
 * 조용히 넘기지 않고 WARN 으로 남긴다(무엇이 왜 비었는지 사후에 추적 가능해야 한다).
 * 반면 파일 쓰기 실패는 업로드의 본질이므로 예외로 올린다.
 */
@Component
public class LocalFileSystemImageStorageAdapter implements StoreProductImagePort {

    private static final Logger log = LoggerFactory.getLogger(LocalFileSystemImageStorageAdapter.class);

    private final String uploadDir;
    private final String baseUrl;

    public LocalFileSystemImageStorageAdapter(
            @Value("${app.upload.dir:/data/uploads}") String uploadDir,
            @Value("${app.upload.base-url:/assets}") String baseUrl) {
        this.uploadDir = uploadDir;
        this.baseUrl = baseUrl;
    }

    @Override
    public StoredImageFile store(Long productId, ImageUpload upload) {
        // 저장 파일명은 원본명이 아니라 UUID + 확장자 — 원본명을 경로에 쓰면 path traversal 이 열린다.
        String storedFileName = UUID.randomUUID() + upload.extension();
        Path targetPath;
        try {
            Path productDir = Paths.get(uploadDir, "products", productId.toString());
            Files.createDirectories(productDir);
            targetPath = productDir.resolve(storedFileName);
            try (ByteArrayInputStream in = new ByteArrayInputStream(upload.getContent())) {
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ImageStorageException(
                    "이미지 저장 실패: productId=" + productId + ", file=" + upload.getOriginalFilename(), e);
        }

        Dimensions dimensions = readDimensions(targetPath);
        String checksum = calculateChecksum(targetPath);
        String url = String.format("%s/products/%d/%s", baseUrl, productId, storedFileName);

        return new StoredImageFile(storedFileName, targetPath.toString(), url,
                dimensions.width(), dimensions.height(), checksum);
    }

    @Override
    public void delete(String filePath) {
        try {
            Files.deleteIfExists(Paths.get(filePath));
        } catch (IOException e) {
            throw new ImageStorageException("이미지 삭제 실패: " + filePath, e);
        }
    }

    /** 이미지 픽셀 크기. 판독 실패 시 (null, null). */
    private record Dimensions(Integer width, Integer height) {
        static Dimensions unknown() {
            return new Dimensions(null, null);
        }
    }

    private Dimensions readDimensions(Path path) {
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                log.warn("이미지 크기 판독 불가 — 지원하지 않는 포맷일 수 있다. path={}", path);
                return Dimensions.unknown();
            }
            return new Dimensions(image.getWidth(), image.getHeight());
        } catch (IOException e) {
            log.warn("이미지 크기 판독 실패 — width/height 없이 진행한다. path={}", path, e);
            return Dimensions.unknown();
        }
    }

    private String calculateChecksum(Path filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(Files.readAllBytes(filePath));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            log.warn("체크섬 산출 실패 — checksum 없이 진행한다. path={}", filePath, e);
            return null;
        }
    }
}
