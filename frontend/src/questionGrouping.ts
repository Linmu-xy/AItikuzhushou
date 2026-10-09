import types from '../../backend/src/main/resources/question-types.json';

export type QuestionSlot = { variantNo?: number; variantLabel: string; sequenceNo: number; questionType: string; typeLabel: string; status?: string };

export function canonicalQuestionType(value: string) {
  const normalized = value.toUpperCase().replace(/[\s_/-]/g, '');
  return types.find(type => normalized === type.code.replace(/_/g, '') || type.aliases.some(alias => normalized.includes(alias)))?.code;
}

export function questionTypeKey(item: Pick<QuestionSlot, 'questionType' | 'typeLabel'>) {
  return canonicalQuestionType(item.questionType) || canonicalQuestionType(item.typeLabel) || `CUSTOM:${item.typeLabel.trim()}`;
}

export function questionTypeLabel(item: Pick<QuestionSlot, 'questionType' | 'typeLabel'>) {
  return types.find(type => type.code === questionTypeKey(item))?.label || item.typeLabel;
}

function compareText(left: string, right: string) { return left < right ? -1 : left > right ? 1 : 0; }

export function groupQuestionSlots<T extends QuestionSlot>(items: readonly T[]) {
  const sorted = [...items].sort((left, right) => {
    const variant = (left.variantNo ?? 0) - (right.variantNo ?? 0) || compareText(left.variantLabel, right.variantLabel);
    const leftKey = questionTypeKey(left), rightKey = questionTypeKey(right);
    const leftRank = types.findIndex(type => type.code === leftKey), rightRank = types.findIndex(type => type.code === rightKey);
    return variant || (leftRank < 0 ? types.length : leftRank) - (rightRank < 0 ? types.length : rightRank)
      || compareText(leftKey, rightKey) || left.sequenceNo - right.sequenceNo;
  });
  const variants = new Map<string, { key: string; variantLabel: string; groups: { key: string; label: string; items: T[] }[] }>();
  for (const item of sorted) {
    const variantKey = `${item.variantNo ?? item.variantLabel}`;
    let variant = variants.get(variantKey);
    if (!variant) { variant = { key: variantKey, variantLabel: item.variantLabel, groups: [] }; variants.set(variantKey, variant); }
    const typeKey = questionTypeKey(item);
    let group = variant.groups.find(group => group.key === typeKey);
    if (!group) { group = { key: typeKey, label: questionTypeLabel(item), items: [] }; variant.groups.push(group); }
    group.items.push(item);
  }
  return [...variants.values()];
}

export const isQuestionApproved = (status?: string) => status === 'APPROVED' || status === 'APPROVED_WITH_RISK';
export const generationStatusLabel = (status: string) => ({
  PLANNED: '等待生成', GENERATING: '生成中', REVIEW_REQUIRED: '待人工审核', REVIEW_PENDING: 'AI 审题待完成',
  APPROVED: '已通过', APPROVED_WITH_RISK: '人工保留', REJECTED: '质量未通过', FAILED: '生成失败', REMOVED: '已删除',
} as Record<string, string>)[status] || status;
