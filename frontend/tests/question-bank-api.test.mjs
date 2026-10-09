import assert from 'node:assert/strict';
import test from 'node:test';
import { QuestionBankApiError, requestQuestionBank } from '../src/questionBankApi.ts';

const path = '/api/question-bank/available/content';

test('session requests use the browser cookie and return grouped paper content', async t => {
  const content = { papers: [{ title: 'ROS · A', questions: [{ id: 'question-1' }] }], standalone: [] };
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, path);
    assert.equal(options.credentials, 'same-origin');
    assert.equal(options.cache, 'no-store');
    assert.equal(options.headers.has('Authorization'), false);
    return Response.json(content);
  });
  assert.deepEqual(await requestQuestionBank(path, 'session:user-1'), content);
});

test('expired sessions retain the 401 status for the login recovery flow', async t => {
  t.mock.method(globalThis, 'fetch', async () => Response.json({
    statusCode: 'AUTHENTICATION_REQUIRED', message: '请先登录后继续操作',
  }, { status: 401 }));
  await assert.rejects(requestQuestionBank(path, 'session:expired'), error => {
    assert.ok(error instanceof QuestionBankApiError);
    assert.equal(error.status, 401);
    assert.equal(error.code, 'AUTHENTICATION_REQUIRED');
    assert.match(error.message, /登录已失效/);
    return true;
  });
});

test('server errors preserve their actual reason instead of reporting an empty library', async t => {
  t.mock.method(globalThis, 'fetch', async () => Response.json({
    statusCode: 'SERVICE_UNAVAILABLE', message: '服务暂时不可用，请重试',
  }, { status: 503 }));
  await assert.rejects(requestQuestionBank(path, 'session:user-1'), error => {
    assert.equal(error.status, 503);
    assert.equal(error.message, '服务暂时不可用，请重试');
    return true;
  });
});

test('non-JSON proxy errors retain the HTTP status', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response('Bad gateway', { status: 502 }));
  await assert.rejects(requestQuestionBank(path, 'session:user-1'), error => {
    assert.equal(error.status, 502);
    assert.match(error.message, /HTTP 502/);
    return true;
  });
});

test('a connection failure can be retried successfully', async t => {
  let attempts = 0;
  t.mock.method(globalThis, 'fetch', async () => {
    if (++attempts === 1) throw new TypeError('Failed to fetch');
    return Response.json({ papers: [], standalone: [] });
  });
  await assert.rejects(requestQuestionBank(path, 'session:user-1'), error => {
    assert.equal(error.code, 'NETWORK_ERROR');
    assert.match(error.message, /无法连接服务/);
    return true;
  });
  assert.deepEqual(await requestQuestionBank(path, 'session:user-1'), { papers: [], standalone: [] });
});

test('cancelled requests remain cancelled rather than becoming connection errors', async t => {
  const controller = new AbortController();
  controller.abort();
  const aborted = new DOMException('Aborted', 'AbortError');
  t.mock.method(globalThis, 'fetch', async () => { throw aborted; });
  await assert.rejects(requestQuestionBank(path, 'session:user-1', { signal: controller.signal }), error => error === aborted);
});

test('delete responses and older Basic authentication remain supported', async t => {
  t.mock.method(globalThis, 'fetch', async (_url, options) => {
    assert.equal(options.headers.get('Authorization'), 'Basic test-authorization');
    assert.equal(options.method, 'DELETE');
    return new Response(null, { status: 204 });
  });
  assert.equal(await requestQuestionBank('/api/question-bank/banks/bank-1', 'Basic test-authorization', { method: 'DELETE' }), undefined);
});
