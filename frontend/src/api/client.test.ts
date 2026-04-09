import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch } from './client';

describe('apiFetch headers', () => {
    const fetchMock = vi.fn<typeof fetch>();

    beforeEach(() => {
        vi.stubGlobal('fetch', fetchMock);
        fetchMock.mockResolvedValue(new Response('{}', { status: 200 }));
        localStorage.clear();
    });

    it('保留对象字面量 headers 并注入 Authorization', async () => {
        localStorage.setItem('lcm_auth_token', 'token-object');

        await apiFetch('/api/test', {
            headers: { 'Content-Type': 'application/json' },
        });

        const requestInit = fetchMock.mock.calls[0]?.[1] as RequestInit;
        const headers = requestInit.headers as Headers;

        expect(headers).toBeInstanceOf(Headers);
        expect(headers.get('Authorization')).toBe('Bearer token-object');
        expect(headers.get('Content-Type')).toBe('application/json');
    });

    it('保留 Headers 实例 headers 并注入 Authorization', async () => {
        localStorage.setItem('lcm_auth_token', 'token-headers');

        await apiFetch('/api/test', {
            headers: new Headers({ 'X-Trace-Id': 'trace-1' }),
        });

        const requestInit = fetchMock.mock.calls[0]?.[1] as RequestInit;
        const headers = requestInit.headers as Headers;

        expect(headers).toBeInstanceOf(Headers);
        expect(headers.get('Authorization')).toBe('Bearer token-headers');
        expect(headers.get('X-Trace-Id')).toBe('trace-1');
    });

    it('保留 tuple 数组 headers 并注入 Authorization', async () => {
        localStorage.setItem('lcm_auth_token', 'token-tuples');

        await apiFetch('/api/test', {
            headers: [['X-Client', 'web-ui']],
        });

        const requestInit = fetchMock.mock.calls[0]?.[1] as RequestInit;
        const headers = requestInit.headers as Headers;

        expect(headers).toBeInstanceOf(Headers);
        expect(headers.get('Authorization')).toBe('Bearer token-tuples');
        expect(headers.get('X-Client')).toBe('web-ui');
    });

    it('没有 token 时仍保留 Content-Type', async () => {
        await apiFetch('/api/test', {
            headers: { 'Content-Type': 'application/json' },
        });

        const requestInit = fetchMock.mock.calls[0]?.[1] as RequestInit;
        const headers = requestInit.headers as Headers;

        expect(headers).toBeInstanceOf(Headers);
        expect(headers.get('Authorization')).toBeNull();
        expect(headers.get('Content-Type')).toBe('application/json');
    });
});
