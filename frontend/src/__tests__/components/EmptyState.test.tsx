import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import EmptyState from '@/components/EmptyState';

describe('EmptyState', () => {
  it('기본 문구와 기본 아이콘을 그린다', () => {
    const { container } = render(<EmptyState />);

    expect(screen.getByText('데이터가 없습니다')).toBeInTheDocument();
    expect(screen.getByText('조회된 결과가 없습니다. 검색 조건을 변경해 보세요.')).toBeInTheDocument();
    expect(container.querySelector('svg')).not.toBeNull();
  });

  it('제목·설명을 바꿀 수 있다', () => {
    render(<EmptyState title="정산 내역 없음" description="기간을 넓혀 보세요" />);

    expect(screen.getByText('정산 내역 없음')).toBeInTheDocument();
    expect(screen.getByText('기간을 넓혀 보세요')).toBeInTheDocument();
  });

  it('아이콘을 주면 기본 아이콘 대신 그것을 쓴다', () => {
    render(<EmptyState icon={<span data-testid="custom-icon">📭</span>} />);

    expect(screen.getByTestId('custom-icon')).toBeInTheDocument();
  });

  it('액션이 없으면 버튼을 그리지 않는다', () => {
    render(<EmptyState />);

    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('액션 버튼을 누르면 콜백이 실행된다', async () => {
    const onClick = vi.fn();
    render(<EmptyState action={{ label: '조건 초기화', onClick }} />);

    await userEvent.click(screen.getByRole('button', { name: '조건 초기화' }));

    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
