import React from 'react';
import { useNavigate } from 'react-router-dom';

const GamesPage: React.FC = () => {
  const navigate = useNavigate();

  const games = [
    {
      id: 'gomoku',
      name: '오목 게임',
      icon: '⚫⚪',
      description: '15×15 바둑판에서 5개를 연속으로 놓으면 승리!',
      color: 'from-orange-400 to-red-500',
      path: '/games/gomoku',
    },
    {
      id: 'baduk',
      name: '바둑 게임',
      icon: '🎯',
      description: '19×19 바둑판에서 즐기는 전통 바둑',
      color: 'from-amber-400 to-yellow-600',
      path: '/games/baduk',
    },
  ];

  return (
    <div className="min-h-screen bg-gradient-to-br from-indigo-100 via-purple-100 to-pink-100 py-12 px-4">
      <div className="max-w-6xl mx-auto">
        {/* 헤더 */}
        <div className="text-center mb-12">
          <h1 className="text-5xl font-bold text-gray-900 mb-4">
            🎮 보드 게임 센터
          </h1>
          <p className="text-xl text-gray-700">
            전통 보드 게임을 즐겨보세요!
          </p>
        </div>

        {/* 게임 카드 그리드 */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-8 mb-12">
          {games.map((game) => (
            <div
              key={game.id}
              onClick={() => navigate(game.path)}
              className="group cursor-pointer transform transition-all duration-300 hover:scale-105"
            >
              <div className="bg-white rounded-2xl shadow-xl overflow-hidden h-full">
                <div className={`h-40 bg-gradient-to-br ${game.color} flex items-center justify-center`}>
                  <span className="text-8xl">{game.icon}</span>
                </div>
                <div className="p-6">
                  <h2 className="text-3xl font-bold text-gray-900 mb-3 group-hover:text-blue-600 transition-colors">
                    {game.name}
                  </h2>
                  <p className="text-gray-600 text-lg mb-4">
                    {game.description}
                  </p>
                  <button className="w-full py-3 bg-gradient-to-r from-blue-500 to-purple-600 text-white font-bold rounded-lg hover:from-blue-600 hover:to-purple-700 transition-all shadow-lg">
                    게임 시작 →
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>

        {/* 게임 설명 섹션 */}
        <div className="bg-white rounded-2xl shadow-xl p-8">
          <h2 className="text-3xl font-bold text-gray-900 mb-6 text-center">
            🎲 게임 소개
          </h2>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
            <div className="space-y-4">
              <h3 className="text-2xl font-bold text-orange-600">⚫ 오목</h3>
              <ul className="space-y-2 text-gray-700">
                <li>• <span className="font-semibold">보드:</span> 15×15</li>
                <li>• <span className="font-semibold">목표:</span> 5개 연속 배치</li>
                <li>• <span className="font-semibold">규칙:</span> 가로/세로/대각선 가능</li>
                <li>• <span className="font-semibold">난이도:</span> 초급~중급</li>
                <li>• <span className="font-semibold">플레이 시간:</span> 5~15분</li>
              </ul>
            </div>

            <div className="space-y-4">
              <h3 className="text-2xl font-bold text-amber-600">🎯 바둑</h3>
              <ul className="space-y-2 text-gray-700">
                <li>• <span className="font-semibold">보드:</span> 19×19</li>
                <li>• <span className="font-semibold">목표:</span> 영역 확보 및 상대 포위</li>
                <li>• <span className="font-semibold">규칙:</span> 활로 기반 포석</li>
                <li>• <span className="font-semibold">난이도:</span> 중급~고급</li>
                <li>• <span className="font-semibold">플레이 시간:</span> 15~60분</li>
              </ul>
            </div>
          </div>
        </div>

        {/* 추가 정보 */}
        <div className="mt-8 text-center">
          <div className="bg-gradient-to-r from-blue-500 to-purple-600 text-white rounded-xl p-6 shadow-lg">
            <h3 className="text-2xl font-bold mb-2">💡 TIP</h3>
            <p className="text-lg">
              두 게임 모두 두 명이서 즐길 수 있는 전략 게임입니다.
              <br />
              친구나 가족과 함께 즐거운 시간을 보내세요!
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};

export default GamesPage;
