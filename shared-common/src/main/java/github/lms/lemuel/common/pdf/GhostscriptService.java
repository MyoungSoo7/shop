package github.lms.lemuel.common.pdf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Ghostscript(GPL) 래퍼 서비스.
 *
 * <p>Ghostscript는 GPL 라이선스이므로 오픈소스 프로젝트에서 무료 사용 가능.
 * Docker 이미지에 {@code gs} 바이너리가 설치되어 있어야 한다 (apk add ghostscript).
 *
 * <p>주요 기능:
 * <ul>
 *   <li>PDF → PNG/JPEG 이미지 변환 (페이지별 렌더링)</li>
 *   <li>PDF 압축 (파일 크기 최적화)</li>
 *   <li>PDF → PDF/A 변환 (장기 보존 포맷)</li>
 *   <li>이미지 → PDF 변환</li>
 * </ul>
 */
@Slf4j
@Service
public class GhostscriptService {

    private static final String GS_BINARY = "gs";
    private static final int TIMEOUT_SECONDS = 120;

    // ──────────────────────────────────────────────────────
    // PDF → 이미지 변환
    // ──────────────────────────────────────────────────────

    /**
     * PDF를 페이지별 PNG 이미지로 변환한다.
     *
     * @param pdfPath   입력 PDF 파일 경로
     * @param outputDir 이미지 저장 디렉토리
     * @param dpi       해상도 (72=저화질, 150=중간, 300=인쇄용)
     * @return 생성된 이미지 파일 목록
     */
    public List<Path> pdfToImages(Path pdfPath, Path outputDir, int dpi) throws IOException {
        Files.createDirectories(outputDir); // 출력 디렉토리 없으면 생성

        // %04d: 페이지 번호를 4자리 숫자로 → page_0001.png, page_0002.png ...
        Path outputPattern = outputDir.resolve("page_%04d.png");

        List<String> cmd = List.of(
                GS_BINARY,
                "-dBATCH",           // 실행 후 대화형 모드로 전환하지 않음
                "-dNOPAUSE",         // 페이지마다 멈추지 않음
                "-dNOSAFER",         // 파일 I/O 허용 (컨테이너 내부라 안전)
                "-sDEVICE=png16m",   // 출력 포맷: 24-bit PNG
                "-r" + dpi,          // 해상도 (DPI)
                "-sOutputFile=" + outputPattern,
                pdfPath.toString()
        );

        runGs(cmd);

        // 생성된 파일 목록 수집
        try (var stream = Files.list(outputDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("page_"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new GhostscriptException("출력 파일 목록 조회 실패", e);
        }
    }

    /**
     * PDF를 페이지별 JPEG 이미지로 변환한다.
     *
     * @param pdfPath   입력 PDF 파일 경로
     * @param outputDir 이미지 저장 디렉토리
     * @param dpi       해상도
     * @param quality   JPEG 품질 (0~100)
     */
    public List<Path> pdfToJpeg(Path pdfPath, Path outputDir, int dpi, int quality) throws IOException {
        Files.createDirectories(outputDir);
        Path outputPattern = outputDir.resolve("page_%04d.jpg");

        List<String> cmd = List.of(
                GS_BINARY,
                "-dBATCH",
                "-dNOPAUSE",
                "-dNOSAFER",
                "-sDEVICE=jpeg",
                "-r" + dpi,
                "-dJPEGQ=" + quality,
                "-sOutputFile=" + outputPattern,
                pdfPath.toString()
        );

        runGs(cmd);

        try (var stream = Files.list(outputDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("page_"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new GhostscriptException("출력 파일 목록 조회 실패", e);
        }
    }

    // ──────────────────────────────────────────────────────
    // PDF 압축
    // ──────────────────────────────────────────────────────

    /**
     * PDF를 압축하여 파일 크기를 줄인다.
     *
     * <p>압축 레벨:
     * <ul>
     *   <li>{@code screen}  — 최고 압축, 화면용 (72dpi)</li>
     *   <li>{@code ebook}   — 중간 압축 (150dpi)</li>
     *   <li>{@code printer} — 인쇄용 (300dpi)</li>
     *   <li>{@code prepress}— 프리프레스용 (300dpi, 색상 보존)</li>
     * </ul>
     *
     * @param inputPath  입력 PDF
     * @param outputPath 압축된 PDF 저장 경로
     * @param level      압축 레벨 (screen/ebook/printer/prepress)
     */
    public void compressPdf(Path inputPath, Path outputPath, String level) throws GhostscriptException {
        List<String> cmd = List.of(
                GS_BINARY,
                "-dBATCH",
                "-dNOPAUSE",
                "-dNOSAFER",
                "-sDEVICE=pdfwrite",
                "-dCompatibilityLevel=1.7",
                "-dPDFSETTINGS=/" + level,   // /screen, /ebook, /printer, /prepress
                "-sOutputFile=" + outputPath,
                inputPath.toString()
        );

        runGs(cmd);
        log.info("PDF 압축 완료: {} → {} (레벨: {})", inputPath.getFileName(), outputPath.getFileName(), level);
    }

    // ──────────────────────────────────────────────────────
    // PDF → PDF/A 변환 (장기 보존 포맷)
    // ──────────────────────────────────────────────────────

    /**
     * PDF를 PDF/A-2B 포맷으로 변환한다.
     *
     * <p>PDF/A는 장기 보존을 위한 ISO 표준 포맷으로,
     * 전자 문서 아카이빙 시스템에서 요구하는 경우가 많다.
     *
     * @param inputPath  입력 PDF
     * @param outputPath 출력 PDF/A 경로
     */
    public void convertToPdfA(Path inputPath, Path outputPath) throws GhostscriptException {
        List<String> cmd = List.of(
                GS_BINARY,
                "-dBATCH",
                "-dNOPAUSE",
                "-dNOSAFER",
                "-sDEVICE=pdfwrite",
                "-dPDFA=2",                  // PDF/A-2
                "-dPDFACompatibilityPolicy=1",
                "-sColorConversionStrategy=RGB",
                "-sOutputFile=" + outputPath,
                inputPath.toString()
        );

        runGs(cmd);
        log.info("PDF/A 변환 완료: {}", outputPath.getFileName());
    }

    // ──────────────────────────────────────────────────────
    // 이미지 → PDF 변환
    // ──────────────────────────────────────────────────────

    /**
     * 이미지 파일들을 하나의 PDF로 합친다.
     *
     * @param imagePaths 입력 이미지 목록 (PNG, JPEG, TIFF 등)
     * @param outputPath 출력 PDF 경로
     */
    public void imagesToPdf(List<Path> imagePaths, Path outputPath) throws GhostscriptException {
        if (imagePaths.isEmpty()) {
            throw new GhostscriptException("변환할 이미지가 없습니다");
        }

        List<String> cmd = new ArrayList<>(List.of(
                GS_BINARY,
                "-dBATCH",
                "-dNOPAUSE",
                "-dNOSAFER",
                "-sDEVICE=pdfwrite",
                "-sOutputFile=" + outputPath
        ));
        imagePaths.forEach(p -> cmd.add(p.toString()));

        runGs(cmd);
        log.info("이미지 → PDF 변환 완료: {}개 이미지 → {}", imagePaths.size(), outputPath.getFileName());
    }

    // ──────────────────────────────────────────────────────
    // 내부 실행 로직
    // ──────────────────────────────────────────────────────

    private void runGs(List<String> command) throws GhostscriptException {
        log.debug("Ghostscript 실행: {}", String.join(" ", command));
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)  // stderr를 stdout으로 합쳐서 읽음
                    .start();

            // 출력 로그 비동기 수집 (버퍼 꽉 차면 프로세스가 블로킹되는 것을 방지)
            String output;
            try (var is = process.getInputStream()) {
                output = new String(is.readAllBytes());
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new GhostscriptException("Ghostscript 타임아웃 (" + TIMEOUT_SECONDS + "초 초과)");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("Ghostscript 오류 (exitCode={}): {}", exitCode, output);
                throw new GhostscriptException("Ghostscript 실행 실패 (exitCode=" + exitCode + "): " + output);
            }

            log.debug("Ghostscript 완료 (exitCode=0)");

        } catch (IOException e) {
            throw new GhostscriptException(
                    "'gs' 바이너리를 찾을 수 없습니다. Docker 이미지에 ghostscript가 설치되어 있는지 확인하세요.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GhostscriptException("Ghostscript 실행 중 인터럽트 발생", e);
        }
    }
}
