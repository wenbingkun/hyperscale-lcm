import React, { createContext, useContext, useState, useCallback, useEffect } from 'react';
import { parseJwt } from '../jwt';

interface User {
    username: string;
    roles: string[];
    tenantId: string;
}

interface AuthState {
    token: string | null;
    user: User | null;
    isAuthenticated: boolean;
    isLoading: boolean;
    login: (username: string, password: string, tenantId?: string) => Promise<void>;
    logout: () => void;
    error: string | null;
}

const AuthContext = createContext<AuthState | undefined>(undefined);

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080';
const TOKEN_KEY = 'lcm_auth_token';
const USER_KEY = 'lcm_auth_user';

/** 检查 Token 是否过期 */
function isTokenExpired(token: string): boolean {
    const payload = parseJwt(token);
    if (!payload || !payload.exp) return true;
    return Date.now() >= (payload.exp as number) * 1000;
}

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
    const [token, setToken] = useState<string | null>(null);
    const [user, setUser] = useState<User | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    // 启动时从 localStorage 恢复会话
    useEffect(() => {
        const savedToken = localStorage.getItem(TOKEN_KEY);
        const savedUser = localStorage.getItem(USER_KEY);
        if (savedToken && !isTokenExpired(savedToken)) {
            setToken(savedToken);
            if (savedUser) {
                try { setUser(JSON.parse(savedUser)); } catch { /* ignore */ }
            }
        } else {
            localStorage.removeItem(TOKEN_KEY);
            localStorage.removeItem(USER_KEY);
        }
        setIsLoading(false);
    }, []);

    const login = useCallback(async (username: string, password: string, tenantId = 'default') => {
        setIsLoading(true);
        setError(null);
        try {
            const res = await fetch(`${API_BASE}/api/auth/login`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password, tenantId }),
            });
            if (!res.ok) {
                const body = await res.text();
                throw new Error(body || `Login failed (${res.status})`);
            }
            const data = await res.json();
            const jwt = data.token;
            const payload = parseJwt(jwt);

            const userInfo: User = {
                username: (payload?.upn as string) || username,
                roles: (payload?.groups as string[]) || [],
                tenantId,
            };

            setToken(jwt);
            setUser(userInfo);
            localStorage.setItem(TOKEN_KEY, jwt);
            localStorage.setItem(USER_KEY, JSON.stringify(userInfo));
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : 'Login failed';
            setError(msg);
            throw e;
        } finally {
            setIsLoading(false);
        }
    }, []);

    const logout = useCallback(() => {
        setToken(null);
        setUser(null);
        setError(null);
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
    }, []);

    return (
        <AuthContext.Provider value={{
            token, user,
            isAuthenticated: !!token && !isTokenExpired(token ?? ''),
            isLoading, error,
            login, logout,
        }}>
            {children}
        </AuthContext.Provider>
    );
};

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
    const ctx = useContext(AuthContext);
    if (!ctx) throw new Error('useAuth must be used within AuthProvider');
    return ctx;
}
