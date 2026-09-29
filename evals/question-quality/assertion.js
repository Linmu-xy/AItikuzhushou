const { isDeepStrictEqual } = require('node:util');

module.exports = (output, context) => {
  try {
    const actual = typeof output === 'string' ? JSON.parse(output) : output;
    const expected = context.config.expected;
    const pass = Object.entries(expected).every(([key, value]) => key === 'requiredFlags'
      ? Array.isArray(actual.flags) && value.every(flag => actual.flags.includes(flag))
      : isDeepStrictEqual(actual[key], value));
    return { pass, score: pass ? 1 : 0, reason: pass ? 'Production Java outcome matches fixture' : 'Production gate regression' };
  } catch {
    return { pass: false, score: 0, reason: 'Invalid outcome or missing expectation' };
  }
};
