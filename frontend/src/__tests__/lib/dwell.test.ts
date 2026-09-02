import { describe, expect, it } from 'vitest';
import { humanizeDwell, LONG_DWELL_SECONDS } from '@/lib/dwell';

/**
 * 체류 시간 표기.
 *
 * 여기서 지키려는 것은 "예쁘게 보이는가" 가 아니라 **경계에서 단위가 안 바뀌는가** 다.
 * 59분 59초가 "0시간" 으로 접히면 그 줄은 병목이 아니라 정상으로 읽힌다 — 이 화면이
 * 답하려는 유일한 질문("어디서 오래 멈췄나")을 표기가 거꾸로 뒤집는 셈이다.
 */
describe('humanizeDwell', () => {
  it('1분 미만은 초로 보여 준다', () => {
    expect(humanizeDwell(0)).toBe('0초');
    expect(humanizeDwell(59)).toBe('59초');
  });

  it('분·시간·일 경계에서 단위가 정확히 넘어간다', () => {
    expect(humanizeDwell(60)).toBe('1분');
    expect(humanizeDwell(3_599)).toBe('59분');
    expect(humanizeDwell(3_600)).toBe('1시간');
    expect(humanizeDwell(86_399)).toBe('23시간 59분');
    expect(humanizeDwell(86_400)).toBe('1일');
  });

  it('나머지가 0 이면 하위 단위를 안 붙인다 — "2시간 0분" 은 사람이 쓰는 말이 아니다', () => {
    expect(humanizeDwell(7_200)).toBe('2시간');
    expect(humanizeDwell(172_800)).toBe('2일');
  });

  it('나머지가 있으면 하위 단위를 붙인다', () => {
    expect(humanizeDwell(5_400)).toBe('1시간 30분');
    expect(humanizeDwell(90_000)).toBe('1일 1시간');
  });

  it('강조 임계는 하루다 — 임계 자체가 포함이라 24시간 정각도 강조된다', () => {
    expect(LONG_DWELL_SECONDS).toBe(86_400);
    expect(86_400 >= LONG_DWELL_SECONDS).toBe(true);
    expect(86_399 >= LONG_DWELL_SECONDS).toBe(false);
  });
});
