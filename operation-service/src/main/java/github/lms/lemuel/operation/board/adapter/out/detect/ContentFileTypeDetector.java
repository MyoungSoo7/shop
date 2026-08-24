package github.lms.lemuel.operation.board.adapter.out.detect;

import github.lms.lemuel.operation.board.application.port.out.DetectFileTypePort;
import github.lms.lemuel.operation.board.domain.DetectedFileType;
import org.springframework.stereotype.Component;

/**
 * 내용 기반 형식 판정 — 포트의 유일한 구현.
 *
 * <p>두 단계다. <b>순서가 규칙</b>이다:
 *
 * <ol>
 *   <li><b>매직바이트</b>({@link MagicByteFileTypeDetector}) — 시그니처가 있는 형식은 여기서 끝난다.</li>
 *   <li><b>텍스트 스니핑</b>({@link TextContentSniffer}) — 시그니처가 없는 형식(txt·csv·md·json…)만
 *       내용으로 가린다.</li>
 * </ol>
 *
 * <p>텍스트 판정을 <b>뒤에</b> 두는 것이 중요하다. 앞에 두면 PDF 처럼 앞부분이 아스키(`%PDF-`)인
 * 형식이 텍스트로 잡혀 버린다 — 시그니처가 있는 형식은 언제나 시그니처가 이긴다.
 *
 * <p>둘 다 아니면 {@code unknown} 이고 도메인이 거절한다. "모르면 통과"로 기울면 서버가 무엇인지
 * 모르는 바이트를 브라우저가 추측해서 실행한다.
 */
@Component
public class ContentFileTypeDetector implements DetectFileTypePort {

    private final MagicByteFileTypeDetector signatures = new MagicByteFileTypeDetector();

    @Override
    public DetectedFileType detect(byte[] content) {
        DetectedFileType bySignature = signatures.detect(content);
        if (!bySignature.isUnknown()) {
            return bySignature;
        }
        return TextContentSniffer.sniff(content).orElseGet(DetectedFileType::unknown);
    }
}
