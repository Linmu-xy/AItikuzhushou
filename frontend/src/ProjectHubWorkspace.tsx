import { useEffect, useState } from 'react';
import { StudioIcon } from './StudioIcon';

type KnowledgeBase = { id: string; name: string };
type ProjectSummary = { id: string; name: string; mode: ProjectMode; status: string; variantCount: number; sourceCount: number; updatedAt: string };
export type ProjectMode = 'KNOWLEDGE_BASE' | 'CAREER' | 'STANDARD' | 'MATERIAL' | 'FUSION';

type Props = {
  auth: string;
  bases: KnowledgeBase[];
  currentBase?: KnowledgeBase;
  onStartProject: (mode: ProjectMode) => void;
  onOpenProject: (project: ProjectSummary) => void;
  onNavigate: (page: string) => void;
};

const modeName: Record<ProjectMode, string> = {
  KNOWLEDGE_BASE: '知识库出题', CAREER: '职业命题', STANDARD: '历史·标准驱动', MATERIAL: '历史·资料驱动', FUSION: '历史·融合驱动'
};

export function ProjectHubWorkspace({ auth, bases, currentBase, onStartProject, onOpenProject, onNavigate }: Props) {
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [visibleCount, setVisibleCount] = useState(8);

  useEffect(() => {
    let live = true;
    const headers = auth.startsWith('Basic ') ? { Authorization: auth } : undefined;
    void fetch('/api/exam-projects', { headers, credentials: 'same-origin', cache: 'no-store' })
      .then(async response => {
        if (!response.ok) throw new Error('项目列表暂时无法读取');
        return response.json() as Promise<ProjectSummary[]>;
      })
      .then(value => { if (live) { setProjects(value.filter(project => project.mode !== 'CAREER')); setError(''); } })
      .catch(caught => { if (live) setError(caught instanceof Error ? caught.message : '项目列表暂时无法读取'); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [auth]);

  const matchingProjects = projects.filter(project => project.name.toLocaleLowerCase().includes(query.toLocaleLowerCase())).sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime());

  return <div className="projects-page">
    <div className="projects-toolbar">
      <div><h2>每一次考核，从这里开始。</h2><p>把资料变成有价值的题目，从构思到交付，有序推进。</p></div>
      <button onClick={() => onStartProject('KNOWLEDGE_BASE')}><StudioIcon name="plus" size={18} />新建项目</button>
    </div>
    <div className="studio-project-overview"><div><strong>{loading ? '—' : projects.length}</strong><span>命题项目</span></div><div><strong>{bases.length}</strong><span>可用知识库</span></div><div><strong>{loading ? '—' : projects.reduce((sum, project) => sum + project.variantCount, 0)}</strong><span>已配置试卷</span></div><p>选择资料 <span>→</span> 生成题目 <span>→</span> 审核与交付</p></div>
    <div className="studio-list-toolbar"><h3>我的项目 <span>{projects.length}</span></h3><label className="studio-search"><StudioIcon name="search" size={17} /><input type="search" aria-label="搜索命题项目" placeholder="搜索项目…" value={query} onChange={event => { setQuery(event.target.value); setVisibleCount(8); }} /></label></div>
    {error ? <p className="projects-error" role="alert">{error}</p> : null}
    {loading && <p className="projects-loading" role="status">正在读取项目…</p>}
    {matchingProjects.length ? <div className="projects-list">{matchingProjects.slice(0, visibleCount).map(project =>
      <button key={project.id} onClick={() => onOpenProject(project)}>
        <span className="studio-project-icon"><StudioIcon name="file" size={23} /></span>
        <span className="projects-list-main"><b>{project.name}</b><small>{modeName[project.mode] || project.mode} · {project.sourceCount} 份资料 · {project.variantCount} 套</small></span>
        <span className="projects-list-date">{new Date(project.updatedAt).toLocaleDateString('zh-CN')}</span>
        <span className="projects-list-open" aria-hidden="true"><StudioIcon name="arrow" size={18} /></span>
      </button>)}
    </div> : !loading && !error && <div className="projects-empty"><StudioIcon name="file" size={32} /><h3>{query ? '没有找到相关项目' : '开启你的第一个命题项目'}</h3><p>{query ? '换个关键词再试试。' : '选择知识库资料，剩下的我们一起完成。'}</p>{!query && <button onClick={() => onStartProject('KNOWLEDGE_BASE')}>创建项目</button>}</div>}
    {matchingProjects.length > visibleCount && <button className="studio-load-more secondary" onClick={() => setVisibleCount(count => count + 8)}>查看更多项目（还有 {matchingProjects.length - visibleCount} 个）</button>}
    {!bases.length && <div className="projects-next-step"><span>还没有知识库</span><button className="secondary" onClick={() => onNavigate('知识库')}>先去创建知识库</button></div>}
    {bases.length > 0 && <p className="projects-base-note">当前知识库：{currentBase?.name || bases[0].name}</p>}
  </div>;
}
