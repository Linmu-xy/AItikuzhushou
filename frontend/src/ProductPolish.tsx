import { CSSProperties, Fragment, PointerEvent as ReactPointerEvent, ReactNode, useEffect, useMemo, useRef, useState } from 'react';

type JobLite = {
  id: string;
  type: string;
  status: string;
  result: Record<string, unknown>[];
  createdAt?: string;
  plannedItems?: number;
  totalItems?: number;
  estimatedRemainingSeconds?: number;
  estimatedCompletionAt?: string;
  currentConcurrency?: number;
  queueAhead?: number;
  generationMode?: string;
};
type ReviewItem = {
  id: string;
  jobId: string;
  sequence: number;
  status: string;
  locked: boolean;
  comment: string;
  question: Record<string, unknown>;
  version: number;
  updatedAt: string;
};
type ReviewSummary = {
  total: number;
  deliveryReady: number;
  pending: number;
  statusCounts: Record<string, number>;
  readyForDelivery: boolean;
};
export type DeliveryPreflight = {
  jobId: string;
  qualityStatus: string;
  qualityScore: number;
  actualTotal: number;
  expectedTotal: number;
  generationMode: string;
  reviewTotal: number;
  approvedReviews: number;
  deliveryReady: boolean;
  warnings: string[];
  checkedAt: string;
};
type ReviewEvent = { id: string; action: string; comment: string; actor: string; createdAt: string };
type TableCell = { id: string; row: number; column: number; rowSpan: number; columnSpan: number; text: string; confidence: number; boundingBox?: number[]; source: string };
type ParsedTable = { id: string; page: number; tableIndex: number; title: string; headers: string[]; rows: string[][]; markdown: string; confidence: number; reviewStatus: string; warnings: string[]; updatedAt: string; structureHtml?: string; cells?: TableCell[]; extractionSource?: string };
type MarkdownPage = { id: string; page: number; markdown: string; reviewStatus: string; version: number; warnings: string[]; confirmedBy?: string; updatedAt: string };
type PageBlock = { type: 'HEADING' | 'PARAGRAPH' | 'LIST_ITEM' | 'TABLE'; text: string; level: number; tableId?: string; tableIndex?: number };
type PageContent = { page: number; blocks: PageBlock[]; reviewStatus: string; version: number; updatedAt: string };
type PageVersion = { version: number; markdown: string; reviewStatus: string; action: string; actor: string; createdAt: string };
type PageUpdateResult = { page: MarkdownPage; vectorsRebuilt: boolean; chunkCount: number };
type ParsedDocument = { status: string; chunks: unknown[]; textQuality: number; warnings: string[]; tables?: ParsedTable[] };
type HealthCheck = { status: string; detail: string; latencyMs: number };
type SystemHealth = { status: string; checkedAt: string; components: Record<string, HealthCheck> };
type ModelConfiguration = Record<string, unknown>;
type ExcelIssue = { row: number; severity: string; code: string; message: string };
type ExcelValidation = { totalRows: number; valid: boolean; errorCount: number; warningCount: number; issues: ExcelIssue[]; preview: Record<string, unknown>[] };

const statusLabel: Record<string, string> = {
  AI_DRAFT: 'AI 草稿', PENDING_REVIEW: '待审核', APPROVED: '已通过', REJECTED: '已驳回', LOCKED: '已锁定', OBSOLETE: '已废弃'
};
const typeLabel: Record<string, string> = {
  SINGLE_CHOICE: '单选题', MULTIPLE_CHOICE: '多选题', TRUE_FALSE: '判断题', FILL_BLANK: '填空题',
  SHORT_ANSWER: '简答题', CALCULATION: '计算题', ESSAY: '论述题', CASE_ANALYSIS: '案例分析', COMPREHENSIVE: '综合题'
};
const difficultyLabel: Record<string, string> = { EASY: '简单', MEDIUM: '中等', HARD: '困难' };

async function request<T>(path: string, options: RequestInit = {}) {
  const response = await fetch(path, { ...options, credentials: 'same-origin' });
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

const seconds = (value?: number) => {
  if (value == null) return '计算中';
  if (value < 60) return `${Math.max(0, Math.ceil(value))} 秒`;
  const minute = Math.floor(value / 60); const rest = Math.ceil(value % 60);
  return rest ? `${minute} 分 ${rest} 秒` : `${minute} 分钟`;
};
const text = (value: unknown) => value == null ? '' : typeof value === 'string' ? value : Array.isArray(value) ? value.join(' | ') : String(value);

const markdownCells = (line: string) => {
  let value = line.trim(); if (value.startsWith('|')) value = value.slice(1); if (value.endsWith('|')) value = value.slice(0, -1);
  return value.split('|').map(cell => cell.trim());
};
const inlineMarkdown = (value: string): ReactNode[] => value.split(/(\*\*[^*]+\*\*|`[^`]+`|<br\s*\/?>)/gi).filter(Boolean).map((part, index) => {
  if (part.startsWith('**') && part.endsWith('**')) return <strong key={index}>{part.slice(2, -2)}</strong>;
  if (part.startsWith('`') && part.endsWith('`')) return <code key={index}>{part.slice(1, -1)}</code>;
  if (/^<br\s*\/?>$/i.test(part)) return <br key={index} />;
  return part;
});

/** Renders saved cells, including merged cells.  It never injects OCR HTML. */
function StructuredTablePreview({ table }: { table: ParsedTable }) {
  const cells = table.cells || [];
  const readingLayout = table.extractionSource === 'DEEPSEEK_READING' || !cells.length || cells.every(cell => cell.rowSpan === 1 && cell.columnSpan === 1);
  if (readingLayout) return <figure className="reading-table-card">{table.title && <figcaption>{inlineMarkdown(table.title)}</figcaption>}<div className="markdown-table-wrap"><table className={`reading-table columns-${table.headers.length}`}><thead><tr>{table.headers.map((cell, index) => <th key={index} scope="col">{inlineMarkdown(cell)}</th>)}</tr></thead><tbody>{table.rows.map((row, rowIndex) => <tr key={rowIndex} className={row[0]?.trim() ? 'group-start' : ''}>{table.headers.map((_, column) => <td key={column}>{inlineMarkdown(row[column] || '')}</td>)}</tr>)}</tbody></table></div></figure>;
  const rows = Math.max(0, ...cells.map(cell => cell.row + Math.max(1, cell.rowSpan)));
  return <div className="structured-table-wrap"><table className="structured-table"><tbody>{Array.from({ length: rows }, (_, row) => <tr key={row}>{cells.filter(cell => cell.row === row).sort((left, right) => left.column - right.column).map(cell => {
    const Tag = row === 0 ? 'th' : 'td';
    return <Tag key={cell.id || `${cell.row}-${cell.column}`} rowSpan={Math.max(1, cell.rowSpan)} colSpan={Math.max(1, cell.columnSpan)}>{inlineMarkdown(cell.text)}</Tag>;
  })}</tr>)}</tbody></table></div>;
}

function BlockDocument({ content, tables }: { content?: PageContent; tables: ParsedTable[] }) {
  if (!content) return <p className="empty">正在加载页面内容…</p>;
  return <div className="safe-markdown page-block-document">{content.blocks.map((block, index) => {
    if (block.type === 'TABLE') {
      const table = tables.find(item => item.id === block.tableId) || tables.find(item => item.page === content.page && item.tableIndex === block.tableIndex);
      return table ? <StructuredTablePreview key={`table-${table.id}`} table={table} /> : <p className="inline-error" key={index}>表格结构缺失，请重新解析本页。</p>;
    }
    if (block.type === 'HEADING') {
      const Tag = `h${Math.max(1, Math.min(6, block.level))}` as 'h1' | 'h2' | 'h3' | 'h4' | 'h5' | 'h6';
      return <Tag key={index}>{inlineMarkdown(block.text)}</Tag>;
    }
    if (block.type === 'LIST_ITEM') return <div className="page-list-item" key={index}><i>•</i><span>{inlineMarkdown(block.text)}</span></div>;
    return <p key={index}>{block.text.split('\n').map((line, lineIndex) => <Fragment key={lineIndex}>{lineIndex > 0 && <br />}{inlineMarkdown(line)}</Fragment>)}</p>;
  })}</div>;
}

function OriginalPageImage({ documentId, page, drawing }: { documentId: string; page: number; drawing: boolean }) {
  const imageUrl = `/api/documents/${documentId}/pages/${page}/image?dpi=300`;
  return <div className="engineering-drawing-page">
    <div className="engineering-drawing-note"><strong>{drawing ? '工程图原页' : '原页截图'}</strong><span>{drawing ? '300 DPI 高清复现；尺寸、标注、剖面和视图关系以原图为准。' : '本页未完成可靠结构化 OCR，已保留 300 DPI 高清原页；请以原图核验。'}</span><a href={imageUrl} target="_blank" rel="noreferrer">打开高清页</a></div>
    <div className="engineering-drawing-image-wrap"><img src={imageUrl} alt={`PDF 第 ${page} 页高清原页`} loading="eager" /></div>
  </div>;
}

/** React escapes every cell and line, so parsed documents can never inject executable HTML. */
function SafeMarkdown({ source, tables = [], page }: { source: string; tables?: ParsedTable[]; page?: number }) {
  const lines = source.replace(/\r/g, '').split('\n'); const blocks: ReactNode[] = [];
  for (let index = 0; index < lines.length;) {
    const line = lines[index].trim();
    if (!line || /^\[第\d+页]$/.test(line)) { index++; continue; }
    const structuredTable = /^\[结构化表格:(\d+)]$/.exec(line);
    if (structuredTable) {
      const table = tables.find(item => item.page === page && item.tableIndex === Number(structuredTable[1]));
      blocks.push(table ? <StructuredTablePreview key={`structured-${page}-${structuredTable[1]}`} table={table} /> : <p key={`missing-${index}`}>表格结构正在加载…</p>);
      index++; continue;
    }
    const heading = /^(#{1,6})\s+(.+)$/.exec(line);
    if (heading) { const level = Math.min(6, heading[1].length); const Tag = `h${level}` as 'h1' | 'h2' | 'h3' | 'h4' | 'h5' | 'h6'; blocks.push(<Tag key={index}>{inlineMarkdown(heading[2])}</Tag>); index++; continue; }
    if (line.includes('|') && index + 1 < lines.length && /^\s*\|?\s*:?-{2,}.*\|/.test(lines[index + 1])) {
      const headers = markdownCells(line); const rows: string[][] = []; index += 2;
      while (index < lines.length && lines[index].includes('|') && lines[index].trim()) { rows.push(markdownCells(lines[index])); index++; }
      blocks.push(<div className="markdown-table-wrap" key={`table-${index}`}><table><thead><tr>{headers.map((cell, cellIndex) => <th key={cellIndex}>{inlineMarkdown(cell)}</th>)}</tr></thead><tbody>{rows.map((row, rowIndex) => <tr key={rowIndex}>{headers.map((_, cellIndex) => <td key={cellIndex}>{inlineMarkdown(row[cellIndex] || '')}</td>)}</tr>)}</tbody></table></div>); continue;
    }
    if (/^[-*]\s+/.test(line)) {
      const items: string[] = []; while (index < lines.length && /^[-*]\s+/.test(lines[index].trim())) { items.push(lines[index].trim().replace(/^[-*]\s+/, '')); index++; }
      blocks.push(<ul key={`ul-${index}`}>{items.map((item, itemIndex) => <li key={itemIndex}>{inlineMarkdown(item)}</li>)}</ul>); continue;
    }
    if (/^\d+[.)、]\s+/.test(line)) {
      const items: string[] = []; while (index < lines.length && /^\d+[.)、]\s+/.test(lines[index].trim())) { items.push(lines[index].trim().replace(/^\d+[.)、]\s+/, '')); index++; }
      blocks.push(<ol key={`ol-${index}`}>{items.map((item, itemIndex) => <li key={itemIndex}>{inlineMarkdown(item)}</li>)}</ol>); continue;
    }
    const paragraph: string[] = [line]; index++;
    while (index < lines.length && lines[index].trim() && !/^(#{1,6})\s+/.test(lines[index].trim()) && !/^[-*]\s+/.test(lines[index].trim()) && !/^\d+[.)、]\s+/.test(lines[index].trim()) && !(lines[index].includes('|') && index + 1 < lines.length && /^\s*\|?\s*:?-{2,}.*\|/.test(lines[index + 1]))) { paragraph.push(lines[index].trim()); index++; }
    blocks.push(<p key={`p-${index}`}>{paragraph.map((part, partIndex) => <span key={partIndex}>{inlineMarkdown(part)}{partIndex < paragraph.length - 1 && <br />}</span>)}</p>);
  }
  return <div className="safe-markdown">{blocks}</div>;
}

export function TaskRuntimeInsights({ jobs }: { jobs: JobLite[] }) {
  const questionJobs = jobs.filter(job => job.type === 'QUESTION_BANK');
  const active = questionJobs.filter(job => ['QUEUED', 'RUNNING'].includes(job.status));
  const newest = active[0];
  if (!questionJobs.length) return null;
  return <section className="panel product-panel runtime-panel">
    <div className="section-heading"><div><p className="eyebrow">运行态洞察</p><h2>命题吞吐与排队情况</h2></div><span className={active.length ? 'pulse-badge' : 'soft-badge'}>{active.length ? `${active.length} 个运行中` : '当前空闲'}</span></div>
    <div className="product-metrics">
      <article><span>预计剩余</span><b>{newest ? seconds(newest.estimatedRemainingSeconds) : '—'}</b><small>{newest?.estimatedCompletionAt ? `约 ${new Date(newest.estimatedCompletionAt).toLocaleTimeString()} 完成` : '运行后按真实吞吐动态修正'}</small></article>
      <article><span>当前并发</span><b>{newest?.currentConcurrency ?? 0}</b><small>{newest?.generationMode || '—'} 生成模式</small></article>
      <article><span>前方队列</span><b>{newest?.queueAhead ?? 0}</b><small>任务恢复后自动继续排队</small></article>
      <article><span>历史任务</span><b>{questionJobs.length}</b><small>包含成功、失败与部分交付</small></article>
    </div>
  </section>;
}

export function BlueprintInsights({ rows }: { rows: Record<string, unknown>[] }) {
  const groups = useMemo(() => {
    const types = new Map<string, number>(), difficulties = new Map<string, number>(), points = new Map<string, number>();
    rows.forEach(row => {
      const add = (map: Map<string, number>, value: string) => map.set(value || '未设置', (map.get(value || '未设置') || 0) + 1);
      add(types, text(row.type)); add(difficulties, text(row.difficulty)); add(points, text(row.assessmentPoint));
    });
    return { types: [...types], difficulties: [...difficulties], points: [...points] };
  }, [rows]);
  if (!rows.length) return null;
  const maxPoint = Math.max(...groups.points.map(([, count]) => count), 0);
  const issues = [
    groups.types.length < 3 ? '题型覆盖较少，正式考核建议至少覆盖 3 类题型。' : '',
    groups.points.length < Math.min(5, rows.length) ? '考点集中度较高，请确认是否符合考试范围。' : '',
    maxPoint > Math.ceil(rows.length * .35) ? '单一考点占比超过 35%，可能影响内容效度。' : ''
  ].filter(Boolean);
  const bars = (entries: [string, number][], labels: Record<string, string>) => <div className="distribution-list">{entries.map(([name, count]) => <div key={name}><span>{labels[name] || name}</span><i><i style={{ width: `${count / rows.length * 100}%` }} /></i><b>{count}</b></div>)}</div>;
  return <section className="panel product-panel blueprint-insights">
    <div className="section-heading"><div><p className="eyebrow">细目表诊断</p><h2>题型、难度与考点覆盖</h2></div><span className={issues.length ? 'warning-badge' : 'success-badge'}>{issues.length ? `${issues.length} 项建议` : '分布正常'}</span></div>
    <div className="insight-grid"><article><h3>题型分布</h3>{bars(groups.types, typeLabel)}</article><article><h3>难度分布</h3>{bars(groups.difficulties, difficultyLabel)}</article><article><h3>考点覆盖</h3><strong>{groups.points.length}</strong><span> 个独立考点 / {rows.length} 个题位</span><p>最高单点重复 {maxPoint} 次</p></article></div>
    {issues.length > 0 && <div className="inline-warnings">{issues.map(issue => <p key={issue}>⚠ {issue}</p>)}</div>}
  </section>;
}

export function DocumentQualityPanel({ documentId }: { documentId: string }) {
  const [result, setResult] = useState<ParsedDocument>();
  const [error, setError] = useState('');
  useEffect(() => {
    setResult(undefined); setError('');
    if (!documentId) return;
    void request<ParsedDocument>(`/api/documents/${documentId}/parsed`).then(setResult).catch(error => setError(error instanceof Error ? error.message : '读取解析质量失败'));
  }, [documentId]);
  if (!documentId) return null;
  const qualityScore = result ? Math.max(0, Math.min(100, result.textQuality <= 1 ? result.textQuality * 100 : result.textQuality)) : 0;
  return <section className="panel product-panel document-quality">
    <div className="section-heading"><div><p className="eyebrow">解析质量</p><h2>OCR 与分块健康度</h2></div><span className={result && qualityScore >= 80 ? 'success-badge' : 'warning-badge'}>{result ? `${qualityScore.toFixed(Number.isInteger(qualityScore) ? 0 : 1)} 分` : '读取中'}</span></div>
    {error ? <p className="inline-error">{error}</p> : result && <><div className="quality-meter"><i style={{ width: `${qualityScore}%` }} /></div><div className="compact-facts"><span>状态 <b>{result.status}</b></span><span>文本片段 <b>{result.chunks?.length ?? 0}</b></span><span>结构化表格 <b>{result.tables?.length ?? 0}</b></span><span>质量提示 <b>{result.warnings?.length ?? 0}</b></span></div>{result.warnings?.length > 0 && <div className="inline-warnings">{result.warnings.map(item => <p key={item}>⚠ {item}</p>)}</div>}</>}
  </section>;
}

export function MarkdownReviewPanel({ documentId, onMessage }: { documentId: string; onMessage: (message: string) => void }) {
  const [pages, setPages] = useState<MarkdownPage[]>([]); const [selectedPage, setSelectedPage] = useState(1);
  const [tables, setTables] = useState<ParsedTable[]>([]);
  const [pageContent, setPageContent] = useState<PageContent>();
  const [draft, setDraft] = useState(''); const [mode, setMode] = useState<'READ' | 'SOURCE' | 'COMPARE'>('READ');
  const [versions, setVersions] = useState<PageVersion[]>([]); const [rebuildVectors, setRebuildVectors] = useState(true);
  const [compareRatio, setCompareRatio] = useState(50); const [mobileComparePane, setMobileComparePane] = useState<'PDF' | 'MARKDOWN'>('MARKDOWN');
  const [urlSyncReady, setUrlSyncReady] = useState(false);
  const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const readerAnchor = useRef<HTMLDivElement>(null); const compareContainer = useRef<HTMLDivElement>(null); const loadedDocument = useRef('');
  const selected = pages.find(page => page.page === selectedPage) || pages[0];
  const selectedIsDrawing = Boolean(selected?.markdown.includes('[工程图页面'));
  const selectedNeedsOriginal = Boolean(selected?.markdown.includes('[工程图页面') || selected?.markdown.includes('[原页截图'));
  const load = async (preferredPage?: number, enableUrlSync = false) => {
    if (!documentId) return; setError('');
    try {
      const [result, tableResult] = await Promise.all([request<MarkdownPage[]>(`/api/documents/${documentId}/pages`), request<ParsedTable[]>(`/api/documents/${documentId}/tables`)]); setPages(result); setTables(tableResult);
      const page = result.find(item => item.page === (preferredPage ?? selectedPage)) || result[0];
      setSelectedPage(page?.page || 1); setDraft(page?.markdown || '');
      if (page) { const [history, content] = await Promise.all([request<PageVersion[]>(`/api/documents/${documentId}/pages/${page.page}/versions`), request<PageContent>(`/api/documents/${documentId}/pages/${page.page}/content`)]); setVersions(history); setPageContent(content); } else { setVersions([]); setPageContent(undefined); }
      if (enableUrlSync) { loadedDocument.current = documentId; setUrlSyncReady(true); }
    } catch (reason) { setError(reason instanceof Error ? reason.message : '读取 Markdown 页面失败'); }
  };
  useEffect(() => {
    loadedDocument.current = ''; setUrlSyncReady(false); setPages([]); setTables([]); setPageContent(undefined); setDraft(''); setVersions([]);
    const params = new URLSearchParams(window.location.search); const sameDocument = params.get('documentId') === documentId;
    const restoredPage = sameDocument ? Number(params.get('docPage')) : 1; const restoredMode = sameDocument ? params.get('docView') : null;
    if (restoredMode === 'READ' || restoredMode === 'SOURCE' || restoredMode === 'COMPARE') setMode(restoredMode);
    void load(Number.isInteger(restoredPage) && restoredPage > 0 ? restoredPage : 1, true);
  }, [documentId]);
  const choosePage = async (page: MarkdownPage) => {
    if (selected && draft.trim() !== selected.markdown.trim() && !window.confirm('当前页面有未保存修改，确定切换页面吗？')) return false;
    setPageContent(undefined);
    setSelectedPage(page.page); setDraft(page.markdown); setError('');
    try { const [history, content] = await Promise.all([request<PageVersion[]>(`/api/documents/${documentId}/pages/${page.page}/versions`), request<PageContent>(`/api/documents/${documentId}/pages/${page.page}/content`)]); setVersions(history); setPageContent(content); }
    catch (reason) { setError(reason instanceof Error ? reason.message : '读取版本历史失败'); }
    window.requestAnimationFrame(() => readerAnchor.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
    return true;
  };
  const navigatePage = async (direction: -1 | 1) => {
    if (!selected) return; const currentIndex = pages.findIndex(page => page.id === selected.id); const target = pages[currentIndex + direction];
    if (target) await choosePage(target);
  };
  const beginCompareResize = (event: ReactPointerEvent<HTMLButtonElement>) => {
    event.preventDefault(); const container = compareContainer.current; if (!container) return;
    const resize = (pointer: PointerEvent) => { const bounds = container.getBoundingClientRect(); setCompareRatio(Math.max(35, Math.min(65, Math.round((pointer.clientX - bounds.left) * 100 / bounds.width)))); };
    const stop = () => { window.removeEventListener('pointermove', resize); window.removeEventListener('pointerup', stop); };
    window.addEventListener('pointermove', resize); window.addEventListener('pointerup', stop, { once: true });
  };
  useEffect(() => {
    if (!selected || !urlSyncReady || loadedDocument.current !== documentId) return;
    const params = new URLSearchParams(window.location.search); params.set('documentId', documentId);
    params.set('docPage', String(selected.page)); params.set('docView', mode); window.history.replaceState(null, '', `${window.location.pathname}?${params}`);
  }, [documentId, selected?.page, mode, urlSyncReady]);
  useEffect(() => {
    if (!selected) return; const currentIndex = pages.findIndex(page => page.id === selected.id);
    for (const adjacent of [pages[currentIndex - 1], pages[currentIndex + 1]]) if (adjacent) {
      const image = new Image();
      const dpi = adjacent.markdown.includes('[工程图页面') || adjacent.markdown.includes('[原页截图') ? 300 : 150;
      image.src = `/api/documents/${documentId}/pages/${adjacent.page}/image?dpi=${dpi}`;
    }
  }, [documentId, pages, selected?.id]);
  useEffect(() => {
    const keyboard = (event: KeyboardEvent) => {
      const element = event.target as HTMLElement | null; if (element?.matches('input,textarea,select,[contenteditable="true"]')) return;
      if (event.key === 'ArrowLeft') { event.preventDefault(); void navigatePage(-1); }
      if (event.key === 'ArrowRight') { event.preventDefault(); void navigatePage(1); }
    };
    window.addEventListener('keydown', keyboard); return () => window.removeEventListener('keydown', keyboard);
  }, [pages, selected?.id, draft]);
  useEffect(() => {
    if (!selected || draft.trim() === selected.markdown.trim()) return;
    const protectDraft = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', protectDraft); return () => window.removeEventListener('beforeunload', protectDraft);
  }, [selected?.id, selected?.markdown, draft]);
  const save = async () => {
    if (!selected || !draft.trim()) return;
    const prompt = rebuildVectors ? '确认保存本页 Markdown，并立即重建该文档的检索向量吗？' : '确认只保存 Markdown？本次不会更新知识库检索向量。';
    if (!window.confirm(prompt)) return;
    setBusy(true); setError('');
    try {
      const result = await request<PageUpdateResult>(`/api/documents/${documentId}/pages/${selected.page}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ markdown: draft, confirmed: true, rebuildVectors }) });
      await load(selected.page); onMessage(result.vectorsRebuilt ? `第 ${selected.page} 页已确认并重建 ${result.chunkCount} 个向量片段。` : `第 ${selected.page} 页 Markdown 已保存，尚未重建向量。`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : '保存页面失败'); }
    finally { setBusy(false); }
  };
  if (!documentId) return null;
  const pageIndex = selected ? pages.findIndex(page => page.id === selected.id) : -1; const hasPrevious = pageIndex > 0; const hasNext = pageIndex >= 0 && pageIndex < pages.length - 1;
  const pager = () => <div className="markdown-pager markdown-pager-top">
    <button className="secondary" onClick={() => void navigatePage(-1)} disabled={!hasPrevious}>← 上一页</button>
    <strong>第 {selected?.page || 0} / {pages.length} 页</strong>
    <button className="secondary" onClick={() => void navigatePage(1)} disabled={!hasNext}>下一页 →</button>
  </div>;
  return <section className="panel product-panel markdown-review-panel">
    <div className="section-heading"><div><p className="eyebrow">文档阅读</p><h2>阅读版文档预览</h2></div><span className="soft-badge">第 {selected?.page || 0} / {pages.length} 页</span></div>
    {error && <p className="inline-error">{error}</p>}
    {!pages.length && !error ? <p className="empty">正在加载页面 Markdown…</p> : <>
      <div className="markdown-toolbar"><div className="page-selector"><label>页面<select value={selected?.page || 1} onChange={event => { const page = pages.find(item => item.page === Number(event.target.value)); if (page) void choosePage(page); }}>{pages.map(page => <option key={page.id} value={page.page}>第 {page.page} 页 · v{page.version} · {page.reviewStatus === 'HUMAN_CONFIRMED' ? '已确认' : '待确认'}</option>)}</select></label></div>{pager()}<div className="view-switch"><button className={mode === 'READ' ? 'active' : 'secondary'} onClick={() => setMode('READ')}>阅读模式</button><button className={mode === 'SOURCE' ? 'active' : 'secondary'} onClick={() => setMode('SOURCE')}>源码编辑</button><button className={mode === 'COMPARE' ? 'active' : 'secondary'} onClick={() => setMode('COMPARE')}>原文对照</button></div></div>
      {selected && <>
        <div ref={readerAnchor} className="reader-scroll-anchor" />
        {mode === 'READ' && <div className="markdown-reader-shell"><button className="side-page-button previous" aria-label="上一页（侧边）" onClick={() => void navigatePage(-1)} disabled={!hasPrevious}>‹</button><article className={`markdown-reader${selectedNeedsOriginal ? ' drawing-reader' : ''}`}>{selectedNeedsOriginal ? <OriginalPageImage documentId={documentId} page={selected.page} drawing={selectedIsDrawing} /> : <BlockDocument content={pageContent} tables={tables} />}</article><button className="side-page-button next" aria-label="下一页（侧边）" onClick={() => void navigatePage(1)} disabled={!hasNext}>›</button></div>}
        {mode === 'SOURCE' && <div className="markdown-source"><textarea value={draft} onChange={event => setDraft(event.target.value)} rows={30} spellCheck={false} /><small>支持 # 标题、- 列表、**加粗**、`代码` 和 GFM 表格。页码标记由系统自动维护。</small></div>}
        {mode === 'COMPARE' && <><div className="compare-controls"><div className="mobile-compare-tabs"><button className={mobileComparePane === 'PDF' ? 'active' : 'secondary'} onClick={() => setMobileComparePane('PDF')}>PDF 原文</button><button className={mobileComparePane === 'MARKDOWN' ? 'active' : 'secondary'} onClick={() => setMobileComparePane('MARKDOWN')}>阅读版结果</button></div><label>原文宽度 <input type="range" min="35" max="65" value={compareRatio} onChange={event => setCompareRatio(Number(event.target.value))} /><b>{compareRatio}%</b></label></div><div ref={compareContainer} className="markdown-compare" style={{ '--compare-left': `${compareRatio}%` } as CSSProperties}><article className={mobileComparePane === 'PDF' ? 'mobile-active' : 'mobile-inactive'}><header><b>PDF 第 {selected.page} 页</b><span>{selectedNeedsOriginal ? '300 DPI 高清原页' : '原文只读'}</span></header><div className="page-image-wrap"><img src={`/api/documents/${documentId}/pages/${selected.page}/image?dpi=${selectedNeedsOriginal ? 300 : 150}`} alt={`PDF 第 ${selected.page} 页`} /></div></article><button className="compare-divider" aria-label="拖动调整原文与解析结果宽度" onPointerDown={beginCompareResize}><i /></button><article className={mobileComparePane === 'MARKDOWN' ? 'mobile-active' : 'mobile-inactive'}><header><b>{selectedNeedsOriginal ? (selectedIsDrawing ? '工程图识别提示' : '原页截图兜底') : '阅读版解析结果'}</b><span>v{selected.version}</span></header><div className="markdown-reader">{selectedNeedsOriginal ? <OriginalPageImage documentId={documentId} page={selected.page} drawing={selectedIsDrawing} /> : <BlockDocument content={pageContent} tables={tables} />}</div></article></div></>}
        {mode === 'SOURCE' && <>
          <div className="markdown-savebar"><label><input type="checkbox" checked={rebuildVectors} onChange={event => setRebuildVectors(event.target.checked)} />保存后重建知识库向量</label><span>{selected.confirmedBy ? `最近确认：${selected.confirmedBy}` : '当前为自动解析结果'}</span><button onClick={() => void save()} disabled={busy || draft.trim() === selected.markdown.trim()}>{busy ? '正在保存并重建…' : '确认修改并保存'}</button></div>
          <details className="page-version-history"><summary>版本历史（{versions.length}）</summary>{versions.map(version => <div key={version.version}><b>v{version.version} · {version.reviewStatus === 'HUMAN_CONFIRMED' ? '人工确认' : '自动解析'}</b><span>{version.actor} · {new Date(version.createdAt).toLocaleString()}</span><small>{version.action}</small></div>)}</details>
        </>}
      </>}
    </>}
  </section>;
}

export function TableReviewPanel({ documentId, onMessage }: { documentId: string; onMessage: (message: string) => void }) {
  const [tables, setTables] = useState<ParsedTable[]>([]); const [selectedId, setSelectedId] = useState('');
  const [markdown, setMarkdown] = useState(''); const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  const load = async () => {
    if (!documentId) return; setError('');
    try {
      const rows = await request<ParsedTable[]>(`/api/documents/${documentId}/tables`); setTables(rows);
      const selected = rows.find(row => row.id === selectedId) || rows[0]; setSelectedId(selected?.id || ''); setMarkdown(selected?.markdown || '');
    } catch (reason) { setError(reason instanceof Error ? reason.message : '读取表格失败'); }
  };
  useEffect(() => { setTables([]); setSelectedId(''); setMarkdown(''); void load(); }, [documentId]);
  const selected = tables.find(table => table.id === selectedId);
  const choose = (table: ParsedTable) => { setSelectedId(table.id); setMarkdown(table.markdown); setError(''); };
  const save = async () => {
    if (!selected) return; setBusy(true); setError('');
    try {
      await request(`/api/documents/${documentId}/tables/${selected.id}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ markdown }) });
      await load(); onMessage(`第 ${selected.page} 页表格已人工确认，表格向量已同步重建。`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : '保存表格失败'); }
    finally { setBusy(false); }
  };
  if (!documentId || (!tables.length && !error)) return null;
  return <section className="panel product-panel table-review-panel">
    <div className="section-heading"><div><p className="eyebrow">表格质量复核</p><h2>PDF 原页与结构化表格对照</h2></div><span className={tables.every(table => table.reviewStatus === 'HUMAN_EDITED') ? 'success-badge' : 'warning-badge'}>{tables.filter(table => table.reviewStatus === 'HUMAN_EDITED').length}/{tables.length} 已人工确认</span></div>
    <p className="muted">仅疑似表格页才会进入结构识别。预览按保存的单元格与合并关系渲染；Markdown 编辑仅用于人工修订文本。</p>
    {error && <p className="inline-error">{error}</p>}
    {tables.length > 0 && <><div className="table-tabs">{tables.map(table => <button className={table.id === selectedId ? 'active' : 'secondary'} onClick={() => choose(table)} key={table.id}>第 {table.page} 页 · 表格 {table.tableIndex + 1}<small>{Math.round(table.confidence * 100)}% · {table.reviewStatus === 'HUMAN_EDITED' ? '已确认' : '待核对'}</small></button>)}</div>{selected && <div className="table-compare"><article><header><b>PDF 第 {selected.page} 页原图</b><span>只读依据</span></header><div className="page-image-wrap"><img src={`/api/documents/${documentId}/pages/${selected.page}/image`} alt={`PDF 第 ${selected.page} 页`} /></div></article><article><header><b>结构化预览</b><span>{selected.cells?.length || selected.headers.length * (selected.rows.length + 1)} 个单元格 · 保留合并</span></header><div className="table-structure-preview"><StructuredTablePreview table={selected} /></div><textarea value={markdown} onChange={event => setMarkdown(event.target.value)} rows={16} spellCheck={false} /><footer><span>编辑 Markdown 会转为普通网格，合并单元格请在预览中核对后再保存。</span><button onClick={() => void save()} disabled={busy || markdown.trim() === selected.markdown.trim()}>{busy ? '正在重建向量…' : '保存并人工确认'}</button></footer></article></div>}</>}
  </section>;
}

export function AdminHealthPanel() {
  const [health, setHealth] = useState<SystemHealth>(); const [model, setModel] = useState<ModelConfiguration>(); const [error, setError] = useState(''); const [loading, setLoading] = useState(false);
  const load = async () => {
    setLoading(true); setError('');
    try { const [nextHealth, nextModel] = await Promise.all([request<SystemHealth>('/api/admin/system/health'), request<ModelConfiguration>('/api/admin/system/model-configuration')]); setHealth(nextHealth); setModel(nextModel); }
    catch (reason) { setError(reason instanceof Error ? reason.message : '健康检查失败'); }
    finally { setLoading(false); }
  };
  useEffect(() => { void load(); }, []);
  return <section className="panel product-panel admin-health">
    <div className="section-heading"><div><p className="eyebrow">生产环境检查</p><h2>核心依赖与模型配置</h2></div><div><span className={health?.status === 'UP' ? 'success-badge' : 'warning-badge'}>{health?.status || '检查中'}</span><button className="secondary compact-button" onClick={() => void load()} disabled={loading}>{loading ? '检查中…' : '重新检查'}</button></div></div>
    {error && <p className="inline-error">{error}</p>}
    <div className="health-grid">{health && Object.entries(health.components).map(([name, item]) => <article key={name} className={item.status === 'UP' ? 'healthy' : 'unhealthy'}><span>{name.toUpperCase()}</span><b>{item.status}</b><small>{item.detail}</small><em>{item.latencyMs} ms</em></article>)}</div>
    {model && <details className="configuration-details"><summary>查看脱敏后的模型配置</summary><pre>{JSON.stringify(model, null, 2)}</pre></details>}
  </section>;
}

export function ReviewWorkbench({ jobs, onMessage }: { jobs: JobLite[]; onMessage: (message: string) => void }) {
  const available = jobs.filter(job => job.type === 'QUESTION_BANK' && job.result.length > 0);
  const [jobId, setJobId] = useState(''); const [items, setItems] = useState<ReviewItem[]>([]); const [summary, setSummary] = useState<ReviewSummary>(); const [preflight, setPreflight] = useState<DeliveryPreflight>();
  const [filter, setFilter] = useState('ALL'); const [query, setQuery] = useState(''); const [selected, setSelected] = useState<number[]>([]); const [focusedSequence, setFocusedSequence] = useState<number>(); const [editing, setEditing] = useState<number>(); const [draft, setDraft] = useState<Record<string, unknown>>({}); const [comment, setComment] = useState(''); const [events, setEvents] = useState<ReviewEvent[]>([]); const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  useEffect(() => { if (!jobId && available[0]) setJobId(available[0].id); }, [available, jobId]);
  const load = async (id = jobId) => {
    if (!id) return; setBusy(true); setError('');
    try { const nextItems = await request<ReviewItem[]>(`/api/reviews/jobs/${id}`); const [nextSummary, nextPreflight] = await Promise.all([request<ReviewSummary>(`/api/reviews/jobs/${id}/summary`), request<DeliveryPreflight>(`/api/quality/jobs/${id}/preflight`)]); setItems(nextItems); setSummary(nextSummary); setPreflight(nextPreflight); setSelected([]); setFocusedSequence(current => nextItems.some(item => item.sequence === current) ? current : nextItems[0]?.sequence); }
    catch (reason) { setError(reason instanceof Error ? reason.message : '加载审核数据失败'); }
    finally { setBusy(false); }
  };
  useEffect(() => { void load(jobId); }, [jobId]);
  const visible = useMemo(() => items.filter(item => (filter === 'ALL' || item.status === filter) && (!query.trim() || [item.question.stem, item.question.assessmentPoint, item.question.sourceRef].some(value => text(value).toLowerCase().includes(query.toLowerCase())))), [items, filter, query]);
  const focused = visible.find(item => item.sequence === focusedSequence) || visible[0];
  useEffect(() => { if (visible.length && !visible.some(item => item.sequence === focusedSequence)) setFocusedSequence(visible[0].sequence); }, [visible, focusedSequence]);
  const update = async (item: ReviewItem, patch: Record<string, unknown>) => {
    setBusy(true); setError('');
    try { await request(`/api/reviews/jobs/${jobId}/items/${item.sequence}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(patch) }); await load(); onMessage(`第 ${item.sequence} 题审核结果已保存${['APPROVED', 'LOCKED'].includes(String(patch.status)) ? '，已归档到题库' : ''}。`); }
    catch (reason) { setError(reason instanceof Error ? reason.message : '保存失败'); setBusy(false); }
  };
  const bulkApprove = async () => {
    const targets = items.filter(item => selected.includes(item.sequence)); if (!targets.length) return;
    setBusy(true); setError('');
    try { for (const item of targets) await request(`/api/reviews/jobs/${jobId}/items/${item.sequence}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status: 'APPROVED', comment: '批量人工通过' }) }); await load(); onMessage(`已通过 ${targets.length} 道题并归档到题库。`); }
    catch (reason) { setError(reason instanceof Error ? reason.message : '批量审核失败'); setBusy(false); }
  };
  const showEvents = async (item: ReviewItem) => { try { setEvents(await request<ReviewEvent[]>(`/api/reviews/jobs/${jobId}/items/${item.sequence}/events`)); } catch (reason) { setError(reason instanceof Error ? reason.message : '读取历史失败'); } };
  const renderReviewCard = (item: ReviewItem) => {
    const question = item.question; const isEditing = editing === item.sequence;
    return <article className={`review-card ${item.status.toLowerCase()}`} key={item.id}>
      <header><label className="review-check"><input type="checkbox" checked={selected.includes(item.sequence)} onChange={event => setSelected(current => event.target.checked ? [...current, item.sequence] : current.filter(value => value !== item.sequence))} /><span>第 {item.sequence} 题</span></label><div className="question-tags"><em>{typeLabel[text(question.type)] || text(question.type)}</em><em>{difficultyLabel[text(question.difficulty)] || text(question.difficulty)}</em><em className={`review-status ${item.status.toLowerCase()}`}>{statusLabel[item.status] || item.status}</em><em>v{item.version}</em></div></header>
      {isEditing ? <div className="question-editor">{editFields.map(field => <label key={field}>{({ stem: '题干', options: '选项', answer: '答案', analysis: '解析', scoringRubric: '评分规则', assessmentPoint: '考点', sourceRef: '原文定位', sourceExcerpt: '原文片段' } as Record<string, string>)[field]}<textarea rows={field === 'stem' || field === 'analysis' || field === 'sourceExcerpt' ? 4 : 2} value={text(draft[field])} onChange={event => setDraft(current => ({ ...current, [field]: event.target.value }))} /></label>)}<label>编辑说明<input value={comment} onChange={event => setComment(event.target.value)} placeholder="说明修改原因，便于后续追溯" /></label><div><button onClick={() => void update(item, { status: item.status, comment, question: draft })} disabled={busy}>保存新版本</button><button className="secondary" onClick={() => setEditing(undefined)}>取消</button></div></div> : <div className="question-body"><h3>{text(question.stem)}</h3>{text(question.options) && <p className="question-options">{text(question.options)}</p>}<div className="answer-grid"><p><span>答案</span>{text(question.answer)}</p><p><span>考点</span>{text(question.assessmentPoint)}</p></div><p><span className="field-label">解析</span>{text(question.analysis)}</p>{text(question.scoringRubric) && <p><span className="field-label">评分规则</span>{text(question.scoringRubric)}</p>}<details open><summary>查看原文依据与追溯位置</summary><p><b>{text(question.sourceRef) || '未提供定位'}</b></p><blockquote>{text(question.sourceExcerpt) || '未提供原文片段'}</blockquote></details></div>}
      <footer><div><button className="secondary" onClick={() => { setEditing(item.sequence); setDraft({ ...question }); setComment(item.comment || ''); }} disabled={item.locked}>编辑</button><button className="secondary" onClick={() => void showEvents(item)}>历史</button></div><div><button className="reject-button" onClick={() => void update(item, { status: 'REJECTED', locked: false, comment: '人工审核驳回' })} disabled={busy}>驳回</button><button onClick={() => void update(item, { status: 'APPROVED', locked: false, comment: '人工审核通过' })} disabled={busy}>通过</button><button className={item.locked ? 'unlock-button' : 'lock-button'} onClick={() => void update(item, { status: item.locked ? 'APPROVED' : 'LOCKED', locked: !item.locked, comment: item.locked ? '解除锁定' : '确认交付并锁定' })} disabled={busy}>{item.locked ? '解除锁定' : '锁定交付'}</button></div></footer>
    </article>;
  };
  const editFields = ['stem', 'options', 'answer', 'analysis', 'scoringRubric', 'assessmentPoint', 'sourceRef', 'sourceExcerpt'] as const;
  return <div className="review-page">
    <section className="panel product-panel review-toolbar">
      <div className="section-heading"><div><p className="eyebrow">人工质量闭环</p><h2>逐题审核、编辑、锁定与版本留痕</h2></div><span className={summary?.readyForDelivery ? 'success-badge' : 'warning-badge'}>{summary?.readyForDelivery ? '人工审核完成' : `${summary?.pending ?? 0} 道待处理`}</span></div>
      <div className="review-controls"><label>题库任务<select value={jobId} onChange={event => setJobId(event.target.value)}><option value="">请选择</option>{available.map(job => <option value={job.id} key={job.id}>{new Date(job.createdAt || Date.now()).toLocaleString()} · {job.result.length} 道</option>)}</select></label><label>审核状态<select value={filter} onChange={event => setFilter(event.target.value)}><option value="ALL">全部状态</option>{Object.entries(statusLabel).map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select></label><label className="review-search">搜索题干 / 考点 / 来源<input value={query} onChange={event => setQuery(event.target.value)} placeholder="输入关键词" /></label><button className="secondary compact-button" onClick={() => void load()} disabled={busy}>刷新</button></div>
      {summary && <div className="review-summary"><span>总题量 <b>{summary.total}</b></span><span>人工通过/锁定 <b>{summary.deliveryReady}</b></span><span>待处理 <b>{summary.pending}</b></span><span>AI 草稿 <b>{summary.statusCounts.AI_DRAFT || 0}</b></span><span>已驳回 <b>{summary.statusCounts.REJECTED || 0}</b></span></div>}
      {preflight && <div className={preflight.deliveryReady ? 'preflight ready' : 'preflight'}><div><b>交付预检：{preflight.deliveryReady ? '可以交付' : '需要确认'}</b><span>质量 {preflight.qualityScore} 分 · 题量 {preflight.actualTotal}/{preflight.expectedTotal} · {preflight.generationMode}</span></div>{preflight.warnings.length > 0 && <ul>{preflight.warnings.map(item => <li key={item}>{item}</li>)}</ul>}</div>}
      {error && <p className="inline-error">{error}</p>}
    </section>
    {selected.length > 0 && <div className="selection-bar"><b>已选 {selected.length} 道</b><button onClick={() => void bulkApprove()} disabled={busy}>批量通过</button><button className="secondary" onClick={() => setSelected([])}>取消选择</button></div>}
    <div className="review-workbench">
      <aside className="panel review-index"><div className="review-index-head"><div><p className="section-kicker">审核队列</p><h3>{visible.length} 道题目</h3></div><span>{summary?.pending ?? 0} 待处理</span></div><div className="review-index-list">{visible.length ? visible.map(item => <button key={item.id} className={focusedSequence === item.sequence ? 'active' : ''} onClick={() => setFocusedSequence(item.sequence)}><span><b>第 {item.sequence} 题</b><small>{typeLabel[text(item.question.type)] || text(item.question.type)} · {difficultyLabel[text(item.question.difficulty)] || text(item.question.difficulty)}</small></span><em className={`review-index-status ${item.status.toLowerCase()}`}>{statusLabel[item.status] || item.status}</em></button>) : <p className="empty">没有符合筛选条件的题目。</p>}</div></aside>
      <section className="review-detail">{focused ? renderReviewCard(focused) : <section className="panel empty-state"><strong>{available.length ? '没有符合筛选条件的题目' : '暂无可审核题库'}</strong><p>{available.length ? '请调整状态筛选或搜索关键词。' : '先完成题库生成，再进入这里逐题审核。'}</p></section>}</section>
    </div>
    {events.length > 0 && <div className="history-drawer"><div className="section-heading"><div><p className="eyebrow">版本与操作历史</p><h2>审核轨迹</h2></div><button className="secondary compact-button" onClick={() => setEvents([])}>关闭</button></div>{events.map(event => <article key={event.id}><b>{event.action}</b><span>{event.actor} · {new Date(event.createdAt).toLocaleString()}</span><p>{event.comment || '无备注'}</p></article>)}</div>}
  </div>;
}

export function ImportExportCenter({ onImported, onMessage }: { onImported: () => Promise<void>; onMessage: (message: string) => void }) {
  const [file, setFile] = useState<File>(); const [validation, setValidation] = useState<ExcelValidation>(); const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const submit = async (mode: 'validate' | 'import') => {
    if (!file) return; setBusy(true); setError('');
    try {
      const body = new FormData(); body.append('file', file);
      if (mode === 'validate') {
        const result = await request<ExcelValidation>('/api/excel/questions/validate', { method: 'POST', body }); setValidation(result);
        onMessage(result.valid ? `Excel 校验通过：${result.totalRows} 道题可导入。` : `Excel 校验未通过：发现 ${result.errorCount} 项错误。`);
      } else {
        await request('/api/excel/questions/import', { method: 'POST', body }); await onImported(); setFile(undefined); setValidation(undefined);
        onMessage('Excel 题库已导入，并已建立可审核的题库任务。');
      }
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Excel 处理失败'); }
    finally { setBusy(false); }
  };
  return <div className="import-export-page">
    <section className="panel product-panel import-card">
      <div className="section-heading"><div><p className="eyebrow">安全导入</p><h2>Excel 题库校验与写入</h2></div><span className={validation?.valid ? 'success-badge' : validation ? 'warning-badge' : 'soft-badge'}>{validation ? validation.valid ? '可以导入' : '需要修正' : '等待文件'}</span></div>
      <p className="muted">仅支持系统导出的 .xlsx 模板，单次最多 1000 道、20MB。文件先校验，不会在校验阶段写入数据库。</p>
      <div className="excel-drop"><input type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" onChange={event => { setFile(event.target.files?.[0]); setValidation(undefined); setError(''); }} /><div><b>{file?.name || '选择 Excel 题库文件'}</b><span>{file ? `${(file.size / 1024).toFixed(1)} KB` : '表头、字段、题型、难度、重复与原文追溯将被检查'}</span></div></div>
      <div className="import-actions"><button onClick={() => void submit('validate')} disabled={!file || busy}>{busy ? '处理中…' : '校验并预览'}</button><button className="secondary" onClick={() => void submit('import')} disabled={!file || busy || !validation?.valid}>确认导入 {validation?.valid ? `${validation.totalRows} 道` : ''}</button></div>
      {error && <p className="inline-error">{error}</p>}
      {validation && <><div className="review-summary excel-summary"><span>读取题目 <b>{validation.totalRows}</b></span><span>阻断错误 <b>{validation.errorCount}</b></span><span>风险提示 <b>{validation.warningCount}</b></span><span>预览数量 <b>{validation.preview.length}</b></span></div>{validation.issues.length > 0 && <div className="excel-issues">{validation.issues.slice(0, 50).map((issue, index) => <p className={issue.severity.toLowerCase()} key={`${issue.row}-${issue.code}-${index}`}><b>{issue.row ? `第 ${issue.row} 行` : '文件'}</b><code>{issue.code}</code><span>{issue.message}</span></p>)}</div>}{validation.preview.length > 0 && <div className="excel-preview"><div className="excel-preview-head"><span>序号</span><span>题型 / 难度</span><span>题干</span><span>考点</span></div>{validation.preview.map((row, index) => <div key={index}><b>{text(row.sequence)}</b><span>{typeLabel[text(row.type)] || text(row.type)}<small>{difficultyLabel[text(row.difficulty)] || text(row.difficulty)}</small></span><p>{text(row.stem)}</p><span>{text(row.assessmentPoint)}</span></div>)}</div>}</>}
    </section>
    <section className="panel product-panel delivery-guide"><p className="eyebrow">安全导出</p><h2>交付前必经检查</h2><div className="delivery-steps"><article><i>1</i><div><b>质量门禁</b><span>检查数量、题型结构、重复、证据与解析完整性。</span></div></article><article><i>2</i><div><b>人工审核</b><span>通过或锁定的题目计入人工交付完成数。</span></div></article><article><i>3</i><div><b>风险确认</b><span>数量不足或快速模式题库允许导出，但会明确弹出风险。</span></div></article><article><i>4</i><div><b>异步生成</b><span>Excel 写入、校验、MinIO 保存完成后才提供下载。</span></div></article></div><p className="muted">请在“题库任务”或“质量验收”选择目标任务发起导出；系统会自动执行上述交付预检。</p></section>
  </div>;
}

export function confirmDelivery(preflight: DeliveryPreflight) {
  if (preflight.deliveryReady) return true;
  return window.confirm(`交付预检尚有风险：\n\n${preflight.warnings.map(item => `• ${item}`).join('\n')}\n\n仍要继续创建导出任务吗？`);
}
