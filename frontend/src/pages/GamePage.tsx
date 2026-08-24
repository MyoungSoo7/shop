import React, { useState } from 'react';

type GameType = 'baduk' | 'omok';

const GamePage: React.FC = () => {
  const [activeGame, setActiveGame] = useState<GameType>('baduk');

  const tabClass = (game: GameType) => {
    const base = 'px-6 py-2 rounded-t-lg font-medium text-sm transition-colors border-b-2';
    return activeGame === game
      ? `${base} bg-white border-blue-600 text-blue-600`
      : `${base} bg-gray-100 border-transparent text-gray-600 hover:bg-gray-200`;
  };

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
      <h1 className="text-2xl font-bold text-gray-900 mb-4">게임</h1>

      {/* 서브 탭 */}
      <div className="flex space-x-1 border-b border-gray-200 mb-0">
        <button
          className={tabClass('baduk')}
          onClick={() => setActiveGame('baduk')}
        >
          바둑
        </button>
        <button
          className={tabClass('omok')}
          onClick={() => setActiveGame('omok')}
        >
          오목
        </button>
      </div>

      {/* 게임 iframe */}
      <div className="bg-white border border-t-0 border-gray-200 rounded-b-lg overflow-hidden">
        <iframe
          key={activeGame}
          src={`/games/${activeGame}`}
          title={activeGame === 'baduk' ? '바둑' : '오목'}
          className="w-full"
          /* dvh — iOS 주소창 개폐를 따라가는 높이. vh 로 두면 주소창이 펼쳐질 때 iframe 하단이 잘린다. */
          style={{ height: 'calc(100dvh - 220px)', minHeight: '600px', border: 'none' }}
        />
      </div>
    </div>
  );
};

export default GamePage;