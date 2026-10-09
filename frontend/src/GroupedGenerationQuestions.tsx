import { generationStatusLabel, groupQuestionSlots, isQuestionApproved, type QuestionSlot } from './questionGrouping';

type GenerationQuestion = QuestionSlot & { id: string; status: string; points: number; question: Record<string, unknown>; review: Record<string, unknown>; errorMessage?: string };
const text = (value: unknown) => value == null ? '' : String(value);

function options(value: unknown): string[] {
  if (Array.isArray(value)) return value.map((entry, index) => `${String.fromCharCode(65 + index)}. ${text(entry)}`);
  if (value && typeof value === 'object') return Object.entries(value).map(([key, content]) => `${key}. ${text(content)}`);
  return text(value).split(/\s*[|｜]\s*(?=[A-HＡ-Ｈ][.．、)）])|\r?\n/).filter(part => part.trim());
}

export function GroupedGenerationQuestions({ items }: { items: GenerationQuestion[] }) {
  const variants = groupQuestionSlots(items.filter(item => item.status !== 'REMOVED'));
  return <div className="grouped-generation-questions">{variants.map(variant => <section key={variant.key}>
    {variants.length > 1 && <h3 className="generation-variant-heading">第 {variant.variantLabel} 套</h3>}
    {variant.groups.map(group => <section className="generation-type-group" key={group.key} aria-label={group.label}>
      <header className="generation-type-heading"><h3>{group.label}</h3><span>已生成 {group.items.filter(item => text(item.question.stem).trim()).length}/{group.items.length} · 已审核 {group.items.filter(item => isQuestionApproved(item.status)).length}/{group.items.length}</span></header>
      <div className="generated-question-list">{group.items.map(item => <article className="generated-question" key={item.id}>
        <div className="generated-question-head"><span>第 {variant.variantLabel} 套 · 第 {item.sequenceNo} 题 · {item.points} 分</span><em className={`generation-item-status ${item.status.toLowerCase()}`}>{generationStatusLabel(item.status)}</em></div>
        {text(item.question.stem).trim() ? <>
          <h4>{text(item.question.stem)}</h4>
          {options(item.question.options).length > 0 && <div className="generated-question-options">{options(item.question.options).map((option, index) => <p key={index}>{option}</p>)}</div>}
          <p className="generated-question-answer"><b>答案：</b>{text(item.question.answer)}</p>
          <p className="generated-question-analysis">{text(item.question.analysis)}</p>
          {text(item.review.feedback) && <details><summary>审题反馈</summary><p className="generated-question-review">{text(item.review.feedback)}</p></details>}
        </> : <p className="generation-slot-placeholder">{item.status === 'FAILED' || item.status === 'REJECTED' ? '此题未生成成功，可在审核台处理。' : item.status === 'GENERATING' ? '正在生成这道题，完成后将在此显示。' : '等待生成，题号与位置已固定。'}</p>}
        {item.errorMessage && <p className="generation-run-error">{item.errorMessage}</p>}
      </article>)}</div>
    </section>)}
  </section>)}</div>;
}
