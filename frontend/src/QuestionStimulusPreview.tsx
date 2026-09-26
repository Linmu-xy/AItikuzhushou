import { useEffect, useState } from 'react';

type Stimulus = { documentId: string; page: number; x?: number; y?: number; width?: number; height?: number };

export function QuestionStimulusPreview({ projectId, auth, stimuli }: {
  projectId: string; auth: string; stimuli: unknown;
}) {
  const [imageUrls, setImageUrls] = useState<string[]>([]);
  const [error, setError] = useState('');
  const all = Array.isArray(stimuli) ? stimuli as Stimulus[] : [];
  const first = all[0];
  const documentId = first?.documentId;
  const page = first?.page;
  const stimuliKey = JSON.stringify(all.slice(0, 2));

  useEffect(() => {
    if (!projectId || !documentId || !page) return;
    setImageUrls([]);
    const controller = new AbortController();
    const urls: string[] = [];
    const headers = new Headers();
    if (auth.startsWith('Basic ')) headers.set('Authorization', auth);
    void Promise.all(all.slice(0, 2).map(async stimulus => {
      const query = new URLSearchParams({ x: String(stimulus.x || 0), y: String(stimulus.y || 0),
        width: String(stimulus.width || 100), height: String(stimulus.height || 100) });
      const response = await fetch(`/api/exam-projects/${projectId}/visuals/${stimulus.documentId}/pages/${stimulus.page}/image?${query}`, {
        headers, credentials: 'same-origin', signal: controller.signal,
      });
      if (!response.ok) throw new Error('原图加载失败');
      return response.blob();
    })).then(blobs => {
      if (controller.signal.aborted) return;
      blobs.forEach(blob => urls.push(URL.createObjectURL(blob)));
      setImageUrls([...urls]);
      setError('');
    }).catch(() => { if (!controller.signal.aborted) setError('原图暂时无法显示，请检查资料是否仍可访问。'); });
    return () => { controller.abort(); urls.forEach(url => URL.revokeObjectURL(url)); };
  }, [auth, stimuliKey, projectId]);

  if (!documentId || !page) return null;
  return <figure className="question-stimulus">
    {imageUrls.length ? imageUrls.map((url, index) => <a key={url} href={url} target="_blank" rel="noopener noreferrer"
      aria-label={index === 0 ? '查看题目原图' : '查看题目局部放大图'}>
      <img src={url} alt={index === 0 ? `题目所需资料图，第 ${page} 页` : '题目局部放大图'} loading="lazy" />
    </a>) : <div className="question-stimulus-placeholder">{error || '正在加载题目原图…'}</div>}
    <figcaption>题目配图{imageUrls.length > 1 ? '与局部放大图' : ''} · 点击查看</figcaption>
  </figure>;
}
