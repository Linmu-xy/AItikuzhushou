const { test } = require('node:test');
const assert = require('node:assert/strict');
const check = require('../assertion.js');
const context = { config: { expected: { available: false, passed: false, requiredFlags: ['REVIEW_PROTOCOL_CONFLICT'] } } };
test('recognizes pending conflict, not an accepted question', () => {
  assert.equal(check(JSON.stringify({ available: false, passed: false, flags: ['REVIEW_PROTOCOL_CONFLICT'] }), context).pass, true);
});
test('rejects accidental approval', () => {
  assert.equal(check(JSON.stringify({ available: true, passed: true, flags: [] }), context).pass, false);
});
test('rejects missing flags and malformed output', () => {
  assert.equal(check(JSON.stringify({ available: false, passed: false }), context).pass, false);
  assert.equal(check('{', context).pass, false);
});
