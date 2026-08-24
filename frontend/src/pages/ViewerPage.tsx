import React, { useState } from 'react';

type ViewerType = 'markdown' | 'text' | null;

const ViewerPage: React.FC = () => {
  const [selectedViewer, setSelectedViewer] = useState<ViewerType>(null);

  const viewers = [
    {
      id: 'markdown' as ViewerType,
      name: 'Markdown Viewer',
      icon: '📝',
      description: 'Markdown 파일을 HTML로 렌더링하여 표시합니다',
      color: 'from-blue-400 to-cyan-500',
      path: '/viewers/md-viewer.html',
      formats: ['.md', '.markdown'],
    },
    {
      id: 'text' as ViewerType,
      name: 'Text Viewer',
      icon: '📄',
      description: '다양한 텍스트 파일을 코드 에디터 스타일로 표시합니다',
      color: 'from-green-400 to-emerald-500',
      path: '/viewers/txt-viewer.html',
      formats: ['.txt', '.log', '.csv', '.json', '.xml', '.html', '.css', '.js', '.ts'],
    },
  ];

  const handleViewerSelect = (viewerType: ViewerType) => {
    setSelectedViewer(viewerType);
  };

  const handleBack = () => {
    setSelectedViewer(null);
  };

  if (selectedViewer) {
    const viewer = viewers.find(v => v.id === selectedViewer);
    if (!viewer) return null;

    return (
      <div className="min-h-screen bg-gray-900 flex flex-col">
        {/* 상단 바 */}
        <div className="bg-gray-800 border-b border-gray-700 px-4 py-3 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <button
              onClick={handleBack}
              className="px-4 py-2 bg-gray-700 text-white rounded-lg hover:bg-gray-600 transition-colors font-medium"
            >
              ← 뒤로
            </button>
            <span className="text-2xl">{viewer.icon}</span>
            <h2 className="text-xl font-bold text-white">{viewer.name}</h2>
          </div>
          <div className="text-sm text-gray-400">
            지원 형식: {viewer.formats.join(', ')}
          </div>
        </div>

        {/* iframe 뷰어 */}
        <div className="flex-1">
          <iframe
            src={viewer.path}
            title={viewer.name}
            className="w-full h-full border-0"
            /* dvh — iOS 주소창 개폐를 따라가는 높이(vh 는 접힌 상태 높이로 고정돼 하단이 잘린다). */
            style={{ minHeight: 'calc(100dvh - 60px)' }}
          />
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 py-12 px-4">
      <div className="max-w-6xl mx-auto">
        {/* 헤더 */}
        <div className="text-center mb-12">
          <h1 className="text-5xl font-bold text-white mb-4">
            📖 문서 뷰어
          </h1>
          <p className="text-xl text-gray-300">
            Markdown과 텍스트 파일을 아름답게 보세요
          </p>
        </div>

        {/* 뷰어 선택 카드 */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-8 mb-12">
          {viewers.map((viewer) => (
            <div
              key={viewer.id}
              onClick={() => handleViewerSelect(viewer.id)}
              className="group cursor-pointer transform transition-all duration-300 hover:scale-105"
            >
              <div className="bg-gray-800 rounded-2xl shadow-2xl overflow-hidden h-full border border-gray-700 hover:border-gray-500">
                <div className={`h-40 bg-gradient-to-br ${viewer.color} flex items-center justify-center`}>
                  <span className="text-8xl">{viewer.icon}</span>
                </div>
                <div className="p-6">
                  <h2 className="text-3xl font-bold text-white mb-3 group-hover:text-blue-400 transition-colors">
                    {viewer.name}
                  </h2>
                  <p className="text-gray-300 text-lg mb-4">
                    {viewer.description}
                  </p>
                  <div className="mb-4">
                    <p className="text-sm text-gray-400 mb-2">지원 형식:</p>
                    <div className="flex flex-wrap gap-2">
                      {viewer.formats.map((format) => (
                        <span
                          key={format}
                          className="px-3 py-1 bg-gray-700 text-gray-300 rounded-full text-sm"
                        >
                          {format}
                        </span>
                      ))}
                    </div>
                  </div>
                  <button className="w-full py-3 bg-gradient-to-r from-blue-500 to-purple-600 text-white font-bold rounded-lg hover:from-blue-600 hover:to-purple-700 transition-all shadow-lg">
                    뷰어 열기 →
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>

        {/* 기능 설명 */}
        <div className="bg-gray-800 rounded-2xl shadow-2xl p-8 border border-gray-700">
          <h2 className="text-3xl font-bold text-white mb-6 text-center">
            ✨ 주요 기능
          </h2>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
            <div className="space-y-4">
              <h3 className="text-2xl font-bold text-blue-400">📝 Markdown Viewer</h3>
              <ul className="space-y-2 text-gray-300">
                <li>• <span className="font-semibold text-white">문법 렌더링:</span> 헤더, 목록, 표, 코드 블록</li>
                <li>• <span className="font-semibold text-white">코드 하이라이트:</span> Highlight.js 지원</li>
                <li>• <span className="font-semibold text-white">테마 변경:</span> GitHub, 다크 테마</li>
                <li>• <span className="font-semibold text-white">HTML 내보내기:</span> 렌더링된 HTML 저장</li>
                <li>• <span className="font-semibold text-white">샘플 로드:</span> 빠른 테스트</li>
              </ul>
            </div>

            <div className="space-y-4">
              <h3 className="text-2xl font-bold text-green-400">📄 Text Viewer</h3>
              <ul className="space-y-2 text-gray-300">
                <li>• <span className="font-semibold text-white">줄 번호:</span> 코드 에디터 스타일</li>
                <li>• <span className="font-semibold text-white">다양한 테마:</span> 다크, 라이트, Solarized</li>
                <li>• <span className="font-semibold text-white">폰트 조절:</span> 8px ~ 32px</li>
                <li>• <span className="font-semibold text-white">파일 정보:</span> 크기, 줄 수, 단어 수</li>
                <li>• <span className="font-semibold text-white">검색 기능:</span> 텍스트 하이라이트</li>
                <li>• <span className="font-semibold text-white">클립보드 복사:</span> 원클릭 복사</li>
              </ul>
            </div>
          </div>
        </div>

        {/* 사용 가이드 */}
        <div className="mt-8 bg-gradient-to-r from-blue-600 to-purple-600 text-white rounded-xl p-6 shadow-lg">
          <h3 className="text-2xl font-bold mb-4 flex items-center gap-2">
            💡 사용 방법
          </h3>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-sm">
            <div className="bg-white/10 rounded-lg p-4 backdrop-blur-sm">
              <p className="font-bold mb-2">1️⃣ 뷰어 선택</p>
              <p className="text-gray-100">원하는 뷰어 카드를 클릭하세요</p>
            </div>
            <div className="bg-white/10 rounded-lg p-4 backdrop-blur-sm">
              <p className="font-bold mb-2">2️⃣ 파일 업로드</p>
              <p className="text-gray-100">파일 선택 버튼으로 파일을 열거나 샘플을 로드하세요</p>
            </div>
            <div className="bg-white/10 rounded-lg p-4 backdrop-blur-sm">
              <p className="font-bold mb-2">3️⃣ 보기 및 편집</p>
              <p className="text-gray-100">렌더링된 문서를 확인하고 필요시 저장하세요</p>
            </div>
          </div>
        </div>

        {/* 추가 정보 */}
        <div className="mt-8 text-center">
          <p className="text-gray-400 text-sm">
            💾 모든 파일은 브라우저에서만 처리되며 서버로 전송되지 않습니다
          </p>
        </div>
      </div>
    </div>
  );
};

export default ViewerPage;
