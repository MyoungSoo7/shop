import React, { useState, useCallback } from 'react';

type Player = 'black' | 'white' | null;
type Board = Player[][];

const BOARD_SIZE = 15;

const GomokuGame: React.FC = () => {
  const [board, setBoard] = useState<Board>(() =>
    Array(BOARD_SIZE).fill(null).map(() => Array(BOARD_SIZE).fill(null))
  );
  const [currentPlayer, setCurrentPlayer] = useState<'black' | 'white'>('black');
  const [winner, setWinner] = useState<string | null>(null);
  const [lastMove, setLastMove] = useState<[number, number] | null>(null);

  const checkWinner = useCallback((board: Board, row: number, col: number, player: Player): boolean => {
    if (!player) return false;

    const directions = [
      [0, 1],   // 가로
      [1, 0],   // 세로
      [1, 1],   // 대각선 \
      [1, -1],  // 대각선 /
    ];

    for (const [dx, dy] of directions) {
      let count = 1;

      // 한 방향으로 체크
      for (let i = 1; i < 5; i++) {
        const newRow = row + dx * i;
        const newCol = col + dy * i;
        if (
          newRow >= 0 && newRow < BOARD_SIZE &&
          newCol >= 0 && newCol < BOARD_SIZE &&
          board[newRow][newCol] === player
        ) {
          count++;
        } else {
          break;
        }
      }

      // 반대 방향으로 체크
      for (let i = 1; i < 5; i++) {
        const newRow = row - dx * i;
        const newCol = col - dy * i;
        if (
          newRow >= 0 && newRow < BOARD_SIZE &&
          newCol >= 0 && newCol < BOARD_SIZE &&
          board[newRow][newCol] === player
        ) {
          count++;
        } else {
          break;
        }
      }

      if (count >= 5) {
        return true;
      }
    }

    return false;
  }, []);

  const handleCellClick = useCallback((row: number, col: number) => {
    if (board[row][col] !== null || winner) return;

    const newBoard = board.map(r => [...r]);
    newBoard[row][col] = currentPlayer;
    setBoard(newBoard);
    setLastMove([row, col]);

    if (checkWinner(newBoard, row, col, currentPlayer)) {
      setWinner(currentPlayer === 'black' ? '흑돌' : '백돌');
    } else {
      setCurrentPlayer(currentPlayer === 'black' ? 'white' : 'black');
    }
  }, [board, currentPlayer, winner, checkWinner]);

  const resetGame = () => {
    setBoard(Array(BOARD_SIZE).fill(null).map(() => Array(BOARD_SIZE).fill(null)));
    setCurrentPlayer('black');
    setWinner(null);
    setLastMove(null);
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-amber-50 to-orange-100 py-8 px-4">
      <div className="max-w-4xl mx-auto">
        <div className="text-center mb-6">
          <h1 className="text-4xl font-bold text-gray-900 mb-2">🎮 오목 게임</h1>
          <p className="text-gray-700">5개를 연속으로 놓으면 승리!</p>
        </div>

        {/* 게임 정보 */}
        <div className="bg-white rounded-lg shadow-lg p-6 mb-6">
          <div className="flex justify-between items-center">
            <div className="flex items-center gap-3">
              <span className="text-lg font-semibold text-gray-700">현재 차례:</span>
              <div className="flex items-center gap-2">
                {currentPlayer === 'black' ? (
                  <>
                    <div className="w-8 h-8 rounded-full bg-gradient-to-br from-gray-800 to-black shadow-lg"></div>
                    <span className="font-bold text-gray-900">흑돌</span>
                  </>
                ) : (
                  <>
                    <div className="w-8 h-8 rounded-full bg-gradient-to-br from-white to-gray-200 border-2 border-gray-300 shadow-lg"></div>
                    <span className="font-bold text-gray-900">백돌</span>
                  </>
                )}
              </div>
            </div>

            <button
              onClick={resetGame}
              className="px-6 py-2 bg-blue-600 text-white rounded-lg font-semibold hover:bg-blue-700 transition-colors"
            >
              새 게임
            </button>
          </div>

          {winner && (
            <div className="mt-4 p-4 bg-green-100 border-2 border-green-400 rounded-lg text-center">
              <p className="text-2xl font-bold text-green-800">
                🎉 {winner} 승리! 🎉
              </p>
            </div>
          )}
        </div>

        {/* 오목판 */}
        <div className="bg-amber-700 rounded-lg shadow-2xl p-6">
          <div className="bg-amber-600 rounded-lg p-4">
            <div
              className="inline-grid gap-0"
              style={{
                gridTemplateColumns: `repeat(${BOARD_SIZE}, minmax(0, 1fr))`,
              }}
            >
              {board.map((row, rowIndex) =>
                row.map((cell, colIndex) => {
                  const isLastMove = lastMove && lastMove[0] === rowIndex && lastMove[1] === colIndex;
                  return (
                    <div
                      key={`${rowIndex}-${colIndex}`}
                      onClick={() => handleCellClick(rowIndex, colIndex)}
                      className={`
                        relative w-8 h-8 sm:w-10 sm:h-10 md:w-12 md:h-12
                        border-t border-l border-gray-800
                        ${rowIndex === BOARD_SIZE - 1 ? 'border-b' : ''}
                        ${colIndex === BOARD_SIZE - 1 ? 'border-r' : ''}
                        ${!cell && !winner ? 'cursor-pointer hover:bg-amber-500/30' : ''}
                        transition-colors
                      `}
                    >
                      {cell && (
                        <div className="absolute inset-0 flex items-center justify-center">
                          <div
                            className={`
                              w-6 h-6 sm:w-7 sm:h-7 md:w-9 md:h-9 rounded-full
                              ${cell === 'black'
                                ? 'bg-gradient-to-br from-gray-800 to-black shadow-lg'
                                : 'bg-gradient-to-br from-white to-gray-200 border-2 border-gray-300 shadow-lg'
                              }
                              ${isLastMove ? 'ring-4 ring-red-500 ring-opacity-50' : ''}
                              transition-all duration-200 transform hover:scale-110
                            `}
                          />
                        </div>
                      )}

                      {/* 화점 표시 */}
                      {((rowIndex === 3 || rowIndex === 7 || rowIndex === 11) &&
                        (colIndex === 3 || colIndex === 7 || colIndex === 11)) && (
                        <div className="absolute inset-0 flex items-center justify-center">
                          <div className="w-2 h-2 rounded-full bg-gray-900"></div>
                        </div>
                      )}
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </div>

        {/* 게임 규칙 */}
        <div className="mt-6 bg-white rounded-lg shadow-lg p-6">
          <h3 className="text-xl font-bold text-gray-900 mb-3">📜 게임 규칙</h3>
          <ul className="space-y-2 text-gray-700">
            <li>• 흑돌이 먼저 시작합니다</li>
            <li>• 가로, 세로, 대각선으로 5개를 연속으로 놓으면 승리합니다</li>
            <li>• 6개 이상이어도 승리입니다 (장목 허용)</li>
            <li>• 빨간 테두리는 가장 최근에 놓은 돌을 표시합니다</li>
          </ul>
        </div>
      </div>
    </div>
  );
};

export default GomokuGame;
