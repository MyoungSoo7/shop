import React, { useState, useEffect, useCallback } from 'react';
import { tagApi } from '@/api/tag';
import { TagResponse } from '@/types';
import { useToast } from '@/contexts/useToast';

const TagManagementPage: React.FC = () => {
  const [tags, setTags] = useState<TagResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [formData, setFormData] = useState({
    name: '',
    color: '#6B7280',
  });
  const { showToast } = useToast();

  const presetColors = [
    { name: '빨강', value: '#EF4444' },
    { name: '주황', value: '#F59E0B' },
    { name: '노랑', value: '#FBBF24' },
    { name: '초록', value: '#10B981' },
    { name: '파랑', value: '#3B82F6' },
    { name: '보라', value: '#8B5CF6' },
    { name: '분홍', value: '#EC4899' },
    { name: '회색', value: '#6B7280' },
  ];

  const loadTags = useCallback(async () => {
    setLoading(true);
    try {
      const data = await tagApi.getAllTags();
      setTags(data);
    } catch {
      showToast('태그 목록 조회 실패', 'error');
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    loadTags();
  }, [loadTags]);


  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await tagApi.createTag(formData);
      showToast('태그가 생성되었습니다', 'success');
      setShowCreateForm(false);
      setFormData({ name: '', color: '#6B7280' });
      loadTags();
    } catch {
      showToast('태그 생성 실패', 'error');
    }
  };

  const handleDelete = async (id: number) => {
    if (!window.confirm('이 태그를 삭제하시겠습니까?')) {
      return;
    }
    try {
      await tagApi.deleteTag(id);
      showToast('태그가 삭제되었습니다', 'success');
      loadTags();
    } catch {
      showToast('태그 삭제 실패', 'error');
    }
  };

  return (
    <div className="min-h-screen bg-gray-50 p-8">
      <div className="max-w-7xl mx-auto">
        <div className="flex justify-between items-center mb-6">
          <h1 className="text-3xl font-bold text-gray-900">태그 관리</h1>
          <button
            onClick={() => setShowCreateForm(!showCreateForm)}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 font-medium"
          >
            {showCreateForm ? '취소' : '+ 새 태그'}
          </button>
        </div>

        {showCreateForm && (
          <div className="bg-white rounded-lg shadow p-6 mb-6">
            <h2 className="text-xl font-semibold text-gray-900 mb-4">새 태그 생성</h2>
            <form onSubmit={handleCreate} className="space-y-4">
              <div>
                <label className="block text-sm font-semibold text-gray-900 mb-1">
                  태그명 *
                </label>
                <input
                  type="text"
                  value={formData.name}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  required
                  maxLength={50}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 text-gray-900"
                />
              </div>
              <div>
                <label className="block text-sm font-semibold text-gray-900 mb-1">
                  색상 *
                </label>
                <div className="grid grid-cols-4 gap-2 mb-2">
                  {presetColors.map((preset) => (
                    <button
                      key={preset.value}
                      type="button"
                      onClick={() => setFormData({ ...formData, color: preset.value })}
                      className={`px-3 py-2 rounded-lg text-white font-medium transition-all ${
                        formData.color === preset.value ? 'ring-2 ring-offset-2 ring-blue-500' : ''
                      }`}
                      style={{ backgroundColor: preset.value }}
                    >
                      {preset.name}
                    </button>
                  ))}
                </div>
                <input
                  type="color"
                  value={formData.color}
                  onChange={(e) => setFormData({ ...formData, color: e.target.value })}
                  className="w-full h-10 rounded-lg cursor-pointer"
                />
              </div>
              <button
                type="submit"
                className="w-full px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 font-medium"
              >
                생성
              </button>
            </form>
          </div>
        )}

        <div className="bg-white rounded-lg shadow">
          {loading ? (
            <div className="p-8 text-center text-gray-500">로딩 중...</div>
          ) : tags.length === 0 ? (
            <div className="p-8 text-center text-gray-500">
              등록된 태그가 없습니다.
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 p-6">
              {tags.map((tag) => (
                <div
                  key={tag.id}
                  className="border border-gray-200 rounded-lg p-4 hover:shadow-md transition-shadow"
                >
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div
                        className="w-8 h-8 rounded-full"
                        style={{ backgroundColor: tag.color }}
                      />
                      <div>
                        <p className="font-semibold text-gray-900">{tag.name}</p>
                        <p className="text-xs text-gray-500">ID: {tag.id}</p>
                      </div>
                    </div>
                    <button
                      onClick={() => handleDelete(tag.id)}
                      className="px-3 py-1 bg-red-600 text-white rounded hover:bg-red-700 text-sm"
                    >
                      삭제
                    </button>
                  </div>
                  <div className="mt-2 text-xs text-gray-500">
                    생성일: {new Date(tag.createdAt).toLocaleDateString('ko-KR')}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default TagManagementPage;
