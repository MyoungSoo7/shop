import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fetchCurrentWeather, describeWeatherCode } from '@/api/weather';

/**
 * weather 는 공용 axios 인스턴스를 쓰지 않고 브라우저 fetch 를 직접 호출한다(외부 도메인).
 * 그래서 여기서는 axios 가 아니라 global fetch 를 스텁한다.
 */
const stubFetch = (impl: (url: string) => unknown) => {
  const spy = vi.fn((url: string) => Promise.resolve(impl(url)));
  vi.stubGlobal('fetch', spy);
  return spy;
};

beforeEach(() => {
  vi.unstubAllGlobals();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('fetchCurrentWeather', () => {
  it('기본 좌표(서울시청)로 현재 기온·날씨코드를 조회한다', async () => {
    const spy = stubFetch(() => ({
      ok: true,
      json: () => Promise.resolve({ current: { temperature_2m: 21.4, weather_code: 3 } }),
    }));

    const result = await fetchCurrentWeather();

    expect(result).toEqual({ temperature: 21.4, weatherCode: 3 });
    const url = spy.mock.calls[0][0];
    expect(url).toContain('latitude=37.57');
    expect(url).toContain('longitude=126.98');
    expect(url).toContain('current=temperature_2m,weather_code');
  });

  it('좌표를 넘기면 그 좌표로 조회한다', async () => {
    const spy = stubFetch(() => ({
      ok: true,
      json: () => Promise.resolve({ current: { temperature_2m: -3, weather_code: 71 } }),
    }));

    const result = await fetchCurrentWeather(35.18, 129.07);

    expect(result).toEqual({ temperature: -3, weatherCode: 71 });
    expect(spy.mock.calls[0][0]).toContain('latitude=35.18');
    expect(spy.mock.calls[0][0]).toContain('longitude=129.07');
  });

  it('weather_code 가 숫자가 아니면 0 으로 보정한다', async () => {
    stubFetch(() => ({
      ok: true,
      json: () => Promise.resolve({ current: { temperature_2m: 10, weather_code: null } }),
    }));

    await expect(fetchCurrentWeather()).resolves.toEqual({ temperature: 10, weatherCode: 0 });
  });

  it('HTTP 실패면 null 로 폴백한다 (페이지가 수동 계절 선택으로 내려가게)', async () => {
    stubFetch(() => ({ ok: false, json: () => Promise.resolve({}) }));

    await expect(fetchCurrentWeather()).resolves.toBeNull();
  });

  it('current 필드가 없으면 null 을 반환한다', async () => {
    stubFetch(() => ({ ok: true, json: () => Promise.resolve({}) }));

    await expect(fetchCurrentWeather()).resolves.toBeNull();
  });

  it('temperature_2m 이 숫자가 아니면 null 을 반환한다', async () => {
    stubFetch(() => ({
      ok: true,
      json: () => Promise.resolve({ current: { temperature_2m: 'warm', weather_code: 0 } }),
    }));

    await expect(fetchCurrentWeather()).resolves.toBeNull();
  });

  it('네트워크 예외를 삼키고 null 을 반환한다', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('network down'))));

    await expect(fetchCurrentWeather()).resolves.toBeNull();
  });
});

describe('describeWeatherCode', () => {
  it.each([
    [0, '맑음'],
    [1, '구름'],
    [3, '구름'],
    [45, '안개'],
    [48, '안개'],
    [61, '비'],
    [67, '비'],
    [71, '눈'],
    [77, '눈'],
    [80, '소나기'],
    [82, '소나기'],
    [85, '눈 소나기'],
    [86, '눈 소나기'],
    [95, '뇌우'],
    [99, '뇌우'],
  ])('WMO %i → %s', (code, expected) => {
    expect(describeWeatherCode(code)).toBe(expected);
  });
});
