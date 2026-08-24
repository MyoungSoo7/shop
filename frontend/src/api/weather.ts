/**
 * Open-Meteo 공개 날씨 API (API 키 불필요).
 *
 * 공용 axios 인스턴스(`@/api/axios`)를 쓰지 않는다 — 그 인스턴스는 JWT Authorization
 * 헤더와 401/403/500 토스트 인터셉터가 붙어 있어, 외부 도메인 호출에 부적절하다.
 * 여기서는 브라우저 기본 fetch 로 직접 호출하고, 실패 시 null 을 돌려
 * 페이지가 수동 계절 선택으로 폴백하게 한다.
 */

export interface CurrentWeather {
  temperature: number; // ℃
  weatherCode: number; // WMO weather code
}

// 서울시청 좌표 (기본값)
const SEOUL_LAT = 37.57;
const SEOUL_LON = 126.98;

export async function fetchCurrentWeather(
  latitude: number = SEOUL_LAT,
  longitude: number = SEOUL_LON,
): Promise<CurrentWeather | null> {
  try {
    const url =
      `https://api.open-meteo.com/v1/forecast?latitude=${latitude}` +
      `&longitude=${longitude}&current=temperature_2m,weather_code`;
    const res = await fetch(url);
    if (!res.ok) return null;
    const data = await res.json();
    const current = data?.current;
    if (!current || typeof current.temperature_2m !== 'number') return null;
    return {
      temperature: current.temperature_2m,
      weatherCode: typeof current.weather_code === 'number' ? current.weather_code : 0,
    };
  } catch {
    return null;
  }
}

/** WMO weather code → 짧은 한글 설명 (아이콘/문구용) */
export function describeWeatherCode(code: number): string {
  if (code === 0) return '맑음';
  if (code <= 3) return '구름';
  if (code <= 48) return '안개';
  if (code <= 67) return '비';
  if (code <= 77) return '눈';
  if (code <= 82) return '소나기';
  if (code <= 86) return '눈 소나기';
  return '뇌우';
}
