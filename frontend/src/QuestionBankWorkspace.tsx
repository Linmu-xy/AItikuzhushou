import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { StudioIcon } from './StudioIcon';
import { QuestionBankApiError, requestQuestionBank } from './questionBankApi';
import './question-bank.css';

type Question = Record<string, unknown>;
type Bank = { id: string; name: string; description: string; questionCount: number; paperCount: number; updatedAt: string };
type Entry = { id: string; entryType: 'QUESTION' | 'PAPER'; sourceType: string; sourceId: string; sourceRunId: string | null; variantNo: number | null; sequence: number; title: string; snapshot: Record<string, unknown>; sourceVersion: number; addedAt: string };
type Detail = { bank: Bank; entries: Entry[] };
type Paper = { runId: string; variantNo: number; title: string; questionCount: number; types: string[] };
type QuestionRef = { sourceType: 'PROJECT' | 'LEGACY_JOB'; id: string };
type AvailableQuestion = { id: string; sourceType: 'PROJECT' | 'LEGACY_JOB'; sequence: number; questionType: string; typeLabel: string; title: string; question: Question };
type AvailablePaper = { runId: string; variantNo: number; title: string; totalQuestionCount: number; selectableQuestionCount: number; wholePaperReady: boolean; questions: AvailableQuestion[] };
type AvailableContent = { papers: AvailablePaper[]; standalone: AvailableQuestion[] };
type GroupQuestion = { key: string; id: string; sequence: number; typeLabel: string; stem: string; entry?: Entry };
type PaperGroup = { key: string; title: string; questions: GroupQuestion[]; marker?: Entry };
type BankApi = <T>(path: string, init?: RequestInit) => Promise<T>;
type Props = { auth: string; bases: { id: string; name: string }[]; onSessionExpired: () => void };

const typeNames: Record<string, string> = { SINGLE_CHOICE: '单选题', MULTIPLE_CHOICE: '多选题', TRUE_FALSE: '判断题', FILL_BLANK: '填空题', SHORT_ANSWER: '简答题', CASE_ANALYSIS: '案例分析题' };
function text(value: unknown): string {
  if (Array.isArray(value)) return value.map(text).join(' · ');
  if (value && typeof value === 'object') return Object.entries(value).map(([key, part]) => `${key}. ${text(part)}`).join(' · ');
  return value == null ? '' : String(value);
}

export function QuestionBankWorkspace({ auth, onSessionExpired }: Props) {
  const [banks, setBanks] = useState<Bank[]>([]);
  const [bankId, setBankId] = useState('');
  const [detail, setDetail] = useState<Detail>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [editor, setEditor] = useState<Bank | 'new' | null>(null);
  const [pickerBankId, setPickerBankId] = useState('');
  const [busy, setBusy] = useState(false);

  const api = useCallback(async <T,>(path: string, init?: RequestInit): Promise<T> => {
    try {
      return await requestQuestionBank<T>(path, auth, init);
    } catch (reason) {
      if (reason instanceof QuestionBankApiError && reason.status === 401 && !init?.signal?.aborted) onSessionExpired();
      throw reason;
    }
  }, [auth, onSessionExpired]);

  const refreshBanks = useCallback(async () => {
    const result = await api<Bank[]>('/api/question-bank');
    setBanks(result);
    return result;
  }, [api]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError('');
    void api<Bank[]>('/api/question-bank', { signal: controller.signal }).then(setBanks).catch(reason => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '题库读取失败');
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [api]);

  useEffect(() => {
    if (!bankId) { setDetail(undefined); return; }
    const controller = new AbortController();
    setError(''); setDetail(undefined);
    void api<Detail>(`/api/question-bank/banks/${bankId}`, { signal: controller.signal }).then(value => { if (!controller.signal.aborted) setDetail(value); })
      .catch(reason => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '题库读取失败'); });
    return () => controller.abort();
  }, [api, bankId]);

  const mutate = async (action: () => Promise<unknown>, success: string | (() => string), targetBankId = bankId): Promise<boolean> => {
    setBusy(true); setError(''); setNotice('');
    try {
      await action();
      await refreshBanks();
      if (targetBankId && targetBankId === bankId) setDetail(await api<Detail>(`/api/question-bank/banks/${targetBankId}`));
      setNotice(typeof success === 'function' ? success() : success);
      return true;
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '操作失败');
      return false;
    } finally { setBusy(false); }
  };

  const onSaveBank = async (name: string, description: string) => {
    const payload = JSON.stringify({ name, description });
    setBusy(true); setError('');
    try {
      if (editor === 'new') {
        await api('/api/question-bank', { method: 'POST', body: payload });
        await refreshBanks(); setEditor(null); setNotice('题库已创建，已显示在我的题库列表中');
      } else if (editor) {
        await api(`/api/question-bank/banks/${editor.id}`, { method: 'PUT', body: payload });
        await refreshBanks();
        if (editor.id === bankId) setDetail(await api<Detail>(`/api/question-bank/banks/${editor.id}`));
        setEditor(null); setNotice('题库信息已保存');
      }
    } catch (reason) { setError(reason instanceof Error ? reason.message : '保存失败'); }
    finally { setBusy(false); }
  };

  const deleteBank = async () => {
    if (!detail || !window.confirm(`确定删除“${detail.bank.name}”吗？只会删除这个题库及其整理关系，不会删除原题或原试卷。`)) return;
    setBusy(true); setError('');
    try {
      await api(`/api/question-bank/banks/${detail.bank.id}`, { method: 'DELETE' });
      await refreshBanks(); setBankId(''); setDetail(undefined); setNotice('题库已删除');
    } catch (reason) { setError(reason instanceof Error ? reason.message : '删除失败'); }
    finally { setBusy(false); }
  };

  const removeEntry = async (entry: Entry) => {
    if (!detail) return;
    const message = entry.entryType === 'PAPER'
      ? `从题库移除“${entry.title}”及其中收录的题目？原始试卷和原题不会被删除。`
      : `从题库移除“${entry.title}”？原始题目不会被删除。`;
    if (!window.confirm(message)) return;
    await mutate(() => api(`/api/question-bank/banks/${detail.bank.id}/entries/${entry.id}`, { method: 'DELETE' }), '内容已从题库移除');
  };

  const addQuestions = async (targetId: string, refs: QuestionRef[]): Promise<boolean> => {
    if (!refs.length) return false;
    let added = 0;
    const success = await mutate(async () => {
      const result = await api<{ added: number }>(`/api/question-bank/banks/${targetId}/questions`, { method: 'POST', body: JSON.stringify({ questionIds: refs }) });
      added = result.added;
    }, () => `已加入 ${added} 道题目；题库内重复题目会自动跳过`, targetId);
    if (success) setPickerBankId('');
    return success;
  };

  const addPaper = async (targetId: string, paper: Paper): Promise<boolean> => {
    let added = 0;
    const success = await mutate(async () => {
      const result = await api<{ added: number }>(`/api/question-bank/banks/${targetId}/papers`, { method: 'POST', body: JSON.stringify({ runId: paper.runId, variantNo: paper.variantNo }) });
      added = result.added;
    }, () => added ? `已将“${paper.title}”及题目加入题库` : '这份试卷的题目已全部在题库中', targetId);
    if (success) setPickerBankId('');
    return success;
  };

  const bankName = banks.find(bank => bank.id === pickerBankId)?.name || '';
  const current = detail?.bank;
  const grouped = useMemo(() => groupEntries(detail?.entries || []), [detail]);
  const totalQuestions = grouped.papers.reduce((total, paper) => total + paper.questions.length, 0) + grouped.loose.length;

  return <div className="question-bank-page">
    <header className="qb-header">
      <div className="qb-heading">
        {current && <button className="qb-back" type="button" onClick={() => { setBankId(''); setDetail(undefined); setError(''); setNotice(''); }}><span aria-hidden="true">←</span>我的题库</button>}
        <h1>{current?.name || '我的题库'}</h1>
        <p>{current ? (current.description || '在这里整理题目和试卷。') : '按课程或考试整理题目与试卷。'}</p>
      </div>
      {!current && <button className="qb-primary" type="button" onClick={() => setEditor('new')}><StudioIcon name="plus" size={17} />新建题库</button>}
      {current && <div className="qb-header-actions"><button className="qb-secondary" type="button" onClick={() => setEditor(current)}>编辑信息</button><button className="qb-quiet-danger" type="button" disabled={busy} onClick={deleteBank}>删除题库</button></div>}
    </header>

    {error && <div className="qb-alert" role="alert">{error}</div>}
    {notice && <div className="qb-notice" role="status">{notice}<button type="button" aria-label="关闭提示" onClick={() => setNotice('')}>×</button></div>}
    {loading ? <div className="qb-empty" role="status">正在读取题库…</div> : current ? <>
      {!detail ? <div className="qb-empty" role="status">正在读取题库内容…</div> : <>
        <div className="qb-summary-row"><span><b>{totalQuestions}</b> 道题目</span><i /><span><b>{grouped.papers.length}</b> 份试卷</span><span className="qb-summary-spacer" /><button className="qb-primary" type="button" onClick={() => setPickerBankId(current.id)}><StudioIcon name="plus" size={15} />添加内容</button></div>
        {!detail.entries.length ? <div className="qb-empty"><div className="qb-empty-icon"><StudioIcon name="file" size={22} /></div><h2>这个题库还没有内容</h2><p>可以添加已审核的单题，也可以把审核完成的整份试卷加入。</p><button className="qb-primary" type="button" onClick={() => setPickerBankId(current.id)}><StudioIcon name="plus" size={16} />添加内容</button></div> : <>
          <div className="qb-section-heading qb-paper-heading"><div><h2>按试卷查看</h2><p>点开试卷名称，查看收录的题目</p></div><span>{grouped.papers.length + (grouped.loose.length ? 1 : 0)}</span></div>
          <div className="qb-paper-list">
            {grouped.papers.map(paper => <PaperGroupCard key={paper.key} paper={paper} busy={busy} onRemoveEntry={removeEntry} onRemovePaper={() => paper.marker && removeEntry(paper.marker)} />)}
            {grouped.loose.length > 0 && <LooseQuestions questions={grouped.loose} busy={busy} onRemove={removeEntry} />}
          </div>
        </>}
      </>}
    </> : banks.length ? <div className="qb-bank-list">{banks.map(bank => <article className="qb-bank-row" key={bank.id}>
      <button className="qb-bank-open" type="button" onClick={() => { setBankId(bank.id); setNotice(''); setError(''); }}>
        <span className="qb-bank-main"><strong>{bank.name}</strong><small>{bank.description || '暂无说明'}</small><span className="qb-bank-meta"><span>{bank.paperCount} 份试卷</span><i />{bank.questionCount} 道题目</span></span>
        <span className="qb-bank-chevron" aria-hidden="true">›</span>
      </button>
      <button className="qb-bank-add" type="button" onClick={() => setPickerBankId(bank.id)}><StudioIcon name="plus" size={15} />添加内容</button>
    </article>)}</div> : <div className="qb-empty"><div className="qb-empty-icon"><StudioIcon name="file" size={22} /></div><h2>先创建你的第一个题库</h2><p>按课程、岗位或考试批次整理，之后可随时添加或移除内容。</p><button className="qb-primary" type="button" onClick={() => setEditor('new')}><StudioIcon name="plus" size={16} />新建题库</button></div>}

    {editor && <BankEditor value={editor === 'new' ? undefined : editor} busy={busy} onClose={() => setEditor(null)} onSave={onSaveBank} />}
    {pickerBankId && <PickerModal bankId={pickerBankId} bankName={bankName} api={api} onClose={() => setPickerBankId('')} onAddQuestions={addQuestions} onAddPaper={addPaper} />}
  </div>;
}

function groupEntries(entries: Entry[]): { papers: PaperGroup[]; loose: GroupQuestion[] } {
  const groups = new Map<string, PaperGroup>();
  const loose: GroupQuestion[] = [];
  const ensureGroup = (key: string, title: string) => {
    let group = groups.get(key);
    if (!group) { group = { key, title, questions: [] }; groups.set(key, group); }
    else if (title && (!group.title || group.title.startsWith('试卷 '))) group.title = title;
    return group;
  };
  const appendQuestion = (group: PaperGroup, question: GroupQuestion) => {
    if (!group.questions.some(existing => existing.id === question.id)) group.questions.push(question);
  };

  for (const entry of entries) {
    const snapshot = entry.snapshot || {};
    if (entry.entryType === 'PAPER') {
      if (entry.sourceRunId && entry.variantNo != null) {
        const group = ensureGroup(`PROJECT:${entry.sourceRunId}:${entry.variantNo}`, entry.title);
        group.marker = entry;
        const olderSnapshotQuestions = Array.isArray(snapshot.questions) ? snapshot.questions as Record<string, unknown>[] : [];
        olderSnapshotQuestions.forEach((item, index) => {
          const question = (item.question || {}) as Question;
          appendQuestion(group, { key: `${entry.id}:${item.id || index}`, id: String(item.id || `${entry.id}-${index}`), sequence: Number(item.sequence || index + 1), typeLabel: String(item.typeLabel || typeNames[String(item.questionType)] || '题目'), stem: text(question.stem) || '未填写题干' });
        });
      }
      continue;
    }

    const question = (snapshot.question || {}) as Question;
    const row: GroupQuestion = { key: entry.id, id: entry.sourceId, sequence: entry.sequence, typeLabel: String(snapshot.typeLabel || typeNames[String(snapshot.questionType || question.type)] || '题目'), stem: text(question.stem) || '未填写题干', entry };
    if (entry.sourceType === 'PROJECT' && entry.sourceRunId && entry.variantNo != null) {
      const paperTitle = String(snapshot.paperTitle || entry.title.replace(/ · 第 \d+ 题$/, ''));
      const group = ensureGroup(`PROJECT:${entry.sourceRunId}:${entry.variantNo}`, paperTitle);
      appendQuestion(group, row);
    } else loose.push(row);
  }

  for (const group of groups.values()) group.questions.sort((a, b) => a.sequence - b.sequence);
  return { papers: [...groups.values()].sort((a, b) => a.title.localeCompare(b.title, 'zh-CN')), loose: loose.sort((a, b) => a.sequence - b.sequence) };
}

function BankEditor({ value, busy, onClose, onSave }: { value?: Bank; busy: boolean; onClose: () => void; onSave: (name: string, description: string) => void }) {
  const [name, setName] = useState(value?.name || '');
  const [description, setDescription] = useState(value?.description || '');
  const submit = (event: FormEvent) => { event.preventDefault(); onSave(name.trim(), description.trim()); };
  useModalEffects(onClose);
  return createPortal(<div className="qb-overlay" onMouseDown={event => { if (event.target === event.currentTarget) onClose(); }}><form className="qb-dialog" onSubmit={submit}>
    <div className="qb-dialog-heading"><div><h2>{value ? '编辑题库' : '新建题库'}</h2></div><button type="button" className="qb-icon-button" onClick={onClose} aria-label="关闭">×</button></div>
    <label>题库名称<input autoFocus maxLength={100} required value={name} onChange={event => setName(event.target.value)} placeholder="例如：机械制图基础" /></label>
    <label>说明 <span className="qb-optional">选填</span><textarea maxLength={500} rows={3} value={description} onChange={event => setDescription(event.target.value)} placeholder="简单说明这个题库的用途" /></label>
    <div className="qb-dialog-actions"><button type="button" className="qb-secondary" onClick={onClose}>取消</button><button className="qb-primary" disabled={busy || !name.trim()}>{busy ? '保存中…' : '保存'}</button></div>
  </form></div>, document.body);
}

function useModalEffects(onClose: () => void) {
  useEffect(() => {
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKeyDown);
    return () => { document.body.style.overflow = previousOverflow; window.removeEventListener('keydown', onKeyDown); };
  }, [onClose]);
}

function PaperGroupCard({ paper, busy, onRemoveEntry, onRemovePaper }: { paper: PaperGroup; busy: boolean; onRemoveEntry: (entry: Entry) => void; onRemovePaper: () => void }) {
  return <details className="qb-paper-card"><summary><span className="qb-paper-icon">卷</span><span className="qb-paper-main"><strong>{paper.title}</strong><small>{paper.questions.length} 道题目{paper.marker ? ' · 整份试卷已加入' : ' · 已选题目'}</small></span><span className="qb-paper-toggle">展开</span><span className="qb-paper-chevron" aria-hidden="true">⌄</span></summary>
    <div className="qb-question-list">{paper.questions.map((question, index) => <article className="qb-question-row" key={question.key}><span className="qb-qno">{String(question.sequence).padStart(2, '0')}</span><div className="qb-question-copy"><p>{question.stem}</p><span>{question.typeLabel}</span></div>{question.entry && <button className="qb-remove-entry" type="button" disabled={busy} onClick={() => onRemoveEntry(question.entry!)}>移除</button>}</article>)}
      {paper.marker && <button className="qb-remove-paper" type="button" disabled={busy} onClick={onRemovePaper}>移除整份试卷及本组题目</button>}
    </div>
  </details>;
}

function LooseQuestions({ questions, busy, onRemove }: { questions: GroupQuestion[]; busy: boolean; onRemove: (entry: Entry) => void }) {
  return <details className="qb-paper-card"><summary><span className="qb-paper-icon qb-paper-icon-loose">题</span><span className="qb-paper-main"><strong>单独添加</strong><small>{questions.length} 道未关联试卷的题目</small></span><span className="qb-paper-toggle">展开</span><span className="qb-paper-chevron" aria-hidden="true">⌄</span></summary>
    <div className="qb-question-list">{questions.map(question => <article className="qb-question-row" key={question.key}><span className="qb-qno">{String(question.sequence).padStart(2, '0')}</span><div className="qb-question-copy"><p>{question.stem}</p><span>{question.typeLabel}</span></div>{question.entry && <button className="qb-remove-entry" type="button" disabled={busy} onClick={() => onRemove(question.entry!)}>移除</button>}</article>)}</div>
  </details>;
}

function PickerModal({ bankId, bankName, api, onClose, onAddQuestions, onAddPaper }: { bankId: string; bankName: string; api: BankApi; onClose: () => void; onAddQuestions: (bankId: string, items: QuestionRef[]) => Promise<boolean>; onAddPaper: (bankId: string, paper: Paper) => Promise<boolean> }) {
  const [query, setQuery] = useState('');
  const [appliedQuery, setAppliedQuery] = useState('');
  const [content, setContent] = useState<AvailableContent>();
  const [selected, setSelected] = useState<Record<string, QuestionRef>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [loadAttempt, setLoadAttempt] = useState(0);
  useModalEffects(onClose);

  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(''); setContent(undefined);
    const params = new URLSearchParams();
    if (appliedQuery) params.set('q', appliedQuery);
    void api<AvailableContent>(`/api/question-bank/available/content?${params}`, { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setContent(result); }).catch(reason => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '候选内容加载失败'); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [api, appliedQuery, loadAttempt]);

  const submit = (event: FormEvent) => { event.preventDefault(); setAppliedQuery(query.trim()); setLoadAttempt(attempt => attempt + 1); };
  const toggle = (item: AvailableQuestion) => {
    const ref: QuestionRef = { sourceType: item.sourceType, id: item.id };
    const key = `${ref.sourceType}:${ref.id}`;
    setSelected(old => { const next = { ...old }; if (next[key]) delete next[key]; else next[key] = ref; return next; });
  };
  const togglePaperQuestions = (questions: AvailableQuestion[]) => setSelected(old => {
    const next = { ...old };
    const allSelected = questions.length > 0 && questions.every(item => next[`${item.sourceType}:${item.id}`]);
    questions.forEach(item => { const key = `${item.sourceType}:${item.id}`; if (allSelected) delete next[key]; else next[key] = { sourceType: item.sourceType, id: item.id }; });
    return next;
  });
  const selectedItems = Object.values(selected);
  const submitAdd = async (action: () => Promise<boolean>) => { setBusy(true); try { await action(); } finally { setBusy(false); } };
  const renderQuestion = (item: AvailableQuestion) => <label className="qb-candidate qb-question-candidate" key={`${item.sourceType}:${item.id}`}>
    <input type="checkbox" checked={!!selected[`${item.sourceType}:${item.id}`]} onChange={() => toggle(item)} />
    <span><strong>第 {item.sequence} 题　{text(item.question.stem) || '未填写题干'}</strong><small>{typeNames[item.questionType] || item.typeLabel || item.questionType}</small></span>
  </label>;

  return createPortal(<div className="qb-overlay" onMouseDown={event => { if (event.target === event.currentTarget) onClose(); }}><section className="qb-picker" role="dialog" aria-modal="true" aria-label="添加题库内容">
    <div className="qb-dialog-heading"><div><h2>添加内容</h2><p>添加到：{bankName}</p></div><button type="button" className="qb-icon-button" onClick={onClose} aria-label="关闭">×</button></div>
    <p className="qb-picker-hint">按试卷浏览：可整份加入，也可展开后勾选部分题目。</p>
    <form className="qb-picker-filters" onSubmit={submit}><div className="qb-picker-search"><StudioIcon name="search" size={16} /><input value={query} onChange={event => setQuery(event.target.value)} placeholder="搜索试卷名称或题目内容" /></div><button type="submit" className="qb-secondary">搜索</button></form>
    {error && <div className="qb-alert" role="alert">{error}</div>}
    <div className="qb-picker-body">
      {loading ? <div className="qb-picker-empty" role="status">正在加载…</div> : error ? <div className="qb-picker-empty"><button type="button" className="qb-secondary" onClick={() => setLoadAttempt(attempt => attempt + 1)}>重试加载</button></div> : (content?.papers.length || content?.standalone.length) ? <div className="qb-candidate-list">
        {content?.papers.map(paper => <details className="qb-source-paper" key={`${paper.runId}-${paper.variantNo}`}>
          <summary><span className="qb-paper-mark">卷</span><span className="qb-source-paper-copy"><strong>{paper.title}</strong><small>{paper.totalQuestionCount} 道题 · {paper.selectableQuestionCount} 道可选</small></span><span className="qb-source-expand">查看题目⌄</span></summary>
          <div className="qb-source-paper-actions"><span>{paper.wholePaperReady ? '整份试卷均已审核通过' : '部分题目尚未审核通过，仍可添加已通过的题目'}</span><button type="button" className="qb-secondary" disabled={busy || !paper.wholePaperReady} title={!paper.wholePaperReady ? '该试卷仍有题目未审核通过' : undefined} onClick={() => void submitAdd(() => onAddPaper(bankId, { runId: paper.runId, variantNo: paper.variantNo, title: paper.title, questionCount: paper.totalQuestionCount, types: [] }))}>{paper.wholePaperReady ? '加入整份试卷' : '整卷暂不可加入'}</button></div>
          {paper.questions.length ? <><div className="qb-source-select"><label><input type="checkbox" checked={paper.questions.length > 0 && paper.questions.every(item => selected[`${item.sourceType}:${item.id}`])} onChange={() => togglePaperQuestions(paper.questions)} />全选本试卷可选题</label><span>{paper.questions.length} 道已通过</span></div><div className="qb-source-questions">{paper.questions.map(renderQuestion)}</div></> : <div className="qb-source-empty">这份试卷目前没有可单独添加的已通过题目</div>}
        </details>)}
        {!!content?.standalone.length && <details className="qb-source-paper"><summary><span className="qb-paper-mark qb-paper-mark-loose">题</span><span className="qb-source-paper-copy"><strong>单独题目</strong><small>{content.standalone.length} 道未关联试卷的已审核题目</small></span><span className="qb-source-expand">查看题目⌄</span></summary><div className="qb-source-questions">{content.standalone.map(renderQuestion)}</div></details>}
      </div> : <div className="qb-picker-empty">没有找到可添加的已审核内容</div>}
    </div>
    <div className="qb-picker-footer"><span>已选 {selectedItems.length} 道题目</span><button type="button" className="qb-secondary" onClick={onClose}>取消</button><button type="button" className="qb-primary" disabled={busy || loading || !!error || !selectedItems.length} onClick={() => void submitAdd(() => onAddQuestions(bankId, selectedItems))}>{busy ? '添加中…' : `加入所选${selectedItems.length ? `（${selectedItems.length}）` : ''}`}</button></div>
  </section></div>, document.body);
}
