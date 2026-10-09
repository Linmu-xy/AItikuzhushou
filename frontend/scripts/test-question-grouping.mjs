import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { transformWithOxc } from 'vite';

const modulePath = new URL('../src/questionGrouping.ts', import.meta.url);
const source = fs.readFileSync(modulePath, 'utf8').replace(/^import types .*;$/m, 'const types = __types;').replace(/\bexport /g, '');
const { code } = await transformWithOxc(source, fileURLToPath(modulePath), { lang: 'ts' });
const scope = { __types: JSON.parse(fs.readFileSync(new URL('../../backend/src/main/resources/question-types.json', import.meta.url), 'utf8')) };
vm.runInNewContext(`${code}\nglobalThis.api = { groupQuestionSlots, questionTypeLabel, isQuestionApproved };`, scope);
const { groupQuestionSlots, questionTypeLabel, isQuestionApproved } = scope.api;
const input = [
  { id: 'b-fill', variantNo: 2, variantLabel: 'B', sequenceNo: 3, questionType: 'FILL_BLANK', typeLabel: 'FILL_BLANK', status: 'PLANNED' },
  { id: 'a-short', variantNo: 1, variantLabel: 'A', sequenceNo: 1, questionType: 'SHORT_ANSWER', typeLabel: '简答题' },
  { id: 'a-single-4', variantNo: 1, variantLabel: 'A', sequenceNo: 4, questionType: 'SINGLE_CHOICE', typeLabel: '单选题', status: 'APPROVED' },
  { id: 'a-single-2', variantNo: 1, variantLabel: 'A', sequenceNo: 2, questionType: 'SINGLE_CHOICE', typeLabel: '单选题', status: 'GENERATING' },
  { id: 'a-fill', variantNo: 1, variantLabel: 'A', sequenceNo: 5, questionType: 'FILL_BLANK', typeLabel: '填空题' },
  { id: 'a-custom', variantNo: 1, variantLabel: 'A', sequenceNo: 6, questionType: 'CUSTOM', typeLabel: '绘图题' },
];
const snapshot = JSON.stringify(input);
const groups = groupQuestionSlots(input);
assert.deepEqual(JSON.parse(JSON.stringify(groups.map(group => group.variantLabel))), ['A', 'B']);
assert.deepEqual(JSON.parse(JSON.stringify(groups[0].groups.map(group => group.label))), ['单选题', '填空题', '简答题', '绘图题']);
assert.deepEqual(JSON.parse(JSON.stringify(groups[0].groups[0].items.map(item => [item.id, item.sequenceNo]))), [['a-single-2', 2], ['a-single-4', 4]]);
assert.equal(JSON.stringify(input), snapshot, 'Grouping must not mutate historical numbers or item identities');
assert.equal(groupQuestionSlots(input.filter(item => item.id === 'a-single-4'))[0].groups[0].items[0].sequenceNo, 4);
assert.equal(questionTypeLabel({ questionType: 'ESSAY', typeLabel: 'ESSAY' }), '论述题');
assert.equal(isQuestionApproved('APPROVED_WITH_RISK'), true);
assert.equal(isQuestionApproved('REVIEW_PENDING'), false);
assert.equal(groupQuestionSlots([]).length, 0);
console.log('Question grouping: all assertions passed (ordering, variants, stable IDs/numbers, filtering, names, statuses).');
