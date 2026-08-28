package github.lms.lemuel.partner.application.port.dto;

import java.nio.charset.StandardCharsets;

/**
 * 주문 내역 CSV 다운로드 결과.
 *
 * <p>{@code truncated} 를 값으로 들고 다니는 이유가 이 타입의 존재 이유다. 레퍼런스는 행수 제한이
 * 없어서 큰 기간을 고르면 백오피스가 통째로 멎었다. 제한을 걸면 멎지는 않지만, <b>잘렸다는 사실을
 * 말하지 않으면 더 나쁘다</b> — 사용자는 잘린 파일을 전량으로 믿고 정산에 쓴다. 그래서 잘림
 * 여부와 전체 건수를 함께 돌려주고, 어댑터가 응답 헤더로 노출한다.
 *
 * @param csv UTF-8 바이트. <b>BOM 은 이미 앞에 붙어 있다</b>(유스케이스가 머리줄에 넣는다).
 *            어댑터에서 한 번 더 붙이면 첫 칸 이름이 깨져 엑셀이 그 열을 못 찾는다.
 */
public record OrderExport(String filename, byte[] csv, long totalMatched, int exportedRows, boolean truncated) {

    public int byteLength() {
        return csv.length;
    }

    public static OrderExport of(String filename, String body, long totalMatched, int exportedRows) {
        return new OrderExport(filename, body.getBytes(StandardCharsets.UTF_8),
                totalMatched, exportedRows, exportedRows < totalMatched);
    }
}
