import { useEffect, useState } from 'react';
import { QuestionStimulusPreview } from './QuestionStimulusPreview';
import { StudioIcon } from './StudioIcon';
import { errorText, maintenanceApi as api } from './maintenanceApi';
import './maintenance.css';

type Question = Record<string, unknown>;
type Item = { id: string; variantLabel: string; sequenceNo: number; typeLabel: string; questionType: string; points: number; difficulty: string; status: string; questionVersion?: number; question: Question; review: Question; reviewerId?: string; reviewComment?: string; errorCode?: string };
type Run = { id: string; status: string; items: Item[] };
type Issue = { code: string; severity: string; message: string };
type Inspection = { id: string; version: number; issues: Issue[]; similar: { id: string; variantLabel: string; sequenceNo: number; similarity: number }[] };
type Proposal = { expectedVersion: number; question: Question; review?: { passed: boolean; available: boolean; feedback: string }; issues: Issue[] };
type Score = { criterion: string; points: number | string };
type Version = { id: string; version: number; question: Question; changeSummary?: string; createdAt: string };
type ReviewEvent = { id: string; action: string; actorName: string; comment: string; createdAt: string };
const statusName: Record<string, string> = { REVIEW_REQUIRED: '待审核', REVIEW_PENDING: '等待 AI 审题', APPROVED: '已通过', REJECTED: '已驳回', FAILED: '生成失败', PLANNED: '待生成', GENERATING: '生成中' };
const difficultyName: Record<string, string> = { EASY: '基础', MEDIUM: '中等', HARD: '进阶' };
const text = (value: unknown) => value == null ? '' : String(value);
const optionText = (value: unknown) => value && typeof value === 'object' ? Object.entries(value).map(([key, content]) => `${key}. ${content}`).join(' | ') : text(value);
const checks = [['target', '确实考查了目标能力'], ['answer', '条件充分，答案与合理替代解法已核对'], ['fairness', '没有无意泄露答案或无关难点'], ['scoring', '评分依据明确，分值分配合理']] as const;

export default function QuestionQualityWorkbench({ auth, projectId, run, onRefresh }: { auth: string; projectId: string; run: Run; onRefresh: () => Promise<void> }) {
  const [selected, setSelected] = useState(run.items[0]?.id || '');
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('ALL');
  const [inspections, setInspections] = useState<Inspection[]>([]);
  const [error, setError] = useState('');
  const [dirty, setDirty] = useState(false);
  const [operationPending, setOperationPending] = useState(false);
  useEffect(() => {
    if (!dirty && !operationPending) return;
    const protect = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', protect);
    return () => window.removeEventListener('beforeunload', protect);
  }, [dirty, operationPending]);
  const root = `/api/exam-projects/${projectId}/variant-generation-runs/${run.id}`;
  useEffect(() => { const controller = new AbortController(); setError(''); api<Inspection[]>(`${root}/quality`, auth, { signal: controller.signal }).then(setInspections).catch(error => { if (!controller.signal.aborted) setError(errorText(error)); }); return () => controller.abort(); }, [auth, root, run]);
  const visible = run.items.filter(item => (filter === 'ALL' || filter === 'ISSUES' && inspections.some(row => row.id === item.id && row.issues.some(issue => issue.severity !== 'INFO')) || filter === item.status) && `${item.sequenceNo} ${item.typeLabel} ${text(item.question.stem)}`.toLowerCase().includes(query.toLowerCase()));
  const item = run.items.find(item => item.id === selected) || run.items[0];
  const choose = (id: string) => { if ((dirty || operationPending) && id !== selected) return setError(operationPending ? '当前操作尚未完成，请稍候再切换题目。' : '当前题目有未保存的修改，请先保存或取消编辑。'); setSelected(id); setError(''); };
  const approved = run.items.filter(item => item.status === 'APPROVED').length;
  const problems = inspections.filter(row => row.issues.some(issue => issue.severity !== 'INFO')).length;
  return <section className="panel maintenance quality-workbench"><div className="maintenance-section-title"><div><h3>题目质量工作台</h3><p className="muted">逐题核对、修订并确认。AI 意见是审题辅助，不代表实测难度或区分度。</p></div><span className="quality-progress">已通过 <b>{approved}</b> / {run.items.length}</span></div>
    <div className="quality-summary"><span><b>{run.items.length - approved}</b> 待完成审核</span><span><b>{problems}</b> 道有检查提示</span><span>当前批次 · 不自动批准题目</span></div>
    {error && <p className="maintenance-error" role="alert">{error}</p>}
    <div className="maintenance-filters"><label className="maintenance-search"><StudioIcon name="search" size={18} /><input aria-label="搜索题目" placeholder="搜索题干、题型或题号" value={query} onChange={event => setQuery(event.target.value)} /></label><select aria-label="筛选审核状态" value={filter} onChange={event => setFilter(event.target.value)}><option value="ALL">全部题目</option><option value="ISSUES">有检查提示</option><option value="REVIEW_REQUIRED">待审核</option><option value="REJECTED">已驳回</option><option value="REVIEW_PENDING">等待 AI 审题</option><option value="APPROVED">已通过</option><option value="FAILED">生成失败</option></select></div>
    <div className="quality-columns"><nav className="quality-question-list" aria-label="待审题目">{visible.map(row => <button key={row.id} aria-current={item?.id === row.id ? 'true' : undefined} onClick={() => choose(row.id)}><span className="quality-list-meta">{row.variantLabel} 卷 · 第 {row.sequenceNo} 题<span>{statusName[row.status] || row.status}</span></span><b>{text(row.question.stem) || '尚无题目正文'}</b><small>{row.typeLabel} · {row.points} 分</small></button>)}{!visible.length && <p className="maintenance-empty">没有匹配的题目。</p>}</nav>
      {item ? <QuestionEditor key={`${item.id}-${item.questionVersion || 1}`} auth={auth} projectId={projectId} root={root} item={item} runActive={['QUEUED', 'RUNNING'].includes(run.status)} inspection={inspections.find(row => row.id === item.id)} onDirty={setDirty} onPending={setOperationPending} onRefresh={onRefresh} onSelect={choose} /> : <p className="maintenance-empty">本批次还没有题目。</p>}
    </div>
  </section>;
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
  const [proposal, setProposal] = useState<Proposal | null>(null);
  const [history, setHistory] = useState<{ versions: Version[]; events: ReviewEvent[] } | null>(null);
  const editable = ['REVIEW_REQUIRED', 'REJECTED'].includes(item.status) && !runActive;
  const retryable = !item.reviewerId && (item.questionVersion || 1) === 1 && (item.status === 'REVIEW_PENDING' || item.status === 'REJECTED' && item.errorCode === 'QUALITY_REJECTED');
  const markDirty = () => { onDirty(true); setNotice(''); setChecklist({}); };
  const change = (field: string, value: string) => { setDraft(current => ({ ...current, [field]: value })); markDirty(); };
  const act = async (label: string, work: () => Promise<void>) => { setPending(label); onPending(true); setError(''); setNotice(''); try { await work(); } catch (error) { setError(errorText(error)); } finally { setPending(''); onPending(false); } };
  const reset = () => { setDraft({ ...item.question, options: optionText(item.question.options) }); setScores(Array.isArray(item.question.scoringItems) ? item.question.scoringItems as Score[] : []); setChecklist({}); setEditing(false); setProposal(null); onDirty(false); setError(''); };
  const submit = (decision: string) => void act('正在保存…', async () => {
    if (decision === 'REJECT' && !comment.trim()) throw new Error('请填写驳回原因。');
    if (decision === 'APPROVE' && !checks.every(([key]) => checklist[key])) throw new Error('请逐项核对人工审题清单。');
    const question = decision === 'REOPEN' ? undefined : { ...draft, scoringItems: scores, scoringRubric: scores.length ? scores.map(row => `${row.criterion}（${row.points}分）`).join('；') : draft.scoringRubric, qualityChecklist: checklist };
    await api(`${root}/items/${item.id}/review`, auth, { method: 'PATCH', body: JSON.stringify({ decision, expectedVersion: item.questionVersion || 1, comment, question }) });
    onDirty(false); setEditing(false); setProposal(null); setNotice('已保存。'); await onRefresh();
  });
  const scoreTotal = scores.reduce((sum, row) => sum + (Number(row.points) || 0), 0);
  return <article className="quality-detail"><header className="quality-detail-heading"><div><h4>{item.variantLabel} 卷 · 第 {item.sequenceNo} 题</h4><p>{item.typeLabel} · {difficultyName[item.difficulty] || item.difficulty} · {item.points} 分 · v{item.questionVersion || 1}</p></div><span className={`quality-state ${item.status.toLowerCase()}`}>{statusName[item.status] || item.status}</span></header>
    {error && <p className="maintenance-error" role="alert">{error}</p>}{notice && <p className="maintenance-notice" role="status">{notice}</p>}{pending && <p className="maintenance-working" role="status"><span />{pending}</p>}
    {!editing && <div className="quality-question-preview"><QuestionContent question={item.question} /><QuestionStimulusPreview projectId={projectId} auth={auth} stimuli={item.question.stimuli} /></div>}
    {!editing && <section className="quality-checks"><h4>检查提示</h4>{inspection ? inspection.issues.length ? <ul>{inspection.issues.map((issue, index) => <li key={`${issue.code}-${index}`} className={issue.severity.toLowerCase()}>{issue.message}</li>)}</ul> : <p>未发现结构性问题；仍需核对学科正确性与考核价值。</p> : <p>正在读取检查结果…</p>}{inspection?.similar.map(similar => <button className="secondary" key={similar.id} onClick={() => onSelect(similar.id)}>对照 {similar.variantLabel} 卷第 {similar.sequenceNo} 题</button>)}{text(item.review.feedback) && <details><summary>初始版本 AI 审题意见</summary><p>{text(item.review.feedback)}</p><small>人工修改后该意见不会自动更新。</small></details>}</section>}
    <div className="maintenance-actions">{editable && !editing && <button disabled={!!pending} onClick={() => { setEditing(true); setChecklist({}); }}>编辑与审核</button>}{editable && <button className="secondary" disabled={!!pending || editing} aria-expanded={showAi} onClick={() => setShowAi(!showAi)}>AI 定向修订</button>}{item.status === 'APPROVED' && <button className="secondary" disabled={!!pending || runActive} onClick={() => submit('REOPEN')}>重新打开审核</button>}{retryable && <button className="secondary" disabled={!!pending || runActive || editing} onClick={() => void act('正在重新审题，保留原题；会消耗 AI 额度…', async () => { await api(`${root}/items/${item.id}/retry-review`, auth, { method: 'POST' }); await onRefresh(); })}>仅重试 AI 审题</button>}</div>
    {showAi && editable && !editing && <section className="maintenance-subpanel maintenance-editor"><label>希望改进什么？<textarea rows={3} maxLength={2000} value={instruction} onChange={event => setInstruction(event.target.value)} placeholder="例如：增加对原理的迁移考查；检查选项是否泄露答案；补齐关键条件。" /></label><p className="muted">生成修订稿并重新审题，使用项目的联网设置。原题保持不变；本操作消耗 AI 额度。</p><button disabled={!!pending || !instruction.trim()} onClick={() => void act('正在生成修订稿并审题，可能需要数分钟；原题未改变…', async () => { const result = await api<Proposal>(`${root}/items/${item.id}/revision-preview`, auth, { method: 'POST', body: JSON.stringify({ expectedVersion: item.questionVersion || 1, instruction }) }); setProposal(result); setNotice('修订稿已生成，请与上方原题对照。'); })}>生成修订预览</button></section>}
    {proposal && !editing && <section className="quality-proposal"><h4>修订稿 · 尚未保存</h4><QuestionContent question={proposal.question} /><QuestionStimulusPreview projectId={projectId} auth={auth} stimuli={proposal.question.stimuli} /><p className={proposal.review?.passed ? 'maintenance-notice' : 'maintenance-error'}>{proposal.review?.available === false ? '本次 AI 审题未完成。' : proposal.review?.passed ? 'AI 审查未发现阻断问题，仍需人工确认。' : 'AI 仍有质量疑点，请修正后再考虑通过。'} {proposal.review?.feedback}</p>{proposal.issues.map((issue, index) => <p key={index}>{issue.message}</p>)}<div className="maintenance-actions"><button disabled={!!pending} onClick={() => { setDraft({ ...proposal.question, options: optionText(proposal.question.options) }); setScores([]); setEditing(true); setChecklist({}); setComment(`AI 定向修订：${instruction}`); onDirty(true); }}>载入编辑器</button><button className="secondary" onClick={() => setProposal(null)}>舍弃修订稿</button></div><small>载入后仍需点击保存；不会自动通过审核。</small></section>}
    {editing && <form className="maintenance-editor quality-editor" onSubmit={event => { event.preventDefault(); submit('SAVE_DRAFT'); }}><h4>编辑与人工审核</h4><label>题干<textarea rows={5} value={text(draft.stem)} onChange={event => change('stem', event.target.value)} /></label>{item.questionType.endsWith('CHOICE') && <label>选项<textarea rows={4} value={text(draft.options)} onChange={event => change('options', event.target.value)} placeholder="A. … | B. … | C. … | D. …" /></label>}<label>参考答案<textarea rows={3} value={text(draft.answer)} onChange={event => change('answer', event.target.value)} /></label><label>解析与替代解法<textarea rows={4} value={text(draft.analysis)} onChange={event => change('analysis', event.target.value)} /></label><label>文字评分细则<textarea rows={3} disabled={scores.length > 0} value={text(draft.scoringRubric)} onChange={event => change('scoringRubric', event.target.value)} /></label>
      <section className="quality-rubric"><div className="maintenance-section-title"><h4>结构化评分点</h4><span className={scores.length && Math.abs(scoreTotal - item.points) > 0.001 ? 'maintenance-danger' : ''}>{scores.length ? `${scoreTotal.toFixed(2).replace(/\.00$/, '')} / ${item.points} 分` : '可选：添加后自动核对总分'}</span></div>{scores.map((row, index) => <div className="quality-score-row" key={index}><label>评分点 {index + 1}<textarea rows={2} value={row.criterion} onChange={event => { setScores(current => current.map((value, i) => i === index ? { ...value, criterion: event.target.value } : value)); markDirty(); }} /></label><label>分值<input type="number" min="0.01" step="0.01" max={item.points} value={row.points} onChange={event => { setScores(current => current.map((value, i) => i === index ? { ...value, points: event.target.value } : value)); markDirty(); }} /></label><button type="button" className="secondary" aria-label={`删除评分点 ${index + 1}`} onClick={() => { setScores(current => current.filter((_, i) => i !== index)); markDirty(); }}><StudioIcon name="close" size={16} /></button></div>)}<button type="button" className="secondary" onClick={() => { setScores([...scores, { criterion: '', points: '' }]); markDirty(); }}>添加评分点</button><small>结构化评分点保存时会同步生成文字细则；不能叠加重复计分。</small></section>
      <fieldset className="quality-checklist"><legend>人工审题清单</legend>{checks.map(([key, label]) => <label key={key}><input type="checkbox" checked={!!checklist[key]} onChange={event => { setChecklist(current => ({ ...current, [key]: event.target.checked })); onDirty(true); }} />{label}</label>)}</fieldset><label>审核意见<textarea rows={2} maxLength={2000} value={comment} onChange={event => { setComment(event.target.value); onDirty(true); }} placeholder="记录修改原因；驳回时必填。" /></label><div className="maintenance-actions"><button type="submit" className="secondary" disabled={!!pending}>保存草稿</button><button type="button" disabled={!!pending || !checks.every(([key]) => checklist[key])} onClick={() => submit('APPROVE')}>保存并通过</button><button type="button" className="secondary maintenance-danger" disabled={!!pending} onClick={() => submit('REJECT')}>驳回</button><button type="button" className="secondary" disabled={!!pending} onClick={reset}>取消编辑</button></div>
    </form>}
    <section className="quality-history"><button className="secondary" disabled={!!pending} aria-expanded={!!history} onClick={() => void act('正在读取版本记录…', async () => { if (history) return setHistory(null); const [versions, events] = await Promise.all([api<Version[]>(`${root}/items/${item.id}/versions`, auth), api<ReviewEvent[]>(`${root}/items/${item.id}/review-events`, auth)]); setHistory({ versions, events }); })}>{history ? '收起版本记录' : '版本与操作记录'}</button>{history && <div className="maintenance-history"><h4>题目版本</h4>{history.versions.map(version => <details key={version.id}><summary>v{version.version} · {new Date(version.createdAt).toLocaleString()}</summary><QuestionContent question={version.question} /><p>{version.changeSummary}</p></details>)}<h4>操作记录</h4>{history.events.map(event => <p key={event.id}>{({ EDITED: '编辑', APPROVED: '通过', REJECTED: '驳回', SAVED: '保存', REOPENED: '重新审核' } as Record<string, string>)[event.action] || event.action} · {event.actorName} · {new Date(event.createdAt).toLocaleString()}<br />{event.comment}</p>)}</div>}</section>
  </article>;
}

function QuestionContent({ question }: { question: Question }) {
  return <div className="quality-content">{text(question.assessmentPoint) && <p className="quality-objective">考核目标：{text(question.assessmentPoint)}</p>}<p className="quality-stem">{text(question.stem) || '暂无题目正文'}</p>{optionText(question.options) && <ol className="quality-options">{optionText(question.options).split(/\s*\|\s*/).map((option, index) => <li key={index}>{option}</li>)}</ol>}<details className="quality-answer-details"><summary>查看答案、解析与评分细则</summary><dl><dt>参考答案</dt><dd>{text(question.answer) || '尚未填写'}</dd><dt>解析</dt><dd>{text(question.analysis) || '尚未填写'}</dd>{text(question.scoringRubric) && <><dt>评分细则</dt><dd>{text(question.scoringRubric)}</dd></>}</dl></details></div>;
}
