import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from './AuthContext';
import { parseJwt } from '../jwt';

function createJwt(overrides: Record<string, unknown> = {}) {
    const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
    const payload = btoa(JSON.stringify({
        upn: 'admin',
        groups: ['ADMIN'],
        exp: Math.floor(Date.now() / 1000) + 3600,
        ...overrides,
    }));

    return `${header}.${payload}.signature`;
}

function AuthHarness() {
    const { isAuthenticated, isLoading, user, error, login, logout } = useAuth();

    return (
        <div>
            <div data-testid="loading">{String(isLoading)}</div>
            <div data-testid="authenticated">{String(isAuthenticated)}</div>
            <div data-testid="username">{user?.username ?? 'none'}</div>
            <div data-testid="tenant">{user?.tenantId ?? 'none'}</div>
            <div data-testid="roles">{user?.roles.join(',') ?? 'none'}</div>
            <div data-testid="error">{error ?? 'none'}</div>
            <button type="button" onClick={() => void login('admin', 'admin123', 'tenant-a')}>
                Login
            </button>
            <button type="button" onClick={logout}>
                Logout
            </button>
        </div>
    );
}

describe('AuthProvider', () => {
    it('restores a valid session from localStorage on mount', async () => {
        const token = createJwt();
        localStorage.setItem('lcm_auth_token', token);
        localStorage.setItem('lcm_auth_user', JSON.stringify({
            username: 'persisted-admin',
            roles: ['ADMIN'],
            tenantId: 'default',
        }));

        render(
            <AuthProvider>
                <AuthHarness />
            </AuthProvider>,
        );

        await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('false'));

        expect(screen.getByTestId('authenticated')).toHaveTextContent('true');
        expect(screen.getByTestId('username')).toHaveTextContent('persisted-admin');
        expect(screen.getByTestId('tenant')).toHaveTextContent('default');
    });

    it('clears expired session data during bootstrap', async () => {
        localStorage.setItem('lcm_auth_token', createJwt({
            exp: Math.floor(Date.now() / 1000) - 60,
        }));
        localStorage.setItem('lcm_auth_user', JSON.stringify({
            username: 'stale-admin',
            roles: ['ADMIN'],
            tenantId: 'default',
        }));

        render(
            <AuthProvider>
                <AuthHarness />
            </AuthProvider>,
        );

        await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('false'));

        expect(screen.getByTestId('authenticated')).toHaveTextContent('false');
        expect(localStorage.getItem('lcm_auth_token')).toBeNull();
        expect(localStorage.getItem('lcm_auth_user')).toBeNull();
    });

    it('logs in against the API, persists the session, and logs out cleanly', async () => {
        const user = userEvent.setup();
        const token = createJwt({
            upn: 'operator',
            groups: ['OPS'],
        });
        const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
            new Response(JSON.stringify({ token }), {
                status: 200,
                headers: { 'Content-Type': 'application/json' },
            }),
        );

        render(
            <AuthProvider>
                <AuthHarness />
            </AuthProvider>,
        );

        await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('false'));
        await user.click(screen.getByRole('button', { name: 'Login' }));

        await waitFor(() => expect(screen.getByTestId('authenticated')).toHaveTextContent('true'));

        expect(fetchSpy).toHaveBeenCalledWith(
            'http://localhost:8080/api/auth/login',
            expect.objectContaining({
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
            }),
        );

        const requestBody = JSON.parse(fetchSpy.mock.calls[0]?.[1]?.body as string);
        expect(requestBody).toEqual({
            username: 'admin',
            password: 'admin123',
            tenantId: 'tenant-a',
        });

        expect(screen.getByTestId('username')).toHaveTextContent('operator');
        expect(screen.getByTestId('tenant')).toHaveTextContent('tenant-a');
        expect(screen.getByTestId('roles')).toHaveTextContent('OPS');
        expect(localStorage.getItem('lcm_auth_token')).toBe(token);
        expect(JSON.parse(localStorage.getItem('lcm_auth_user') ?? '{}')).toEqual({
            username: 'operator',
            roles: ['OPS'],
            tenantId: 'tenant-a',
        });

        await user.click(screen.getByRole('button', { name: 'Logout' }));

        expect(screen.getByTestId('authenticated')).toHaveTextContent('false');
        expect(localStorage.getItem('lcm_auth_token')).toBeNull();
        expect(localStorage.getItem('lcm_auth_user')).toBeNull();
    });
});

describe('parseJwt', () => {
    function createBase64UrlToken(payload: string): string {
        const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
            .replace(/\+/g, '-')
            .replace(/\//g, '_')
            .replace(/=+$/g, '');
        const payloadEncoded = btoa(payload)
            .replace(/\+/g, '-')
            .replace(/\//g, '_')
            .replace(/=+$/g, '');

        return `${header}.${payloadEncoded}.signature`;
    }

    it('parses a valid payload without base64 padding', () => {
        const token = createBase64UrlToken(JSON.stringify({
            sub: 'user-1',
            exp: Math.floor(Date.now() / 1000) + 600,
        }));

        expect(parseJwt(token)).toMatchObject({ sub: 'user-1' });
    });

    it('returns null when token has insufficient segments', () => {
        expect(parseJwt('invalid.token')).toBeNull();
    });

    it('returns null for non-JSON payload', () => {
        const token = createBase64UrlToken('not-json');
        expect(parseJwt(token)).toBeNull();
    });
});
