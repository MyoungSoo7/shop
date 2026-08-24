import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GomokuGame from '@/pages/GomokuGame';

const BOARD_SIZE = 15;

/** 15×15 격자의 (row, col) 칸 — 렌더 순서가 곧 좌표다. */
const cellAt = (container: HTMLElement, row: number, col: number) =>
  container.querySelectorAll('.inline-grid > div')[row * BOARD_SIZE + col] as HTMLElement;

const place = async (container: HTMLElement, moves: [number, number][]) => {
  for (const [r, c] of moves) {
    await userEvent.click(cellAt(container, r, c));
  }
};

describe('GomokuGame', () => {
  it('빈 15×15 판과 흑돌 차례로 시작한다', () => {
    const { container } = render(<GomokuGame />);

    expect(container.querySelectorAll('.inline-grid > div')).toHaveLength(BOARD_SIZE * BOARD_SIZE);
    expect(screen.getByText('흑돌')).toBeInTheDocument();
  });

  it('한 수를 두면 차례가 백돌로 넘어간다', async () => {
    const { container } = render(<GomokuGame />);

    await place(container, [[7, 7]]);

    expect(screen.getByText('백돌')).toBeInTheDocument();
  });

  it('이미 돌이 있는 칸은 무시한다 (차례가 바뀌지 않는다)', async () => {
    const { container } = render(<GomokuGame />);

    await place(container, [[7, 7], [7, 7]]);

    expect(screen.getByText('백돌')).toBeInTheDocument();
  });

  it('가로 5목이면 흑돌 승리를 선언한다', async () => {
    const { container } = render(<GomokuGame />);

    // 흑: (0,0)~(0,4) / 백: (5,0)~(5,3) 로 번갈아 둔다
    await place(container, [
      [0, 0], [5, 0],
      [0, 1], [5, 1],
      [0, 2], [5, 2],
      [0, 3], [5, 3],
      [0, 4],
    ]);

    expect(screen.getByText('🎉 흑돌 승리! 🎉')).toBeInTheDocument();
  });

  it('세로 5목도 승리로 판정한다', async () => {
    const { container } = render(<GomokuGame />);

    await place(container, [
      [0, 0], [0, 5],
      [1, 0], [1, 5],
      [2, 0], [2, 5],
      [3, 0], [3, 5],
      [4, 0],
    ]);

    expect(screen.getByText('🎉 흑돌 승리! 🎉')).toBeInTheDocument();
  });

  it('대각선 5목도 승리로 판정한다', async () => {
    const { container } = render(<GomokuGame />);

    await place(container, [
      [0, 0], [10, 0],
      [1, 1], [10, 1],
      [2, 2], [10, 2],
      [3, 3], [10, 3],
      [4, 4],
    ]);

    expect(screen.getByText('🎉 흑돌 승리! 🎉')).toBeInTheDocument();
  });

  it('백돌도 5목이면 이긴다', async () => {
    const { container } = render(<GomokuGame />);

    await place(container, [
      [10, 0], [0, 0],
      [11, 0], [0, 1],
      [12, 0], [0, 2],
      [13, 0], [0, 3],
      [10, 5], [0, 4],
    ]);

    expect(screen.getByText('🎉 백돌 승리! 🎉')).toBeInTheDocument();
  });

  it('승부가 난 뒤에는 더 둘 수 없다', async () => {
    const { container } = render(<GomokuGame />);
    await place(container, [
      [0, 0], [5, 0],
      [0, 1], [5, 1],
      [0, 2], [5, 2],
      [0, 3], [5, 3],
      [0, 4],
    ]);

    await place(container, [[8, 8]]);

    expect(screen.getByText('🎉 흑돌 승리! 🎉')).toBeInTheDocument();
  });

  it('새 게임을 누르면 판과 차례가 초기화된다', async () => {
    const { container } = render(<GomokuGame />);
    await place(container, [[7, 7]]);

    await userEvent.click(screen.getByRole('button', { name: '새 게임' }));

    expect(screen.getByText('흑돌')).toBeInTheDocument();
    // 규칙 안내("5개를 연속으로 놓으면 승리!")는 남고 승자 배너만 사라진다
    expect(screen.queryByText(/🎉 .+ 승리! 🎉/)).not.toBeInTheDocument();
  });
});
