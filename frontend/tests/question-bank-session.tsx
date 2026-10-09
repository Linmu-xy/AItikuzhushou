// Uses the real application and mock responses; this page never sends requests to the backend.
import { createRoot } from 'react-dom/client';
import App from '../src/App';
import '../src/studio.css';

const profile = { id: 'fixture-user', username: 'fixture_teacher', displayName: '验收教师', roles: [], status: 'ACTIVE', dailyQuota: 1000 };
let hasSession = true;
let unavailable = false;
const bank = { id: 'fixture-bank', name: 'ROS', description: '隔离样例', questionCount: 0, paperCount: 0 };
const content = { papers: [{ runId: 'fixture-run', variantNo: 1, title: 'ROS 入门试卷 · A', totalQuestionCount: 2,
  selectableQuestionCount: 2, wholePaperReady: true, questions: [1, 2].map(sequence => ({
    id: `fixture-question-${sequence}`, sourceType: 'PROJECT', sequence, questionType: 'SHORT_ANSWER', typeLabel: '简答题',
    title: 'ROS 入门试卷 · A', question: { stem: `第 ${sequence} 道验收题目` },
  })) }], standalone: [] };

window.fetch = async (input, init) => {
  const path = String(input).split('?')[0];
  if (path === '/api/health') return Response.json({ status: 'UP', models: { configured: true } });
  if (path === '/api/auth/login' && init?.method === 'POST') { hasSession = true; return Response.json(profile); }
  if (!hasSession) return Response.json({ statusCode: 'AUTHENTICATION_REQUIRED', message: '请先登录后继续操作' }, { status: 401 });
  if (path === '/api/auth/me') return Response.json(profile);
  if (path === '/api/model-quota') return Response.json({ accountId: profile.id, accountName: profile.username, dailyQuota: 1000, requests: 0, remaining: 1000 });
  if (path === '/api/auth/profile/summary') return Response.json({ knowledgeBases: 0, documents: 0, storageUsedBytes: 0, storageQuotaBytes: 1000, standards: 0, blueprints: 0, questionJobs: 0, runningQuestionJobs: 0, deliverableQuestionBanks: 0, assistantConversations: 0 });
  if (path === '/api/question-bank') return Response.json([bank]);
  if (path === '/api/question-bank/available/content') return unavailable
    ? Response.json({ statusCode: 'SERVICE_UNAVAILABLE', message: '候选内容服务暂时不可用，请重试' }, { status: 503 })
    : Response.json(content);
  if (!init?.method || init.method === 'GET') return Response.json([]);
  return Response.json({ message: '隔离验收禁止数据写入' }, { status: 403 });
};

function Preview() {
  return <><aside style={{ position: 'fixed', right: 12, bottom: 12, zIndex: 3000, display: 'flex', gap: 8, padding: 10, border: '1px solid #ddd', borderRadius: 8, background: '#fff', fontSize: 12 }}>
    <span>隔离验收（模拟数据）</span>
    <button type="button" onClick={() => { hasSession = false; }}>模拟会话失效</button>
    <button type="button" onClick={() => { unavailable = true; }}>模拟加载失败</button>
    <button type="button" onClick={() => { hasSession = true; unavailable = false; }}>恢复接口</button>
  </aside><App /></>;
}
createRoot(document.getElementById('root')!).render(<Preview />);
