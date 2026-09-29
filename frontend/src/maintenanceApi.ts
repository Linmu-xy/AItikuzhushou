export async function maintenanceApi<T>(path: string, auth: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  if (auth.startsWith('Basic ')) headers.set('Authorization', auth);
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, { ...options, headers, credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message || `请求失败（${response.status}）`);
  }
  return response.status === 204 ? undefined as T : response.json();
}
export const errorText = (error: unknown) => error instanceof Error ? error.message : '操作未完成，请重试';
