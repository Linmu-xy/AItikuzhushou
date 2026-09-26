import { FormEvent, RefObject, useEffect, useRef, useState } from 'react';
import { PanelToggleIcon } from './PanelToggleIcon';
import { KnowledgeGraphic, StudioIcon } from './StudioIcon';

export type AssistantMode = 'CHAT' | 'KNOWLEDGE_BASE' | 'OCCUPATIONAL_STANDARD';
export type AssistantConversation = { id: string; knowledgeBaseId?: string; mode: AssistantMode; title: string; updatedAt: string };
export type AssistantCitation = { evidenceId?: string; sourceRef: string; chunkId: string; score: number; excerpt: string; sourceType?: string; field?: string };
export type AssistantAnswerSegment = { text: string; sources: string[] };
export type AssistantAnswerBlock = { text: string; evidenceType: 'DIRECT_STANDARD' | 'STANDARD_INFERENCE' | 'DIRECT_SOURCE' | 'SOURCE_INFERENCE' | 'NOT_COVERED' | 'GENERAL_SUPPLEMENT' | 'WEB_SUPPLEMENT' | 'WEB_UNAVAILABLE'; sources: string[]; segments?: AssistantAnswerSegment[] };
export type AssistantMessage = { id: string; role: 'USER' | 'ASSISTANT'; content: string; citations: AssistantCitation[]; genericAnswer: boolean; pendingAction: Record<string, unknown>; answerBlocks?: AssistantAnswerBlock[]; statusCode?: string; createdAt: string };
export type QuestionDraft = { requirement: string; questionCount: number; questionType: string; difficulty: string; setCount: number; webSearch?: boolean };
export type AssistantAttachment = { id: string; originalName: string; sizeBytes: number; truncated: boolean };
export type AssistantEffort = 'FAST' | 'STANDARD' | 'DEEP';

type Props = {
  conversations: AssistantConversation[];
  activeConversationId: string;
  knowledgeBaseName?: string;
  knowledgeBases: { id: string; name: string }[];
  selectedKnowledgeBaseId: string;
  documentCount: number;
  messages: AssistantMessage[];
  attachments: AssistantAttachment[];
  input: string;
  effort: AssistantEffort;
  webSearch: boolean;
  busy: boolean;
  sending: boolean;
  uploading: boolean;
  messagesRef: RefObject<HTMLDivElement | null>;
  onCreate: () => void;
  onSelectConversation: (conversation: AssistantConversation) => void;
  onDelete: (id: string) => void;
  onSelectKnowledgeBase: (id: string) => void;
  onOpenKnowledgeBase: () => void;
  onStartKnowledgeBaseProject: (draft?: QuestionDraft) => void;
  onInputChange: (value: string) => void;
  onEffortChange: (value: AssistantEffort) => void;
  onWebSearchChange: (value: boolean) => void;
  onUpload: (file: File, target: 'CONVERSATION' | 'KNOWLEDGE_BASE') => void;
  onRemoveAttachment: (id: string) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onSuggestedPrompt: (prompt: string) => void;
  onAction: (action: Record<string, unknown>) => void;
};

function draftFromPrompt(prompt: string): QuestionDraft {
  const count = Number(prompt.match(/(\d{1,3})\s*(?:道|题)/)?.[1] || 20);
  const sets = Number(prompt.match(/(\d{1,2})\s*套/)?.[1] || 1);
  const questionType = prompt.includes('多选') ? '多选题' : prompt.includes('判断') ? '判断题' : prompt.includes('简答') ? '简答题' : prompt.includes('案例') ? '案例题' : '单选题';
  const difficulty = prompt.includes('困难') || prompt.includes('难题') ? '困难' : prompt.includes('简单') || prompt.includes('基础') ? '简单' : '中等';
  return { requirement: prompt, questionCount: Math.max(1, Math.min(100, count)), questionType, difficulty, setCount: Math.max(1, Math.min(20, sets)) };
}

function isQuestionRequest(prompt: string): boolean {
  if (/^(如何|怎么|怎样|为什么|什么是|请解释|介绍)/.test(prompt)) return false;
  return /(出\s*\d*\s*道?题|生成.{0,30}(?:题|试卷)|组卷|创建题库)/.test(prompt);
}

export function AssistantWorkspace(props: Props) {
  const [historyCollapsed, setHistoryCollapsed] = useState(true);
  const [uploadMenuOpen, setUploadMenuOpen] = useState(false);
  const [waitingLong, setWaitingLong] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const uploadMenu = useRef<HTMLDivElement>(null);
  const uploadTarget = useRef<'CONVERSATION' | 'KNOWLEDGE_BASE'>('CONVERSATION');
  const [draft, setDraft] = useState<QuestionDraft>();
  const [source, setSource] = useState<{ messageId: string; citation: AssistantCitation }>();
  const conversations = props.conversations.filter(item => item.mode === 'KNOWLEDGE_BASE' && item.knowledgeBaseId === props.selectedKnowledgeBaseId);
  const hasSource = Boolean(props.selectedKnowledgeBaseId && props.documentCount > 0);
  useEffect(() => {
    if (historyCollapsed) return;
    const closeHistory = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setHistoryCollapsed(true);
        document.querySelector<HTMLButtonElement>('.assistant-history-open')?.focus();
      }
    };
    document.addEventListener('keydown', closeHistory);
    return () => document.removeEventListener('keydown', closeHistory);
  }, [historyCollapsed]);
  useEffect(() => {
    if (!uploadMenuOpen) return;
    uploadMenu.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus();
    const dismiss = (event: PointerEvent) => {
      if (!uploadMenu.current?.contains(event.target as Node)) setUploadMenuOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setUploadMenuOpen(false);
        uploadMenu.current?.querySelector<HTMLButtonElement>('button')?.focus();
      }
      if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key) && uploadMenu.current?.contains(document.activeElement)) {
        const items = Array.from(uploadMenu.current.querySelectorAll<HTMLButtonElement>('[role="menuitem"]'));
        if (!items.length) return;
        event.preventDefault();
        const current = items.indexOf(document.activeElement as HTMLButtonElement);
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (current + (event.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length;
        items[next]?.focus();
      }
    };
    document.addEventListener('pointerdown', dismiss);
    document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('pointerdown', dismiss); document.removeEventListener('keydown', escape); };
  }, [uploadMenuOpen]);
  useEffect(() => {
    setWaitingLong(false);
    if (!props.sending) return;
    const timer = window.setTimeout(() => setWaitingLong(true), 7000);
    return () => window.clearTimeout(timer);
  }, [props.sending]);
  const chooseUpload = (target: 'CONVERSATION' | 'KNOWLEDGE_BASE') => {
    uploadTarget.current = target;
    setUploadMenuOpen(false);
    if (fileInput.current) fileInput.current.accept = target === 'CONVERSATION' ? '.pdf,.doc,.docx,.txt,.md' : '.pdf,.doc,.docx,.ppt,.pptx,image/jpeg,image/png,image/gif,image/webp';
    fileInput.current?.click();
  };

  const submit = (event: FormEvent<HTMLFormElement>) => {
    const prompt = props.input.trim();
    if (isQuestionRequest(prompt)) {
      event.preventDefault();
      setDraft({ ...draftFromPrompt(prompt), webSearch: props.webSearch });
      props.onInputChange('');
      return;
    }
    props.onSubmit(event);
  };

  return <section className={`assistant-page assistant-experience assistant-v2 studio-assistant ${!props.messages.length && !props.sending && !draft ? 'is-empty' : ''} ${historyCollapsed ? 'history-collapsed' : ''}`}>
    {!historyCollapsed && <button className="assistant-history-backdrop" type="button" aria-label="关闭会话列表" onClick={() => setHistoryCollapsed(true)} />}
    <aside className="assistant-conversations assistant-history" aria-label="最近会话">
      <div className="assistant-history-heading"><h2>最近会话</h2><button onClick={() => setHistoryCollapsed(true)} aria-label="收起会话列表" title="收起会话列表"><PanelToggleIcon /></button></div>
      <button className="assistant-new" onClick={props.onCreate} disabled={props.busy || props.sending || !props.selectedKnowledgeBaseId}><StudioIcon name="plus" size={17} />新建对话</button>
      <div className="assistant-conversation-list">{conversations.length ? conversations.map(item => <div className={item.id === props.activeConversationId ? 'assistant-conversation active' : 'assistant-conversation'} key={item.id}>
        <button disabled={props.sending} onClick={() => { props.onSelectConversation(item); setHistoryCollapsed(true); }}><b>{item.title}</b><small>{new Date(item.updatedAt).toLocaleDateString('zh-CN')}</small></button>
        <button className="assistant-delete" aria-label={`删除会话：${item.title}`} title="删除会话" onClick={() => props.onDelete(item.id)}>×</button>
      </div>) : <p className="assistant-history-empty">还没有会话</p>}</div>
    </aside>

    <section className="assistant-chat assistant-stage">
      <header className="assistant-stage-head">
        <div className="assistant-stage-tools"><button className="assistant-history-open" onClick={() => setHistoryCollapsed(value => !value)} aria-label={historyCollapsed ? '展开会话列表' : '收起会话列表'} title="对话历史"><StudioIcon name="clock" size={18} /><span>对话历史</span></button><button className="studio-new-chat" onClick={props.onCreate} disabled={props.busy || props.sending || !props.selectedKnowledgeBaseId} title="新建对话" aria-label="新建对话"><StudioIcon name="plus" size={18} /></button></div>
        {props.knowledgeBases.length ? <label className="assistant-base-select"><span>知识库</span><select aria-label="知识库" value={props.selectedKnowledgeBaseId} onChange={event => props.onSelectKnowledgeBase(event.target.value)}>{props.knowledgeBases.map(base => <option key={base.id} value={base.id}>{base.name}</option>)}</select></label> : <button className="secondary" onClick={props.onOpenKnowledgeBase}>创建知识库</button>}
      </header>

      <div ref={props.messagesRef} className="assistant-messages" aria-live="polite">{props.messages.length ? props.messages.map((item, index) => <article className={`assistant-message ${item.role === 'USER' ? 'user' : 'ai'}${index === props.messages.length - 1 && item.role === 'ASSISTANT' ? ' latest' : ''}`} key={item.id}>
        <header><b>{item.role === 'USER' ? '你' : '助手'}</b></header>
        {item.answerBlocks?.length ? <div className="assistant-answer-blocks">{item.answerBlocks.map((block, index) => {
          const webSources = block.evidenceType === 'WEB_SUPPLEMENT' ? block.sources.map(code => item.citations?.find(citation => citation.evidenceId === code && citation.sourceType === 'WEB')).filter((citation): citation is AssistantCitation => Boolean(citation)) : [];
          return <div key={`${item.id}-${index}`} className={block.evidenceType === 'WEB_SUPPLEMENT' ? 'assistant-web-answer' : block.evidenceType === 'WEB_UNAVAILABLE' ? 'assistant-web-unavailable' : ''}>
            {block.evidenceType === 'WEB_SUPPLEMENT' && <strong className="assistant-web-heading">联网补充</strong>}
            <p className="assistant-answer-line">{(block.segments?.length ? block.segments : [{ text: block.text, sources: block.sources }]).map((segment, segmentIndex) => <span key={segmentIndex}>{segment.text}{block.evidenceType !== 'WEB_SUPPLEMENT' && segment.sources.map(code => { const citation = item.citations?.find(c => c.evidenceId === code); return citation ? <button className="assistant-inline-citation" key={code} onClick={() => setSource({ messageId: item.id, citation })} aria-label={`查看资料来源 ${code}`}>[{code}]</button> : null; })}</span>)}</p>
            {webSources.length > 0 && <div className="assistant-web-sources"><span>检索到的网页 · 请自行核对</span>{webSources.map(citation => /^https?:\/\//i.test(citation.chunkId) ? <a key={citation.evidenceId} href={citation.chunkId} target="_blank" rel="noopener noreferrer">{citation.sourceRef}</a> : null)}</div>}
          </div>;
        })}</div> : <p>{item.content}</p>}
        {source?.messageId === item.id && <aside className="assistant-inline-source-card"><div><b>{source.citation.sourceRef}</b><button onClick={() => setSource(undefined)} aria-label="关闭资料来源">×</button></div><p>{source.citation.excerpt}</p></aside>}
        {item.role === 'ASSISTANT' && item.statusCode && !['OK', 'MODEL_UNAVAILABLE', 'WEB_SEARCH_UNAVAILABLE'].includes(item.statusCode) && <p className="assistant-error">这条回答未能正常完成，请重新尝试。</p>}
        {Boolean(item.pendingAction?.type) && <div className="assistant-action"><span>{String(item.pendingAction.label || '需要确认')}</span><button className="secondary" onClick={() => props.onAction(item.pendingAction)}>确认</button></div>}
      </article>) : !props.sending && !draft ? <div className="assistant-welcome"><KnowledgeGraphic /><h2>把知识，转化为好问题。</h2><p>{hasSource ? `从「${props.knowledgeBaseName}」出发，一起探索知识、构思考核。` : '上传一份资料，让下一次命题有个好开始。'}</p><div className="assistant-welcome-actions">{hasSource ? <><button onClick={() => props.onStartKnowledgeBaseProject()}><StudioIcon name="spark" size={17} />创建一组题目<StudioIcon name="arrow" size={16} /></button><button onClick={() => props.onSuggestedPrompt('请梳理当前知识库中的核心知识与适合考核的能力。')}><StudioIcon name="book" size={17} />梳理核心知识<StudioIcon name="arrow" size={16} /></button></> : <button onClick={props.onOpenKnowledgeBase}><StudioIcon name="plus" size={17} />添加知识库资料<StudioIcon name="arrow" size={16} /></button>}</div></div> : null}{props.sending && <div className="assistant-thinking" role="status" aria-live="polite"><span className="assistant-thinking-dots" aria-hidden="true"><i /><i /><i /></span><span>{waitingLong ? '还在整理回答，请稍候…' : props.webSearch ? '正在检索知识库与网页…' : '正在阅读资料，整理思路…'}</span></div>}</div>

      {draft && <section className="assistant-draft" aria-label="确认出题设置"><div className="assistant-draft-heading"><div><h3>确认出题设置</h3><p>先进入项目选择资料，确认后才会调用模型生成。</p></div><button onClick={() => setDraft(undefined)} aria-label="关闭出题设置">×</button></div><div className="assistant-draft-fields"><label>题量<input type="number" min={1} max={100} value={draft.questionCount} onChange={event => setDraft(value => value && ({ ...value, questionCount: Number(event.target.value) }))} /></label><label>题型<select value={draft.questionType} onChange={event => setDraft(value => value && ({ ...value, questionType: event.target.value }))}><option>单选题</option><option>多选题</option><option>判断题</option><option>简答题</option><option>案例题</option></select></label><label>难度<select value={draft.difficulty} onChange={event => setDraft(value => value && ({ ...value, difficulty: event.target.value }))}><option>简单</option><option>中等</option><option>困难</option></select></label><label>套数<input type="number" min={1} max={20} value={draft.setCount} onChange={event => setDraft(value => value && ({ ...value, setCount: Number(event.target.value) }))} /></label></div><div className="assistant-draft-actions"><button className="secondary" onClick={() => setDraft(undefined)}>取消</button><button onClick={() => props.onStartKnowledgeBaseProject(draft)} disabled={!hasSource || draft.questionCount < 1 || draft.setCount < 1}>选择资料并继续</button></div></section>}

      <div className="assistant-v2-composer-wrap">{props.attachments.length > 0 && <div className="assistant-v2-attachments" aria-label="本次对话的文件">{props.attachments.map(file => <span key={file.id}>{file.originalName}{file.truncated ? '（已截取）' : ''}<button type="button" onClick={() => props.onRemoveAttachment(file.id)} aria-label={`移除 ${file.originalName}`}>×</button></span>)}</div>}
      <form className="assistant-input assistant-v2-composer" onSubmit={submit}>
        <textarea value={props.input} onChange={event => props.onInputChange(event.target.value)} onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); } }} placeholder={props.sending ? '可先输入下一条问题，当前回答完成后再发送…' : '输入问题，或描述需要生成的题目…'} rows={2} maxLength={4000} disabled={!props.selectedKnowledgeBaseId} aria-label="输入问题或出题要求" />
        <div className="assistant-v2-toolbar"><div className="assistant-v2-tools">
          <div className="assistant-v2-upload" ref={uploadMenu}><button type="button" className="secondary" aria-label="添加文件" onClick={() => setUploadMenuOpen(value => !value)} disabled={props.uploading || !props.selectedKnowledgeBaseId} aria-haspopup="menu" aria-expanded={uploadMenuOpen}><StudioIcon name="attach" size={18} /><span>添加文件</span></button>{uploadMenuOpen && <div className="assistant-v2-upload-menu" role="menu" aria-label="选择文件用途"><button type="button" role="menuitem" onClick={() => chooseUpload('CONVERSATION')}><StudioIcon name="file" /><span>仅在对话中使用<small>临时参考，不保存至知识库</small></span></button><button type="button" role="menuitem" onClick={() => chooseUpload('KNOWLEDGE_BASE')}><StudioIcon name="book" /><span>保存到知识库<small>解析后可用于后续命题</small></span></button></div>}<input ref={fileInput} type="file" hidden onChange={event => { const file = event.target.files?.[0]; if (file) props.onUpload(file, uploadTarget.current); event.target.value = ''; }} /></div>
          <label className="assistant-web-toggle"><input type="checkbox" checked={props.webSearch} onChange={event => props.onWebSearchChange(event.target.checked)} disabled={props.sending} /><StudioIcon name="globe" size={17} /><span>联网搜索</span></label>
          <label className="studio-effort"><span className="sr-only">思考强度</span><select aria-label="思考强度" value={props.effort} onChange={event => props.onEffortChange(event.target.value as AssistantEffort)}><option value="FAST">快速思考</option><option value="STANDARD">标准思考</option><option value="DEEP">深入思考</option></select></label>
        </div><button type="submit" className="studio-send" aria-label={props.sending ? '正在生成' : '发送'} disabled={props.sending || !props.input.trim() || !props.selectedKnowledgeBaseId}><StudioIcon name="arrow" size={19} /></button></div>
      </form><p className={`assistant-v2-note${props.webSearch ? ' web-active' : ''}`}>{props.uploading ? '正在上传文件…' : 'AI 协助构思，专业判断由你掌握。'}<span>Enter 发送 · Shift + Enter 换行</span></p></div>
    </section>
  </section>;
}
