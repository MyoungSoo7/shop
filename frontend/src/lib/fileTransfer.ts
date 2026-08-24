import { isNativePlatform } from './platform';

const blobToBase64 = async (blob: Blob): Promise<string> => {
  const buffer = await blob.arrayBuffer();
  let binary = '';
  for (const byte of new Uint8Array(buffer)) binary += String.fromCharCode(byte);
  return btoa(binary);
};

export const downloadBlob = async (blob: Blob, filename: string): Promise<string | null> => {
  if (!isNativePlatform()) {
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
    URL.revokeObjectURL(url);
    return null;
  }

  const { Directory, Filesystem } = await import('@capacitor/filesystem');
  const result = await Filesystem.writeFile({
    path: filename,
    data: await blobToBase64(blob),
    directory: Directory.Documents,
    recursive: true,
  });
  return result.uri;
};
