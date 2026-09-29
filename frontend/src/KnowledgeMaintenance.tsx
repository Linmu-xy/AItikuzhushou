import { useEffect, useState } from 'react';
import { StudioIcon } from './StudioIcon';
import { errorText, maintenanceApi as api } from './maintenanceApi';
import './maintenance.css';

type Base = { id: string; name: string; description?: string; usedBytes?: number; quotaBytes?: number };
type Document = { id: string; originalFilename: string; status: string; sizeBytes?: number };
type PointDraft = { title: string; chapter: string; description: string; objective: string; misconceptions: string; status: string; origin: string; documentIds: string[] };
type Point = PointDraft & { id: string; version: number; updatedAt: string };
type Version = { version: number; point: Point; createdAt: string };
type Recycled = { id: string; name: string; recoverableUntil: string };
type Props = { auth: string; base?: Base; docs: Document[]; busy: boolean; parsingDocumentId?: string; file?: File; onFile: (file?: File) => void; onUpload: () => void; onParse: (id: string) => void; onPreview: (id: string) => void; onReload: () => Promise<void> };
const emptyDraft = (): PointDraft => ({ title: '', chapter: '', description: '', objective: '', misconceptions: '', status: 'DRAFT', origin: 'MANUAL', documentIds: [] });
const statusText: Record<string, string> = { DRAFT: '待确认', CONFIRMED: '已确认', ARCHIVED: '已归档', PARSED: '已解析', PARSED_PARTIAL: '部分解析', UPLOADED: '待解析', PARSING: '解析中', FAILED: '解析失败', PARSE_FAILED: '解析失败', OCR_REQUIRED: '解析质量不足', PROCESSING: '解析中' };
const size = (value = 0) => value >= 1024 ** 3 ? `${(value / 1024 ** 3).toFixed(1)} GB` : `${(value / 1024 ** 2).toFixed(1)} MB`;

export default function KnowledgeMaintenance(props: Props) {
  const { auth, base, docs, busy, parsingDocumentId, file, onFile, onUpload, onParse, onPreview, onReload } = props;
  const [view, setView] = useState<'documents' | 'points'>('documents');
  const [query, setQuery] = useState('');
  const [settings, setSettings] = useState(false);
  const [name, setName] = useState(base?.name || '');
  const [description, setDescription] = useState(base?.description || '');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [confirmArchive, setConfirmArchive] = useState(false);
  const [recycle, setRecycle] = useState<Recycled[] | null>(null);
  const act = async (work: () => Promise<void>) => {
    setPending(true); setError(''); setNotice('');
    try { await work(); } catch (error) { setError(errorText(error)); } finally { setPending(false); }
  };
  return <div className="maintenance knowledge-maintenance">
    <section className="panel maintenance-heading"><div><h2>{base?.name || '知识库'}</h2><p>{base ? `${docs.length} 份资料 · ${size(base.usedBytes)} / ${size(base.quotaBytes)}` : '选择或新建一个知识库，逐步积累课程资料。'}</p>{base?.description && <p className="knowledge-description">{base.description}</p>}</div><div className="maintenance-actions"><button className="secondary" disabled={pending} onClick={() => void act(async () => setRecycle(recycle ? null : await api<Recycled[]>('/api/knowledge-bases/recycle-bin', auth)))}>回收站</button>{base && <button className="secondary" aria-expanded={settings} onClick={() => { setSettings(!settings); setConfirmArchive(false); }}>管理知识库</button>}</div></section>
    {error && <p className="maintenance-error" role="alert">{error}</p>}{notice && <p className="maintenance-notice" role="status">{notice}</p>}
    {recycle && <section className="panel"><div className="maintenance-section-title"><h3>回收站</h3><button className="secondary" onClick={() => setRecycle(null)}>关闭</button></div>{recycle.length ? recycle.map(row => <div className="maintenance-row" key={row.id}><span><b>{row.name}</b><small>可恢复至 {new Date(row.recoverableUntil).toLocaleDateString()}</small></span><button className="secondary" disabled={pending} onClick={() => void act(async () => { await api(`/api/knowledge-bases/recycle-bin/${row.id}/restore`, auth, { method: 'POST' }); setRecycle(recycle.filter(value => value.id !== row.id)); await onReload(); setNotice('知识库已恢复。'); })}>恢复</button></div>) : <p className="maintenance-empty">没有可恢复的知识库。</p>}</section>}
    {settings && base && <section className="panel maintenance-editor"><h3>知识库信息</h3><label>名称<input maxLength={120} value={name} onChange={event => setName(event.target.value)} /></label><label>说明<textarea maxLength={1000} rows={2} value={description} onChange={event => setDescription(event.target.value)} /></label><div className="maintenance-actions"><button disabled={pending || !name.trim()} onClick={() => void act(async () => { await api(`/api/knowledge-bases/${base.id}`, auth, { method: 'PUT', body: JSON.stringify({ name, description }) }); await onReload(); setSettings(false); setNotice('知识库信息已更新。'); })}>保存信息</button><button className="secondary" onClick={() => setSettings(false)}>取消</button><button className="secondary maintenance-danger" disabled={pending} onClick={() => setConfirmArchive(true)}>移入回收站</button></div>{confirmArchive && <div className="maintenance-confirm"><p>“{base.name}”将从当前列表移除，资料保留，可在 30 天内恢复。</p><div className="maintenance-actions"><button className="danger-button" disabled={pending} onClick={() => void act(async () => { await api(`/api/knowledge-bases/${base.id}`, auth, { method: 'DELETE' }); await onReload(); })}>确认移入回收站</button><button className="secondary" onClick={() => setConfirmArchive(false)}>保留知识库</button></div></div>}</section>}
    {base && <><nav className="maintenance-tabs" aria-label="知识库内容"><button aria-current={view === 'documents' ? 'page' : undefined} onClick={() => setView('documents')}>资料</button><button aria-current={view === 'points' ? 'page' : undefined} onClick={() => setView('points')}>知识点</button></nav>
      <div hidden={view !== 'documents'}><section className="panel"><div className="maintenance-section-title"><div><h3>课程资料</h3><p className="muted">上传、解析并校对资料。相同内容不能重复上传；任何已上传资料都可从原文件重新解析。</p></div></div><div className="knowledge-upload-line"><label className="studio-file-picker"><StudioIcon name="attach" size={18} /><span>{file?.name || '选择资料'}</span><input className="sr-only" type="file" accept=".pdf,.doc,.docx,.ppt,.pptx,.jpg,.jpeg,.png" aria-label="选择知识库资料文件" onChange={event => onFile(event.target.files?.[0])} /></label><button disabled={busy || !file} onClick={onUpload}>{busy ? '处理中…' : '上传资料'}</button><small>PDF、Word、PPT、JPG、PNG</small></div><label className="maintenance-search"><StudioIcon name="search" size={18} /><input aria-label="搜索资料" placeholder="搜索资料名称" value={query} onChange={event => setQuery(event.target.value)} /></label><div className="maintenance-document-list">{docs.filter(doc => doc.originalFilename.toLowerCase().includes(query.toLowerCase())).map(doc => <article className="maintenance-document" key={doc.id}><StudioIcon name="file" /><div><b>{doc.originalFilename}</b><small>{size(doc.sizeBytes)} · {parsingDocumentId === doc.id ? '解析中' : statusText[doc.status] || doc.status}</small></div><div className="maintenance-actions"><button className="secondary" disabled={busy || parsingDocumentId === doc.id} onClick={() => { if (doc.status === 'UPLOADED' || window.confirm('重新解析会从原文件提取内容，可能覆盖已校对的页面和表格。确定继续吗？')) onParse(doc.id); }}>{parsingDocumentId === doc.id ? '解析中…' : doc.status === 'UPLOADED' ? '开始解析' : '重新解析'}</button><button className="secondary" onClick={() => onPreview(doc.id)}>查看与校对</button></div></article>)}{!docs.some(doc => doc.originalFilename.toLowerCase().includes(query.toLowerCase())) && <p className="maintenance-empty">{query ? '没有匹配的资料。' : '还没有资料，上传第一份课程文档开始整理。'}</p>}</div></section></div>
      <div hidden={view !== 'points'}><KnowledgePoints auth={auth} baseId={base.id} docs={docs} /></div></>}
  </div>;
}

function KnowledgePoints({ auth, baseId, docs }: { auth: string; baseId: string; docs: Document[] }) {
  const [points, setPoints] = useState<Point[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [pending, setPending] = useState(false);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('ACTIVE');
  const [editing, setEditing] = useState<Point | null | undefined>();
  const [draft, setDraft] = useState<PointDraft>(emptyDraft);
  const [dirty, setDirty] = useState(false);
  useEffect(() => {
    if (!dirty) return;
    const protect = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', protect);
    return () => window.removeEventListener('beforeunload', protect);
  }, [dirty]);
  const [versions, setVersions] = useState<Version[] | null>(null);
  const [selection, setSelection] = useState<string[]>([]);
  const [showSuggest, setShowSuggest] = useState(false);
  const [suggestions, setSuggestions] = useState<PointDraft[]>([]);
  const root = `/api/knowledge-bases/${baseId}/points`;
  useEffect(() => { const controller = new AbortController(); api<Point[]>(root, auth, { signal: controller.signal }).then(setPoints).catch(error => { if (!controller.signal.aborted) setError(errorText(error)); }).finally(() => { if (!controller.signal.aborted) setLoading(false); }); return () => controller.abort(); }, [root, auth]);
  const act = async (work: () => Promise<void>) => { setPending(true); setError(''); setNotice(''); try { await work(); } catch (error) { setError(errorText(error)); } finally { setPending(false); } };
  const edit = (point: Point | null) => { if (dirty) return setError('当前修改尚未保存，请先保存或取消编辑。'); setEditing(point); setDraft(point ? { ...point } : emptyDraft()); setVersions(null); setError(''); };
  const change = (key: keyof PointDraft, value: string) => { setDraft(current => ({ ...current, [key]: value })); setDirty(true); };
  const persist = async (value: PointDraft, point?: Point | null) => {
    const saved = await api<Point>(point ? `${root}/${point.id}` : root, auth, { method: point ? 'PUT' : 'POST', body: JSON.stringify({ ...value, expectedVersion: point?.version }) });
    setPoints(current => [saved, ...current.filter(row => row.id !== saved.id)]); return saved;
  };
  const visible = points.filter(point => (filter === 'ACTIVE' ? point.status !== 'ARCHIVED' : point.status === filter) && `${point.title} ${point.chapter} ${point.objective}`.toLowerCase().includes(query.toLowerCase()));
  return <section className="panel knowledge-points"><div className="maintenance-section-title"><div><h3>可维护的知识点</h3><p className="muted">确认后作为课程背景用于新命题；已有试题与历史计划不变。</p></div><div className="maintenance-actions"><button className="secondary" onClick={() => setShowSuggest(!showSuggest)} aria-expanded={showSuggest}>从资料整理</button><button onClick={() => edit(null)}><StudioIcon name="plus" size={16} />新建知识点</button></div></div>
    {error && <p className="maintenance-error" role="alert">{error}</p>}{notice && <p className="maintenance-notice" role="status">{notice}</p>}
    {showSuggest && <section className="maintenance-subpanel"><h4>选择要整理的资料</h4><p className="muted">AI 生成候选知识点，不会覆盖已有内容。长文档会抽样，需人工补齐。</p><div className="knowledge-document-picks">{docs.filter(doc => ['PARSED', 'PARSED_PARTIAL'].includes(doc.status)).map(doc => <label key={doc.id}><input type="checkbox" checked={selection.includes(doc.id)} disabled={pending} onChange={event => setSelection(event.target.checked ? [...selection, doc.id] : selection.filter(id => id !== doc.id))} />{doc.originalFilename}</label>)}</div><button disabled={pending || !selection.length || selection.length > 8} onClick={() => void act(async () => { const result = await api<{ points: PointDraft[]; message: string }>(`${root}/suggestions`, auth, { method: 'POST', body: JSON.stringify({ documentIds: selection }) }); setSuggestions(result.points); setNotice(result.points.length ? result.message : '没有发现新的知识点，可更换资料或手动补充。'); })}>{pending ? '正在整理，请稍候…' : '生成建议（消耗 AI 额度）'}</button>{!selection.length && <small>可选择 1–8 份已解析资料。</small>}{suggestions.map((suggestion, index) => <article className="knowledge-suggestion" key={`${index}-${suggestion.title}`}><h4>{suggestion.title}</h4><p>{suggestion.description}</p><p className="muted">能力目标：{suggestion.objective}</p><div className="maintenance-actions"><button className="secondary" disabled={pending} onClick={() => void act(async () => { await persist(suggestion); setSuggestions(current => current.filter(value => value !== suggestion)); setNotice('已加入待确认知识点，可继续编辑。'); })}>加入草稿</button><button className="secondary" disabled={pending} onClick={() => setSuggestions(current => current.filter(value => value !== suggestion))}>忽略</button></div></article>)}</section>}
    <div className="maintenance-filters"><label className="maintenance-search"><StudioIcon name="search" size={18} /><input aria-label="搜索知识点" placeholder="搜索名称、章节或能力目标" value={query} onChange={event => setQuery(event.target.value)} /></label><select aria-label="知识点状态" value={filter} onChange={event => setFilter(event.target.value)}><option value="ACTIVE">使用中的知识点</option><option value="DRAFT">待确认</option><option value="CONFIRMED">已确认</option><option value="ARCHIVED">已归档</option></select><small>{visible.length} 个</small></div>
    <div className={`knowledge-point-layout ${editing !== undefined ? 'has-editor' : ''}`}><div className="knowledge-point-list" aria-label="知识点列表">{loading ? <p role="status" className="maintenance-empty">正在加载知识点…</p> : visible.length ? visible.map(point => <button className={`knowledge-point-row ${editing?.id === point.id ? 'selected' : ''}`} key={point.id} onClick={() => edit(point)}><span><b>{point.title}</b><small>{point.chapter || '未分章节'} · {statusText[point.status]} · v{point.version}</small><p>{point.objective || point.description || '待补充说明'}</p></span><StudioIcon name="arrow" size={16} /></button>) : <div className="maintenance-empty"><h4>{query || filter !== 'ACTIVE' ? '没有匹配的知识点' : '把课程内容整理成可复用的知识'}</h4><p>可手动新建，或从已解析的资料生成建议。</p></div>}</div>
      {editing !== undefined && <form className="maintenance-editor knowledge-point-editor" onSubmit={event => { event.preventDefault(); void act(async () => { const saved = await persist(draft, editing); setEditing(saved); setDraft(saved); setDirty(false); setVersions(null); setNotice('知识点已保存，并保留版本记录。'); }); }}><div className="maintenance-section-title"><h4>{editing ? '编辑知识点' : '新建知识点'}</h4><button type="button" className="secondary" disabled={pending} onClick={() => { setEditing(undefined); setDirty(false); setError(''); }}>取消编辑</button></div><label>名称<input required maxLength={180} value={draft.title} onChange={event => change('title', event.target.value)} /></label><label>章节 / 主题<input maxLength={180} value={draft.chapter} onChange={event => change('chapter', event.target.value)} placeholder="例如：投影原理" /></label><label>概念与适用条件<textarea rows={3} maxLength={6000} value={draft.description} onChange={event => change('description', event.target.value)} /></label><label>能力目标<textarea rows={3} maxLength={6000} value={draft.objective} onChange={event => change('objective', event.target.value)} placeholder="学完后能够做出什么判断、完成什么任务？" /></label><label>常见误解<textarea rows={2} maxLength={6000} value={draft.misconceptions} onChange={event => change('misconceptions', event.target.value)} /></label><label>状态<select value={draft.status} onChange={event => change('status', event.target.value)}><option value="DRAFT">待确认</option><option value="CONFIRMED">已确认（用于后续命题）</option><option value="ARCHIVED">已归档（不再用于新命题）</option></select></label><div className="maintenance-actions"><button disabled={pending}>{pending ? '保存中…' : '保存知识点'}</button>{editing && <button type="button" className="secondary" disabled={pending} onClick={() => void act(async () => setVersions(versions ? null : await api<Version[]>(`${root}/${editing.id}/versions`, auth)))}>版本记录</button>}</div>{versions && <div className="maintenance-history">{versions.map(version => <details key={version.version}><summary>v{version.version} · {new Date(version.createdAt).toLocaleString()}</summary><b>{version.point.title}</b><p>{version.point.description}</p><p>{version.point.objective}</p><small>{statusText[version.point.status]}</small></details>)}</div>}</form>}
    </div>
  </section>;
}
