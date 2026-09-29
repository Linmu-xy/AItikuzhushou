import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react';

const ObjPreview = lazy(() => import('./ObjPreview').then(module => ({ default: module.ObjPreview })));

type KnowledgeBase = { id: string; name: string; status: string; quotaBytes?: number; usedBytes?: number };
type Material = {
  id: string;
  knowledgeBaseId: string;
  originalName: string;
  mediaType: string;
  format: string;
  sizeBytes: number;
  status: string;
  createdAt: string;
  updatedAt: string;
};
type WorkflowTask = {
  id: string;
  taskType: string;
  resourceId?: string;
  status: string;
  stageCode: string;
  progress: number;
  statusMessage: string;
  errorCode?: string;
  errorMessage?: string;
};
type Fact = {
  id: string;
  name: string;
  valueJson: string;
  unit?: string;
  sourceRef: string;
  confidence: number;
  verified: boolean;
  usableForGeneration: boolean;
  verificationNote?: string;
};
type Annotation = {
  id: string;
  kind: string;
  pageNumber?: number;
  valueJson: string;
  sourceRef: string;
  confidence: number;
  verified: boolean;
  usableForGeneration: boolean;
};
type AnalysisView = {
  material: Material;
  job?: { id: string; parser: string; status: string; errorCode?: string; errorMessage?: string; createdAt: string };
  facts: Fact[];
  annotations: Annotation[];
  previewAssets: PreviewAsset[];
};

type PreviewAsset = {
  id: string;
  assetType: string;
  pageNumber?: number;
  mediaType: string;
  sizeBytes: number;
};

type Props = {
  auth: string;
  onMessage: (message: string) => void;
};

async function request<T>(path: string, auth: string, options: RequestInit = {}) {
  const headers = new Headers(options.headers);
  if (auth.startsWith('Basic ')) headers.set('Authorization', auth);
  if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, { ...options, headers, credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) {
    const raw = await response.text();
    try {
      const detail = JSON.parse(raw) as { statusCode?: string; message?: string };
      throw new Error(`${detail.statusCode || `HTTP_${response.status}`}：${detail.message || '请求失败'}`);
    } catch (error) {
      if (error instanceof Error && error.message.includes('：')) throw error;
      throw new Error(`HTTP_${response.status}：${raw || '请求失败'}`);
    }
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
}

function formatBytes(value = 0) {
  if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(1)} GB`;
  if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(1)} MB`;
  return `${Math.max(1, Math.ceil(value / 1024))} KB`;
}

function statusLabel(status: string) {
  return ({ UPLOADED: '待分析', ANALYZING: '分析中', ANALYZED: '已分析', ANALYSIS_PARTIAL: '部分读取', ANALYSIS_FAILED: '分析失败', ADAPTER_REQUIRED: '需适配器', PARSED: '已读取', PARSED_PARTIAL: '部分读取', FAILED: '失败', SUCCEEDED: '完成', QUEUED: '排队中', RUNNING: '处理中' } as Record<string, string>)[status] || status;
}

function readJson(value: string) {
  try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
}

function factTitle(name: string) {
  return ({ bounding_box: '包围盒尺寸', volume: '实体体积', surface_area: '表面积', topology_counts: '拓扑数量', surface_counts: '曲面类型', cylinder_radii: '圆柱半径', unit: '模型单位' } as Record<string, string>)[name] || name;
}

export function MaterialExamWorkspace({ auth, onMessage }: Props) {
  const [bases, setBases] = useState<KnowledgeBase[]>([]);
  const [baseId, setBaseId] = useState('');
  const [materials, setMaterials] = useState<Material[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [analysis, setAnalysis] = useState<AnalysisView>();
  const [task, setTask] = useState<WorkflowTask>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [file, setFile] = useState<File>();
  const [note, setNote] = useState<Record<string, string>>({});
  const mounted = useRef(true);

  const selectedMaterial = useMemo(() => materials.find(item => item.id === selectedId), [materials, selectedId]);
  const factsReady = analysis?.facts.filter(item => item.usableForGeneration).length || 0;
  const canPreview = Boolean(analysis?.job && ['PARSED', 'PARSED_PARTIAL'].includes(analysis.job.status) && ['STEP', 'STP'].includes(analysis.material.format));
  const drawingPages = useMemo(() => (analysis?.previewAssets || [])
    .filter(asset => asset.assetType === 'DRAWING_PAGE' && asset.pageNumber)
    .sort((left, right) => (left.pageNumber || 0) - (right.pageNumber || 0)), [analysis?.previewAssets]);
  const drawingPageKey = drawingPages.map(asset => `${asset.id}:${asset.pageNumber}`).join(',');
  const [drawingImageUrls, setDrawingImageUrls] = useState<Record<string, string>>({});

  useEffect(() => {
    if (!selectedId || !drawingPages.length) {
      setDrawingImageUrls({});
      return undefined;
    }
    const controller = new AbortController();
    const headers = new Headers();
    if (auth.startsWith('Basic ')) headers.set('Authorization', auth);
    const urls: Record<string, string> = {};
    void Promise.all(drawingPages.map(async asset => {
      const response = await fetch(`/api/cad-materials/${selectedId}/drawing-pages/${asset.pageNumber}/image`, {
        headers, credentials: 'same-origin', signal: controller.signal,
      });
      if (!response.ok) throw new Error('图纸页加载失败');
      return [asset.id, URL.createObjectURL(await response.blob())] as const;
    })).then(entries => {
      if (controller.signal.aborted) return;
      entries.forEach(([id, url]) => { urls[id] = url; });
      setDrawingImageUrls({ ...urls });
    }).catch(() => { if (!controller.signal.aborted) setDrawingImageUrls({}); });
    return () => {
      controller.abort();
      Object.values(urls).forEach(url => URL.revokeObjectURL(url));
    };
  }, [auth, drawingPageKey, selectedId]);

  const loadBases = async () => {
    const result = await request<KnowledgeBase[]>('/api/knowledge-bases', auth);
    if (!mounted.current) return;
    setBases(result);
    setBaseId(current => result.some(item => item.id === current) ? current : result[0]?.id || '');
  };

  const loadMaterials = async (knowledgeBaseId: string, keepId = selectedId) => {
    if (!knowledgeBaseId) { setMaterials([]); setSelectedId(''); return; }
    const result = await request<Material[]>(`/api/cad-materials?knowledgeBaseId=${encodeURIComponent(knowledgeBaseId)}`, auth);
    if (!mounted.current) return;
    setMaterials(result);
    setSelectedId(current => result.some(item => item.id === current) ? current : result.some(item => item.id === keepId) ? keepId : result[0]?.id || '');
  };

  const loadAnalysis = async (materialId: string) => {
    const result = await request<AnalysisView>(`/api/cad-materials/${materialId}/analysis`, auth);
    if (mounted.current) setAnalysis(result);
  };

  useEffect(() => {
    mounted.current = true;
    void loadBases().catch(caught => { if (mounted.current) setError(caught instanceof Error ? caught.message : '知识库加载失败'); });
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    setAnalysis(undefined);
    setTask(undefined);
    void loadMaterials(baseId).catch(caught => { if (mounted.current) setError(caught instanceof Error ? caught.message : '模型资料加载失败'); });
  }, [baseId]);

  useEffect(() => {
    if (!selectedId) { setAnalysis(undefined); return; }
    void loadAnalysis(selectedId).catch(caught => { if (mounted.current) setError(caught instanceof Error ? caught.message : '分析结果加载失败'); });
  }, [selectedId]);

  useEffect(() => {
    if (!task || !['QUEUED', 'RUNNING'].includes(task.status)) return undefined;
    let live = true;
    let timer: number | undefined;
    const poll = async () => {
      try {
        const next = await request<WorkflowTask>(`/api/workflow-tasks/${task.id}`, auth);
        if (!live) return;
        setTask(next);
        if (['QUEUED', 'RUNNING'].includes(next.status)) timer = window.setTimeout(() => void poll(), 1200);
        else {
          await Promise.all([loadAnalysis(selectedId), loadMaterials(baseId)]);
          if (next.status === 'SUCCEEDED') onMessage('资料分析完成：请逐项确认事实后再进入出题。');
        }
      } catch (caught) {
        if (live) setError(caught instanceof Error ? caught.message : '分析任务状态读取失败');
      }
    };
    void poll();
    return () => { live = false; if (timer) window.clearTimeout(timer); };
  }, [task?.id, task?.status]);

  const upload = async () => {
    if (!baseId || !file) return;
    setBusy(true); setError('');
    try {
      const form = new FormData();
      form.append('file', file);
      const result = await request<{ material: Material }>(`/api/cad-materials?knowledgeBaseId=${encodeURIComponent(baseId)}`, auth, { method: 'POST', body: form });
      setFile(undefined);
      await loadMaterials(baseId, result.material.id);
      setSelectedId(result.material.id);
      onMessage(`已加入资料：${result.material.originalName}`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '资料上传失败');
    } finally { setBusy(false); }
  };

  const analyze = async (material: Material) => {
    setBusy(true); setError('');
    try {
      const result = await request<WorkflowTask>(`/api/cad-materials/${material.id}/analysis-jobs`, auth, { method: 'POST', headers: { 'Idempotency-Key': `cad-ui-${material.id}-${Date.now()}` } });
      setTask(result);
      setAnalysis(undefined);
      onMessage(`已提交分析：${material.originalName}`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '分析任务提交失败');
    } finally { setBusy(false); }
  };

  const verifyFact = async (fact: Fact, usableForGeneration: boolean) => {
    try {
      const updated = await request<Fact>(`/api/cad-materials/facts/${fact.id}`, auth, { method: 'PATCH', body: JSON.stringify({ verified: true, usableForGeneration, note: note[fact.id] || '' }) });
      setAnalysis(current => current ? { ...current, facts: current.facts.map(item => item.id === updated.id ? updated : item) } : current);
      onMessage(usableForGeneration ? `已确认“${factTitle(fact.name)}”，允许作为出题依据。` : `已记录“${factTitle(fact.name)}”的人工复核。`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '事实确认失败');
    }
  };

  const isDrawing = selectedMaterial && ['PDF', 'DWG', 'DXF'].includes(selectedMaterial.format);
  const previewUrl = selectedMaterial ? `/api/cad-materials/${selectedMaterial.id}/preview` : '';
  const downloadUrl = selectedMaterial ? `/api/cad-materials/${selectedMaterial.id}/download` : '';

  return (
    <div className="material-exam-workspace">
      <section className="material-exam-intro">
        <div>
          <p className="section-kicker">资料智能组卷 · 第一步</p>
          <h2>先把模型和工程图读准，再开始出题</h2>
          <p className="muted">上传任务资料后，系统只展示可追溯的模型事实和图纸标注。未经教师确认的内容不会进入后续组卷依据。</p>
        </div>
        <div className="material-exam-guard"><span className="guard-dot" /><div><b>人工确认门禁</b><small>{factsReady} 项事实已允许出题</small></div></div>
      </section>

      {error && <div className="material-exam-error" role="alert"><b>操作未完成</b><span>{error}</span><button className="secondary" onClick={() => setError('')}>知道了</button></div>}

      <div className="material-exam-grid">
        <aside className="panel material-exam-library">
          <div className="section-heading"><div><p className="section-kicker">资料空间</p><h3>模型与工程图</h3></div><span className="quiet-label">{materials.length} 份</span></div>
          <label className="material-base-select">当前知识库<select value={baseId} onChange={event => setBaseId(event.target.value)}>{bases.map(base => <option key={base.id} value={base.id}>{base.name}</option>)}</select></label>
          <div className="material-upload-box"><label htmlFor="material-upload">添加模型 / 图纸</label><input id="material-upload" type="file" accept=".step,.stp,.x_t,.prt,.dwg,.dxf,.pdf,.stl,.obj" onChange={event => setFile(event.target.files?.[0])} /><small>支持 STEP、STP、X_T、PRT、DWG、DXF、PDF、STL、OBJ，单文件不超过 64 MB。</small>{file && <div className="selected-file"><span>{file.name}</span><button className="secondary" onClick={() => setFile(undefined)}>移除</button></div>}<button onClick={() => void upload()} disabled={busy || !file || !baseId}>上传到资料组卷</button></div>
          <div className="material-list" aria-label="模型与工程图列表">{materials.length ? materials.map(material => <button key={material.id} className={material.id === selectedId ? 'active' : ''} onClick={() => setSelectedId(material.id)}><span className="material-list-mark">{material.format.slice(0, 3)}</span><span className="material-list-copy"><b>{material.originalName}</b><small>{formatBytes(material.sizeBytes)} · {statusLabel(material.status)}</small></span><i>›</i></button>) : <p className="empty">当前知识库还没有模型或工程图。</p>}</div>
        </aside>

        <main className="material-exam-main">
          {!selectedMaterial ? <section className="panel material-exam-empty"><span className="empty-mark">＋</span><h3>选择一份资料开始</h3><p className="muted">建议先上传 STEP/STP 模型，第一版可直接查看三维形体与模型事实。</p></section> : <>
            <section className="panel material-exam-file-head"><div><p className="section-kicker">当前资料 · {selectedMaterial.format}</p><h2>{selectedMaterial.originalName}</h2><p className="muted">{formatBytes(selectedMaterial.sizeBytes)} · 上传于 {new Date(selectedMaterial.createdAt).toLocaleString()}</p></div><div className="material-head-actions"><span className={`material-status ${selectedMaterial.status.toLowerCase()}`}>{statusLabel(selectedMaterial.status)}</span><a className="button secondary" href={downloadUrl}>下载原文件</a>{(!task || !['QUEUED', 'RUNNING'].includes(task.status)) && <button onClick={() => void analyze(selectedMaterial)} disabled={busy}>{analysis?.job ? '重新分析' : '开始分析'}</button>}</div></section>
            {task && <section className="material-task-strip" aria-live="polite"><div><b>{statusLabel(task.status)}</b><span>{task.statusMessage || statusLabel(task.stageCode)}</span></div><div className="material-task-progress"><i style={{ width: `${task.progress}%` }} /></div><strong>{task.progress}%</strong>{task.errorMessage && <small>{task.errorCode || 'FAILED'}：{task.errorMessage}</small>}</section>}
            <section className="panel material-preview-panel"><div className="section-heading"><div><p className="section-kicker">内容预览</p><h3>{canPreview ? '三维模型预览' : isDrawing ? '工程图复现预览' : '资料预览'}</h3></div><span className="quiet-label">{analysis?.job ? `${analysis.job.parser} · ${statusLabel(analysis.job.status)}` : '尚未分析'}</span></div>{canPreview ? <Suspense fallback={<div className="obj-preview-loading">正在准备三维预览组件…</div>}><ObjPreview src={previewUrl} label={`${selectedMaterial.originalName} 三维模型`} /></Suspense> : isDrawing && drawingPages.length ? <div className="drawing-pages-preview"><div className="drawing-pages-list">{drawingPages.map(asset => <figure key={asset.id} className="drawing-page-card">{drawingImageUrls[asset.id] ? <a href={drawingImageUrls[asset.id]} target="_blank" rel="noopener noreferrer"><img src={drawingImageUrls[asset.id]} alt={`${selectedMaterial.originalName} 第 ${asset.pageNumber} 页`} loading="lazy" /></a> : <div className="drawing-page-loading">正在加载第 {asset.pageNumber} 页…</div>}<figcaption>第 {asset.pageNumber} 页 · 高清复现</figcaption></figure>)}</div><p className="muted drawing-pages-note">页面由原始 PDF 按系统配置的高清 DPI 渲染并保存，后续可在此基础上进行标注确认。</p></div> : isDrawing ? <div className="drawing-preview-placeholder"><span className="drawing-icon">图</span><div><b>图纸已纳入资料管理</b><p className="muted">开始分析后会生成高清页面复现；当前不会把未确认的图纸内容直接用于出题。</p><a className="button secondary" href={downloadUrl}>打开 / 下载原工程图</a></div></div> : <div className="drawing-preview-placeholder"><span className="drawing-icon">?</span><div><b>等待分析后生成可用预览</b><p className="muted">当前格式暂不提供浏览器内三维预览，但原始资料会完整保留。</p></div></div>}</section>
          </>}
        </main>

        <aside className="material-exam-inspector">
          <section className="panel material-facts-panel"><div className="section-heading"><div><p className="section-kicker">可追溯依据</p><h3>模型事实与图纸标注</h3></div><span className="quiet-label">{analysis?.facts.length || 0} 项</span></div><p className="muted material-facts-lede">每项事实都保留解析器来源和置信度。确认后才能用于正式出题；教师可以在备注中写下人工核对结论。</p>{analysis?.job?.errorMessage && <div className="material-inline-warning">{analysis.job.errorCode || '分析失败'}：{analysis.job.errorMessage}</div>}{analysis?.facts.length ? <div className="material-facts-list">{analysis.facts.map(fact => <article className={`material-fact ${fact.usableForGeneration ? 'usable' : ''}`} key={fact.id}><header><div><b>{factTitle(fact.name)}</b><small>{fact.name} · 置信度 {Math.round(fact.confidence * 100)}%</small></div><span>{fact.usableForGeneration ? '可出题' : fact.verified ? '已复核' : '待复核'}</span></header><pre>{readJson(fact.valueJson)}{fact.unit ? `\n单位：${fact.unit}` : ''}</pre><small className="material-fact-source">来源：{fact.sourceRef || '解析器返回'}</small><textarea value={note[fact.id] || fact.verificationNote || ''} onChange={event => setNote(current => ({ ...current, [fact.id]: event.target.value }))} placeholder="可选：记录人工核对说明" rows={2} /><div className="material-fact-actions">{fact.usableForGeneration ? <button className="secondary" onClick={() => void verifyFact(fact, false)}>撤回出题资格</button> : <button className="secondary" onClick={() => void verifyFact(fact, false)} disabled={fact.verified}>确认复核</button>}<button onClick={() => void verifyFact(fact, true)} disabled={fact.usableForGeneration}>确认并允许出题</button></div></article>)}</div> : <div className="material-facts-empty"><b>暂无可确认事实</b><span>上传并开始分析后，解析结果会显示在这里。</span></div>}{analysis?.annotations.length ? <details className="material-annotations"><summary>图纸标注（{analysis.annotations.length}）</summary>{analysis.annotations.map(annotation => <div key={annotation.id}><b>{annotation.kind}{annotation.pageNumber ? ` · 第 ${annotation.pageNumber} 页` : ''}</b><pre>{readJson(annotation.valueJson)}</pre><small>来源：{annotation.sourceRef || '解析器返回'} · 置信度 {Math.round(annotation.confidence * 100)}%</small></div>)}</details> : null}</section>
          <section className="panel material-next-step"><p className="section-kicker">下一阶段</p><h3>确认后进入组卷设计</h3><p className="muted">当前阶段只负责资料读取和事实确认。后续会接入任务书、A/B/C 样题、模板、分值结构和变式要求。</p><div className="material-step-list"><span className="done"><i>1</i>资料上传</span><span className={analysis?.facts.length ? 'current' : ''}><i>2</i>事实确认</span><span><i>3</i>组卷要求</span><span><i>4</i>人工审核导出</span></div></section>
        </aside>
      </div>
    </div>
  );
}
