import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PrintDocument from '@/components/print/PrintDocument';

const print = vi.fn();
const close = vi.fn();

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('print', print);
  vi.stubGlobal('close', close);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('PrintDocument', () => {
  it('문서명을 탭 제목으로 쓰고 언마운트 시 원복한다', () => {
    document.title = '원래 제목';

    const { unmount } = render(
      <PrintDocument documentTitle="정산 명세서" ready={false} autoPrint={false}>
        <p>본문</p>
      </PrintDocument>,
    );
    expect(document.title).toBe('정산 명세서');

    unmount();

    expect(document.title).toBe('원래 제목');
  });

  it('본문과 툴바를 그린다', () => {
    render(
      <PrintDocument documentTitle="정산 명세서" ready autoPrint={false}>
        <p>본문</p>
      </PrintDocument>,
    );

    expect(screen.getByText('본문')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '인쇄' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '닫기' })).toBeInTheDocument();
  });

  it('내용이 확정되기 전에는 인쇄 버튼이 잠긴다 — 빈 종이 출력 방지', () => {
    render(
      <PrintDocument documentTitle="정산 명세서" ready={false} autoPrint={false}>
        <p>본문</p>
      </PrintDocument>,
    );

    expect(screen.getByRole('button', { name: '인쇄' })).toBeDisabled();
  });

  it('ready 가 false 면 자동 인쇄하지 않는다', async () => {
    render(
      <PrintDocument documentTitle="정산 명세서" ready={false}>
        <p>본문</p>
      </PrintDocument>,
    );

    await new Promise((r) => setTimeout(r, 20));
    expect(print).not.toHaveBeenCalled();
  });

  it('autoPrint=false 면 자동 인쇄하지 않는다', async () => {
    render(
      <PrintDocument documentTitle="정산 명세서" ready autoPrint={false}>
        <p>본문</p>
      </PrintDocument>,
    );

    await new Promise((r) => setTimeout(r, 20));
    expect(print).not.toHaveBeenCalled();
  });

  it('내용이 확정되면 폰트 준비 후 자동으로 인쇄 대화상자를 띄운다', async () => {
    render(
      <PrintDocument documentTitle="정산 명세서" ready>
        <p>본문</p>
      </PrintDocument>,
    );

    await waitFor(() => expect(print).toHaveBeenCalledTimes(1));
  });

  it('리렌더돼도 자동 인쇄는 한 번만 뜬다', async () => {
    const { rerender } = render(
      <PrintDocument documentTitle="정산 명세서" ready>
        <p>본문</p>
      </PrintDocument>,
    );
    await waitFor(() => expect(print).toHaveBeenCalledTimes(1));

    rerender(
      <PrintDocument documentTitle="정산 명세서" ready>
        <p>본문 갱신</p>
      </PrintDocument>,
    );

    await new Promise((r) => setTimeout(r, 20));
    expect(print).toHaveBeenCalledTimes(1);
  });

  it('툴바 인쇄·닫기 버튼이 각각 동작한다', async () => {
    render(
      <PrintDocument documentTitle="정산 명세서" ready autoPrint={false}>
        <p>본문</p>
      </PrintDocument>,
    );

    await userEvent.click(screen.getByRole('button', { name: '인쇄' }));
    await userEvent.click(screen.getByRole('button', { name: '닫기' }));

    expect(print).toHaveBeenCalledTimes(1);
    expect(close).toHaveBeenCalledTimes(1);
  });
});
