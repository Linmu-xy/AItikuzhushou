export class QuestionBankApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(message: string, status: number, code: string) {
    super(message);
    this.name = 'QuestionBankApiError';
    this.status = status;
    this.code = code;
  }
}

export async function requestQuestionBank<T>(path: string, auth: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  if (auth.startsWith('Basic ')) headers.set('Authorization', auth);
  if (init?.body) headers.set('Content-Type', 'application/json');
  let response: Response;
  try {
    response = await fetch(path, { ...init, headers, credentials: 'same-origin', cache: 'no-store' });
  } catch (reason) {
    if (init?.signal?.aborted || (reason instanceof Error && reason.name === 'AbortError')) throw reason;
    throw new QuestionBankApiError('无法连接服务，请检查网络后重试', 0, 'NETWORK_ERROR');
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => ({})) as { message?: string; error?: string; statusCode?: string };
    const code = payload.statusCode || `HTTP_${response.status}`;
    const message = response.status === 401 ? '登录已失效，请重新登录后继续操作'
      : payload.message || payload.error || `内容请求失败（HTTP ${response.status}），请重试`;
    throw new QuestionBankApiError(message, response.status, code);
  }
  if (response.status === 204) return undefined as T;
  try {
    return await response.json() as T;
  } catch {
    throw new QuestionBankApiError('服务返回的内容格式异常，请重试', response.status, 'INVALID_RESPONSE');
  }
}
