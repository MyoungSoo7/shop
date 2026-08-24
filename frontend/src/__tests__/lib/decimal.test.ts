import { describe, it, expect } from 'vitest';
import { decimalSign, formatDecimal } from '@/lib/decimal';

/**
 * 이슈 #184 — 금액 소수 문자열을 Number() 로 바꾸면 안전 정수 범위 밖·유효 소수에서
 * 표시 직전에 자릿수가 조용히 깨진다. 아래 테스트가 그 회귀 게이트다.
 */
describe('formatDecimal', () => {
  it('안전 정수 범위를 넘는 금액도 자릿수를 잃지 않는다', () => {
    // Number('9007199254740993') === 9007199254740992 — 마지막 자리가 깨진다
    expect(formatDecimal('9007199254740993')).toBe('9,007,199,254,740,993');
    expect(formatDecimal('123456789012345678901')).toBe('123,456,789,012,345,678,901');
  });

  it('유효 소수 자리를 반올림 없이 보존한다', () => {
    // Number('0.1234567').toLocaleString('ko-KR') === '0.123' — 조용히 잘린다
    expect(formatDecimal('0.1234567')).toBe('0.1234567');
    expect(formatDecimal('1234.5678')).toBe('1,234.5678');
  });

  it('의미 없는 뒤쪽 0 은 떼어 기존 표시와 같게 유지한다', () => {
    expect(formatDecimal('43750000.00')).toBe('43,750,000');
    expect(formatDecimal('1000.500')).toBe('1,000.5');
  });

  it('부호와 천단위 구분을 문자열로 처리한다', () => {
    expect(formatDecimal('-2500000')).toBe('-2,500,000');
    expect(formatDecimal('+2500000')).toBe('2,500,000');
    expect(formatDecimal('-0.00')).toBe('0');
    expect(formatDecimal('000123')).toBe('123');
  });

  it('수치 입력은 기존 로케일 포맷을 그대로 쓴다', () => {
    expect(formatDecimal(1234567)).toBe('1,234,567');
    expect(formatDecimal(-3.5)).toBe('-3.5');
  });

  it('표시 불가 입력은 null 을 돌려준다', () => {
    expect(formatDecimal(null)).toBeNull();
    expect(formatDecimal(undefined)).toBeNull();
    expect(formatDecimal('')).toBeNull();
    expect(formatDecimal('1e21')).toBeNull();
    expect(formatDecimal('abc')).toBeNull();
    expect(formatDecimal(Number.NaN)).toBeNull();
    expect(formatDecimal(Number.POSITIVE_INFINITY)).toBeNull();
  });
});

describe('decimalSign', () => {
  it('문자열 부호를 Number 경유 없이 판별한다', () => {
    expect(decimalSign('9007199254740993')).toBe(1);
    expect(decimalSign('-0.0000001')).toBe(-1);
    expect(decimalSign('0.0000001')).toBe(1);
  });

  it('부호만 붙은 0 은 0 으로 본다', () => {
    expect(decimalSign('-0.00')).toBe(0);
    expect(decimalSign('0')).toBe(0);
    expect(decimalSign(-0)).toBe(0);
  });

  it('수치·표시 불가 입력을 기존과 같게 다룬다', () => {
    expect(decimalSign(5)).toBe(1);
    expect(decimalSign(-5)).toBe(-1);
    expect(decimalSign(null)).toBeNull();
    expect(decimalSign('abc')).toBeNull();
    expect(decimalSign(Number.NaN)).toBeNull();
  });
});
