package com.sc.lcm.core.domain;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 凭据档案描述可复用的首次纳管策略，而不是直接保存明文凭据。
 * 运行时可将 secret ref 解析到 Vault / KMS / 外部密钥系统。
 */
@Entity
@Table(name = "credential_profiles")
@Getter
@Setter
@NoArgsConstructor
public class CredentialProfile extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;
    private String protocol = "REDFISH";
    private boolean enabled = true;
    private boolean autoClaim = true;
    /** 匹配优先级：数值越大越先匹配（与 k8s 等工具相反）。默认 100；特殊规则建议使用 200+。 */
    private int priority = 100;
    /** 来源类型：MANUAL / CMDB / DELIVERY_LEDGER。 */
    private String sourceType = "MANUAL";
    /** 外部系统中的记录 ID，用于导入 upsert。 */
    private String externalRef;

    private String vendorPattern;
    private String modelPattern;
    private String subnetCidr;
    private String deviceType;
    private String hostnamePattern;
    private String ipAddressPattern;
    private String macAddressPattern;
    private String redfishTemplate;

    private String usernameSecretRef;
    private String passwordSecretRef;
    private boolean managedAccountEnabled = false;
    private String managedUsernameSecretRef;
    private String managedPasswordSecretRef;
    private String managedAccountRoleId = "Administrator";

    private String description;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public static Uni<List<CredentialProfile>> findEnabledOrdered() {
        return find("enabled = ?1", Sort.descending("priority").and("name"), true).list();
    }

    public static Uni<CredentialProfile> findByNameExact(String name) {
        return find("name", name).firstResult();
    }

    public static Uni<CredentialProfile> findBySourceAndExternalRef(String sourceType, String externalRef) {
        return find("sourceType = ?1 and externalRef = ?2", sourceType, externalRef).firstResult();
    }
}
