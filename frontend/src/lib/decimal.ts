/**
 * 금액 십진 문자열을 정밀도 손실 없이 표시하기 위한 문자열 기반 포맷터.
 *
 * 백엔드 금액 계약은 BigDecimal 을 소수 문자열로 직렬화한 값이다(AGENTS.md 돈 규칙).
 * 이를 `Number()` 로 바꾸면 JS 안전 정수 범위(2^53-1) 밖이거나 소수 자리가 유효할 때
 * 표시 직전에 자릿수가 조용히 깨진다. 그래서 문자열 그대로 부호·자릿수를 처리한다.
 *
 * 입력이 이미 `number` 인 값(비율 등 금액이 아닌 지표)은 보존할 정밀도가 없으므로
 * 기존 로케일 포맷을 그대로 쓴다 — 표시가 바뀌지 않는다.
 */

/** 부호 · 정수부 · (선택)소수부. 지수표기는 금액 계약에 없어 받지 않는다. */
const DECIMAL_RE = /^([+-]?)(\d+)(?:\.(\d+))?$/;

const isAllZero = (digits: string): boolean => /^0*$/.test(digits);

/** 정수부 문자열에 천단위 구분자를 넣는다 (Number 경유 없음). */
const groupThousands = (digits: string): string =>
  digits.replace(/^0+(?=\d)/, '').replace(/\B(?=(\d{3})+(?!\d))/g, ',');

/**
 * 표시용 문자열로 변환한다. 변환 불가(널·형식 위반·NaN)면 `null`.
 * 소수부의 무의미한 뒤쪽 0 은 떼고, 남는 자리는 반올림 없이 전부 보존한다.
 */
export const formatDecimal = (value: string | number | null | undefined): string | null => {
  if (value === null || value === undefined) return null;
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value.toLocaleString('ko-KR') : null;
  }

  const matched = DECIMAL_RE.exec(value.trim());
  if (!matched) return null;

  const [, sign, integer, fraction = ''] = matched;
  const trimmedFraction = fraction.replace(/0+$/, '');
  const zero = isAllZero(integer) && trimmedFraction === '';
  const negative = sign === '-' && !zero;

  return `${negative ? '-' : ''}${groupThousands(integer)}${trimmedFraction ? `.${trimmedFraction}` : ''}`;
};

/**
 * 부호만 판별한다 (색상·`+` 접두 결정용). 변환 불가면 `null`.
 * `-0.00` 처럼 부호만 붙은 0 은 0 으로 본다.
 */
export const decimalSign = (value: string | number | null | undefined): -1 | 0 | 1 | null => {
  if (value === null || value === undefined) return null;
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) return null;
    return value > 0 ? 1 : value < 0 ? -1 : 0;
  }

  const matched = DECIMAL_RE.exec(value.trim());
  if (!matched) return null;

  const [, sign, integer, fraction = ''] = matched;
  if (isAllZero(integer) && isAllZero(fraction)) return 0;
  return sign === '-' ? -1 : 1;
};
