/**
 * 체류 시간 표기 — 초를 사람이 읽는 단위로.
 *
 * <p>"45시간" 이 "162000초" 보다 훨씬 빨리 이상함을 드러낸다. 이 화면들이 답하려는 질문이
 * "어디서 오래 멈췄나" 라서, 읽는 데 산수가 필요한 표기는 그 질문을 못 푼다.
 *
 * <p>화면 파일이 아니라 여기에 있는 이유는 두 가지다 — 상태 이력 말고도 체류 시간을 보여 줄
 * 화면이 더 생길 자리이고(배송 지연·승인 대기), 컴포넌트 파일이 함수까지 내보내면
 * react-refresh 의 HMR 경계가 깨진다(eslint `react-refresh/only-export-components`).
 */
export function humanizeDwell(seconds: number): string {
  if (seconds < 60) return `${seconds}초`;
  if (seconds < 3_600) return `${Math.floor(seconds / 60)}분`;
  if (seconds < 86_400) {
    const h = Math.floor(seconds / 3_600);
    const m = Math.floor((seconds % 3_600) / 60);
    return m > 0 ? `${h}시간 ${m}분` : `${h}시간`;
  }
  const d = Math.floor(seconds / 86_400);
  const h = Math.floor((seconds % 86_400) / 3_600);
  return h > 0 ? `${d}일 ${h}시간` : `${d}일`;
}

/** 이 시간 넘게 한 상태에 머물렀으면 눈에 띄게 표시한다. */
export const LONG_DWELL_SECONDS = 24 * 3_600;
