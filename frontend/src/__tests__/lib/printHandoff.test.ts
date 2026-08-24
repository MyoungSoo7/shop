import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  PRINT_DOC,
  putPrintHandoff,
  readPrintHandoff,
  openPrintWindow,
  type SettlementPrintHandoff,
} from '@/lib/printHandoff';

beforeEach(() => {
  sessionStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('printHandoff — 보조 표시값 전달', () => {
  it('심은 값을 같은 키로 읽는다', () => {
    putPrintHandoff<SettlementPrintHandoff>(PRINT_DOC.settlement, 55, {
      ordererName: '홍길동',
      productName: '티셔츠',
    });

    const read = readPrintHandoff<SettlementPrintHandoff>(PRINT_DOC.settlement, 55);

    expect(read).toEqual({ ordererName: '홍길동', productName: '티셔츠' });
  });

  it('개인정보를 URL 이 아니라 sessionStorage 에 둔다', () => {
    putPrintHandoff(PRINT_DOC.settlement, 55, { ordererName: '홍길동' });

    expect(sessionStorage.getItem('print:handoff:settlement:55')).toBe(
      JSON.stringify({ ordererName: '홍길동' }),
    );
  });

  it('숫자 id 와 문자열 id 는 같은 키를 만든다', () => {
    putPrintHandoff(PRINT_DOC.settlement, 55, { ordererName: 'A' });

    expect(readPrintHandoff(PRINT_DOC.settlement, '55')).toEqual({ ordererName: 'A' });
  });

  it('없는 값은 null 이다 (인쇄는 해당 행만 생략하고 진행)', () => {
    expect(readPrintHandoff(PRINT_DOC.settlement, 999)).toBeNull();
  });

  it('읽어도 지우지 않는다 — 인쇄 창 새로고침에도 같은 내용이 나와야 한다', () => {
    putPrintHandoff(PRINT_DOC.settlement, 55, { ordererName: '홍길동' });

    readPrintHandoff(PRINT_DOC.settlement, 55);

    expect(readPrintHandoff(PRINT_DOC.settlement, 55)).toEqual({ ordererName: '홍길동' });
  });

  it('깨진 JSON 이 들어 있으면 null 로 떨어진다', () => {
    sessionStorage.setItem('print:handoff:settlement:55', '{not-json');

    expect(readPrintHandoff(PRINT_DOC.settlement, 55)).toBeNull();
  });

  it('용량 초과 등 저장 실패는 조용히 포기한다 (보조값)', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });

    expect(() => putPrintHandoff(PRINT_DOC.settlement, 55, { ordererName: 'A' })).not.toThrow();
  });

  it('스토리지가 차단된 환경에서도 던지지 않고 null 을 돌려준다', () => {
    const blocked = {
      get sessionStorage() {
        throw new Error('access denied');
      },
      open: vi.fn(),
    };
    vi.stubGlobal('window', blocked);

    expect(() => putPrintHandoff(PRINT_DOC.settlement, 1, {})).not.toThrow();
    expect(readPrintHandoff(PRINT_DOC.settlement, 1)).toBeNull();
  });
});

describe('openPrintWindow', () => {
  it('새 탭으로 열고 opener 를 끊는다', () => {
    const open = vi.fn().mockReturnValue({} as Window);
    vi.stubGlobal('window', { open, sessionStorage });

    const win = openPrintWindow('/print/settlements/55');

    expect(open).toHaveBeenCalledWith('/print/settlements/55', '_blank', 'noopener,noreferrer');
    expect(win).not.toBeNull();
  });

  it('팝업이 차단되면 null 을 돌려준다 (호출부가 안내할 수 있게)', () => {
    vi.stubGlobal('window', { open: vi.fn().mockReturnValue(null), sessionStorage });

    expect(openPrintWindow('/print/settlements/55')).toBeNull();
  });
});
