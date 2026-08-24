import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import PgRoutingHealthCard from '@/components/PgRoutingHealthCard';
import { pgRoutingApi } from '@/api/pgRouting';

vi.mock('@/api/pgRouting', () => ({ pgRoutingApi: { health: vi.fn() } }));

const mocked = vi.mocked(pgRoutingApi);

beforeEach(() => vi.clearAllMocks());

/**
 * 이 카드가 지키는 것.
 *
 * <p>① <b>조회 실패를 "전부 정상"으로 그리지 않는다.</b> 장애 중에 보는 화면이라, 실패를
 * 초록으로 위장하면 정반대 신호를 준다.
 * <p>② <b>PG 목록을 화면이 짓지 않는다.</b> 서버 응답의 키를 그대로 그린다 — 목록을 화면이
 * 들고 있으면 PG 가 추가될 때 조용히 빠진다.
 * <p>③ <b>조작 버튼이 없다.</b> 서버가 읽기 전용이라(설정 엔드포인트 없음) 버튼을 두면
 * 있지도 않은 제어를 암시한다.
 */
describe('PgRoutingHealthCard', () => {
  it('중단된 PG 를 이름과 함께 드러낸다', async () => {
    mocked.health.mockResolvedValue({
      providers: { TOSS: true, KCP: false, NICE: true, INICIS: false }, healthy: false,
    });
    render(<PgRoutingHealthCard />);

    await waitFor(() => expect(screen.getByTestId('pg-overall')).toBeInTheDocument());
    // 몇 곳이 빠졌는지와 어느 것인지를 둘 다 말해야 결제 실패율 변동을 설명할 수 있다.
    expect(screen.getByTestId('pg-overall')).toHaveTextContent('2곳');
    expect(screen.getByTestId('pg-overall')).toHaveTextContent('KCP, INICIS');
    expect(screen.getByTestId('pg-KCP')).toHaveTextContent('중단');
    expect(screen.getByTestId('pg-TOSS')).toHaveTextContent('정상');
  });

  it('전부 정상이면 그렇다고 말한다', async () => {
    mocked.health.mockResolvedValue({ providers: { TOSS: true, KCP: true }, healthy: true });
    render(<PgRoutingHealthCard />);

    await waitFor(() => expect(screen.getByTestId('pg-overall')).toHaveTextContent('모든 PG 가 정상'));
  });

  it('서버가 준 PG 만 그린다 — 화면이 목록을 들고 있지 않다', async () => {
    // 서버 enum 에 5개가 있어도 응답에 둘만 오면 둘만 그린다(그 반대도 마찬가지).
    mocked.health.mockResolvedValue({ providers: { TOSS: true, MOCK: false }, healthy: false });
    render(<PgRoutingHealthCard />);

    await waitFor(() => expect(screen.getByTestId('pg-list')).toBeInTheDocument());
    expect(screen.getByTestId('pg-list').children).toHaveLength(2);
    expect(screen.queryByTestId('pg-NICE')).not.toBeInTheDocument();
  });

  it('조회가 실패하면 초록으로 위장하지 않는다', async () => {
    mocked.health.mockRejectedValue(new Error('boom'));
    render(<PgRoutingHealthCard />);

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.queryByTestId('pg-overall')).not.toBeInTheDocument();
    expect(screen.queryByTestId('pg-list')).not.toBeInTheDocument();
  });

  it('조작 버튼이 없다 — 서버가 읽기 전용이다', async () => {
    mocked.health.mockResolvedValue({ providers: { TOSS: false }, healthy: false });
    render(<PgRoutingHealthCard />);
    await waitFor(() => expect(screen.getByTestId('pg-list')).toBeInTheDocument());

    // 새로고침 외에 아무 버튼도 없어야 한다 — 복구는 서킷브레이커나 PG 사 쪽 일이다.
    const buttons = screen.getAllByRole('button').map((b) => b.textContent);
    expect(buttons).toEqual(['PG 상태 새로고침']);
  });

  it('새로고침하면 다시 읽는다', async () => {
    mocked.health.mockResolvedValue({ providers: { TOSS: true }, healthy: true });
    render(<PgRoutingHealthCard />);
    await waitFor(() => expect(mocked.health).toHaveBeenCalledTimes(1));

    // 이 버튼은 로딩 중 '조회 중…' 으로 바뀌므로 정적 chrome 이 아니다 — 호출이 끝난 시점과
    // 문구가 되돌아온 시점 사이에 틱이 있어, 동기 조회는 CI 에서 랜덤하게 실패한다.
    fireEvent.click(await screen.findByRole('button', { name: 'PG 상태 새로고침' }));

    await waitFor(() => expect(mocked.health).toHaveBeenCalledTimes(2));
  });
});
