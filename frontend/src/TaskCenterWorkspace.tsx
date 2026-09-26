import { useEffect, useState } from 'react';
import type { ProjectMode } from './ProjectHubWorkspace';
import { StudioIcon } from './StudioIcon';

type Project = { id: string; name: string; mode: ProjectMode; status: string; variantCount: number; sourceCount: number; updatedAt: string };
type Run = { id: string; projectId: string; status: string; plannedCount: number; processedCount: number; reviewRequiredCount: number; failedCount: number; createdAt: string };
type LegacyJob = { id: string; type: string; status: string; progress: number; createdAt?: string; errorMessage?: string };
type Row = { id: string; project: Project; run: Run };

const statuses: Record<string, string> = {
  QUEUED: '排队中', RUNNING: '生成中', REVIEW_REQUIRED: '已生成', REVIEW_PENDING: '审题中',
  PARTIAL: '部分完成', FAILED: '需要处理', APPROVED: '已通过', SUCCEEDED: '已完成'
};

export function TaskCenterWorkspace({ auth, legacyJobs, onOpenProject }: {
  auth: string;
  legacyJobs: LegacyJob[];
  onOpenProject: (project: Project) => void;
}) {
  const [rows, setRows] = useState<Row[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let live = true;
    const headers = auth.startsWith('Basic ') ? { Authorization: auth } : undefined;
    const read = async <T,>(path: string): Promise<T> => {
      const response = await fetch(path, { headers, credentials: 'same-origin', cache: 'no-store' });
      if (!response.ok) throw new Error('任务暂时无法读取');
      return response.json() as Promise<T>;
    };
    const load = async () => {
      try {
        const [projects, runs] = await Promise.all([
          read<Project[]>('/api/exam-projects'),
          read<Run[]>('/api/exam-projects/generation-runs')
        ]);
        const byId = new Map(projects.map(project => [project.id, project]));
        if (live) { setRows(runs.flatMap(run => { const project = byId.get(run.projectId); return project ? [{ id: run.id, project, run }] : []; })); setError(''); }
      } catch (caught) { if (live) setError(caught instanceof Error ? caught.message : '任务暂时无法读取'); }
      finally { if (live) setLoading(false); }
    };
    void load();
    const timer = window.setInterval(() => void load(), 15000);
    return () => { live = false; window.clearInterval(timer); };
  }, [auth]);

  return <div className="task-center-page">
    <div className="studio-page-intro"><h2>进展，一目了然。</h2><p>关注生成进度，完成后进入项目审核与交付。</p></div>
    {loading && <p className="projects-loading" role="status">正在读取任务…</p>}
    {error && <p className="projects-error" role="alert">{error}</p>}
    {rows.length ? <div className="task-center-list">{rows.map(({ id, project, run }) => <button key={id} onClick={() => onOpenProject(project)}>
      <span className="studio-task-icon"><StudioIcon name={['RUNNING', 'QUEUED'].includes(run.status) ? 'clock' : 'file'} /></span><span className="studio-task-copy"><b>{project.name}</b><small>{new Date(run.createdAt).toLocaleString('zh-CN')} · {run.processedCount}/{run.plannedCount} 题</small><span className="studio-task-progress"><i style={{ width: `${run.plannedCount ? Math.min(100, run.processedCount / run.plannedCount * 100) : 0}%` }} /></span></span>
      <em className={`task-status-${run.status.toLowerCase()}`}>{statuses[run.status] || run.status}</em><StudioIcon name="arrow" size={17} />
    </button>)}</div> : !loading && !error && !legacyJobs.length && <div className="projects-empty"><h3>暂无出题任务</h3><p>在命题项目中生成题目后，进度会显示在这里。</p></div>}
    {legacyJobs.length > 0 && <details className="task-center-legacy"><summary>历史任务 <span>{legacyJobs.length} 条记录</span></summary><div className="task-center-list">{legacyJobs.map(job => <div key={job.id}><span><b>{job.type === 'BLUEPRINT' ? '细目表任务' : '题库任务'}</b><small>{job.createdAt ? new Date(job.createdAt).toLocaleString('zh-CN') : job.id}</small></span><em>{statuses[job.status] || job.status} {job.progress}%</em></div>)}</div></details>}
  </div>;
}
