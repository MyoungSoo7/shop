import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import DateRangePicker from '@/components/DateRangePicker';

const noop = () => undefined;

/** 라벨이 input 과 for/id 로 묶여 있지 않아 순서로 집는다(시작일, 종료일). */
const dateInputs = (container: HTMLElement) =>
  Array.from(container.querySelectorAll<HTMLInputElement>('input[type="date"]'));

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-08-15T00:00:00Z'));
});

afterEach(() => {
  vi.useRealTimers();
});

describe('DateRangePicker', () => {
  it('시작일·종료일 값을 그대로 표시한다', () => {
    const { container } = render(
      <DateRangePicker
        startDate="2026-08-01"
        endDate="2026-08-14"
        onStartDateChange={noop}
        onEndDateChange={noop}
      />,
    );

    const [start, end] = dateInputs(container);
    expect(start.value).toBe('2026-08-01');
    expect(end.value).toBe('2026-08-14');
    expect(screen.getByText('시작일')).toBeInTheDocument();
    expect(screen.getByText('종료일')).toBeInTheDocument();
  });

  it('날짜를 바꾸면 각각의 콜백이 불린다', () => {
    const onStart = vi.fn();
    const onEnd = vi.fn();
    const { container } = render(
      <DateRangePicker
        startDate="2026-08-01"
        endDate="2026-08-14"
        onStartDateChange={onStart}
        onEndDateChange={onEnd}
      />,
    );

    const [start, end] = dateInputs(container);
    fireEvent.change(start, { target: { value: '2026-08-05' } });
    fireEvent.change(end, { target: { value: '2026-08-20' } });

    expect(onStart).toHaveBeenCalledWith('2026-08-05');
    expect(onEnd).toHaveBeenCalledWith('2026-08-20');
  });

  it('오류 문구가 있으면 표시하고 입력 테두리를 붉게 만든다', () => {
    const { container } = render(
      <DateRangePicker
        startDate=""
        endDate=""
        onStartDateChange={noop}
        onEndDateChange={noop}
        error="시작일이 종료일보다 늦습니다"
      />,
    );

    expect(screen.getByText('시작일이 종료일보다 늦습니다')).toBeInTheDocument();
    dateInputs(container).forEach((i) => expect(i.className).toContain('border-red-300'));
  });

  it('오류가 없으면 기본 테두리를 쓴다', () => {
    const { container } = render(
      <DateRangePicker startDate="" endDate="" onStartDateChange={noop} onEndDateChange={noop} />,
    );

    dateInputs(container).forEach((i) => expect(i.className).toContain('border-gray-300'));
  });

  it('onQuickSelect 가 없으면 빠른 선택 칩을 그리지 않는다', () => {
    render(
      <DateRangePicker startDate="" endDate="" onStartDateChange={noop} onEndDateChange={noop} />,
    );

    expect(screen.queryByText('빠른 선택:')).not.toBeInTheDocument();
  });

  it.each([
    ['최근 7일', '2026-08-08'],
    ['최근 30일', '2026-07-16'],
    ['이번 달', '2026-08-01'],
  ])('%s 을 고르면 시작일 %s ~ 오늘을 넘긴다', (label, expectedStart) => {
    const onQuick = vi.fn();
    render(
      <DateRangePicker
        startDate=""
        endDate=""
        onStartDateChange={noop}
        onEndDateChange={noop}
        onQuickSelect={onQuick}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: label }));

    expect(onQuick).toHaveBeenCalledWith(expectedStart, '2026-08-15');
  });
});
