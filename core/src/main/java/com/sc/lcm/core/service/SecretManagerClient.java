package com.sc.lcm.core.service;

/**
 * Secret Manager 通用适配接口。
 * 当前默认实现为 {@link VaultSecretClient}；后续可切换为
 * AWS Secrets Manager、Azure Key Vault 或其他企业级密钥系统，
 * 无需修改上层 {@link SecretRefResolver} 逻辑。
 */
public interface SecretManagerClient {

    /**
     * 解析 {@code vault://} 形式的 secret ref，返回字段值。
     *
     * @param ref       形如 {@code vault://mount/path#field} 的 secret ref
     * @param fieldName 用于错误消息中的字段名称，例如 "username"
     * @return 解析结果
     */
    SecretResolution resolve(String ref, String fieldName);

    record SecretResolution(boolean resolved, String value, String message) {

        public static SecretResolution resolved(String value) {
            return new SecretResolution(true, value, "Secret ref resolved.");
        }

        public static SecretResolution unresolved(String message) {
            return new SecretResolution(false, null, message);
        }
    }
}
