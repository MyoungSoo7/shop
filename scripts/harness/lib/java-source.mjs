/**
 * 자바 소스 전처리 — 하네스 검출기들이 공유하는 최소 유틸.
 *
 * <p>여기 있는 이유: 주석 제거를 정규식으로 하면 <b>문자열 리터럴을 먹는다</b>.
 * {@code src.replace(/\/\*[\s\S]*?\*\//g, '')} 는 `"/payments/*&#47;refund"` 의 `/*` 를 주석 시작으로
 * 읽고 다음 `*&#47;` 까지 지워 `"/paymentsrefund"` 로 만든다. 경로에 와일드카드가 든 설정
 * (SecurityConfig 의 `requestMatchers("/coupons/*&#47;use")` 등)에서 조용히 터지며,
 * <b>검출기는 오류 없이 "매처 6건"처럼 축소된 사실을 보고한다</b> — 실제로 2026-08-23 에 이 게이트를
 * 만들다 겪었다(79건 중 6건만 파싱됐고 BUILD 는 초록이었다).
 *
 * <p>그래서 문자열·문자 리터럴을 인식하는 스캐너로 처리한다. 성능보다 정확성이 우선이다 —
 * 게이트는 빌드당 한 번 돈다.
 */

/**
 * 자바 소스에서 주석만 제거한다. 문자열·문자 리터럴 내용은 손대지 않는다.
 *
 * @param {string} src 자바 소스 전문
 * @returns {string} 주석이 제거된 소스(라인 수는 보존되지 않는다 — 라인 번호가 필요하면 원본을 쓸 것)
 */
export function stripJavaComments(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];

    if (c === '"' || c === "'") {
      const quote = c;
      out += c;
      i++;
      while (i < n) {
        if (src[i] === '\\') {
          out += src[i] + (src[i + 1] ?? '');
          i += 2;
          continue;
        }
        out += src[i];
        if (src[i] === quote) { i++; break; }
        i++;
      }
      continue;
    }

    if (c === '/' && src[i + 1] === '*') {
      i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) i++;
      i += 2;
      out += ' ';  // 토큰이 붙어버리지 않게 공백 하나로 치환
      continue;
    }

    if (c === '/' && src[i + 1] === '/') {
      while (i < n && src[i] !== '\n') i++;
      continue;
    }

    out += c;
    i++;
  }
  return out;
}

/**
 * 여는 괄호 위치에서 시작해 짝이 맞는 닫는 괄호의 인덱스를 돌려준다.
 *
 * <p>애노테이션 인자를 정규식 비탐욕 매칭으로 자르면 인자 안의 `)`(문자열·중첩 호출)에서 끊긴다.
 * 괄호 균형으로 세는 편이 안전하다.
 *
 * @returns {number} 닫는 괄호 인덱스. 짝을 못 찾으면 -1
 */
export function matchingParen(src, openIndex) {
  let depth = 0;
  for (let i = openIndex; i < src.length; i++) {
    if (src[i] === '(') depth++;
    else if (src[i] === ')') {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

/**
 * Ant 경로 패턴을 정규식으로 바꾼다(Spring `requestMatchers` 의미론 근사).
 *
 * <p>`/**` 는 <b>뒤가 비어도 매칭</b>한다 — `/admin/points/**` 가 `/admin/points` 도 덮는다는 뜻이고,
 * 이걸 놓치면 멀쩡히 보호된 경로를 미보호로 잘못 보고한다.
 */
export function antToRegExp(pattern) {
  let re = '';
  for (let i = 0; i < pattern.length; i++) {
    const c = pattern[i];
    if (c === '*' && pattern[i + 1] === '*') {
      if (re.endsWith('/')) re = re.slice(0, -1) + '(?:/.*)?';
      else re += '.*';
      i++;
    } else if (c === '*') {
      re += '[^/]*';
    } else if (c === '?') {
      re += '[^/]';
    } else if ('\\^$.|+()[]{}'.includes(c)) {
      re += '\\' + c;
    } else {
      re += c;
    }
  }
  return new RegExp('^' + re + '$');
}
