package github.lms.lemuel.operation.board.adapter.out.sanitize;

import github.lms.lemuel.operation.board.application.port.out.SanitizeHtmlPort;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * jsoup 화이트리스트 기반 HTML 정화 어댑터.
 *
 * <p><b>화이트리스트인 것이 핵심이다.</b> "위험한 태그를 지운다"는 블랙리스트는 새 우회 기법이
 * 나올 때마다 뚫린다 — {@code <svg/onload=>} 처럼 태그·속성·프로토콜을 조합한 형태는 목록으로
 * 따라잡을 수 없다. 허용할 것만 남기고 나머지는 이름조차 보지 않고 버린다.
 *
 * <p>{@link Safelist#relaxed()} 를 쓰는 이유: 게시판 본문에 필요한 표시용 태그(문단·강조·목록·
 * 표·링크·이미지·코드 블록)를 이미 담고 있고, {@code script}·{@code style}·{@code iframe}·
 * {@code object}·{@code form} 과 모든 {@code on*} 이벤트 핸들러를 포함하지 않는다.
 * 링크·이미지 프로토콜도 http(s)/mailto 로 제한돼 {@code javascript:} 와 {@code data:} 가 걸러진다.
 *
 * <p>{@code prettyPrint(false)} 인 이유: 기본 설정은 줄바꿈·들여쓰기를 재구성해 {@code <pre>} 안의
 * 코드 모양을 바꾼다. 정화가 본문의 <b>의미</b>를 바꾸면 안 된다.
 */
@Component
public class JsoupHtmlSanitizerAdapter implements SanitizeHtmlPort {

    private static final Safelist SAFELIST = Safelist.relaxed();

    private static final Document.OutputSettings OUTPUT_SETTINGS =
            new Document.OutputSettings().prettyPrint(false);

    @Override
    public String sanitize(String html) {
        if (html == null) {
            return null;
        }
        // baseUri 를 비워 상대 경로를 절대 경로로 부풀리지 않는다 — 본문은 원문 그대로가 기준이다.
        return Jsoup.clean(html, "", SAFELIST, OUTPUT_SETTINGS);
    }
}
