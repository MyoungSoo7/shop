import React, { useState, useRef } from 'react';
import api from '@/api/axios';
import { useToast } from '@/contexts/useToast';

interface ProductImage {
  id: number;
  url: string;
  originalFileName: string;
  isPrimary: boolean;
  orderIndex: number;
  sizeBytes: number;
  width?: number;
  height?: number;
}

interface ImageUploadProps {
  productId: number;
  images: ProductImage[];
  onImagesChange: () => void;
}

const ImageUpload: React.FC<ImageUploadProps> = ({ productId, images, onImagesChange }) => {
  const [uploading, setUploading] = useState(false);
  const [dragActive, setDragActive] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const { showToast } = useToast();

  const handleFileChange = async (files: FileList | null) => {
    if (!files || files.length === 0) return;

    const formData = new FormData();
    Array.from(files).forEach(file => {
      // 파일 타입 검증
      if (!['image/jpeg', 'image/jpg', 'image/png', 'image/webp'].includes(file.type)) {
        showToast(`${file.name}은(는) 지원하지 않는 파일 형식입니다.`, 'error');
        return;
      }
      // 파일 크기 검증 (5MB)
      if (file.size > 5 * 1024 * 1024) {
        showToast(`${file.name}의 크기가 5MB를 초과합니다.`, 'error');
        return;
      }
      formData.append('files', file);
    });

    setUploading(true);
    try {
      await api.post(`/admin/products/${productId}/images`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      showToast('이미지가 업로드되었습니다.', 'success');
      onImagesChange();
    } catch {
      showToast('이미지 업로드 실패', 'error');
    } finally {
      setUploading(false);
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
    }
  };

  const handleDrag = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.type === 'dragenter' || e.type === 'dragover') {
      setDragActive(true);
    } else if (e.type === 'dragleave') {
      setDragActive(false);
    }
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setDragActive(false);
    handleFileChange(e.dataTransfer.files);
  };

  const handleSetPrimary = async (imageId: number) => {
    try {
      await api.patch(`/admin/products/${productId}/images/${imageId}/primary`);
      showToast('대표 이미지가 설정되었습니다.', 'success');
      onImagesChange();
    } catch {
      showToast('대표 이미지 설정 실패', 'error');
    }
  };

  const handleDelete = async (imageId: number) => {
    if (!window.confirm('이미지를 삭제하시겠습니까?')) return;
    try {
      await api.delete(`/admin/products/${productId}/images/${imageId}`);
      showToast('이미지가 삭제되었습니다.', 'success');
      onImagesChange();
    } catch {
      showToast('이미지 삭제 실패', 'error');
    }
  };

  const handleMoveUp = async (index: number) => {
    if (index === 0) return;
    const newOrder = [...images];
    [newOrder[index - 1], newOrder[index]] = [newOrder[index], newOrder[index - 1]];
    await reorderImages(newOrder);
  };

  const handleMoveDown = async (index: number) => {
    if (index === images.length - 1) return;
    const newOrder = [...images];
    [newOrder[index], newOrder[index + 1]] = [newOrder[index + 1], newOrder[index]];
    await reorderImages(newOrder);
  };

  const reorderImages = async (newOrder: ProductImage[]) => {
    try {
      await api.patch(`/admin/products/${productId}/images/reorder`, {
        imageIds: newOrder.map(img => img.id),
      });
      showToast('이미지 순서가 변경되었습니다.', 'success');
      onImagesChange();
    } catch {
      showToast('이미지 순서 변경 실패', 'error');
    }
  };

  return (
    <div className="space-y-4">
      {/* 업로드 영역 */}
      <div
        className={`border-2 border-dashed rounded-lg p-8 text-center transition-colors ${
          dragActive ? 'border-blue-500 bg-blue-50' : 'border-gray-300 hover:border-gray-400'
        }`}
        onDragEnter={handleDrag}
        onDragLeave={handleDrag}
        onDragOver={handleDrag}
        onDrop={handleDrop}
      >
        <input
          ref={fileInputRef}
          type="file"
          multiple
          accept="image/jpeg,image/jpg,image/png,image/webp"
          onChange={(e) => handleFileChange(e.target.files)}
          className="hidden"
        />
        {uploading ? (
          <div className="text-gray-600">업로드 중...</div>
        ) : (
          <>
            <div className="text-gray-600 mb-2">
              이미지를 드래그하거나 클릭하여 업로드
            </div>
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              className="px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700"
            >
              파일 선택
            </button>
            <div className="text-xs text-gray-500 mt-2">
              JPG, PNG, WEBP (최대 5MB)
            </div>
          </>
        )}
      </div>

      {/* 이미지 목록 */}
      {images.length > 0 && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {images.map((image, index) => (
            <div key={image.id} className="relative border rounded-lg p-2">
              <img
                src={image.url}
                alt={image.originalFileName}
                className="w-full h-32 object-cover rounded"
              />
              {image.isPrimary && (
                <span className="absolute top-1 left-1 bg-blue-600 text-white text-xs px-2 py-1 rounded">
                  대표
                </span>
              )}
              <div className="mt-2 text-xs text-gray-600 truncate">
                {image.originalFileName}
              </div>
              <div className="text-xs text-gray-500">
                {image.width && image.height && `${image.width}×${image.height}`}
              </div>
              <div className="mt-2 flex gap-1">
                {!image.isPrimary && (
                  <button
                    onClick={() => handleSetPrimary(image.id)}
                    className="flex-1 text-xs px-2 py-1 bg-green-600 text-white rounded hover:bg-green-700"
                  >
                    대표
                  </button>
                )}
                <button
                  onClick={() => handleMoveUp(index)}
                  disabled={index === 0}
                  className="px-2 py-1 bg-gray-600 text-white rounded hover:bg-gray-700 disabled:opacity-50"
                >
                  ↑
                </button>
                <button
                  onClick={() => handleMoveDown(index)}
                  disabled={index === images.length - 1}
                  className="px-2 py-1 bg-gray-600 text-white rounded hover:bg-gray-700 disabled:opacity-50"
                >
                  ↓
                </button>
                <button
                  onClick={() => handleDelete(image.id)}
                  className="px-2 py-1 bg-red-600 text-white rounded hover:bg-red-700"
                >
                  삭제
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default ImageUpload;
