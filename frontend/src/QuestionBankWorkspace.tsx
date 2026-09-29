import { FormEvent, useEffect, useState } from 'react';
import { StudioIcon } from './StudioIcon';
import './question-bank.css';

type KnowledgeBase = { id: string; name: string };
type Question = Record<string, unknown>;
type BankItem = {
  id: string;
  sourceType: 'PROJECT' | 'LEGACY_JOB';
  sourceId: string;
  sequence: number;
  knowledgeBaseId: string | null;
  knowledgeBaseName: string;
  sourceName: string;
  questionType: string;
  difficulty: string;
  question: Question;
  reviewedAt: string;
  archiveDate: string;
};
type BankPage = { items: BankItem[]; total: number; page: number; size: number; hasMore: boolean };

const typeNames: Record<string, string> = { SINGLE_CHOICE: '单选题', MULTIPLE_CHOICE: '多选题', TRUE_FALSE: '判断题', FILL_BLANK: '填空题', SHORT_ANSWER: '简答题', CASE_ANALYSIS: '案例分析题' };
const difficultyNames: Record<string, string> = { EASY: '简单', MEDIUM: '中等', HARD: '困难' };
function text(value: unknown): string {
  if (Array.isArray(value)) return value.map(text).join(' · ');
  if (value && typeof value === 'object') return Object.entries(value).map(([key, part]) => `${key}. ${text(part)}`).join(' · ');
  return value == null ? '' : String(value);
}

export function QuestionBankWorkspace({ auth, bases }: { auth: string; bases: KnowledgeBase[] }) {
  const [knowledgeBaseId, setKnowledgeBaseId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [query, setQuery] = useState('');
  const [appliedQuery, setAppliedQuery] = useState('');
  const [page, setPage] = useState<BankPage>();
  const [pageIndex, setPageIndex] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    const params = new URLSearchParams({ page: String(pageIndex), size: '20' });
    if (knowledgeBaseId === 'unlinked') params.set('unlinked', 'true');
    else if (knowledgeBaseId) params.set('knowledgeBaseId', knowledgeBaseId);
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    if (appliedQuery) params.set('q', appliedQuery);
    setLoading(true);
    setError('');
    const headers = auth.startsWith('Basic ') ? { Authorization: auth } : undefined;
    void fetch(`/api/question-bank?${params}`, { headers, credentials: 'same-origin', cache: 'no-store', signal: controller.signal })
      .then(async response => {
        if (!response.ok) {
          const detail = await response.json().catch(() => ({})) as { message?: string };
          throw new Error(detail.message || '题库读取失败');
        }
        return response.json() as Promise<BankPage>;
      })
      .then(result => { setPage(result); setLoading(false); })
      .catch(caught => {
        if (controller.signal.aborted) return;
        setError(caught instanceof Error ? caught.message : '题库读取失败');
        setLoading(false);
      });
    return () => controller.abort();
  }, [auth, knowledgeBaseId, from, to, appliedQuery, pageIndex]);

  const submitSearch = (event: FormEvent) => { event.preventDefault(); setPageIndex(0); setAppliedQuery(query.trim()); };
  const items = page?.items || [];
  const groupDates = [...new Set(items.map(item => item.archiveDate))];

  return <div className="question-bank-page">
    <section className="question-bank-intro"><div><p className="eyebrow">QUESTION BANK</p><h2>审核通过，沉淀为题库。</h2><p>按归档日期与知识库查找题目，随时查看题干、答案和来源。</p></div><div className="question-bank-count"><strong>{page?.total ?? '—'}</strong><span>道已归档题目</span></div></section>
    <form className="question-bank-filters" onSubmit={submitSearch}>
      <label>知识库<select value={knowledgeBaseId} onChange={event => { setKnowledgeBaseId(event.target.value); setPageIndex(0); }}><option value="">全部知识库</option>{bases.map(base => <option key={base.id} value={base.id}>{base.name}</option>)}<option value="unlinked">未关联知识库</option></select></label>
      <label>开始日期<input type="date" value={from} max={to || undefined} onChange={event => { setFrom(event.target.value); setPageIndex(0); }} /></label>
      <label>结束日期<input type="date" value={to} min={from || undefined} onChange={event => { setTo(event.target.value); setPageIndex(0); }} /></label>
      <label className="question-bank-search">查找题目<div><StudioIcon name="search" size={17} /><input type="search" value={query} onChange={event => setQuery(event.target.value)} placeholder="题干、答案、考点或来源" /></div></label>
      <button type="submit">查找</button>
    </form>
    {error && <p className="question-bank-error" role="alert">{error}</p>}
    {loading ? <div className="question-bank-empty" role="status">正在读取题库…</div> : !error && !items.length ? <div className="question-bank-empty"><StudioIcon name="file" size={30} /><h3>{page?.total === 0 && !knowledgeBaseId && !from && !to && !appliedQuery ? '还没有审核通过的题目' : '没有找到匹配的题目'}</h3><p>通过审核的题目会自动出现在这里。</p></div> : null}
    {!loading && !error && groupDates.map(date => {
      const dated = items.filter(item => item.archiveDate === date);
      const baseGroups = [...new Set(dated.map(item => item.knowledgeBaseId || 'unlinked'))];
      return <section className="question-bank-date" key={date}><div className="question-bank-date-heading"><h3>{date}</h3><span>{dated.length} 道题目</span></div>{baseGroups.map(base => {
        const grouped = dated.filter(item => (item.knowledgeBaseId || 'unlinked') === base);
        return <div className="question-bank-base-group" key={base}><h4>{grouped[0].knowledgeBaseName}<span>{grouped.length}</span></h4><div className="question-bank-items">{grouped.map(item => <QuestionCard key={`${item.sourceType}-${item.id}`} item={item} />)}</div></div>;
      })}</section>;
    })}
    {!loading && !error && page && page.total > 20 && <div className="question-bank-pagination"><span>第 {pageIndex * page.size + 1}–{Math.min((pageIndex + 1) * page.size, page.total)} 道，共 {page.total} 道</span><div><button className="secondary" disabled={pageIndex === 0} onClick={() => setPageIndex(value => value - 1)}>上一页</button><button className="secondary" disabled={!page.hasMore} onClick={() => setPageIndex(value => value + 1)}>下一页</button></div></div>}
  </div>;
}

function QuestionCard({ item }: { item: BankItem }) {
  const question = item.question;
  return <details className="question-bank-card"><summary><span className="question-bank-card-main"><span className="question-bank-card-meta"><b>{item.knowledgeBaseName}</b><span>{typeNames[item.questionType] || item.questionType || '题目'}</span><span>{difficultyNames[item.difficulty] || item.difficulty}</span></span><strong>{text(question.stem) || '未填写题干'}</strong><small>{item.sourceName} · 第 {item.sequence} 题</small></span><span className="question-bank-expand">查看详情</span></summary><div className="question-bank-detail">{text(question.options) && <div><b>选项</b><p>{text(question.options)}</p></div>}<div><b>参考答案</b><p>{text(question.answer) || '未填写'}</p></div>{text(question.analysis) && <div><b>解析</b><p>{text(question.analysis)}</p></div>}{text(question.assessmentPoint) && <div><b>考点</b><p>{text(question.assessmentPoint)}</p></div>}{text(question.scoringRubric) && <div><b>评分细则</b><p>{text(question.scoringRubric)}</p></div>}{text(question.sourceRef) && <div><b>资料来源</b><p>{text(question.sourceRef)}</p></div>}</div></details>;
}
