import { useEffect, useLayoutEffect, useRef, useState, type TextareaHTMLAttributes } from 'react';
import { createPortal } from 'react-dom';
import { QuestionStimulusPreview } from './QuestionStimulusPreview';
import { StudioIcon } from './StudioIcon';
import { errorText, maintenanceApi as api } from './maintenanceApi';
import { groupQuestionSlots, isQuestionApproved } from './questionGrouping';
import './maintenance.css';

type Question = Record<string, unknown>;
type Item = { id: string; variantLabel: string; sequenceNo: number; typeLabel: string; questionType: string; points: number; difficulty: string; status: string; questionVersion?: number; question: Question; review: Question; reviewerId?: string; reviewComment?: string; errorCode?: string; errorMessage?: string };
type Run = { id: string; status: string; items: Item[] };
type Issue = { code: string; severity: string; message: string };
type Inspection = { id: string; version: number; issues: Issue[]; similar: { id: string; variantLabel: string; sequenceNo: number; similarity: number }[] };
type Proposal = { expectedVersion: number; question: Question; review?: { passed: boolean; available: boolean; feedback: string }; issues: Issue[] };
type Score = { criterion: string; points: number | string };
type Version = { id: string; version: number; question: Question; changeSummary?: string; createdAt: string };
type ReviewEvent = { id: string; action: string; actorName: string; comment: string; createdAt: string };
const statusName: Record<string, string> = { REVIEW_REQUIRED: '待审核', REVIEW_PENDING: '等待 AI 审题', APPROVED: '已通过', APPROVED_WITH_RISK: '人工保留（有风险）', REJECTED: '已驳回', FAILED: '生成失败', REMOVED: '已删除，不导出', PLANNED: '待生成', GENERATING: '生成中' };
const statusMeta: Record<string, { label: string; symbol: string; tone: string }> = {
  APPROVED: { label: '已通过', symbol: '✓', tone: 'approved' },
  APPROVED_WITH_RISK: { label: '人工保留', symbol: '!', tone: 'risk' },
  REVIEW_REQUIRED: { label: '待审核', symbol: '●', tone: 'review' },
  REVIEW_PENDING: { label: 'AI 审题中', symbol: '…', tone: 'pending' },
  REJECTED: { label: '已驳回', symbol: '!', tone: 'rejected' },
  FAILED: { label: '生成失败', symbol: '×', tone: 'failed' },
  PLANNED: { label: '待生成', symbol: '○', tone: 'planned' },
  GENERATING: { label: '生成中', symbol: '↻', tone: 'generating' },
  REMOVED: { label: '已删除', symbol: '—', tone: 'removed' },
};
const difficultyName: Record<string, string> = { EASY: '基础', MEDIUM: '中等', HARD: '进阶' };
const text = (value: unknown) => value == null ? '' : String(value);
const optionText = (value: unknown) => value && typeof value === 'object' ? Object.entries(value).map(([key, content]) => `${key}. ${content}`).join(' | ') : text(value);
const checks = [['target', '确实考查了目标能力'], ['answer', '条件充分，答案与合理替代解法已核对'], ['fairness', '没有无意泄露答案或无关难点'], ['scoring', '评分依据明确，分值分配合理']] as const;
const getStatusMeta = (status: string) => statusMeta[status] || { label: statusName[status] || status, symbol: '•', tone: 'unknown' };
const canBatchApprove = (status: string) => ['REVIEW_REQUIRED', 'REJECTED'].includes(status);

export default function QuestionQualityWorkbench({ auth, projectId, run, onRefresh }: { auth: string; projectId: string; run: Run; onRefresh: () => Promise<void> }) {
  const [selected, setSelected] = useState(() => groupQuestionSlots(run.items.filter(item => item.status !== 'REMOVED'))[0]?.groups[0]?.items[0]?.id || '');
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('ALL');
  const [questionDrawerOpen, setQuestionDrawerOpen] = useState(false);
  const drawerTriggerRef = useRef<HTMLButtonElement>(null);
  const drawerCloseRef = useRef<HTMLButtonElement>(null);
  const drawerRef = useRef<HTMLElement>(null);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [batchPending, setBatchPending] = useState(false);
  const [batchProgress, setBatchProgress] = useState(0);
  const [batchConfirm, setBatchConfirm] = useState(false);
  const [batchNotice, setBatchNotice] = useState('');
  const [inspections, setInspections] = useState<Inspection[]>([]);
  const [error, setError] = useState('');
  const [dirty, setDirty] = useState(false);
  const [operationPending, setOperationPending] = useState(false);
  useEffect(() => {
    if (!questionDrawerOpen) return;
    drawerCloseRef.current?.focus();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const handleDrawerKeys = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { setQuestionDrawerOpen(false); return; }
      if (event.key !== 'Tab') return;
      const focusable = drawerRef.current?.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), [href], [tabindex]:not([tabindex="-1"])');
      if (!focusable?.length) return;
      const first = focusable[0]; const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    window.addEventListener('keydown', handleDrawerKeys);
    return () => {
      window.removeEventListener('keydown', handleDrawerKeys);
      document.body.style.overflow = previousOverflow;
      drawerTriggerRef.current?.focus();
    };
  }, [questionDrawerOpen]);
  useEffect(() => {
    if (!dirty && !operationPending) return;
    const protect = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', protect);
    return () => window.removeEventListener('beforeunload', protect);
  }, [dirty, operationPending]);
  const root = `/api/exam-projects/${projectId}/variant-generation-runs/${run.id}`;
  useEffect(() => { const controller = new AbortController(); setError(''); api<Inspection[]>(`${root}/quality`, auth, { signal: controller.signal }).then(setInspections).catch(error => { if (!controller.signal.aborted) setError(errorText(error)); }); return () => controller.abort(); }, [auth, root, run]);
  const activeItems = run.items.filter(item => item.status !== 'REMOVED');
  const normalizedQuery = query.trim().replace(/^第\s*/, '').replace(/\s*题$/, '').trim();
  const visible = activeItems.filter(item => (filter === 'ALL' || filter === 'ISSUES' && inspections.some(row => row.id === item.id && row.issues.some(issue => issue.severity !== 'INFO')) || filter === item.status) && (!normalizedQuery || String(item.sequenceNo).includes(normalizedQuery)));
  const visibleGroups = groupQuestionSlots(visible);
  const item = activeItems.find(item => item.id === selected) || groupQuestionSlots(activeItems)[0]?.groups[0]?.items[0];
  const runActive = ['QUEUED', 'RUNNING'].includes(run.status);
  const selectableVisibleItems = visible.filter(row => canBatchApprove(row.status) && !runActive);
  const allVisibleSelected = selectableVisibleItems.length > 0 && selectableVisibleItems.every(row => selectedIds.includes(row.id));
  const selectedItems = activeItems.filter(row => selectedIds.includes(row.id) && canBatchApprove(row.status) && !runActive);
  const toggleBatchSelection = (id: string, checked: boolean) => { setSelectedIds(current => checked ? current.includes(id) ? current : [...current, id] : current.filter(value => value !== id)); setBatchNotice(''); };
  const toggleSelectAllVisible = () => {
    const visibleIds = new Set(selectableVisibleItems.map(row => row.id));
    setSelectedIds(current => allVisibleSelected
      ? current.filter(id => !visibleIds.has(id))
      : [...new Set([...current, ...visibleIds])]);
    setBatchNotice('');
  };
  const batchApprove = async () => {
    if (!selectedItems.length || batchPending || dirty || operationPending) return;
    const targets = [...selectedItems];
    const checklist = checks.reduce<Record<string, boolean>>((result, [key]) => { result[key] = true; return result; }, {});
    const succeeded: Item[] = [];
    const failed: { item: Item; message: string }[] = [];
    setBatchPending(true); setBatchProgress(0); setError(''); setBatchNotice('');
    try {
      for (let index = 0; index < targets.length; index += 1) {
        const target = targets[index];
        try {
          await api(`${root}/items/${target.id}/review`, auth, { method: 'PATCH', body: JSON.stringify({ decision: 'APPROVE', expectedVersion: target.questionVersion || 1, comment: '批量人工通过', question: { ...target.question, qualityChecklist: checklist } }) });
          succeeded.push(target);
        } catch (reason) { failed.push({ item: target, message: errorText(reason) }); }
        setBatchProgress(index + 1);
      }
      setSelectedIds([]); setBatchConfirm(false); await onRefresh();
      if (failed.length) setError(`批量通过完成：成功 ${succeeded.length} 道，失败 ${failed.length} 道。${failed.map(row => `第 ${row.item.variantLabel} 套第 ${row.item.sequenceNo} 题：${row.message}`).join('；')}`);
      else setBatchNotice(`已批量通过 ${succeeded.length} 道题目，题目已归档到题库。`);
    } catch (reason) { setError(errorText(reason)); }
    finally { setBatchPending(false); }
  };
  const choose = (id: string) => { if ((dirty || operationPending) && id !== selected) return setError(operationPending ? '当前操作尚未完成，请稍候再切换题目。' : '当前题目有未保存的修改，请先保存或取消编辑。'); setSelected(id); setError(''); setQuestionDrawerOpen(false); };
  const approved = activeItems.filter(item => ['APPROVED', 'APPROVED_WITH_RISK'].includes(item.status)).length;
  const riskAccepted = activeItems.filter(item => item.status === 'APPROVED_WITH_RISK').length;
  return <><section className="panel maintenance quality-workbench"><div className="maintenance-section-title"><div><h3>题目质量工作台</h3><p className="muted">逐题核对、修订并确认。AI 意见是审题辅助，不代表实测难度或区分度。</p></div><span className="quality-progress">已处理 <b>{approved}</b> / {activeItems.length}</span></div>
    <div className="quality-overview"><div className="quality-overview-main"><span className="quality-overview-label">审核进度</span><strong>{approved}</strong><span>/ {activeItems.length} 题已处理</span>{activeItems.length - approved > 0 && <span className="quality-overview-pending">· {activeItems.length - approved} 待处理</span>}{riskAccepted > 0 && <span className="quality-overview-note">其中 {riskAccepted} 道人工保留</span>}{run.items.length - activeItems.length > 0 && <span className="quality-overview-note">已删除 {run.items.length - activeItems.length} 道，不导出</span>}</div><div className="quality-status-legend" aria-label="题目状态说明">{['APPROVED', 'APPROVED_WITH_RISK', 'REVIEW_REQUIRED', 'REVIEW_PENDING', 'REJECTED', 'FAILED'].map(status => { const meta = getStatusMeta(status); return <span key={status}><i className={`quality-question-status ${meta.tone}`} aria-hidden="true">{meta.symbol}</i>{meta.label}</span>; })}</div></div>
    {error && <p className="maintenance-error" role="alert">{error}</p>}{batchNotice && <p className="maintenance-notice" role="status">{batchNotice}</p>}
    <div className="maintenance-filters"><label className="maintenance-search"><StudioIcon name="search" size={18} /><input aria-label="搜索题号" placeholder="搜索题号，例如 3" value={query} onChange={event => setQuery(event.target.value)} /></label><select aria-label="筛选审核状态" value={filter} onChange={event => setFilter(event.target.value)}><option value="ALL">全部题目</option><option value="ISSUES">有检查提示</option><option value="REVIEW_REQUIRED">待审核</option><option value="REJECTED">已驳回</option><option value="REVIEW_PENDING">等待 AI 审题</option><option value="APPROVED">已通过</option><option value="APPROVED_WITH_RISK">人工保留</option><option value="FAILED">生成失败</option></select></div>
    {selectedItems.length > 0 && <section className="quality-batch-toolbar" aria-label="批量审核操作"><div><strong>已选 {selectedItems.length} 道题</strong><span>{selectedItems.map(row => `${row.variantLabel} 卷第 ${row.sequenceNo} 题`).join('、')}</span></div><div className="maintenance-actions"><button type="button" className="secondary" disabled={batchPending} onClick={() => { setSelectedIds([]); setBatchConfirm(false); }}>清空选择</button><button type="button" disabled={batchPending || dirty || operationPending} onClick={() => setBatchConfirm(true)}>{batchPending ? `批量通过中 ${batchProgress}/${selectedItems.length}` : '批量通过'}</button></div></section>}
    {batchConfirm && selectedItems.length > 0 && <section className="quality-quick-approve-confirm quality-batch-confirm" role="alertdialog" aria-labelledby="quality-batch-approve-title"><h4 id="quality-batch-approve-title">确认批量通过 {selectedItems.length} 道题？</h4><p>将自动完成所选题目的人工审题清单，并逐题校验后提交。通过后题目会进入题库，并可参与导出。</p><div className="maintenance-actions"><button type="button" disabled={batchPending} onClick={() => void batchApprove()}>{batchPending ? `正在处理 ${batchProgress}/${selectedItems.length}` : '确认批量通过'}</button><button type="button" className="secondary" disabled={batchPending} onClick={() => setBatchConfirm(false)}>返回检查</button></div></section>}
    <div className="quality-columns">
      {item ? <QuestionEditor key={`${item.id}-${item.questionVersion || 1}`} auth={auth} projectId={projectId} root={root} item={item} runActive={runActive} inspection={inspections.find(row => row.id === item.id)} onDirty={setDirty} onPending={setOperationPending} onRefresh={onRefresh} onSelect={choose} /> : <p className="maintenance-empty">本批次还没有题目。</p>}
    </div>
  </section>{item && createPortal(<>
    <button ref={drawerTriggerRef} type="button" className="quality-drawer-trigger" aria-label={`打开题号导航，当前第 ${item.variantLabel} 套第 ${item.sequenceNo} 题，共 ${visible.length} 道可见题目`} aria-expanded={questionDrawerOpen} aria-controls="quality-question-drawer" onClick={() => setQuestionDrawerOpen(value => !value)}>
      <i className={`quality-drawer-trigger-state ${getStatusMeta(item.status).tone}`} aria-hidden="true" /><span>题号</span><strong>第 {item.sequenceNo} 题</strong><span className="quality-drawer-trigger-arrow" aria-hidden="true">‹</span>
    </button>
    {questionDrawerOpen && <div className="quality-question-drawer-layer">
      <button type="button" className="quality-question-drawer-backdrop" aria-label="关闭题号抽屉" onClick={() => setQuestionDrawerOpen(false)} />
      <aside ref={drawerRef} id="quality-question-drawer" className="quality-question-drawer" role="dialog" aria-modal="true" aria-labelledby="quality-question-drawer-title">
        <header className="quality-question-drawer-heading"><div><h4 id="quality-question-drawer-title">题号导航</h4><p>当前第 {item.sequenceNo} 题 · 共 {visible.length} 题</p></div><button ref={drawerCloseRef} type="button" className="quality-drawer-close" aria-label="关闭题号抽屉" onClick={() => setQuestionDrawerOpen(false)}>×</button></header>
        {error && <p className="maintenance-error quality-drawer-error" role="alert">{error}</p>}
        <div className="quality-drawer-select-bar"><span>可批量通过 {selectableVisibleItems.length} 道</span><button type="button" className="secondary" disabled={!selectableVisibleItems.length || batchPending || dirty || operationPending} onClick={toggleSelectAllVisible}>{allVisibleSelected ? '取消全选' : '一键全选'}</button></div>
        <nav className="quality-question-list" aria-label="题号导航">
          {visibleGroups.map(variant => <div className="quality-variant-group" key={variant.key}>
            {visibleGroups.length > 1 && <h4 className="quality-variant-heading">第 {variant.variantLabel} 套</h4>}
            {variant.groups.map(group => <section className="quality-type-group" key={group.key} aria-label={group.label}>
              <div className="quality-type-heading"><strong>{group.label}</strong><small>{group.items.length} 题 · {group.items.filter(row => !isQuestionApproved(row.status)).length} 待处理</small></div>
              {group.items.map(row => { const meta = getStatusMeta(row.status); const selectable = canBatchApprove(row.status) && !runActive; const checked = selectable && selectedIds.includes(row.id); return <div className={`quality-question-row${checked ? ' is-batch-selected' : ''}`} key={row.id}><label className="quality-question-check" title={selectable ? `选择第 ${row.sequenceNo} 题` : `${meta.label}题目不可批量通过`}><input type="checkbox" checked={checked} disabled={!selectable || batchPending || dirty || operationPending} aria-label={`第 ${row.variantLabel} 套选择第 ${row.sequenceNo} 题`} onChange={event => toggleBatchSelection(row.id, event.target.checked)} /></label><button type="button" className="quality-question-choice" aria-current={item?.id === row.id ? 'true' : undefined} aria-label={`第 ${row.variantLabel} 套第 ${row.sequenceNo} 题，${meta.label}`} title={`${row.variantLabel}卷 · ${meta.label}`} onClick={() => choose(row.id)}><span className={`quality-question-status ${meta.tone}`} aria-hidden="true">{meta.symbol}</span><span className="quality-question-number">第 {row.sequenceNo} 题</span></button></div>; })}
            </section>)}
          </div>)}
          {!visible.length && <p className="maintenance-empty">没有匹配的题目。</p>}
        </nav>
      </aside>
    </div>}
  </>, document.body)}</>;
}

function QuestionEditor({ auth, projectId, root, item, runActive, inspection, onDirty, onPending, onRefresh, onSelect }: { auth: string; projectId: string; root: string; item: Item; runActive: boolean; inspection?: Inspection; onDirty: (value: boolean) => void; onPending: (value: boolean) => void; onRefresh: () => Promise<void>; onSelect: (id: string) => void }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<Question>({ ...item.question, options: optionText(item.question.options) });
  const [scores, setScores] = useState<Score[]>(Array.isArray(item.question.scoringItems) ? item.question.scoringItems as Score[] : []);
  const [comment, setComment] = useState(item.reviewComment || '');
  const [checklist, setChecklist] = useState<Record<string, boolean>>({});
  const [pending, setPending] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [instruction, setInstruction] = useState('');
  const [showAi, setShowAi] = useState(false);
  const [showQuickApproveConfirm, setShowQuickApproveConfirm] = useState(false);
  const [proposal, setProposal] = useState<Proposal | null>(null);
  const [history, setHistory] = useState<{ versions: Version[]; events: ReviewEvent[] } | null>(null);
  const editable = ['REVIEW_REQUIRED', 'REJECTED'].includes(item.status) && !runActive;
  const retryable = !item.reviewerId && (item.questionVersion || 1) === 1 && (item.status === 'REVIEW_PENDING' || item.status === 'REJECTED' && item.errorCode === 'QUALITY_REJECTED');
  const markDirty = () => { onDirty(true); setNotice(''); setChecklist({}); };
  const change = (field: string, value: string) => { setDraft(current => ({ ...current, [field]: value })); markDirty(); };
  const act = async (label: string, work: () => Promise<void>) => {
    setPending(label); onPending(true); setError(''); setNotice('');
    let progressTimer: number | undefined;
    if (label.includes('重新审题')) {
      let elapsed = 0;
      progressTimer = window.setInterval(() => {
        elapsed += 1;
        setPending(`正在重新审题：AI 审题服务处理中，已等待 ${elapsed} 秒；原题保持不变…`);
      }, 1000);
    }
    try {
      await work();
      if (label.includes('重新审题')) {
        const refreshed = await api<Run>(root, auth);
        const result = refreshed.items.find(row => row.id === item.id);
        if (!result) throw new Error('AI 审题结果未返回，请刷新后查看。');
        if (result.status === 'REVIEW_REQUIRED') setNotice('AI 审题重试成功：未发现阻断问题，题目已回到待人工审核。');
        else if (result.status === 'REJECTED') setNotice(`AI 审题重试完成，但题目仍未通过。${result.errorMessage ? `原因：${result.errorMessage}` : '请查看审题反馈。'}`);
        else if (result.status === 'REVIEW_PENDING') setNotice(`AI 审题重试未完成：${result.errorCode === 'REVIEW_PROTOCOL_ERROR' ? 'AI 返回的审题结构不完整。' : '审题服务没有返回有效结果。'}${result.errorMessage ? `原因：${result.errorMessage}` : '请稍后再次重试。'}`);
        else setNotice(`AI 审题重试完成，当前状态：${statusName[result.status] || result.status}。`);
      }
    } catch (error) { setError(errorText(error)); }
    finally { if (progressTimer !== undefined) window.clearInterval(progressTimer); setPending(''); onPending(false); }
  };
  const reset = () => { setDraft({ ...item.question, options: optionText(item.question.options) }); setScores(Array.isArray(item.question.scoringItems) ? item.question.scoringItems as Score[] : []); setChecklist({}); setShowQuickApproveConfirm(false); setEditing(false); setProposal(null); onDirty(false); setError(''); };
  const submit = (decision: string) => void act('正在保存…', async () => {
    if (decision === 'REJECT' && !comment.trim()) throw new Error('请填写驳回原因。');
    if (decision === 'APPROVE' && !checks.every(([key]) => checklist[key])) throw new Error('请逐项核对人工审题清单。');
    const question = decision === 'REOPEN' ? undefined : { ...draft, scoringItems: scores, scoringRubric: scores.length ? scores.map(row => `${row.criterion}（${row.points}分）`).join('；') : draft.scoringRubric, qualityChecklist: checklist };
    await api(`${root}/items/${item.id}/review`, auth, { method: 'PATCH', body: JSON.stringify({ decision, expectedVersion: item.questionVersion || 1, comment, question }) });
    onDirty(false); setEditing(false); setProposal(null); setNotice(decision === 'APPROVE' ? '已通过审核，题目已归档到题库。' : decision === 'REOPEN' ? '已重新打开审核，题目暂时退出题库。' : '已保存。'); await onRefresh();
  });
  const scoreTotal = scores.reduce((sum, row) => sum + (Number(row.points) || 0), 0);
  const currentStatus = getStatusMeta(item.status);
  const canRetryGeneration = !item.reviewerId && !runActive && (item.questionVersion || 1) === 1 && (item.status === 'FAILED' || item.status === 'REJECTED' && item.errorCode === 'QUALITY_REJECTED');
  const canRemoveGeneration = !item.reviewerId && !runActive && (item.questionVersion || 1) === 1 && ['FAILED', 'REJECTED'].includes(item.status);
  const canKeepOriginal = !item.reviewerId && !runActive && (item.questionVersion || 1) === 1 && Object.keys(item.question || {}).length > 0 && ['FAILED', 'REJECTED'].includes(item.status);
  const retryGeneration = () => void act('正在重新生成这一题…', async () => { await api(`${root}/items/${item.id}/retry-generation`, auth, { method: 'POST' }); await onRefresh(); setNotice(`第 ${item.sequenceNo} 题已重新提交生成。`); });
  const removeGeneration = () => { if (!window.confirm(`确定删除第 ${item.sequenceNo} 题吗？删除后只会导出其余已审核通过的题目。`)) return; void act('正在删除失败题目…', async () => { await api(`${root}/items/${item.id}/remove`, auth, { method: 'POST' }); await onRefresh(); setNotice(`第 ${item.sequenceNo} 题已删除，导出时将跳过该题。`); }); };
  const keepOriginal = () => { if (!window.confirm(`第 ${item.sequenceNo} 题存在生成或质量风险。确认保留原题，并允许它参与导出吗？`)) return; void act('正在记录人工保留…', async () => { await api(`${root}/items/${item.id}/keep-original`, auth, { method: 'POST', body: JSON.stringify({ comment: '人工确认保留原题，接受 AI/质量风险' }) }); await onRefresh(); setNotice(`第 ${item.sequenceNo} 题已标记为人工保留（有风险），可以参与导出。`); }); };
  const checklistComplete = checks.every(([key]) => checklist[key]);
  const completeChecklist = () => {
    const allChecked = checks.reduce<Record<string, boolean>>((result, [key]) => { result[key] = true; return result; }, {});
    setChecklist(allChecked);
    onDirty(true);
    setNotice('已完成 4 项人工核对，请确认题目内容后点击“保存并通过”。');
  };
  const openQuickApprove = () => {
    if (!checklistComplete) completeChecklist();
    setShowQuickApproveConfirm(true);
  };
  return <article className="quality-detail"><header className="quality-detail-heading"><div><h4>{item.variantLabel} 卷 · 第 {item.sequenceNo} 题</h4><p>{item.typeLabel} · {difficultyName[item.difficulty] || item.difficulty} · {item.points} 分 · v{item.questionVersion || 1}</p></div><span className={`quality-state ${currentStatus.tone}`}><i aria-hidden="true">{currentStatus.symbol}</i>{currentStatus.label}</span></header>
    {error && <p className="maintenance-error" role="alert">{error}</p>}{notice && <p className="maintenance-notice" role="status">{notice}</p>}{pending && <p className="maintenance-working" role="status"><span />{pending}</p>}{item.errorMessage && ['FAILED', 'REVIEW_PENDING'].includes(item.status) && <p className="maintenance-error" role="alert">{item.status === 'FAILED' ? '本次题目生成失败原因：' : '本次 AI 审题原因：'}{item.errorMessage}</p>}
    {!editing && <div className="quality-question-preview"><QuestionContent question={item.question} /><QuestionStimulusPreview projectId={projectId} auth={auth} stimuli={item.question.stimuli} /></div>}
    {!editing && <section className="quality-checks"><h4>检查提示</h4>{inspection ? inspection.issues.length ? <ul>{inspection.issues.map((issue, index) => <li key={`${issue.code}-${index}`} className={issue.severity.toLowerCase()}>{issue.message}</li>)}</ul> : <p>未发现结构性问题；仍需核对学科正确性与考核价值。</p> : <p>正在读取检查结果…</p>}{inspection?.similar.map(similar => <button className="secondary" key={similar.id} onClick={() => onSelect(similar.id)}>对照 {similar.variantLabel} 卷第 {similar.sequenceNo} 题</button>)}{text(item.review.feedback) && <details><summary>初始版本 AI 审题意见</summary><p>{text(item.review.feedback)}</p><small>人工修改后该意见不会自动更新。</small></details>}</section>}
    <div className="maintenance-actions">{editable && !editing && <button disabled={!!pending} onClick={() => { setEditing(true); setChecklist({}); }}>编辑与审核</button>}{editable && <button className="secondary" disabled={!!pending || editing} aria-expanded={showAi} onClick={() => setShowAi(!showAi)}>AI 定向修订</button>}{['APPROVED', 'APPROVED_WITH_RISK'].includes(item.status) && <button className="secondary" disabled={!!pending || runActive} onClick={() => submit('REOPEN')}>重新打开审核</button>}{canRetryGeneration && <button className="secondary" disabled={!!pending} onClick={retryGeneration}>重新生成这一题</button>}{canRemoveGeneration && <button className="secondary maintenance-danger" disabled={!!pending} onClick={removeGeneration}>删除此题，导出其余</button>}{canKeepOriginal && <button className="secondary" disabled={!!pending} onClick={keepOriginal}>保留原题</button>}{retryable && <button className="secondary" disabled={!!pending || runActive || editing} onClick={() => void act('正在重新审题，保留原题；会消耗 AI 额度…', async () => { await api(`${root}/items/${item.id}/retry-review`, auth, { method: 'POST' }); await onRefresh(); })}>仅重试 AI 审题</button>}</div>
    {showAi && editable && !editing && <section className="maintenance-subpanel maintenance-editor"><label>希望改进什么？<textarea rows={3} maxLength={2000} value={instruction} onChange={event => setInstruction(event.target.value)} placeholder="例如：增加对原理的迁移考查；检查选项是否泄露答案；补齐关键条件。" /></label><p className="muted">生成修订稿并重新审题，使用项目的联网设置。原题保持不变；本操作消耗 AI 额度。</p><button disabled={!!pending || !instruction.trim()} onClick={() => void act('正在生成修订稿并审题，可能需要数分钟；原题未改变…', async () => { const result = await api<Proposal>(`${root}/items/${item.id}/revision-preview`, auth, { method: 'POST', body: JSON.stringify({ expectedVersion: item.questionVersion || 1, instruction }) }); setProposal(result); setNotice('修订稿已生成，请与上方原题对照。'); })}>生成修订预览</button></section>}
    {proposal && !editing && <section className="quality-proposal"><h4>修订稿 · 尚未保存</h4><QuestionContent question={proposal.question} /><QuestionStimulusPreview projectId={projectId} auth={auth} stimuli={proposal.question.stimuli} /><p className={proposal.review?.passed ? 'maintenance-notice' : 'maintenance-error'}>{proposal.review?.available === false ? '本次 AI 审题未完成。' : proposal.review?.passed ? 'AI 审查未发现阻断问题，仍需人工确认。' : 'AI 仍有质量疑点，请修正后再考虑通过。'} {proposal.review?.feedback}</p>{proposal.issues.map((issue, index) => <p key={index}>{issue.message}</p>)}<div className="maintenance-actions"><button disabled={!!pending} onClick={() => { setDraft({ ...proposal.question, options: optionText(proposal.question.options) }); setScores([]); setEditing(true); setChecklist({}); setComment(`AI 定向修订：${instruction}`); onDirty(true); }}>载入编辑器</button><button className="secondary" onClick={() => setProposal(null)}>舍弃修订稿</button></div><small>载入后仍需点击保存；不会自动通过审核。</small></section>}
    {editing && <form className="maintenance-editor quality-editor" onSubmit={event => { event.preventDefault(); submit('SAVE_DRAFT'); }}><h4>编辑与人工审核</h4><label>题干<AutoResizeTextarea rows={5} value={text(draft.stem)} onChange={event => change('stem', event.target.value)} /></label>{item.questionType.endsWith('CHOICE') && <label>选项<AutoResizeTextarea rows={4} value={text(draft.options)} onChange={event => change('options', event.target.value)} placeholder="A. … | B. … | C. … | D. …" /></label>}<label>参考答案<AutoResizeTextarea rows={3} value={text(draft.answer)} onChange={event => change('answer', event.target.value)} /></label><label>解析与替代解法<AutoResizeTextarea rows={4} value={text(draft.analysis)} onChange={event => change('analysis', event.target.value)} /></label><label>文字评分细则<AutoResizeTextarea rows={3} disabled={scores.length > 0} value={text(draft.scoringRubric)} onChange={event => change('scoringRubric', event.target.value)} /></label>
      <section className="quality-rubric"><div className="maintenance-section-title"><h4>结构化评分点</h4><span className={scores.length && Math.abs(scoreTotal - item.points) > 0.001 ? 'maintenance-danger' : ''}>{scores.length ? `${scoreTotal.toFixed(2).replace(/\.00$/, '')} / ${item.points} 分` : '可选：添加后自动核对总分'}</span></div>{scores.map((row, index) => <div className="quality-score-row" key={index}><label>评分点 {index + 1}<AutoResizeTextarea rows={2} value={row.criterion} onChange={event => { setScores(current => current.map((value, i) => i === index ? { ...value, criterion: event.target.value } : value)); markDirty(); }} /></label><label>分值<input type="number" min="0.01" step="0.01" max={item.points} value={row.points} onChange={event => { setScores(current => current.map((value, i) => i === index ? { ...value, points: event.target.value } : value)); markDirty(); }} /></label><button type="button" className="secondary" aria-label={`删除评分点 ${index + 1}`} onClick={() => { setScores(current => current.filter((_, i) => i !== index)); markDirty(); }}><StudioIcon name="close" size={16} /></button></div>)}<button type="button" className="secondary" onClick={() => { setScores([...scores, { criterion: '', points: '' }]); markDirty(); }}>添加评分点</button><small>结构化评分点保存时会同步生成文字细则；不能叠加重复计分。</small></section>
      <fieldset className="quality-checklist"><legend><span>人工审题清单</span><button type="button" className="secondary quality-checklist-complete" disabled={!!pending} onClick={openQuickApprove}>一键勾选并通过</button></legend>{checks.map(([key, label]) => <label key={key}><input type="checkbox" checked={!!checklist[key]} onChange={event => { setChecklist(current => ({ ...current, [key]: event.target.checked })); onDirty(true); }} />{label}</label>)}</fieldset>{showQuickApproveConfirm && <section className="quality-quick-approve-confirm" role="alertdialog" aria-labelledby="quality-quick-approve-title"><h4 id="quality-quick-approve-title">确认一键通过第 {item.sequenceNo} 题？</h4><p>系统将自动完成 4 项人工审题清单并提交审核。通过后题目会进入题库，并可参与导出。</p><div className="maintenance-actions"><button type="button" disabled={!!pending} onClick={() => { setShowQuickApproveConfirm(false); submit('APPROVE'); }}>确认通过</button><button type="button" className="secondary" disabled={!!pending} onClick={() => setShowQuickApproveConfirm(false)}>先检查</button></div></section>}<label>审核意见<AutoResizeTextarea rows={2} maxLength={2000} value={comment} onChange={event => { setComment(event.target.value); onDirty(true); }} placeholder="记录修改原因；驳回时必填。" /></label>{!checklistComplete && <p className="quality-approve-hint">完成上方 4 项人工审题核对后，才能点击“保存并通过”。</p>}<div className="maintenance-actions"><button type="submit" className="secondary" disabled={!!pending}>保存草稿</button><button type="button" disabled={!!pending || !checklistComplete} title={!checklistComplete ? '请先完成 4 项人工审题核对' : undefined} onClick={() => submit('APPROVE')}>保存并通过</button><button type="button" className="secondary maintenance-danger" disabled={!!pending} onClick={() => submit('REJECT')}>驳回</button><button type="button" className="secondary" disabled={!!pending} onClick={reset}>取消编辑</button></div>
    </form>}
    <section className="quality-history"><button className="secondary" disabled={!!pending} aria-expanded={!!history} onClick={() => void act('正在读取版本记录…', async () => { if (history) return setHistory(null); const [versions, events] = await Promise.all([api<Version[]>(`${root}/items/${item.id}/versions`, auth), api<ReviewEvent[]>(`${root}/items/${item.id}/review-events`, auth)]); setHistory({ versions, events }); })}>{history ? '收起版本记录' : '版本与操作记录'}</button>{history && <div className="maintenance-history"><h4>题目版本</h4>{history.versions.map(version => <details key={version.id}><summary>v{version.version} · {new Date(version.createdAt).toLocaleString()}</summary><QuestionContent question={version.question} /><p>{version.changeSummary}</p></details>)}<h4>操作记录</h4>{history.events.map(event => <p key={event.id}>{({ EDITED: '编辑', APPROVED: '通过', REJECTED: '驳回', SAVED: '保存', REOPENED: '重新审核', RISK_ACCEPTED: '人工保留原题' } as Record<string, string>)[event.action] || event.action} · {event.actorName} · {new Date(event.createdAt).toLocaleString()}<br />{event.comment}</p>)}</div>}</section>
  </article>;
}

function QuestionContent({ question }: { question: Question }) {
  return <div className="quality-content">{text(question.assessmentPoint) && <p className="quality-objective">考核目标：{text(question.assessmentPoint)}</p>}<p className="quality-stem">{text(question.stem) || '暂无题目正文'}</p>{optionText(question.options) && <ol className="quality-options">{optionText(question.options).split(/\s*\|\s*/).map((option, index) => <li key={index}>{option}</li>)}</ol>}<details className="quality-answer-details"><summary>查看答案、解析与评分细则</summary><dl><dt>参考答案</dt><dd>{text(question.answer) || '尚未填写'}</dd><dt>解析</dt><dd>{text(question.analysis) || '尚未填写'}</dd>{text(question.scoringRubric) && <><dt>评分细则</dt><dd>{text(question.scoringRubric)}</dd></>}</dl></details></div>;
}

function AutoResizeTextarea({ value, onChange, onInput, style, ...props }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  const ref = useRef<HTMLTextAreaElement>(null);
  const resize = () => {
    const element = ref.current;
    if (!element) return;
    element.style.height = '0px';
    element.style.height = `${element.scrollHeight}px`;
  };
  useLayoutEffect(() => { resize(); }, [value]);
  useEffect(() => {
    const element = ref.current;
    if (!element || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(resize);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  return <textarea {...props} ref={ref} value={value} onChange={onChange} onInput={event => { resize(); onInput?.(event); }} style={{ ...style, overflowY: 'hidden', resize: 'none' }} />;
}
