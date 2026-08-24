import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import OptionFacetPanel from '@/components/product/OptionFacetPanel';
import type { Facet } from '@/api/facet';

/**
 * 파셋 패널의 표시 규칙 회귀 테스트.
 *
 * <p>개수·체크 상태는 서버가 준 값을 그대로 그린다 — 화면이 다시 계산하면 축 간 AND 를 SKU 단위로
 * 판정하는 서버 규칙과 어긋나고, 그 순간 어느 쪽이 맞는지 알 수 없게 된다.
 */

const facets: Facet[] = [
  {
    axisCode: '색상',
    axisName: '색상',
    values: [
      { code: '빨강', name: '빨강', productCount: 2, selected: false },
      { code: '파랑', name: '파랑', productCount: 1, selected: false },
      { code: '초록', name: '초록', productCount: 0, selected: false },
    ],
  },
  {
    axisCode: '사이즈',
    axisName: '사이즈',
    values: [{ code: 'L', name: 'L', productCount: 3, selected: true }],
  },
];

describe('OptionFacetPanel — 옵션 파셋 필터', () => {
  it('축별로 값과 상품 수를 그린다', () => {
    render(<OptionFacetPanel facets={facets} selection={{}} onToggle={vi.fn()} onClear={vi.fn()} />);

    expect(screen.getByText('색상')).toBeInTheDocument();
    expect(screen.getByText('사이즈')).toBeInTheDocument();
    expect(screen.getByLabelText(/빨강/)).toBeInTheDocument();
    expect(screen.getByText('(2)')).toBeInTheDocument();
  });

  it('체크 상태는 서버가 준 selected 를 따른다', () => {
    render(<OptionFacetPanel facets={facets} selection={{}} onToggle={vi.fn()} onClear={vi.fn()} />);

    expect(screen.getByLabelText(/L/)).toBeChecked();
    expect(screen.getByLabelText(/빨강/)).not.toBeChecked();
  });

  it('0 건인 값은 고를 수 없다 — 눌러도 빈 결과가 나오는 선택지', () => {
    render(<OptionFacetPanel facets={facets} selection={{}} onToggle={vi.fn()} onClear={vi.fn()} />);

    expect(screen.getByLabelText(/초록/)).toBeDisabled();
    expect(screen.getByLabelText(/빨강/)).toBeEnabled();
  });

  it('이미 고른 값은 0 건이어도 끌 수 있다 — 아니면 되돌릴 길이 막힌다', () => {
    const zeroButSelected: Facet[] = [{
      axisCode: '색상',
      axisName: '색상',
      values: [{ code: '빨강', name: '빨강', productCount: 0, selected: true }],
    }];

    render(<OptionFacetPanel facets={zeroButSelected} selection={{ 색상: ['빨강'] }}
                             onToggle={vi.fn()} onClear={vi.fn()} />);

    expect(screen.getByLabelText(/빨강/)).toBeEnabled();
  });

  it('값을 누르면 축·값 코드를 그대로 올려보낸다', async () => {
    const onToggle = vi.fn();
    render(<OptionFacetPanel facets={facets} selection={{}} onToggle={onToggle} onClear={vi.fn()} />);

    await userEvent.click(screen.getByLabelText(/빨강/));

    expect(onToggle).toHaveBeenCalledWith('색상', '빨강');
  });

  it('선택이 있을 때만 초기화 버튼과 개수를 보여준다', async () => {
    const onClear = vi.fn();
    const { rerender } = render(
      <OptionFacetPanel facets={facets} selection={{}} onToggle={vi.fn()} onClear={onClear} />,
    );
    expect(screen.queryByRole('button', { name: '초기화' })).not.toBeInTheDocument();

    rerender(
      <OptionFacetPanel facets={facets} selection={{ 색상: ['빨강'], 사이즈: ['L'] }}
                        onToggle={vi.fn()} onClear={onClear} />,
    );
    expect(screen.getByText('2개 선택')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '초기화' }));
    expect(onClear).toHaveBeenCalled();
  });

  it('파셋이 없으면 패널 자체를 그리지 않는다 — 옵션 없는 카탈로그에 빈 상자를 남기지 않는다', () => {
    const { container } = render(
      <OptionFacetPanel facets={[]} selection={{}} onToggle={vi.fn()} onClear={vi.fn()} />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('조회 중에는 입력을 잠근다 — 응답 전에 또 누르면 요청이 엇갈린다', () => {
    render(<OptionFacetPanel facets={facets} selection={{}} onToggle={vi.fn()} onClear={vi.fn()} loading />);

    expect(screen.getByLabelText(/빨강/)).toBeDisabled();
  });
});
