import { describe, expect, it, vi } from 'vitest';
import { downloadBlob } from '@/lib/fileTransfer';

describe('fileTransfer', () => {
  it('uses a browser download for web blobs', async () => {
    const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test');
    const revokeObjectURL = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);

    await expect(downloadBlob(new Blob(['report']), 'report.txt')).resolves.toBeNull();
    expect(createObjectURL).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:test');
  });
});
