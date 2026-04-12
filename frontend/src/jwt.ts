export function parseJwt(token: string): Record<string, unknown> | null {
    try {
        const parts = token.split('.');
        if (parts.length < 3 || !parts[1]) {
            throw new Error('Invalid JWT format: expected header.payload.signature');
        }

        let base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
        const remainder = base64.length % 4;
        if (remainder === 2) {
            base64 += '==';
        } else if (remainder === 3) {
            base64 += '=';
        } else if (remainder !== 0) {
            throw new Error('Invalid base64url payload length');
        }

        return JSON.parse(atob(base64));
    } catch (error) {
        if (import.meta.env.DEV) {
            // 仅在开发环境输出详细诊断，避免生产环境噪音
            console.debug('Failed to parse JWT payload', { error, tokenPreview: token.slice(0, 24) });
        }
        return null;
    }
}
