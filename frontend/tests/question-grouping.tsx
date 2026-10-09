// Manual regression fixture: uses production components but never contacts an API.
import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import QuestionQualityWorkbench from '../src/QuestionQualityWorkbench';
import { GroupedGenerationQuestions } from '../src/GroupedGenerationQuestions';
import '../src/studio.css';

const slot = (id: string, sequenceNo: number, questionType: string, typeLabel: string, status: string, variantNo = 1) => ({
  id, sequenceNo, variantNo, variantLabel: variantNo === 1 ? 'A' : 'B', questionType, typeLabel, status,
  difficulty: 'EASY', points: 2, questionVersion: 1, review: {},
  question: ['PLANNED', 'GENERATING', 'FAILED'].includes(status) ? {} : {
    stem: `${typeLabel}验收样例：第 ${sequenceNo} 题。`, answer: questionType === 'SINGLE_CHOICE' ? 'B' : '样例答案',
    options: questionType === 'SINGLE_CHOICE' ? { A: '第一个选项', B: '第二个选项', C: '第三个选项', D: '第四个选项' } : undefined,
    analysis: '此页面只用于检查题型分组、固定题位和按 ID 审核，不连接真实题库。',
  },
});
let run = { id: 'fixture', status: 'PARTIAL', items: [
  slot('a-short', 6, 'SHORT_ANSWER', '简答题', 'REVIEW_REQUIRED'),
  slot('a-single-2', 2, 'SINGLE_CHOICE', '单选题', 'REVIEW_REQUIRED'),
  slot('a-fill', 5, 'FILL_BLANK', '填空题', 'PLANNED'),
  slot('a-single-1', 1, 'SINGLE_CHOICE', '单选题', 'APPROVED'),
  slot('a-multiple', 3, 'MULTIPLE_CHOICE', '多选题', 'GENERATING'),
  slot('a-true', 4, 'TRUE_FALSE', '判断题', 'REVIEW_REQUIRED'),
  slot('removed', 7, 'ESSAY', '论述题', 'REMOVED'),
  slot('b-short', 2, 'SHORT_ANSWER', '简答题', 'REVIEW_REQUIRED', 2),
  slot('b-single', 1, 'SINGLE_CHOICE', '单选题', 'REVIEW_REQUIRED', 2),
] };
const calls: string[] = [];
window.fetch = async (input, init) => {
  const path = String(input);
  if (!path.startsWith('/api/exam-projects/fixture/variant-generation-runs/fixture')) return Response.json({ message: '隔离样例禁止联网' }, { status: 403 });
  if ((!init?.method || init.method === 'GET') && path.endsWith('/quality'))
    return Response.json(run.items.map(item => ({ id: item.id, version: 1, issues: [], similar: [] })));
  const review = path.match(/\/items\/([^/]+)\/review$/);
  if (review && init?.method === 'PATCH') {
    const body = JSON.parse(String(init.body));
    if (body.decision !== 'APPROVE') return Response.json({ message: '验收样例仅支持通过' }, { status: 400 });
    calls.push(review[1]);
    run = { ...run, items: run.items.map(item => item.id === review[1] ? { ...item, status: 'APPROVED', questionVersion: 2 } : item) };
    return Response.json(run);
  }
  return Response.json({ message: '隔离样例不执行此操作' }, { status: 403 });
};

function Preview() {
  const [view, setView] = useState('review');
  const [current, setCurrent] = useState(run);
  return <main style={{ maxWidth: 1240, margin: '30px auto', padding: '0 20px' }}>
    <h1 style={{ fontSize: 22 }}>题型分组验收样例</h1>
    <p className="muted">隔离样例，不连接真实题库。包含乱序数据、两套试卷、等待题位和已删除题目。</p>
    <div style={{ display: 'flex', gap: 12, margin: '20px 0' }}>
      <button type="button" onClick={() => setView('review')}>审核台</button>
      <button type="button" className="secondary" onClick={() => setView('generation')}>生成过程</button>
    </div>
    {view === 'review' ? <QuestionQualityWorkbench auth="" projectId="fixture" run={current} onRefresh={async () => setCurrent(run)} />
      : <section className="panel"><GroupedGenerationQuestions items={current.items} /></section>}
    <p className="muted" aria-label="模拟提交记录">模拟提交 ID：{calls.join('、') || '无'}</p>
  </main>;
}
createRoot(document.getElementById('root')!).render(<Preview />);
