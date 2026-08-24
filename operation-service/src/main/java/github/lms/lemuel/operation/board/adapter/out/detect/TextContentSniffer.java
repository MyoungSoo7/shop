package github.lms.lemuel.operation.board.adapter.out.detect;

import github.lms.lemuel.operation.board.domain.DetectedFileType;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CoderResult;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * 텍스트 판정기 — 시그니처가 없는 형식을 <b>내용으로</b> 가린다.
 *
 * <p>텍스트 파일(txt·csv·md·json…)은 매직바이트가 없다. 그래서 "확장자가 .txt 면 통과"로 가면
 * 검사 자체가 사라진다 — 아무 바이너리나 이름만 바꿔 올릴 수 있게 된다. 대신 <b>내용이 실제로
 * 텍스트인지</b> 세 가지로 본다:
 *
 * <ol>
 *   <li><b>NUL 바이트가 없다</b> — 텍스트에는 거의 나오지 않고, 실행 파일·이미지에는 흔하다.
 *       가장 값싸고 강한 신호라 먼저 본다.</li>
 *   <li><b>UTF-8 로 디코딩된다</b> — 깨지는 바이트열이 있으면 텍스트가 아니다.</li>
 *   <li><b>제어문자 비율이 낮다</b> — 탭·개행·캐리지리턴을 뺀 제어문자가 섞여 있으면 바이너리다.</li>
 * </ol>
 *
 * <p>판정을 <b>앞부분만</b> 보고 내린다. 20MB 파일을 전부 디코딩할 이유가 없고, 위장은 앞부분에서
 * 이미 드러난다. 잘린 경계에서 글자가 반 토막 나는 것은 오탐이 아니라 <b>잘림</b>이므로 눈감아 준다.
 *
 * <p><b>안전은 판정이 아니라 서빙에서 온다</b>: 텍스트로 판정돼도 다운로드는 언제나
 * {@code attachment} + {@code nosniff} 다(이미지만 inline). HTML 을 {@code .txt} 로 올려도
 * 브라우저가 문서로 열지 못한다.
 */
final class TextContentSniffer {

    /** 앞 8KB 면 위장 여부가 드러난다. */
    private static final int SNIFF_LIMIT = 8 * 1024;

    /** 제어문자 허용 비율(%). 서식 문자를 뺀 제어문자가 이보다 많으면 바이너리로 본다. */
    private static final int CONTROL_PERCENT_LIMIT = 1;

    private static final DetectedFileType TEXT = DetectedFileType.of(
            "txt", "text/plain;charset=UTF-8", false,
            "csv", "tsv", "md", "markdown", "log", "json", "yml", "yaml");

    private TextContentSniffer() {
    }

    static Optional<DetectedFileType> sniff(byte[] content) {
        if (content == null || content.length == 0) {
            return Optional.empty();
        }
        byte[] head = content.length > SNIFF_LIMIT ? Arrays.copyOf(content, SNIFF_LIMIT) : content;
        boolean truncated = content.length > SNIFF_LIMIT;

        if (containsNul(head)) {
            return Optional.empty();
        }
        CharBuffer decoded = decodeUtf8(head, truncated);
        if (decoded == null || hasTooManyControlChars(decoded)) {
            return Optional.empty();
        }
        return Optional.of(TEXT);
    }

    private static boolean containsNul(byte[] head) {
        for (byte b : head) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 디코딩에 실패하면 null.
     *
     * <p>잘린 입력에는 {@code endOfInput=false} 로 디코딩한다 — 끝에 걸친 반 토막 글자를
     * "아직 안 온 바이트"로 보고 오류로 치지 않는 것이 디코더의 스트리밍 계약이다.
     * (임의로 몇 바이트 잘라 재시도하는 방식은 글자 경계를 못 맞춰 정상 텍스트를 떨어뜨린다.)
     */
    private static CharBuffer decodeUtf8(byte[] head, boolean truncated) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        CharBuffer out = CharBuffer.allocate(head.length + 1);
        CoderResult result = decoder.decode(ByteBuffer.wrap(head), out, !truncated);
        if (result.isError()) {
            return null;
        }
        if (!truncated && decoder.flush(out).isError()) {
            return null;
        }
        out.flip();
        return out;
    }

    private static boolean hasTooManyControlChars(CharBuffer decoded) {
        int control = 0;
        int total = decoded.remaining();
        if (total == 0) {
            return true;
        }
        for (int i = 0; i < total; i++) {
            char c = decoded.charAt(i);
            // 탭·개행·캐리지리턴·폼피드는 서식이지 제어가 아니다. BOM(FEFF)도 허용한다.
            if (c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '﻿') {
                continue;
            }
            if (Character.isISOControl(c)) {
                control++;
            }
        }
        // 부동소수 없이 비교한다 — control/total > 1% ⇔ control*100 > total*1.
        return control * 100 > total * CONTROL_PERCENT_LIMIT;
    }
}
