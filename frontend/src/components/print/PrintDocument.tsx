import React, { useEffect, useRef } from 'react';
import '@/styles/print.css';

interface PrintDocumentProps {
  /** 문서명. 브라우저 탭·"PDF 로 저장" 기본 파일명·인쇄 머리글에 그대로 쓰인다. */
  documentTitle: string;
  /**
   * 데이터 로딩이 끝나 종이에 나갈 내용이 확정됐는지.
   * false 인 동안에는 인쇄 대화상자를 띄우지 않는다 — 빈 종이가 출력되는 사고를 막는다.
   */
  ready: boolean;
  /** 열자마자 인쇄 대화상자를 띄울지. 미리보기만 원하면 false. */
  autoPrint?: boolean;
  children: React.ReactNode;
}

/**
 * 인쇄 전용 페이지의 공통 껍데기.
 *
 * - 화면에서는 A4 비율의 흰 종이 + 상단 툴바(인쇄/닫기)를 보여주고,
 * - 인쇄에서는 툴바를 걷어내고 종이 여백을 @page 에 넘긴다(print.css).
 *
 * 새 인쇄 화면을 추가할 때는 이 컴포넌트로 감싸고 본문만 새로 쓰면 된다.
 */
const PrintDocument: React.FC<PrintDocumentProps> = ({
  documentTitle,
  ready,
  autoPrint = true,
  children,
}) => {
  const firedRef = useRef(false);

  // 문서명 = 탭 제목. 인쇄 창을 닫지 않고 되돌아가는 경우를 위해 원복한다.
  useEffect(() => {
    const previous = document.title;
    document.title = documentTitle;
    return () => {
      document.title = previous;
    };
  }, [documentTitle]);

  useEffect(() => {
    if (!ready || !autoPrint || firedRef.current) return;
    firedRef.current = true;

    // 폰트·레이아웃이 안정된 뒤에 띄운다. 곧바로 print() 하면 웹폰트가 아직
    // 적용되지 않은 상태로 페이지 분할이 계산돼 줄이 밀리는 브라우저가 있다.
    const fontsReady: Promise<unknown> = document.fonts?.ready ?? Promise.resolve();
    let raf = 0;
    fontsReady.then(() => {
      raf = requestAnimationFrame(() => {
        raf = requestAnimationFrame(() => window.print());
      });
    });

    return () => {
      if (raf) cancelAnimationFrame(raf);
    };
  }, [ready, autoPrint]);

  return (
    <div className="min-h-screen w-full bg-gray-200 print:bg-white">
      {/* 화면 전용 툴바 — 자동 인쇄를 닫았거나 다시 뽑고 싶을 때 쓴다. */}
      <div className="no-print sticky top-0 z-10 flex items-center justify-between gap-3 border-b border-gray-300 bg-white px-6 py-3 shadow-sm">
        <span className="truncate text-sm font-semibold text-gray-800">{documentTitle}</span>
        <div className="flex shrink-0 gap-2">
          <button
            type="button"
            onClick={() => window.print()}
            disabled={!ready}
            className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
          >
            인쇄
          </button>
          <button
            type="button"
            onClick={() => window.close()}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50"
          >
            닫기
          </button>
        </div>
      </div>

      <div className="print-sheet text-gray-900">{children}</div>
    </div>
  );
};

export default PrintDocument;
