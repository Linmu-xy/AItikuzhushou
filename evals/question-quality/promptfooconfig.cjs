const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const snapshot = JSON.parse(readFileSync(resolve(__dirname, '../../backend/target/question-quality-eval.json'), 'utf8'));
// Relative refs avoid Promptfoo treating the Windows drive colon as a named-export separator.
const local = name => `file://./${name}`;

module.exports = {
  description: 'Offline production Java regression — not a fresh LLM quality benchmark',
  sharing: false,
  prompts: ['{{fixtureId}}'],
  providers: [local('replay-provider.cjs')],
  tests: snapshot.rows.map(row => ({
    description: row.id,
    vars: { fixtureId: row.id },
    metadata: { dimension: row.dimension, teacherReviewed: false },
    assert: [{ type: 'javascript', value: local('assertion.js'), config: { expected: row.expected } }],
  })),
};
