import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import BadukGame from '@/pages/BadukGame';

// 이 페이지는 API 도 라우터도 안 쓴다. 대신 착수 규칙(자살수·포획·패스 종료)이 전부
// 컴포넌트 안에 들어 있어서, 검증할 게 화면이 아니라 *규칙*이다.
//
// 칸에는 role 도 test-id 도 없다(순수 div + onClick). 그래서 좌표로 집는 헬퍼를 둔다 —
// DOM 순서가 곧 (row, col) 순서라는 게 이 헬퍼가 기대는 유일한 전제다.
const BOARD_SIZE = 19;

let container: HTMLElement;

const cells = () =>
  Array.from(container.querySelectorAll<HTMLElement>('.inline-grid > div'));

const cell = (row: number, col: number) => cells()[row * BOARD_SIZE + col];

const play = (row: number, col: number) => fireEvent.click(cell(row, col));

/** 그 칸에 놓인 돌의 색. 돌이 없으면 null. */
const stoneAt = (row: number, col: number): 'black' | 'white' | null => {
  const stone = cell(row, col).querySelector(
    '.rounded-full.w-5, .w-5.rounded-full',
  );
  if (!stone) return null;
  return stone.className.includes('from-gray-800') ? 'black' : 'white';
};

const turn = () =>
  screen.getByText('현재 차례:').parentElement?.textContent ?? '';

describe('BadukGame', () => {
  let alertSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    // jsdom 의 window.alert 는 호출하면 'Not implemented' 로 죽는다. 자살수·게임종료 경로가
    // 둘 다 alert 를 타므로 스텁하지 않으면 그 두 분기를 아예 못 지나간다.
    alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
    container = render(<BadukGame />).container;
  });

  afterEach(() => {
    alertSpy.mockRestore();
  });

  describe('초기 상태', () => {
    it('19×19 빈 바둑판을 그리고 흑돌부터 시작한다', () => {
      expect(cells()).toHaveLength(BOARD_SIZE * BOARD_SIZE);
      expect(cells().some((c) => c.querySelector('.w-5'))).toBe(false);
      expect(turn()).toContain('흑돌');
      expect(screen.getByText('🎯 바둑 게임')).toBeInTheDocument();
    });

    it('화점 9개를 3·9·15 교차점에만 표시한다', () => {
      const marked = cells().filter((c) => c.querySelector('.w-2.h-2'));
      expect(marked).toHaveLength(9);
      // 표본으로 한 점만 좌표까지 확인한다 — 9개 전부 확인하면 화점 좌표표를
      // 테스트에 복사하는 셈이라, 구현이 바뀌면 같이 틀리는 중복이 된다.
      expect(cell(3, 3).querySelector('.w-2.h-2')).toBeTruthy();
      expect(cell(4, 4).querySelector('.w-2.h-2')).toBeFalsy();
    });

    it('잡은 돌은 양쪽 다 0 에서 출발하고 종료 배너는 없다', () => {
      expect(
        screen.getByText('흑돌이 잡은 돌').nextElementSibling,
      ).toHaveTextContent('0');
      expect(
        screen.getByText('백돌이 잡은 돌').nextElementSibling,
      ).toHaveTextContent('0');
      expect(
        screen.queryByText('게임이 종료되었습니다!'),
      ).not.toBeInTheDocument();
    });
  });

  describe('착수', () => {
    it('돌을 놓으면 차례가 넘어가고 마지막 수에 표시가 붙는다', () => {
      play(4, 4);

      expect(stoneAt(4, 4)).toBe('black');
      expect(turn()).toContain('백돌');
      expect(cell(4, 4).querySelector('.ring-4')).toBeTruthy();

      play(10, 10);

      expect(stoneAt(10, 10)).toBe('white');
      expect(turn()).toContain('흑돌');
      // 마지막 수 표시는 하나뿐이어야 한다 — 누적되면 '최근'이라는 의미가 사라진다.
      expect(cell(4, 4).querySelector('.ring-4')).toBeFalsy();
      expect(container.querySelectorAll('.ring-4')).toHaveLength(1);
    });

    it('이미 돌이 있는 칸은 클릭해도 아무 일이 없다', () => {
      play(4, 4);
      play(4, 4); // 백돌 차례에 흑돌 자리를 다시 클릭

      expect(stoneAt(4, 4)).toBe('black');
      expect(turn()).toContain('백돌'); // 차례가 넘어가지 않았다
      expect(alertSpy).not.toHaveBeenCalled();
    });
  });

  describe('포획', () => {
    it('활로를 다 막으면 상대 돌을 들어내고 잡은 수를 올린다', () => {
      // 귀(0,0)의 백돌은 활로가 (0,1)·(1,0) 둘뿐이라 두 수로 잡힌다.
      play(0, 1); // 흑
      play(0, 0); // 백
      play(1, 0); // 흑 — 이 수로 (0,0) 백돌이 잡힌다

      expect(stoneAt(0, 0)).toBeNull();
      expect(
        screen.getByText('흑돌이 잡은 돌').nextElementSibling,
      ).toHaveTextContent('1');
      expect(
        screen.getByText('백돌이 잡은 돌').nextElementSibling,
      ).toHaveTextContent('0');
    });

    it('그룹으로 이어진 돌은 통째로 잡힌다', () => {
      // 백 (0,0)·(0,1) 두 점을 한 그룹으로 만들고 활로 (1,0)·(1,1)·(0,2) 를 모두 막는다.
      play(0, 2); // 흑
      play(0, 0); // 백
      play(1, 0); // 흑
      play(0, 1); // 백 — (0,0) 과 이어져 한 그룹
      play(1, 1); // 흑 — 마지막 활로를 막아 두 점을 한 번에 잡는다

      expect(stoneAt(0, 0)).toBeNull();
      expect(stoneAt(0, 1)).toBeNull();
      expect(
        screen.getByText('흑돌이 잡은 돌').nextElementSibling,
      ).toHaveTextContent('2');
    });

    it('상대를 잡는 수는 스스로 활로가 없어도 둘 수 있다', () => {
      // 흑이 마지막 활로를 메우는 자리는 그 자체로는 활로 0 이지만, 두는 순간
      // 백 그룹이 걷혀 활로가 생긴다. 자살수 판정이 포획 판정보다 먼저 오면
      // 이 수가 잘못 막히는데, 그걸 잡아내는 테스트다.
      play(0, 1); // 흑
      play(0, 0); // 백
      play(1, 1); // 흑
      play(1, 0); // 백 — (0,0) 과 한 그룹, 남은 활로는 (2,0) 하나
      play(2, 0); // 흑 — 자기 활로는 (3,0) 이 있지만 백 두 점을 잡는다

      expect(alertSpy).not.toHaveBeenCalled();
      expect(stoneAt(0, 0)).toBeNull();
      expect(stoneAt(1, 0)).toBeNull();
      expect(stoneAt(2, 0)).toBe('black');
      expect(
        screen.getByText('흑돌이 잡은 돌').nextElementSibling,
      ).toHaveTextContent('2');
    });
  });

  describe('자살수', () => {
    it('활로 없는 자리는 경고하고 돌을 놓지 않는다', () => {
      play(5, 5); // 흑 (더미)
      play(0, 1); // 백
      play(5, 6); // 흑 (더미)
      play(1, 0); // 백 — 이제 (0,0) 은 흑에게 활로 0 인 자리
      play(0, 0); // 흑 — 자살수

      expect(alertSpy).toHaveBeenCalledWith(
        '그곳에는 둘 수 없습니다. (자살수)',
      );
      expect(stoneAt(0, 0)).toBeNull();
      expect(turn()).toContain('흑돌'); // 차례를 잃지 않는다
    });
  });

  describe('패스와 종료', () => {
    it('한 번 패스하면 차례만 넘어간다', () => {
      fireEvent.click(screen.getByRole('button', { name: '패스' }));

      expect(turn()).toContain('백돌');
      expect(alertSpy).not.toHaveBeenCalled();
      expect(
        screen.queryByText('게임이 종료되었습니다!'),
      ).not.toBeInTheDocument();
    });

    it('양측이 연속으로 패스하면 게임이 끝난다', () => {
      const pass = () =>
        fireEvent.click(screen.getByRole('button', { name: '패스' }));
      pass();
      pass();

      expect(alertSpy).toHaveBeenCalledWith(
        '양측 모두 패스했습니다. 게임이 종료되었습니다.',
      );
      expect(screen.getByText('게임이 종료되었습니다!')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '패스' })).toBeDisabled();
    });

    it('착수하면 패스 카운트가 초기화되어 한 번의 패스로는 끝나지 않는다', () => {
      const pass = () =>
        fireEvent.click(screen.getByRole('button', { name: '패스' }));
      pass(); // 흑 패스 → 백 차례
      play(3, 3); // 백 착수 → 카운트 리셋
      pass(); // 흑 패스 (연속 2회가 아니다)

      expect(
        screen.queryByText('게임이 종료되었습니다!'),
      ).not.toBeInTheDocument();
    });

    it('종료 후에는 착수도 패스도 받지 않는다', () => {
      const pass = () =>
        fireEvent.click(screen.getByRole('button', { name: '패스' }));
      pass();
      pass();
      alertSpy.mockClear();

      play(4, 4);
      fireEvent.click(screen.getByRole('button', { name: '패스' })); // disabled 라 무시된다

      expect(stoneAt(4, 4)).toBeNull();
      expect(alertSpy).not.toHaveBeenCalled();
    });
  });

  describe('새 게임', () => {
    it('판·차례·잡은 돌·종료 상태를 모두 되돌린다', () => {
      play(0, 1); // 흑
      play(0, 0); // 백
      play(1, 0); // 흑 — 1점 포획
      fireEvent.click(screen.getByRole('button', { name: '패스' }));
      fireEvent.click(screen.getByRole('button', { name: '패스' }));
      expect(screen.getByText('게임이 종료되었습니다!')).toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: '새 게임' }));

      expect(cells().some((c) => c.querySelector('.w-5'))).toBe(false);
      expect(turn()).toContain('흑돌');
      expect(
        screen.getByText('흑돌이 잡은 돌').nextElementSibling,
      ).toHaveTextContent('0');
      expect(
        screen.queryByText('게임이 종료되었습니다!'),
      ).not.toBeInTheDocument();
      expect(container.querySelectorAll('.ring-4')).toHaveLength(0);
      expect(screen.getByRole('button', { name: '패스' })).not.toBeDisabled();
    });
  });
});
