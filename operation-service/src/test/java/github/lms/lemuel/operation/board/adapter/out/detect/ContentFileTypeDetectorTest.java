package github.lms.lemuel.operation.board.adapter.out.detect;

import github.lms.lemuel.operation.board.domain.DetectedFileType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 두 단계 판정(시그니처 → 텍스트 스니핑).
 *
 * <p>텍스트 판정은 <b>"확장자가 .txt 면 통과"의 대안</b>이다. 그렇게 갔다면 아무 바이너리나 이름만
 * 바꿔 올릴 수 있었다. 그래서 이 클래스가 지키는 것은 두 가지다: ① 진짜 텍스트는 받는다
 * ② 텍스트인 척하는 바이너리는 막는다.
 */
class ContentFileTypeDetectorTest {

    private final ContentFileTypeDetector detector = new ContentFileTypeDetector();

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("평문·CSV·JSON 을 텍스트로 판정하고 확장자 별칭을 인정한다")
    void detectsText() {
        DetectedFileType type = detector.detect(utf8("주문번호,금액\n1001,15000\n"));

        assertThat(type.isUnknown()).isFalse();
        assertThat(type.extension()).isEqualTo("txt");
        assertThat(type.contentType()).startsWith("text/plain");
        assertThat(type.image()).isFalse();
        assertThat(type.matches("csv")).isTrue();
        assertThat(type.matches("md")).isTrue();
        assertThat(type.matches("json")).isTrue();
        assertThat(type.matches("exe")).isFalse();
    }

    @Test
    @DisplayName("한글·이모지도 UTF-8 이면 텍스트다")
    void detectsUnicodeText() {
        assertThat(detector.detect(utf8("안녕하세요 🙂\n두 번째 줄")).extension()).isEqualTo("txt");
    }

    @Test
    @DisplayName("BOM 이 붙어 있어도 텍스트다 — 엑셀이 저장한 CSV 가 그렇다")
    void allowsBom() {
        byte[] withBom = utf8("﻿주문번호,금액\n1001,15000\n");

        assertThat(detector.detect(withBom).extension()).isEqualTo("txt");
    }

    @Test
    @DisplayName("NUL 이 섞이면 텍스트가 아니다 — 가장 값싸고 강한 바이너리 신호")
    void rejectsNulBytes() {
        byte[] disguised = new byte[]{'h', 'e', 'l', 'l', 'o', 0, 'w', 'o', 'r', 'l', 'd'};

        assertThat(detector.detect(disguised).isUnknown()).isTrue();
    }

    @Test
    @DisplayName("UTF-8 로 안 풀리면 텍스트가 아니다")
    void rejectsInvalidUtf8() {
        byte[] broken = new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xA9, (byte) 0xFF, (byte) 0xFE};

        assertThat(detector.detect(broken).isUnknown()).isTrue();
    }

    @Test
    @DisplayName("제어문자가 많으면 텍스트가 아니다 — 서식 문자(탭·개행)는 세지 않는다")
    void rejectsControlHeavy() {
        StringBuilder controls = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            controls.append('a').append('');
        }
        assertThat(detector.detect(utf8(controls.toString())).isUnknown()).isTrue();

        // 탭·개행만 잔뜩 있는 것은 정상 텍스트다
        assertThat(detector.detect(utf8("a\tb\tc\r\nd\te\tf\r\n".repeat(50))).extension()).isEqualTo("txt");
    }

    @Test
    @DisplayName("시그니처가 텍스트 스니핑보다 먼저다 — PDF 앞부분은 아스키다")
    void signatureWinsOverText() {
        DetectedFileType type = detector.detect("%PDF-1.7\n텍스트처럼 보이는 앞부분".getBytes(StandardCharsets.UTF_8));

        assertThat(type.extension()).isEqualTo("pdf");
    }

    @Test
    @DisplayName("이미지는 그대로 이미지로 판정된다 — 2단계가 1단계를 흐리지 않는다")
    void imagesUnaffected() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

        assertThat(detector.detect(png).extension()).isEqualTo("png");
        assertThat(detector.detect(png).image()).isTrue();
    }

    @Test
    @DisplayName("빈 입력·null 은 unknown")
    void emptyInput() {
        assertThat(detector.detect(new byte[0]).isUnknown()).isTrue();
        assertThat(detector.detect(null).isUnknown()).isTrue();
    }

    @Test
    @DisplayName("앞부분만 보고 판정한다 — 큰 텍스트도 통과하고 잘린 글자에 걸리지 않는다")
    void sniffsPrefixOnly() {
        // 8KB 를 넘기고, 경계에 다바이트 글자가 걸치도록 한글로 채운다
        byte[] large = utf8("가나다라마바사아자차".repeat(2000));

        assertThat(large.length).isGreaterThan(8 * 1024);
        assertThat(detector.detect(large).extension()).isEqualTo("txt");
    }

    @Test
    @DisplayName("앞부분이 텍스트여도 그 안에 NUL 이 있으면 막는다 — 텍스트로 감싼 바이너리")
    void binaryHiddenAfterTextPrefix() {
        byte[] mixed = new byte[100];
        byte[] prefix = utf8("보고서 요약\n");
        System.arraycopy(prefix, 0, mixed, 0, prefix.length);
        mixed[prefix.length] = 0;

        assertThat(detector.detect(mixed).isUnknown()).isTrue();
    }
}
