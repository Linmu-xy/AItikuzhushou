import { useEffect, useMemo, useState } from 'react';

type DocumentRow = { id: string; originalFilename: string; status: string; mediaType?: string; sizeBytes?: number };
type FastJob = {
  id: string; documentId: string; status: string; progress: number; density: string; questionTypes: string[];
  requestedQuestions: number; generatedQuestions: number; failedQuestions: number; processedSegments: number;
  totalSegments: number; errorMessage?: string;
};
type FastItem = { id: string; sequence: number; type: string; difficulty: string; question: Record<string, unknown>; review: Record<string, unknown>; status: string; errorMessage?: string };
type NormalizationJob = { id: string; originalName: string; status: string; totalQuestions: number; validQuestions: number; errorQuestions: number; errorMessage?: string };
type NormalizationItem = { id: string; sequence: number; question: Record<string, unknown>; status: string; errorMessage?: string };

const QUESTION_TYPES = [
  ['SINGLE_CHOICE', '单选'], ['MULTIPLE_CHOICE', '多选'], ['TRUE_FALSE', '判断'],
  ['FILL_BLANK', '填空'], ['SHORT_ANSWER', '简答'], ['CASE_ANALYSIS', '案例']
] as const;

async function request<T>(path: string, auth: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  if (auth.startsWith('Basic ')) headers.set('Authorization', auth);
  const response = await fetch(path, { ...options, headers, credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) {
    const raw = await response.text();
    try { const detail = JSON.parse(raw) as { statusCode?: string; message?: string }; throw new Error(`${detail.statusCode || `HTTP_${response.status}`}：${detail.message || '请求处理失败'}`); }
    catch (error) { if (error instanceof Error && error.message.includes('：')) throw error; throw new Error(`HTTP_${response.status}：${raw || '请求处理失败'}`); }
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
}

const text = (value: unknown) => value == null ? '' : String(value);
const questionTypesText: Record<string, string> = Object.fromEntries(QUESTION_TYPES);

export function FastDocumentWorkspace({ auth, documents, onMessage, onNavigate }: { auth: string; documents: DocumentRow[]; onMessage: (message: string) => void; onNavigate: (page: string) => void }) {
  const parsedDocuments = useMemo(() => documents.filter(item => ['PARSED', 'PARSED_PARTIAL'].includes(item.status)), [documents]);
  const [documentId, setDocumentId] = useState('');
  const [density, setDensity] = useState<'LOW' | 'HIGH'>('LOW');
  const [difficulty, setDifficulty] = useState('EASY');
  const [selectedTypes, setSelectedTypes] = useState<string[]>(['SINGLE_CHOICE', 'TRUE_FALSE', 'SHORT_ANSWER']);
  const [job, setJob] = useState<FastJob>();
  const [items, setItems] = useState<FastItem[]>([]);
  const [busy, setBusy] = useState(false);
  const [normalizationFile, setNormalizationFile] = useState<File>();
  const [normalizationJob, setNormalizationJob] = useState<NormalizationJob>();
  const [normalizationItems, setNormalizationItems] = useState<NormalizationItem[]>([]);

  useEffect(() => { if (!documentId || !parsedDocuments.some(item => item.id === documentId)) setDocumentId(parsedDocuments[0]?.id || ''); }, [parsedDocuments, documentId]);
  useEffect(() => {
    if (!job || !['QUEUED', 'RUNNING'].includes(job.status)) return;
    let cancelled = false;
    const poll = async () => { try { const next = await request<FastJob>(`/api/fast-document/jobs/${job.id}`, auth); if (!cancelled) setJob(next); } catch (error) { if (!cancelled) onMessage(error instanceof Error ? error.message : '快速建库任务查询失败'); } };
    void poll(); const timer = window.setInterval(() => void poll(), 1800); return () => { cancelled = true; window.clearInterval(timer); };
  }, [auth, job?.id, job?.status, onMessage]);
  useEffect(() => {
    if (!job || !['READY_FOR_REVIEW', 'PARTIAL_SUCCESS', 'FAILED'].includes(job.status)) return;
    void request<FastItem[]>(`/api/fast-document/jobs/${job.id}/items`, auth).then(setItems).catch(error => onMessage(error instanceof Error ? error.message : '读取题目失败'));
  }, [auth, job?.id, job?.status, onMessage]);

  const toggleType = (type: string) => setSelectedTypes(current => current.includes(type) ? current.filter(item => item !== type) : [...current, type]);
  const createJob = async () => {
    if (!documentId) return onMessage('请先在知识库中上传并解析文档。');
    if (!selectedTypes.length) return onMessage('至少选择一种题型。');
    setBusy(true);
    try {
      const next = await request<FastJob>('/api/fast-document/jobs', auth, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `fast-${documentId}-${Date.now()}` }, body: JSON.stringify({ documentId, density, difficulty, questionTypes: selectedTypes }) });
      setJob(next); setItems([]); onMessage('快速建库任务已提交，生成后会停在人工审核状态。');
    } catch (error) { onMessage(error instanceof Error ? error.message : '快速建库提交失败'); }
    finally { setBusy(false); }
  };
  const importJob = async () => {
    if (!job) return;
    if (!window.confirm('确认将通过基础结构检查的题目导入原题库？导入后仍可在题目审核中继续处理。')) return;
    setBusy(true); try { await request('/api/fast-document/jobs/' + job.id + '/import', auth, { method: 'POST' }); setJob({ ...job, status: 'IMPORTED' }); onMessage('题目已导入原题库，原有审核和导出流程保持不变。'); } catch (error) { onMessage(error instanceof Error ? error.message : '题目导入失败'); } finally { setBusy(false); }
  };
  const normalize = async () => {
    if (!normalizationFile) return onMessage('请选择 Word 或 Excel 题库文件。');
    setBusy(true); try { const body = new FormData(); body.append('file', normalizationFile); const next = await request<NormalizationJob>('/api/question-normalization/jobs', auth, { method: 'POST', body }); setNormalizationJob(next); setNormalizationItems(await request<NormalizationItem[]>(`/api/question-normalization/jobs/${next.id}/items`, auth)); onMessage('题库格式整理完成，请先检查错误项再导入。'); } catch (error) { onMessage(error instanceof Error ? error.message : '题库格式整理失败'); } finally { setBusy(false); }
  };
  const confirmNormalization = async () => {
    if (!normalizationJob) return;
    if (!window.confirm('确认将通过校验的题目导入原题库？无效题目不会被导入。')) return;
    setBusy(true); try { await request(`/api/question-normalization/jobs/${normalizationJob.id}/confirm`, auth, { method: 'POST' }); setNormalizationJob({ ...normalizationJob, status: 'IMPORTED' }); onMessage('整理后的有效题目已导入原题库。'); } catch (error) { onMessage(error instanceof Error ? error.message : '题库导入失败'); } finally { setBusy(false); }
  };
  const statusLabel = (status?: string) => ({ QUEUED: '排队中', RUNNING: '处理中', READY_FOR_REVIEW: '待审核', PARTIAL_SUCCESS: '部分完成', FAILED: '失败', IMPORTED: '已导入' }[status || ''] || status || '—');

  return <div className="fast-document-workspace">
    <section className="panel fast-hero"><div><p className="eyebrow">新增优化 · 默认关闭</p><h2>快速文档建库</h2><p className="muted">沿用现有 FAST 命题器，把已解析文档按证据段落拆分后批量生成题目；生成结果先进入人工审核，不改变原有项目、V2、Pro 命题流程。</p></div><span className="soft-badge">旧功能不变</span></section>
    <section className="panel"><div className="section-heading"><div><p className="eyebrow">文档出题</p><h2>从已解析资料快速生成题目</h2></div><span className={job && ['QUEUED', 'RUNNING'].includes(job.status) ? 'pulse-badge' : 'soft-badge'}>{statusLabel(job?.status)}</span></div>
      {!parsedDocuments.length ? <div className="fast-empty"><p>当前知识库还没有已解析文档。</p><button className="secondary" onClick={() => onNavigate('知识库')}>去知识库上传并解析</button></div> : <>
        <div className="split fast-form"><label>来源文档<select value={documentId} onChange={event => setDocumentId(event.target.value)}>{parsedDocuments.map(item => <option key={item.id} value={item.id}>{item.originalFilename} · {item.status}</option>)}</select></label><label>出题密度<select value={density} onChange={event => setDensity(event.target.value as 'LOW' | 'HIGH')}><option value="LOW">低（每段约 5 题）</option><option value="HIGH">高（每段约 10 题）</option></select></label><label>默认难度<select value={difficulty} onChange={event => setDifficulty(event.target.value)}><option value="EASY">简单</option><option value="MEDIUM">中等</option><option value="HARD">困难</option></select></label></div>
        <fieldset className="fast-type-picker"><legend>题型</legend>{QUESTION_TYPES.map(([type, label]) => <label key={type}><input type="checkbox" checked={selectedTypes.includes(type)} onChange={() => toggleType(type)} />{label}</label>)}</fieldset>
        <div className="fast-actions"><button onClick={() => void createJob()} disabled={busy || Boolean(job && ['QUEUED', 'RUNNING'].includes(job.status))}>提交快速建库</button>{job && ['READY_FOR_REVIEW', 'PARTIAL_SUCCESS'].includes(job.status) && <button className="secondary" onClick={() => void importJob()} disabled={busy}>导入原题库</button>}</div>
      </>}
      {job && <div className="fast-job-summary"><div><b>{job.progress}%</b><span>处理进度</span></div><div><b>{job.generatedQuestions}</b><span>待审核题目</span></div><div><b>{job.failedQuestions}</b><span>失败题目</span></div><div><b>{job.processedSegments}/{job.totalSegments}</b><span>资料段</span></div>{job.errorMessage && <p className="inline-error">{job.errorMessage}</p>}</div>}
    </section>
     {items.length > 0 && <section className="panel"><div className="section-heading"><div><p className="eyebrow">证据与门禁</p><h2>快速题目预览</h2></div><span className="soft-badge">仅展示基础校验结果</span></div><div className="fast-question-list">{items.slice(0, 50).map(item => <article key={item.id} className={item.status === 'REVIEW_REQUIRED' ? 'valid' : 'invalid'}><header><b>#{item.sequence} · {questionTypesText[item.type] || item.type}</b><span>{item.status === 'REVIEW_REQUIRED' ? '待人工审核' : '未通过'}</span></header><p>{text(item.question.stem) || '题干未返回'}</p>{Boolean(item.review?.sourceRef) && <small>证据：{text(item.review.sourceRef)}</small>}{item.errorMessage && <small className="inline-error">{item.errorMessage}</small>}</article>)}</div></section>}
    <section className="panel fast-normalization"><div className="section-heading"><div><p className="eyebrow">格式整理</p><h2>Word / Excel 题库整理</h2></div><span className="soft-badge">先预览 · 再导入</span></div><p className="muted">把外部题库映射到现有题库字段，保留原有校验、审核和导出规则；不直接覆盖原题库。</p><div className="excel-drop"><input type="file" accept=".doc,.docx,.xls,.xlsx" onChange={event => setNormalizationFile(event.target.files?.[0])} /><span>{normalizationFile?.name || '支持 .doc / .docx / .xls / .xlsx，最大 20MB'}</span><button onClick={() => void normalize()} disabled={busy || !normalizationFile}>开始整理</button></div>{normalizationJob && <div className="fast-normalization-result"><div className="fast-job-summary"><div><b>{normalizationJob.totalQuestions}</b><span>识别题目</span></div><div><b>{normalizationJob.validQuestions}</b><span>可导入</span></div><div><b>{normalizationJob.errorQuestions}</b><span>需修正</span></div><div><b>{statusLabel(normalizationJob.status)}</b><span>状态</span></div></div><div className="fast-actions"><button onClick={() => void confirmNormalization()} disabled={busy || normalizationJob.status !== 'READY_FOR_REVIEW' || normalizationJob.validQuestions === 0}>导入有效题目</button></div>{normalizationItems.length > 0 && <div className="fast-question-list">{normalizationItems.slice(0, 50).map(item => <article key={item.id} className={item.status === 'VALID' ? 'valid' : 'invalid'}><header><b>#{item.sequence} · {text(item.question.type) || '待识别题型'}</b><span>{item.status === 'VALID' ? '通过' : '需修正'}</span></header><p>{text(item.question.stem) || '题干未识别'}</p>{item.errorMessage && <small className="inline-error">{item.errorMessage}</small>}</article>)}</div>}</div>}</section>
  </div>;
}
