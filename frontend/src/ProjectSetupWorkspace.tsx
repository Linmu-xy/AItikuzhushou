import { useEffect, useMemo, useState } from 'react';
import type { QuestionDraft } from './AssistantWorkspace';
import QuestionQualityWorkbench from './QuestionQualityWorkbench';

type Mode = 'KNOWLEDGE_BASE' | 'CAREER' | 'STANDARD' | 'MATERIAL' | 'FUSION';
type KnowledgeBase = { id: string; name: string };
type DocumentRow = { id: string; originalFilename: string; status: string; mediaType?: string; sizeBytes?: number };
type CadMaterial = { id: string; originalName: string; format: string; status: string; sizeBytes: number };
type ScoreRow = { type: string; count: number; points: number };
type ProjectSource = { id: string; sourceType: string; sourceId: string; sourceRole: string; name: string; status: string; enabled: boolean };
type Project = { id: string; name: string; mode: Mode; knowledgeBaseId: string; status: string; requirementText: string; variantCount: number; difficultyProfileJson: string; scoringStructureJson: string; sourceCount: number; authorizationConfirmed: boolean; webSearchEnabled: boolean; sources: ProjectSource[] };
type Preparation = { snapshotId: string; projectId: string; version: number; status: 'READY' | 'BLOCKED'; blockers: string[]; sources: { sourceId: string; sourceType: string; sourceRole: string; name: string; status: string; versionRef: string; profile?: Record<string, unknown>; usableFactCount?: number; usableAnnotationCount?: number }[]; factCount: number; profiles: Record<string, unknown>; snapshotHash: string; createdAt: string };
type VariantItem = { variantNo: number; variantLabel: string; sequenceNo: number; questionType: string; typeLabel: string; difficulty: 'EASY' | 'MEDIUM' | 'HARD'; points: number; status: string };
type AssessmentItem = { sequence: number; competency: string; task: string; type: string; difficulty: string; points: number; needsImage: boolean };
type VariantTask = { id: string; projectId: string; evidenceSnapshotId: string; status: 'READY' | 'SUPERSEDED'; variantCount: number; questionCountPerVariant: number; totalQuestionCount: number; createdAt: string; updatedAt: string; assessmentPlan?: { summary: string; items: AssessmentItem[]; webEvidence?: { query: string; summary: string; sources: { title: string; url: string }[]; searchedAt: string } }; items: VariantItem[] };
type GenerationItem = { id: string; variantItemId: string; variantNo: number; variantLabel: string; sequenceNo: number; questionType: string; typeLabel: string; difficulty: 'EASY' | 'MEDIUM' | 'HARD'; points: number; status: string; attempts: number; question: Record<string, unknown>; evidence: Record<string, unknown>; review: Record<string, unknown>; questionVersion?: number; reviewerId?: string; reviewComment?: string; reviewedAt?: string; errorCode?: string; errorMessage?: string; updatedAt: string };
type GenerationRun = { id: string; projectId: string; generationTaskId: string; evidenceSnapshotId: string; status: 'QUEUED' | 'RUNNING' | 'REVIEW_REQUIRED' | 'REVIEW_PENDING' | 'PARTIAL' | 'FAILED' | 'CANCELLED'; generationMode: 'FAST' | 'PROFESSIONAL_PRO'; plannedCount: number; processedCount: number; reviewRequiredCount: number; reviewPendingCount: number; failedCount: number; errorMessage?: string; createdAt: string; startedAt?: string; finishedAt?: string; updatedAt: string; items: GenerationItem[] };
type ExportReadiness = { runId: string; totalCount: number; approvedCount: number; failedCount: number; ready: boolean; blockers: string[] };
type ProjectExportArtifact = { id: string; exportRunId: string; outputType: string; filename: string; mediaType: string; sizeBytes: number; sha256: string; createdAt: string };
type ProjectExportRun = { id: string; projectId: string; generationRunId: string; evidenceSnapshotId: string; status: 'QUEUED' | 'RUNNING' | 'DOWNLOAD_READY' | 'FAILED' | 'CANCELLED'; outputTypesJson: string; requestedCount: number; completedCount: number; errorMessage?: string; createdAt: string; startedAt?: string; finishedAt?: string; updatedAt: string; artifacts: ProjectExportArtifact[] };

type Props = {
  auth: string;
  projectId?: string;
  initialMode: Mode;
  initialSeed?: QuestionDraft;
  defaultWebSearchEnabled?: boolean;
  initialBaseId?: string;
  initialSourceId?: string;
  bases: KnowledgeBase[];
  onNavigate: (page: string) => void;
  onMessage: (message: string) => void;
};

const modeOptions: { id: Mode; name: string; detail: string }[] = [
  { id: 'KNOWLEDGE_BASE', name: '知识库出题', detail: '从所选知识库文档生成题目' },
  { id: 'CAREER', name: '职业命题', detail: '从已确认的职业标准与选定考点生成题目' },
  { id: 'STANDARD', name: '标准驱动', detail: '职业标准与知识库为主' },
  { id: 'MATERIAL', name: '资料驱动', detail: '任务书、样题、模板与模型为主' },
  { id: 'FUSION', name: '融合驱动', detail: '标准能力 + 项目资料共同约束' },
];
const roleOptions = [
  ['STANDARD', '职业标准'], ['TASK_BOOK', '任务书'], ['SAMPLE', '样题'], ['TEMPLATE', '试题模板'], ['DRAWING', '工程图'], ['MODEL', '三维模型'], ['OTHER', '其他资料']
] as const;
const projectExportTypes = [
  { id: 'PAPER_XLSX', name: '试卷（Excel）', detail: '按变式卷分别成页，题型归组、难度由简单到困难' },
  { id: 'ANSWER_XLSX', name: '答案与评分细则（Excel）', detail: '答案、解析、评分细则和题目版本' },
  { id: 'APPROVAL_XLSX', name: '审批表（Excel）', detail: '审批栏和逐题审核记录' },
  { id: 'PAPER_DOCX', name: '试卷（Word）', detail: '适合继续编辑和套用学校打印设置的通用版式' },
  { id: 'ANSWER_DOCX', name: '答案与评分细则（Word）', detail: '保留题目、答案、解析和评分细则' },
  { id: 'APPROVAL_DOCX', name: '审批表（Word）', detail: '可编辑的审批信息和审核记录' },
  { id: 'PAPER_PDF', name: '试卷（PDF）', detail: '固定版式、可搜索中文文本，适合正式留档和打印' },
  { id: 'ANSWER_PDF', name: '答案与评分细则（PDF）', detail: '固定版式留存审核后的答案和评分依据' },
  { id: 'APPROVAL_PDF', name: '审批表（PDF）', detail: '固定版式留存审批信息和审核记录' },
  { id: 'ATTACHMENTS_ZIP', name: '图纸 / 附件压缩包', detail: '任务书、样题、模板、图纸和三维模型原文件' },
] as const;
const defaultScores: ScoreRow[] = [
  { type: '单选题', count: 10, points: 2 },
  { type: '多选题', count: 5, points: 4 },
  { type: '简答 / 案例题', count: 2, points: 10 },
];

async function request<T>(path: string, auth: string, options: RequestInit = {}) {
  const headers = new Headers(options.headers);
  if (auth.startsWith('Basic ')) headers.set('Authorization', auth);
  if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, { ...options, headers, credentials: 'same-origin', cache: 'no-store' });
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

async function requestBlob(path: string, auth: string) {
  const headers = new Headers();
  if (auth.startsWith('Basic ')) headers.set('Authorization', auth);
  const response = await fetch(path, { headers, credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) throw new Error(`HTTP_${response.status}：${await response.text() || '文件下载失败'}`);
  return response.blob();
}

function formatBytes(value = 0) { return value >= 1024 ** 2 ? `${(value / 1024 ** 2).toFixed(1)} MB` : `${Math.max(1, Math.ceil(value / 1024))} KB`; }
function modeName(mode: Mode) { return modeOptions.find(item => item.id === mode)?.name || mode; }
function difficultyName(value: VariantItem['difficulty']) { return value === 'EASY' ? '简单' : value === 'MEDIUM' ? '中等' : '困难'; }
function runStatusName(value: GenerationRun['status']) { return value === 'QUEUED' ? '排队中' : value === 'RUNNING' ? 'AI 生成中' : value === 'REVIEW_REQUIRED' ? '待人工审核' : value === 'REVIEW_PENDING' ? '等待审题服务' : value === 'PARTIAL' ? '部分完成' : value === 'FAILED' ? '生成失败' : '已取消'; }
function itemStatusName(value: string) { return value === 'REVIEW_REQUIRED' ? '待审核' : value === 'REVIEW_PENDING' ? '等待审题' : value === 'REJECTED' ? '质量未通过' : value === 'APPROVED' ? '已通过' : value; }
function exportStatusName(value: ProjectExportRun['status']) { return value === 'QUEUED' ? '排队中' : value === 'RUNNING' ? '正在打包' : value === 'DOWNLOAD_READY' ? '可下载' : value === 'FAILED' ? '导出失败' : '已取消'; }
function exportTypeName(value: string) { return projectExportTypes.find(item => item.id === value)?.name || value; }
function mapText(value: Record<string, unknown>, key: string) { return typeof value[key] === 'string' ? String(value[key]) : ''; }

function QuestionOptions({ text }: { text: string }) {
  if (!text.trim()) return null;
  const choices = text.split(/\s*\|\s*(?=[A-Z][.、．])/u);
  return <div className="generated-question-options">{choices.map((choice, index) => <p key={index}>{choice}</p>)}</div>;
}

export function ProjectSetupWorkspace({ auth, projectId, initialMode, initialSeed, defaultWebSearchEnabled, initialBaseId, initialSourceId, bases, onNavigate, onMessage }: Props) {
  const [mode, setMode] = useState<Mode>(initialMode);
  const [baseId, setBaseId] = useState(initialBaseId || bases[0]?.id || '');
  const [name, setName] = useState(initialMode === 'KNOWLEDGE_BASE' ? '新题库' : initialMode === 'CAREER' ? `${initialSeed?.requirement.match(/^职业：(.+)$/m)?.[1] || '职业'}命题` : `${modeName(initialMode)}命题项目`);
  const [requirementText, setRequirementText] = useState(initialSeed?.requirement || '');
  const [variantCount, setVariantCount] = useState(initialSeed?.setCount || 1);
  const [step, setStep] = useState(0);
  const [difficulty, setDifficulty] = useState(initialSeed?.difficulty === '简单' ? { easy: 60, medium: 35, hard: 5 } : initialSeed?.difficulty === '困难' ? { easy: 10, medium: 40, hard: 50 } : { easy: 30, medium: 50, hard: 20 });
  const [scores, setScores] = useState<ScoreRow[]>(initialSeed ? [{ type: initialSeed.questionType, count: initialSeed.questionCount, points: 2 }] : initialMode === 'KNOWLEDGE_BASE' ? [{ type: '智能题型', count: 10, points: 2 }] : initialMode === 'CAREER' ? [{ type: '单选题', count: 20, points: 2 }] : defaultScores);
  const [customSettings, setCustomSettings] = useState(!['KNOWLEDGE_BASE', 'CAREER'].includes(initialMode));
  const [generationMode, setGenerationMode] = useState<'FAST' | 'PROFESSIONAL_PRO'>('FAST');
  const [webSearchEnabled, setWebSearchEnabled] = useState(initialSeed?.webSearch ?? defaultWebSearchEnabled ?? initialMode === 'KNOWLEDGE_BASE');
  const [authorized, setAuthorized] = useState(false);
  const [documents, setDocuments] = useState<DocumentRow[]>([]);
  const [cadMaterials, setCadMaterials] = useState<CadMaterial[]>([]);
  const [selectedSources, setSelectedSources] = useState<Record<string, string>>(initialMode === 'CAREER' && initialSourceId ? { [`DOCUMENT:${initialSourceId}`]: 'STANDARD' } : {});
  const [project, setProject] = useState<Project>();
  const [preparation, setPreparation] = useState<Preparation>();
  const [variantTask, setVariantTask] = useState<VariantTask>();
  const [generationRun, setGenerationRun] = useState<GenerationRun>();
  const [exportReadiness, setExportReadiness] = useState<ExportReadiness>();
  const [projectExports, setProjectExports] = useState<ProjectExportRun[]>([]);
  const [selectedExportTypes, setSelectedExportTypes] = useState<string[]>(initialMode === 'KNOWLEDGE_BASE'
    ? ['PAPER_XLSX', 'ANSWER_XLSX', 'APPROVAL_XLSX']
    : ['PAPER_XLSX', 'ANSWER_XLSX', 'APPROVAL_XLSX', 'ATTACHMENTS_ZIP']);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const hasActiveExport = projectExports.some(item => ['QUEUED', 'RUNNING'].includes(item.status));

  const difficultyTotal = difficulty.easy + difficulty.medium + difficulty.hard;
  const selectedCount = Object.keys(selectedSources).length;

  useEffect(() => {
    if (!baseId) return;
    let live = true;
    setError('');
    void Promise.all([
      request<DocumentRow[]>(`/api/knowledge-bases/${baseId}/documents`, auth),
      request<CadMaterial[]>(`/api/cad-materials?knowledgeBaseId=${encodeURIComponent(baseId)}`, auth),
    ]).then(([docs, materials]) => {
      if (!live) return;
      setDocuments(docs);
      setCadMaterials(materials);
      if (!projectId && mode === 'KNOWLEDGE_BASE') {
        setSelectedSources(Object.fromEntries(docs.filter(doc => ['PARSED', 'PARSED_PARTIAL'].includes(doc.status))
          .map(doc => [`DOCUMENT:${doc.id}`, 'OTHER'])));
      }
    }).catch(caught => { if (live) setError(caught instanceof Error ? caught.message : '资料列表加载失败'); });
    return () => { live = false; };
  }, [auth, baseId, projectId]);

  useEffect(() => {
    if (!projectId) return;
    let live = true;
    setError('');
    void request<Project>(`/api/exam-projects/${projectId}`, auth).then(value => {
      if (!live) return;
      setProject(value);
      setMode(value.mode);
      setName(value.name);
      setBaseId(value.knowledgeBaseId || baseId);
      setVariantCount(value.variantCount);
      setAuthorized(value.authorizationConfirmed);
      setWebSearchEnabled(Boolean(value.webSearchEnabled));
      setRequirementText(value.requirementText || '');
      setCustomSettings(!['KNOWLEDGE_BASE', 'CAREER'].includes(value.mode));
      try {
        const parsed = JSON.parse(value.difficultyProfileJson || '{}') as Partial<{ easy: number; medium: number; hard: number }>;
        setDifficulty({ easy: Number(parsed.easy ?? 30), medium: Number(parsed.medium ?? 50), hard: Number(parsed.hard ?? 20) });
      } catch {
        setDifficulty({ easy: 30, medium: 50, hard: 20 });
      }
      try {
        const parsed = JSON.parse(value.scoringStructureJson || '[]') as ScoreRow[];
        setScores(parsed.length ? parsed.map(row => ({ type: row.type || '新题型', count: Number(row.count) || 1, points: Number(row.points) || 1 })) : defaultScores);
        if (parsed.length > 1 || (value.mode !== 'CAREER' && value.requirementText)) setCustomSettings(true);
      } catch {
        setScores(defaultScores);
      }
      setSelectedSources(Object.fromEntries(value.sources.filter(source => source.enabled).map(source => [`${source.sourceType}:${source.sourceId}`, source.sourceRole])));
      void Promise.all([
        request<Preparation | undefined>(`/api/exam-projects/${value.id}/generation-preparation`, auth),
        request<VariantTask[]>(`/api/exam-projects/${value.id}/variant-generation-tasks`, auth),
        request<GenerationRun[]>(`/api/exam-projects/${value.id}/variant-generation-runs`, auth),
      ]).then(([snapshot, tasks, runs]) => {
        if (!live) return;
        setPreparation(snapshot);
        const currentTask = snapshot ? tasks.find(task => task.status === 'READY' && task.evidenceSnapshotId === snapshot.snapshotId) : undefined;
        setVariantTask(currentTask);
        const currentRun = currentTask ? runs.find(run => run.generationTaskId === currentTask.id) : undefined;
        setGenerationRun(currentRun);
        if (currentRun) setGenerationMode(currentRun.generationMode);
        setStep(currentRun && !['QUEUED', 'RUNNING'].includes(currentRun.status) ? 2 : snapshot ? 1 : 0);
      }).catch(() => { if (live) { setPreparation(undefined); setVariantTask(undefined); } });
    }).catch(caught => { if (live) setError(caught instanceof Error ? caught.message : '项目配置加载失败'); });
    return () => { live = false; };
  }, [auth, projectId]);

  useEffect(() => {
    if (!project || !generationRun || !['QUEUED', 'RUNNING'].includes(generationRun.status)) return;
    let live = true;
    const timer = window.setInterval(() => {
      void request<GenerationRun>(`/api/exam-projects/${project.id}/variant-generation-runs/${generationRun.id}`, auth)
        .then(value => { if (live) setGenerationRun(value); })
        .catch(() => { /* The last durable status remains visible when polling is interrupted. */ });
    }, 1800);
    return () => { live = false; window.clearInterval(timer); };
  }, [auth, project?.id, generationRun?.id, generationRun?.status]);

  useEffect(() => {
    if (!project || !generationRun || ['QUEUED', 'RUNNING'].includes(generationRun.status)) {
      setExportReadiness(undefined);
      setProjectExports([]);
      return;
    }
    let live = true;
    void Promise.all([
      request<ExportReadiness>(`/api/exam-projects/${project.id}/variant-generation-runs/${generationRun.id}/export-readiness`, auth),
      request<ProjectExportRun[]>(`/api/exam-projects/${project.id}/variant-generation-runs/${generationRun.id}/exports`, auth),
    ]).then(([readiness, runs]) => {
      if (!live) return;
      setExportReadiness(readiness);
      setProjectExports(runs);
    }).catch(() => {
      if (!live) return;
      setExportReadiness(undefined);
      setProjectExports([]);
    });
    return () => { live = false; };
  }, [auth, project?.id, generationRun?.id, generationRun?.status, generationRun?.reviewRequiredCount, generationRun?.failedCount]);

  useEffect(() => {
    if (!project || !generationRun || !hasActiveExport) return;
    let live = true;
    const timer = window.setInterval(() => {
      void request<ProjectExportRun[]>(`/api/exam-projects/${project.id}/variant-generation-runs/${generationRun.id}/exports`, auth)
        .then(value => { if (live) setProjectExports(value); })
        .catch(() => { /* Keep the last durable export status visible during a transient poll failure. */ });
    }, 1800);
    return () => { live = false; window.clearInterval(timer); };
  }, [auth, project?.id, generationRun?.id, hasActiveExport]);

  const prepareGeneration = async () => {
    if (!project) return setError('请先保存项目配置');
    setBusy(true); setError('');
    try {
      const snapshot = await request<Preparation>(`/api/exam-projects/${project.id}/generation-preparation`, auth, { method: 'POST' });
      setPreparation(snapshot);
      setVariantTask(undefined);
      setGenerationRun(undefined);
      onMessage(snapshot.status === 'READY'
        ? `命题依据已冻结 V${snapshot.version}，${snapshot.factCount} 项模型/图纸事实已纳入快照。`
        : `命题依据检查完成，仍有 ${snapshot.blockers.length} 项问题需要处理。`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '命题依据检查失败');
    } finally { setBusy(false); }
  };

  const toggleSource = (type: 'DOCUMENT' | 'CAD_MATERIAL', id: string, checked: boolean) => {
    const key = `${type}:${id}`;
    setSelectedSources(current => {
      const next = { ...current };
      if (checked) next[key] = type === 'CAD_MATERIAL' ? 'MODEL' : mode === 'CAREER' ? 'STANDARD' : 'OTHER';
      else delete next[key];
      return next;
    });
  };

  const changeSourceRole = (type: 'DOCUMENT' | 'CAD_MATERIAL', id: string, role: string) => {
    setSelectedSources(current => ({ ...current, [`${type}:${id}`]: role }));
  };

  const save = async () => {
    if (!name.trim()) return setError('请填写项目名称');
    if (!baseId) return setError('请先选择知识库');
    if (!authorized) return setError('请确认已获得上传资料和样题的使用授权');
    if (difficultyTotal !== 100) return setError(`难度比例合计必须为 100%，当前为 ${difficultyTotal}%`);
    if (scores.some(row => !row.type.trim() || row.count < 1 || row.points <= 0)) return setError('请检查题型、题量和分值');
    setBusy(true); setError('');
    try {
      const body = JSON.stringify({ name: name.trim(), knowledgeBaseId: baseId, mode, requirementText, variantCount, difficultyProfile: difficulty, scoringStructure: scores, authorizationConfirmed: true, webSearchEnabled: mode === 'KNOWLEDGE_BASE' && webSearchEnabled });
      const saved = project
        ? await request<Project>(`/api/exam-projects/${project.id}`, auth, { method: 'PATCH', body })
        : await request<Project>('/api/exam-projects', auth, { method: 'POST', body });
      const sourceEntries = Object.entries(selectedSources);
      const desiredSources = new Map(sourceEntries);
      const removed = saved.sources.filter(source => {
        const key = `${source.sourceType}:${source.sourceId}`;
        return !desiredSources.has(key) || desiredSources.get(key) !== source.sourceRole;
      });
      const attached = await Promise.all(sourceEntries.map(([key, role]) => {
        const [sourceType, sourceId] = key.split(':');
        const type = sourceType === 'CAD_MATERIAL' ? 'CAD_MATERIAL' : 'DOCUMENT';
        return request<ProjectSource>(`/api/exam-projects/${saved.id}/sources`, auth, { method: 'POST', body: JSON.stringify({ sourceType: type, sourceId, sourceRole: role }) });
      }));
      await Promise.all(removed.map(source => request<void>(`/api/exam-projects/${saved.id}/sources/${source.id}`, auth, { method: 'DELETE' })));
      const refreshed = await request<Project>(`/api/exam-projects/${saved.id}`, auth);
      setProject(refreshed);
      setStep(1);
      setPreparation(undefined);
      setVariantTask(undefined);
      setGenerationRun(undefined);
      if (!project) setSelectedSources(current => ({ ...current }));
      onMessage(`项目已保存：${saved.name}${attached.length ? `，已关联 ${attached.length} 份资料` : ''}`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '项目保存失败');
    } finally { setBusy(false); }
  };

  const scoreUpdate = (index: number, field: keyof ScoreRow, value: string) => setScores(current => current.map((row, rowIndex) => rowIndex === index ? { ...row, [field]: field === 'type' ? value : Number(value) } : row));
  const createVariantPlan = async () => {
    if (!project) return setError('请先保存项目配置');
    if (!preparation || preparation.status !== 'READY') return setError('请先完成命题依据检查并获得 READY 快照');
    setBusy(true); setError('');
    try {
      const task = await request<VariantTask>(`/api/exam-projects/${project.id}/variant-generation-tasks`, auth, { method: 'POST' });
      setVariantTask(task);
      setGenerationRun(undefined);
      onMessage(`变式题位计划已创建：${task.variantCount} 套，共 ${task.totalQuestionCount} 个题位。`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '变式题位计划创建失败');
    } finally { setBusy(false); }
  };
  const startVariantGeneration = async () => {
    if (!project || !currentPlan) return setError('请先创建有效的变式题位计划');
    if (generationRun && ['QUEUED', 'RUNNING'].includes(generationRun.status)) return;
    setBusy(true); setError('');
    try {
      const run = await request<GenerationRun>(`/api/exam-projects/${project.id}/variant-generation-tasks/${currentPlan.id}/runs`, auth, {
        method: 'POST', body: JSON.stringify({ generationMode }),
      });
      setGenerationRun(run);
      onMessage(`已提交 ${run.plannedCount} 个题位的深度命题任务，生成完成后将进入人工审核。`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'AI 题目生成任务提交失败');
    } finally { setBusy(false); }
  };
  const currentPlan = variantTask && preparation?.snapshotId === variantTask.evidenceSnapshotId ? variantTask : undefined;
  const toggleExportType = (type: string) => setSelectedExportTypes(current => current.includes(type) ? current.filter(value => value !== type) : [...current, type]);
  const startProjectExport = async () => {
    if (!project || !generationRun) return;
    if (!exportReadiness?.ready) return setError(exportReadiness?.blockers.join('；') || '请先完成全部题目的人工审核');
    if (!selectedExportTypes.length) return setError('至少选择一种导出内容');
    setBusy(true); setError('');
    try {
      const created = await request<ProjectExportRun>(`/api/exam-projects/${project.id}/variant-generation-runs/${generationRun.id}/exports`, auth, {
        method: 'POST', body: JSON.stringify({ outputTypes: selectedExportTypes }),
      });
      setProjectExports(current => [created, ...current.filter(item => item.id !== created.id)]);
      onMessage(`导出任务已提交：${selectedExportTypes.map(exportTypeName).join('、')}。完成后可在此下载。`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '项目交付导出失败');
    } finally { setBusy(false); }
  };
  const downloadProjectArtifact = async (artifact: ProjectExportArtifact) => {
    if (!project || !generationRun) return;
    try {
      const blob = await requestBlob(`/api/exam-projects/${project.id}/variant-generation-runs/${generationRun.id}/exports/${artifact.exportRunId}/artifacts/${artifact.id}`, auth);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a'); link.href = url; link.download = artifact.filename; link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '项目交付文件下载失败');
    }
  };
  const planGroups = useMemo(() => {
    if (!currentPlan) return [] as { variantNo: number; variantLabel: string; items: VariantItem[] }[];
    const groups = new Map<number, { variantNo: number; variantLabel: string; items: VariantItem[] }>();
    currentPlan.items.forEach(item => {
      const group = groups.get(item.variantNo) || { variantNo: item.variantNo, variantLabel: item.variantLabel, items: [] };
      group.items.push(item);
      groups.set(item.variantNo, group);
    });
    return Array.from(groups.values()).sort((left, right) => left.variantNo - right.variantNo);
  }, [currentPlan]);
  const sourceButton = (type: 'DOCUMENT' | 'CAD_MATERIAL', id: string, nameValue: string, status: string, meta: string) => {
    const key = `${type}:${id}`;
    const checked = key in selectedSources;
    const unavailable = type === 'DOCUMENT' && ((mode === 'KNOWLEDGE_BASE' && !['PARSED', 'PARSED_PARTIAL'].includes(status)) || (mode === 'CAREER' && (status !== 'PARSED' || (initialSourceId ? id !== initialSourceId : !checked))));
    return <article className={`setup-source ${checked ? 'selected' : ''}`} key={key}><label className="setup-source-check"><input type="checkbox" checked={checked} disabled={unavailable && !checked} onChange={event => toggleSource(type, id, event.target.checked)} /><span><b>{nameValue}</b><small>{meta} · {unavailable ? '不可选' : status}</small></span></label>{checked && !['KNOWLEDGE_BASE', 'CAREER'].includes(mode) && <select aria-label={`${nameValue}资料角色`} value={selectedSources[key]} onChange={event => changeSourceRole(type, id, event.target.value)}>{roleOptions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>}</article>;
  };

  return <div className={`project-setup ${['KNOWLEDGE_BASE', 'CAREER'].includes(mode) ? 'knowledge-project' : ''} ${customSettings ? 'custom-settings' : 'simple-settings'}`} data-step={step}>
    <nav className="project-steps" aria-label="项目步骤">{['资料与配置', '生成题目', '质量工作台', '导出交付'].map((label, index) => <button key={label} className={step === index ? 'active' : ''} aria-current={step === index ? 'step' : undefined} onClick={() => setStep(index)}>{index + 1}<span>{label}</span></button>)}</nav>
    <section className="setup-intro"><div><h2>{mode === 'KNOWLEDGE_BASE' ? '知识库出题' : mode === 'CAREER' ? '职业命题' : '历史命题项目'}</h2><p className="muted">{mode === 'KNOWLEDGE_BASE' ? '选择知识库文档，确定题量和难度，生成后审核交付。' : mode === 'CAREER' ? '依据已确认的职业标准与选定考点命题，生成后仍需人工审核。' : '此项目沿用创建时的命题方式，原有资料与审核记录保持不变。'}</p></div><div className="setup-state"><span className={project ? 'saved' : ''} />{project ? `已保存 · ${project.sourceCount} 份资料` : '尚未保存'}</div></section>
    {error && <div className="setup-error" role="alert"><b>配置未保存</b><span>{error}</span><button className="secondary" onClick={() => setError('')}>知道了</button></div>}
    <section className="panel setup-basic"><div className="setup-section-head"><div><h3>项目设置</h3></div></div>{project && !['KNOWLEDGE_BASE', 'CAREER'].includes(mode) && <p className="muted">历史模式：{modeName(mode)}</p>}<div className="setup-form-grid"><label>项目名称<input value={name} onChange={event => setName(event.target.value)} placeholder="例如：数控车工综合实训考核" maxLength={180} /></label><label>知识库<select value={baseId} disabled={Boolean(project) || mode === 'CAREER'} onChange={event => setBaseId(event.target.value)}>{bases.map(base => <option key={base.id} value={base.id}>{base.name}</option>)}</select>{project && <small>项目保存后不能切换知识库，避免资料版本串库。</small>}</label><label>试卷套数<input type="number" min={1} max={20} value={variantCount} onChange={event => setVariantCount(Math.max(1, Math.min(20, Number(event.target.value))))} /></label></div></section>
    {!customSettings && <section className="panel setup-simple">
      <div className="setup-section-head"><h3>出题设置</h3><button className="secondary" onClick={() => setCustomSettings(true)}>自定义要求与参数</button></div>
      <div className="setup-simple-grid">
        <label>每套题量<input type="number" min={1} max={100} value={scores[0]?.count || 1} onChange={event => setScores(rows => [{ ...rows[0], count: Number(event.target.value) }])} /></label>
        <label>题型<select value={scores[0]?.type || '智能题型'} onChange={event => setScores(rows => [{ ...rows[0], type: event.target.value }])}>
          {mode === 'KNOWLEDGE_BASE' && <option>智能题型</option>}
          <option>单选题</option><option>多选题</option><option>判断题</option><option>简答题</option><option>案例题</option>
        </select></label>
        <label>难度<select disabled={mode === 'KNOWLEDGE_BASE' && scores[0]?.type === '智能题型'} value={difficulty.hard >= 50 ? 'HARD' : difficulty.easy >= 60 ? 'EASY' : 'MEDIUM'} onChange={event => setDifficulty(event.target.value === 'EASY' ? { easy: 60, medium: 35, hard: 5 } : event.target.value === 'HARD' ? { easy: 10, medium: 40, hard: 50 } : { easy: 30, medium: 50, hard: 20 })}><option value="EASY">偏基础</option><option value="MEDIUM">均衡</option><option value="HARD">偏进阶</option></select></label>
      </div>
      <p className="muted">{mode === 'KNOWLEDGE_BASE' && scores[0]?.type === '智能题型' ? 'AI 先阅读所选资料，规划考核能力，再决定题型、难度和每题分值；计划生成后可先预览。' : '资料依据不足的题目不会直接交付。需要混合题型、细分分值或补充要求时，使用自定义设置。'}</p>
      {requirementText && <details className="setup-simple-requirement"><summary>{mode === 'CAREER' ? '查看选定考点' : '查看当前出题要求'}</summary><p>{requirementText}</p></details>}
    </section>}
    <section className="setup-columns"><section className="panel setup-sources"><div className="setup-section-head"><div><h3>选择资料</h3></div><span>{selectedCount} 份已选</span></div><p className="muted">{mode === 'KNOWLEDGE_BASE' ? '至少选择一份已解析的文档。默认依据所选资料；开启联网搜索后，检索事实也可作为命题依据。' : mode === 'CAREER' ? '仅使用已确认的职业标准文档作为命题依据。' : '为每份资料标记角色，模型事实需先人工确认。'}</p><div className="setup-source-group"><h4>文档 <small>{documents.length} 份</small></h4>{documents.length ? documents.map(doc => sourceButton('DOCUMENT', doc.id, doc.originalFilename, doc.status, doc.mediaType || '文档')) : <p className="empty">暂无文档。先到知识库上传并解析资料。</p>}</div>{!['KNOWLEDGE_BASE', 'CAREER'].includes(mode) && <div className="setup-source-group"><h4>模型与工程图 <small>{cadMaterials.length} 份</small></h4>{cadMaterials.length ? cadMaterials.map(material => sourceButton('CAD_MATERIAL', material.id, material.originalName, material.status, `${material.format} · ${formatBytes(material.sizeBytes)}`)) : <p className="empty">当前知识库暂无模型或工程图。</p>}</div>}<div className="setup-source-links"><button className="secondary" onClick={() => onNavigate('知识库')}>管理知识库文档</button>{!['KNOWLEDGE_BASE', 'CAREER'].includes(mode) && <button className="secondary" onClick={() => onNavigate('资料组卷')}>管理模型图纸</button>}</div></section>
      <div className="setup-right-column"><section className="panel setup-requirements"><div className="setup-section-head"><div><h3>具体要求</h3></div><span>可选</span></div><textarea value={requirementText} onChange={event => setRequirementText(event.target.value)} rows={8} placeholder={mode === 'CAREER' ? '例如：重点考查设备操作中的异常判断与规范处置。' : mode === 'KNOWLEDGE_BASE' ? '例如：重点覆盖安全规范，避免仅考记忆性定义。' : '补充资料使用与命题要求…'} /><small>你的要求会与所选资料一起作为命题约束，不会替代原文依据。</small></section><section className="panel setup-difficulty"><div className="setup-section-head"><div><h3>每套卷的难度比例</h3></div><strong className={difficultyTotal === 100 ? 'valid' : 'invalid'}>{difficultyTotal}%</strong></div><div className="difficulty-fields"><label>简单<input type="number" min={0} max={100} value={difficulty.easy} onChange={event => setDifficulty(current => ({ ...current, easy: Number(event.target.value) }))} />%</label><label>中等<input type="number" min={0} max={100} value={difficulty.medium} onChange={event => setDifficulty(current => ({ ...current, medium: Number(event.target.value) }))} />%</label><label>困难<input type="number" min={0} max={100} value={difficulty.hard} onChange={event => setDifficulty(current => ({ ...current, hard: Number(event.target.value) }))} />%</label></div></section><section className="panel setup-generation-mode"><h3>生成方式</h3><label>模型工作强度<select value={generationMode} onChange={event => setGenerationMode(event.target.value as 'FAST' | 'PROFESSIONAL_PRO')}><option value="FAST">快速生成</option><option value="PROFESSIONAL_PRO">深度生成</option></select></label><small>深度生成需要管理员开启，会使用更多模型额度；两种方式都必须人工审核。</small></section></div>
    </section>
    {mode === 'KNOWLEDGE_BASE' && step < 2 && <section className="panel setup-web-search"><label><input type="checkbox" checked={webSearchEnabled} onChange={event => setWebSearchEnabled(event.target.checked)} />允许 AI 按需联网</label><p className="muted">知识库提供学科背景，AI 可自主设计迁移与应用题。在命题或审题需要核实事实时搜索公开专业主题，不向搜索工具发送资料全文。关闭后仍可使用稳定学科知识。联网可能增加费用与等待时间，题目须人工确认。</p></section>}
    <section className="panel setup-scoring"><div className="setup-section-head"><div><h3>题型与分值</h3></div><button className="secondary" onClick={() => setScores(current => [...current, { type: '新题型', count: 1, points: 5 }])}>添加题型</button></div><div className="setup-score-table"><div className="setup-score-head"><span>题型</span><span>题量</span><span>每题分值</span><span>小计</span><span /></div>{scores.map((row, index) => <div className="setup-score-row" key={`${index}-${row.type}`}><input aria-label="题型" value={row.type} onChange={event => scoreUpdate(index, 'type', event.target.value)} /><input aria-label="题量" type="number" min={1} value={row.count} onChange={event => scoreUpdate(index, 'count', event.target.value)} /><input aria-label="每题分值" type="number" min={1} value={row.points} onChange={event => scoreUpdate(index, 'points', event.target.value)} /><b>{row.count * row.points} 分</b><button className="secondary" onClick={() => setScores(current => current.filter((_, rowIndex) => rowIndex !== index))} disabled={scores.length <= 1}>删除</button></div>)}</div><p className="muted">当前总分：<b>{scores.reduce((sum, row) => sum + row.count * row.points, 0)} 分</b>。AI 不会擅自改变题量和分值，只会在后续阶段补充评分细则。</p></section>
    <section className="panel setup-preparation"><div className="setup-section-head"><div><h3>冻结本次命题依据</h3></div><span className={preparation?.status === 'READY' ? 'preparation-ready' : preparation ? 'preparation-blocked' : ''}>{preparation ? (preparation.status === 'READY' ? `已冻结 V${preparation.version}` : '存在待处理问题') : '尚未检查'}</span></div><p className="muted">{mode === 'KNOWLEDGE_BASE' ? '确认本次使用的资料版本与考核设置。知识库提供背景；需要时在命题和审题中联网核验，并保留内部记录。资料或设置改变后需重新检查。' : '冻结后，后续变式生成只使用这次确认的资料版本、样题结构、模板结构和模型/图纸事实。资料或配置变更后需要重新冻结。'}</p>{preparation && <div className="preparation-summary"><div><b>{preparation.sources.length}</b><span>份来源</span></div><div><b>{preparation.factCount}</b><span>项已确认事实</span></div><div><b>{preparation.sources.filter(source => source.sourceRole === 'SAMPLE').length}</b><span>份样题</span></div><div><b>{preparation.sources.filter(source => source.sourceRole === 'TEMPLATE').length}</b><span>份模板</span></div></div>}{preparation?.blockers.length ? <div className="preparation-blockers"><b>需要先处理</b><ul>{preparation.blockers.map(item => <li key={item}>{item}</li>)}</ul></div> : preparation ? <p className="preparation-success">{mode === 'KNOWLEDGE_BASE' ? '文档解析状态与资料授权检查通过，可以创建题位计划。' : '资料角色、解析状态、人工授权和模型事实检查通过，可以进入下一阶段的变式生成任务。'}</p> : null}<div className="preparation-actions"><button className="secondary" onClick={() => void prepareGeneration()} disabled={busy || !project}>{busy ? '正在检查…' : preparation?.status === 'READY' ? '重新冻结命题依据' : '检查并冻结命题依据'}</button><button onClick={() => void createVariantPlan()} disabled={busy || preparation?.status !== 'READY'}>{busy ? '正在生成计划…' : currentPlan ? '重新查看变式题位计划' : '创建变式题位计划'}</button></div>{currentPlan && <div className="variant-plan"><div className="variant-plan-header"><div><b>变式题位计划</b><span>依据快照 V{preparation?.version} · 按题型归组，难度由简单到困难</span></div><strong>{currentPlan.totalQuestionCount} 个题位</strong></div><div className="variant-plan-list">{planGroups.map(group => <details key={group.variantNo} open={group.variantNo === 1}><summary><span>第 {group.variantLabel} 套</span><em>{group.items.length} 题 · {group.items.reduce((sum, item) => sum + item.points, 0)} 分</em></summary><div className="variant-plan-rows"><div className="variant-plan-row variant-plan-row-head"><span>题号</span><span>题型</span><span>难度</span><span>分值</span></div>{group.items.map(item => <div className="variant-plan-row" key={`${item.variantNo}-${item.sequenceNo}`}><span>{item.sequenceNo}</span><b>{item.typeLabel}</b><span className={`variant-plan-badge ${item.difficulty.toLowerCase()}`}>{difficultyName(item.difficulty)}</span><span>{item.points} 分</span></div>)}</div></details>)}</div></div>}{currentPlan && <div className="generation-run-panel"><div className="generation-run-head"><div><b>AI 生成题目</b><span>{mode === 'KNOWLEDGE_BASE' ? '自主命题、独立解题与质量审查；必要时修订一次，完成后仍需人工审核。' : '深度命题将逐题经过结构门禁和独立职业审题，完成后仍需教师人工审核。'}</span></div>{generationRun && <em className={`generation-run-status ${generationRun.status.toLowerCase()}`}>{runStatusName(generationRun.status)}</em>}</div>{generationRun ? <><div className="generation-run-summary"><div><b>{generationRun.processedCount} / {generationRun.plannedCount}</b><span>已处理题位</span></div><div><b>{generationRun.reviewRequiredCount}</b><span>待人工审核</span></div><div><b>{generationRun.reviewPendingCount}</b><span>等待审题服务</span></div><div><b>{generationRun.failedCount}</b><span>未通过题位</span></div></div>{generationRun.errorMessage && <p className="generation-run-error">{generationRun.errorMessage}</p>}{generationRun.items.filter(item => ['FAILED', 'REJECTED'].includes(item.status) && item.errorMessage).slice(0, 3).map(item => <p className="generation-run-error" role="alert" key={item.id}>第 {item.variantLabel} 套第 {item.sequenceNo} 题：{item.errorMessage}</p>)}<button onClick={() => void startVariantGeneration()} disabled={busy || ['QUEUED', 'RUNNING'].includes(generationRun.status)}>{busy ? '正在提交…' : ['QUEUED', 'RUNNING'].includes(generationRun.status) ? 'AI 正在生成…' : '重新生成一批题目'}</button><div className="generated-question-list">{generationRun.items.filter(item => mapText(item.question, 'stem')).map(item => <article className="generated-question" key={item.id}><div className="generated-question-head"><span>第 {item.variantLabel} 套 · {item.sequenceNo} 题 · {item.typeLabel}</span><em className={`generation-item-status ${item.status.toLowerCase()}`}>{item.status === 'REVIEW_REQUIRED' ? '待审核' : item.status === 'REVIEW_PENDING' ? '等待审题' : item.status === 'REJECTED' ? '质量未通过' : item.status}</em></div><h4>{mapText(item.question, 'stem')}</h4><QuestionOptions text={mapText(item.question, 'options')} /><p className="generated-question-answer"><b>答案：</b>{mapText(item.question, 'answer')} · <b>{item.points} 分</b></p><p className="generated-question-analysis">{mapText(item.question, 'analysis')}</p>{mapText(item.review, 'feedback') && <details><summary>审题反馈</summary><p className="generated-question-review">{mapText(item.review, 'feedback')}</p></details>}</article>)}</div></> : <><p className="muted">题位计划已准备好。点击后才会调用模型，生成结果将全部进入人工审核队列。</p><button onClick={() => void startVariantGeneration()} disabled={busy}>{busy ? '正在提交…' : '开始 AI 生成题目'}</button></>}</div>}{!project && <small>请先创建项目，系统才能生成依据快照。</small>}</section>
    {step === 1 && currentPlan?.assessmentPlan && <section className="panel assessment-plan-preview">
      <div className="setup-section-head"><h3>本次考核设计</h3><span>{currentPlan.questionCountPerVariant} 个任务</span></div>
      <p>{currentPlan.assessmentPlan.summary}</p>
      {currentPlan.assessmentPlan.webEvidence && <details className="assessment-web-evidence"><summary>本次联网命题依据 · {currentPlan.assessmentPlan.webEvidence.sources.length} 个网页</summary><p>检索主题：{currentPlan.assessmentPlan.webEvidence.query} · {new Date(currentPlan.assessmentPlan.webEvidence.searchedAt).toLocaleString('zh-CN')}</p><p>{currentPlan.assessmentPlan.webEvidence.summary}</p><div>{currentPlan.assessmentPlan.webEvidence.sources.map(source => <a href={source.url} key={source.url} target="_blank" rel="noopener noreferrer">{source.title}</a>)}</div><small>网页版本和适用范围可能变化；生成题目后请结合链接复核答案。</small></details>}
      <div className="assessment-plan-items">{currentPlan.assessmentPlan.items.map(item => <article key={item.sequence}>
        <span>{item.sequence}</span><div><b>{item.competency}</b><p>{item.task}</p></div>
        <small>{currentPlan.items.find(slot => slot.sequenceNo === item.sequence)?.typeLabel || item.type} · {item.points} 分{item.needsImage ? ' · 配图' : ''}</small>
      </article>)}</div>
    </section>}
    {step === 2 && !generationRun && <section className="panel project-step-empty"><h3>还没有可审核的题目</h3><p>先完成资料检查并生成题目。</p><button className="secondary" onClick={() => setStep(1)}>前往生成</button></section>}
    {step === 2 && project && generationRun && <QuestionQualityWorkbench key={generationRun.id} auth={auth} projectId={project.id} run={generationRun} onRefresh={async () => { const refreshed = await request<GenerationRun>(`/api/exam-projects/${project.id}/variant-generation-runs/${generationRun.id}`, auth); setGenerationRun(refreshed); }} />}
    {step === 3 && !generationRun && <section className="panel project-step-empty"><h3>暂无可导出的题目</h3><p>生成并审核题目后，导出选项会显示在这里。</p><button className="secondary" onClick={() => setStep(1)}>前往生成</button></section>}
    {currentPlan && generationRun && <section className="panel project-export-panel"><div className="setup-section-head"><div><p className="section-kicker">09 · 审核后交付</p><h3>选择要导出的交付内容</h3></div><strong className={exportReadiness?.ready ? 'valid' : 'invalid'}>{exportReadiness?.ready ? '服务端已解锁导出' : '审核未完成'}</strong></div><p className="muted">全部题目通过审核后才能导出。完成后可在下方下载文件。</p>{exportReadiness?.blockers.length ? <div className="export-readiness"><b>暂不能导出</b><ul>{exportReadiness.blockers.map(blocker => <li key={blocker}>{blocker}</li>)}</ul></div> : exportReadiness?.ready && <><div className="project-export-options">{projectExportTypes.map(item => <label className={`project-export-option ${selectedExportTypes.includes(item.id) ? 'selected' : ''}`} key={item.id}><input type="checkbox" checked={selectedExportTypes.includes(item.id)} onChange={() => toggleExportType(item.id)} /><span><b>{item.name}</b><small>{item.detail}</small></span></label>)}</div><button onClick={() => void startProjectExport()} disabled={busy || !selectedExportTypes.length}>{busy ? '正在提交导出…' : '生成选中的交付文件'}</button></>}{projectExports.length > 0 && <div className="project-export-history"><div className="project-export-history-head"><b>导出记录</b><span>同一生成批次可保留多次导出记录</span></div>{projectExports.slice(0, 5).map(run => <article className="project-export-run" key={run.id}><div><b>{exportStatusName(run.status)}</b><span>{new Date(run.createdAt).toLocaleString()} · {run.completedCount}/{run.requestedCount} 项</span>{run.errorMessage && <small className="generation-run-error">{run.errorMessage}</small>}</div><div className="project-export-artifacts">{run.artifacts.map(artifact => <button className="secondary" key={artifact.id} disabled={run.status !== 'DOWNLOAD_READY'} onClick={() => void downloadProjectArtifact(artifact)}>{exportTypeName(artifact.outputType)} · 下载</button>)}</div></article>)}</div>}</section>}
    <section className="setup-footer"><label className="setup-authorization"><input type="checkbox" checked={authorized} onChange={event => setAuthorized(event.target.checked)} /><span><b>我确认拥有所选资料的使用授权</b><small>系统会记录本次确认和资料版本。</small></span></label><div className="setup-actions"><button className="secondary" onClick={() => onNavigate(mode === 'CAREER' ? '职业标准' : '总览')}>{mode === 'CAREER' ? '返回职业解析' : '返回项目列表'}</button>{project && !['KNOWLEDGE_BASE', 'CAREER'].includes(mode) && <button className="secondary" onClick={() => onNavigate(mode === 'STANDARD' ? '职业标准' : '资料组卷')}>进入历史资料准备</button>}<button onClick={() => void save()} disabled={busy}>{busy ? '正在保存…' : project ? '保存并继续' : '创建并继续'}</button></div></section>
  </div>;
}
