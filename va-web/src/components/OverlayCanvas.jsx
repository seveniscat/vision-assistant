import { useEffect, useRef } from 'react';

const REGION_COLOR = { match: '#16a34a', mismatch: '#dc2626', low_confidence: '#d97706' };

/**
 * 检测结果画布叠加：OCR 文本框（蓝色细框，坐标基于裁白边后图片需加 crop 偏移）
 * + IP 形象区域（按判定着色的粗框 + 角色标签）。
 */
export default function OverlayCanvas({ imageUrl, imageMeta, ocr, ip }) {
  const canvasRef = useRef(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !imageMeta) return undefined;
    const W = imageMeta.width || 1200;
    const H = imageMeta.height || 900;
    canvas.width = W;
    canvas.height = H;
    const ctx = canvas.getContext('2d');

    const draw = (img) => {
      ctx.fillStyle = '#f1f5f9';
      ctx.fillRect(0, 0, W, H);
      if (img) ctx.drawImage(img, 0, 0, W, H);

      const crop = ocr?.image_meta?.crop;
      const ox = Array.isArray(crop) ? crop[0] : 0;
      const oy = Array.isArray(crop) ? crop[1] : 0;

      if (Array.isArray(ocr?.items)) {
        ctx.strokeStyle = '#3b82f6';
        ctx.lineWidth = Math.max(1, W / 800);
        ctx.font = `${Math.max(12, W / 70)}px sans-serif`;
        for (const item of ocr.items) {
          const [x1, y1, x2, y2] = item.bbox;
          ctx.strokeRect(x1 + ox, y1 + oy, x2 - x1, y2 - y1);
          const label = item.text || item.content || item.type;
          ctx.fillStyle = 'rgba(59,130,246,.85)';
          ctx.fillRect(x1 + ox, Math.max(0, y1 + oy - 18), ctx.measureText(label).width + 8, 16);
          ctx.fillStyle = '#fff';
          ctx.fillText(label, x1 + ox + 4, Math.max(12, y1 + oy - 6));
        }
      }

      if (Array.isArray(ip?.regions)) {
        ctx.lineWidth = Math.max(3, W / 300);
        ctx.font = `bold ${Math.max(14, W / 55)}px sans-serif`;
        for (const r of ip.regions) {
          const b = r.bbox;
          if (!b) continue;
          const x1 = b.x1 * W;
          const y1 = b.y1 * H;
          ctx.strokeStyle = REGION_COLOR[r.verdict] || '#64748b';
          ctx.strokeRect(x1, y1, (b.x2 - b.x1) * W, (b.y2 - b.y1) * H);
          const label = `${r.printed_name ?? '?'} → ${r.role_name ?? '?'} (${(r.similarity ?? 0).toFixed(2)})`;
          ctx.fillStyle = REGION_COLOR[r.verdict] || '#64748b';
          ctx.fillRect(x1, Math.max(0, y1 - 24), ctx.measureText(label).width + 10, 22);
          ctx.fillStyle = '#fff';
          ctx.fillText(label, x1 + 5, Math.max(16, y1 - 8));
        }
      }
    };

    if (!imageUrl) {
      draw(null);
      return undefined;
    }
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => draw(img);
    img.onerror = () => draw(null);
    img.src = imageUrl;
    return undefined;
  }, [imageUrl, imageMeta, ocr, ip]);

  if (!imageMeta) return null;
  return (
    <canvas
      ref={canvasRef}
      style={{ maxWidth: '100%', border: '1px solid #e2e8f0', borderRadius: 8, background: '#fff' }}
    />
  );
}
