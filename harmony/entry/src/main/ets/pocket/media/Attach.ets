import { common } from '@kit.AbilityKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { picker } from '@kit.CoreFileKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { image } from '@kit.ImageKit';

/**
 * 附件媒体层：系统 picker（免权限）+ 图片压缩 + 文件分块读取。
 * 对齐 Android 端 media/ 与 PocketRepository 的常量语义：
 *   图片最长边 1024px、JPEG 目标 ≤90KB（质量递减搜索）；
 *   文件按 768KB 原始字节分块，读取随传随读（不把 200MB 整个读进内存）。
 */

export const IMG_MAX_DIM = 1024;
export const IMG_MAX_BYTES = 90_000;
export const FILE_CHUNK_RAW = 768_000;
export const MAX_UPLOAD_BYTES = 200 * 1024 * 1024;

// ── 图片 ─────────────────────────────────────────────────────────────

/** 系统相册选择器（免权限），返回图片 URI 列表。 */
export async function pickImages(ctx: common.UIAbilityContext, maxSelect: number): Promise<string[]> {
  const opts = new photoAccessHelper.PhotoSelectOptions();
  opts.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
  opts.maxSelectNumber = Math.max(1, maxSelect);
  const pv = new photoAccessHelper.PhotoViewPicker();
  const result = await pv.select(opts);
  return result.photoUris;
}

export interface CompressedImage {
  bytes: Uint8Array;     // JPEG 压缩结果（≤ IMG_MAX_BYTES，除非质量触底）
  preview: image.PixelMap; // 缩放后的 PixelMap，直接用于气泡预览
}

/** 读 URI → 缩放到最长边 ≤1024 → JPEG 质量递减直到 ≤90KB。对齐 compressImage()。 */
export async function compressImage(uri: string): Promise<CompressedImage> {
  const fd = fs.openSync(uri, fs.OpenMode.READ_ONLY).fd;
  try {
    const source = image.createImageSource(fd);
    const info = await source.getImageInfo();
    const w = info.size.width;
    const h = info.size.height;
    const scale = Math.min(1, IMG_MAX_DIM / Math.max(w, h));
    const opts: image.DecodingOptions = {
      desiredSize: { width: Math.max(1, Math.round(w * scale)), height: Math.max(1, Math.round(h * scale)) },
    };
    const pm = await source.createPixelMap(opts);
    const packer = image.createImagePacker();
    let quality = 85;
    let buf = await packImage(packer, pm, quality);
    while (buf.byteLength > IMG_MAX_BYTES && quality > 30) {
      quality -= 15;
      buf = await packImage(packer, pm, quality);
    }
    source.release();
    return { bytes: new Uint8Array(buf), preview: pm };
  } finally {
    fs.closeSync(fd);
  }
}

async function packImage(packer: image.ImagePacker, pm: image.PixelMap, quality: number): Promise<ArrayBuffer> {
  const opts: image.PackingOption = { format: 'image/jpeg', quality };
  return packer.packing(pm, opts);
}

// ── 文件 ─────────────────────────────────────────────────────────────

export interface PickedFileRef {
  uri: string;
  name: string;
  size: number;
  mediaType: string;
}

/** 系统文档选择器（免权限），任意类型文件。 */
export async function pickFiles(ctx: common.UIAbilityContext, maxSelect: number): Promise<PickedFileRef[]> {
  const opts = new picker.DocumentSelectOptions();
  opts.selectMode = picker.DocumentSelectMode.FILE;
  opts.maxSelectNumber = Math.max(1, maxSelect);
  const dp = new picker.DocumentViewPicker(ctx);
  const uris = await dp.select(opts);
  const out = new Array<PickedFileRef>();
  for (const uri of uris) {
    try {
      const stat = fs.statSync(uri);
      out.push({ uri, name: baseName(uri), size: stat.size, mediaType: guessMediaType(uri) });
    } catch (e) {
      // 读不到的条目跳过（用户会在列表里看到缺一个，而不是整个流程失败）
    }
  }
  return out;
}

function baseName(uri: string): string {
  const seg = uri.split('/').pop() ?? uri;
  try {
    return decodeURIComponent(seg);
  } catch (e) {
    return seg;
  }
}

function guessMediaType(name: string): string {
  const dot = name.lastIndexOf('.');
  const ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : '';
  const map: Record<string, string> = {
    'pdf': 'application/pdf', 'csv': 'text/csv', 'txt': 'text/plain', 'md': 'text/markdown',
    'json': 'application/json', 'zip': 'application/zip', 'png': 'image/png', 'jpg': 'image/jpeg',
    'jpeg': 'image/jpeg', 'gif': 'image/gif', 'mp4': 'video/mp4', 'mov': 'video/quicktime',
    'doc': 'application/msword', 'xls': 'application/vnd.ms-excel',
  };
  const hit = map[ext];
  return hit !== undefined ? hit : 'application/octet-stream';
}

/**
 * 分块读取一个 URI 的原始字节（随传随读）。返回 null 表示读取出错。
 * 每个 chunk 调用一次 onChunk(idx, bytes, last)，调用方负责 base64 + 发送。
 */
export async function readFileInChunks(
  uri: string,
  onChunk: (idx: number, bytes: Uint8Array, last: boolean) => Promise<void>,
): Promise<boolean> {
  let fd = -1;
  try {
    fd = fs.openSync(uri, fs.OpenMode.READ_ONLY).fd;
    const stat = fs.statSync(fd);
    const total = stat.size;
    const buf = new ArrayBuffer(FILE_CHUNK_RAW);
    let idx = 0;
    let offset = 0;
    while (true) {
      const read = fs.readSync(fd, buf, { offset, length: FILE_CHUNK_RAW });
      const last = offset + read >= total || read === 0;
      await onChunk(idx, new Uint8Array(buf.slice(0, read)), last);
      if (last) return true;
      offset += read;
      idx++;
    }
  } catch (e) {
    return false;
  } finally {
    if (fd >= 0) fs.closeSync(fd);
  }
}
