const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');

module.exports = class JavaGateReplay {
  constructor() {
    this.snapshot = JSON.parse(readFileSync(resolve(__dirname, '../../backend/target/question-quality-eval.json'), 'utf8'));
    if (this.snapshot.kind !== 'engineering-regression-not-teacher-gold' || this.snapshot.modelCalls !== 0) {
      throw new Error('Not an offline Java regression export');
    }
  }
  id() { return `java-gate-replay:${this.snapshot.executionVersion}`; }
  async callApi(prompt, context) {
    const row = this.snapshot.rows.find(row => row.id === context.vars.fixtureId);
    if (!row) return { error: 'Missing Java fixture outcome' };
    return { output: JSON.stringify(row.actual), cost: 0, cached: false,
      tokenUsage: { total: 0, prompt: 0, completion: 0 },
      metadata: { replay: true, kind: this.snapshot.kind, dimension: row.dimension } };
  }
};
