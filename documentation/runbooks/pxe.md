# PXE / iPXE 运维手册 (Runbook)

> **Last Updated:** 2026-04-18
> **Scope:** Software Closure Round 2 期间的 PXE 运维口径收敛为 `UEFI + iPXE + kickstart` 单一路径；`cloud-init` 端点保留为 readiness 资产，不作为当前已验证完成的真实装机路径。当前仍无真实裸机节点，因此本文覆盖部署前提、网络要求、镜像准备、失败回退与无设备条件下的验收步骤，而不宣称真实环境验证已完成。

---

## 1. 架构与引导链路

```
DHCP Server / ProxyDHCP
  ├─ Option 66 -> Boot Server Host
  └─ Option 67 -> UEFI iPXE boot file (first hop)
                    |
                    v
TFTP (:69/udp) -> serve iPXE boot binary from LCM_PXE_TFTP_ROOT
                    |
                    v
iPXE script (GET /ipxe on :8090/tcp)
  ├─ kernel -> LCM_PXE_BOOT_KERNEL_URL
  ├─ initrd -> LCM_PXE_BOOT_INITRD_URL
  └─ inst.ks -> GET /kickstart?mac=...&hostname=...
                    |
                    v
Kickstart template render
  └─ install repo -> LCM_PXE_INSTALL_REPO_URL
```

### 关键端口与接口

| 组件 | 默认值 | 作用 |
|------|--------|------|
| TFTP | `:69/udp` | 提供首跳 iPXE 引导文件 |
| HTTP PXE | `:8090/tcp` | 提供 `/ipxe`、`/kickstart`、`/cloud-init/*`、`/api/images` |
| DHCP Proxy | `:4011/udp` | 作为 ProxyDHCP 返回 PXE boot metadata |
| `/ipxe` | `GET` | 返回 iPXE 脚本 |
| `/kickstart` | `GET` | 按 MAC / hostname 渲染 kickstart |
| `/cloud-init/user-data` | `GET` | 返回 demo 级 cloud-init user-data |
| `/cloud-init/meta-data` | `GET` | 返回 demo 级 NoCloud meta-data |
| `/api/images` | `GET/POST` | 列出或上传镜像文件到 `LCM_PXE_IMAGE_DIR` |
| `/api/images/{name}` | `DELETE` | 删除已上传镜像 |

### 当前实现边界

- Satellite 进程启动后会自动拉起 PXE 服务；当前没有单独的 enable/disable 总开关，运行参数全部通过 `LCM_PXE_*` 环境变量覆盖。
- `kickstart` 是当前推荐主路径；`cloud-init` 端点存在，但 `user-data` 仍是仓库内置的 demo 负载，不能写成真实装机模板已验收。
- 镜像 API 只负责文件存储与删除，不负责挂载 ISO、展开安装树或校验仓库内容。

---

## 2. 部署前提与网络要求

### 2.1 环境前提

- Satellite 所在主机必须能被目标裸机通过 TFTP (`69/udp`) 和 HTTP (`8090/tcp` 或你自定义的端口) 访问。
- `LCM_PXE_BOOT_SERVER_HOST` 必须显式配置为裸机可达的 IP 或 FQDN；不要依赖自动探测的“第一个非 loopback IPv4”结果，尤其是在多网卡、容器或 NAT 环境中。
- `LCM_PXE_TFTP_ROOT` 与 `LCM_PXE_IMAGE_DIR` 必须对 Satellite 进程可写。
- `LCM_PXE_INSTALL_REPO_URL` 指向的安装树必须可访问；如果不使用默认 Rocky Linux 9 路径，则要同步显式设置 `LCM_PXE_BOOT_KERNEL_URL` 与 `LCM_PXE_BOOT_INITRD_URL`。

### 2.2 UEFI + iPXE 单一路径要求

- Round 2 的运维口径固定为 UEFI + iPXE；现场需要把实际使用的 UEFI iPXE 引导文件放入 `LCM_PXE_TFTP_ROOT`。
- 当前代码默认的 `LCM_PXE_DHCP_BOOTFILE=undionly.kpxe` 适合 BIOS 链路，不应在 UEFI-only 环境中直接沿用。
- 在 UEFI 场景下，请把 `LCM_PXE_DHCP_BOOTFILE` 改成现场真实使用的 UEFI iPXE 文件名，例如 `ipxe.efi`、`snponly.efi` 或运维团队统一的等价文件名。

### 2.3 DHCP Option 66 / 67 口径

- 内置 ProxyDHCP 打开时，Satellite 会在 `LCM_PXE_DHCP_PROXY_ADDR` 上向 PXE 客户端返回：
  - Option 66 = `LCM_PXE_BOOT_SERVER_HOST`
  - Option 67 = 首跳 boot file；iPXE 客户端则直接收到 `LCM_PXE_DHCP_IPXE_SCRIPT_URL`
- 如果现场不用内置 ProxyDHCP，而是由主 DHCP 服务直接下发 PXE 参数，也必须保持同一口径：
  - Option 66 指向 boot server host
  - Option 67 指向 UEFI iPXE 引导文件

---

## 3. 配置速查

| 环境变量 | 默认值 | 用途 |
|----------|--------|------|
| `LCM_PXE_TFTP_ADDR` | `:69` | TFTP 监听地址 |
| `LCM_PXE_TFTP_ROOT` | `/var/lib/lcm/tftpboot` | TFTP 根目录 |
| `LCM_PXE_HTTP_ADDR` | `:8090` | HTTP PXE 服务监听地址 |
| `LCM_PXE_IMAGE_DIR` | `/var/lib/lcm/images` | 镜像文件目录 |
| `LCM_PXE_KICKSTART_TEMPLATE` | 空 | 自定义 kickstart 模板路径；为空时使用内置模板 |
| `LCM_PXE_INSTALL_REPO_URL` | `http://dl.rockylinux.org/pub/rocky/9/BaseOS/x86_64/os` | 安装源 |
| `LCM_PXE_BOOT_KERNEL_URL` | `${repo}/images/pxeboot/vmlinuz` | 安装内核地址 |
| `LCM_PXE_BOOT_INITRD_URL` | `${repo}/images/pxeboot/initrd.img` | 安装 initrd 地址 |
| `LCM_PXE_BOOT_KERNEL_ARGS` | `console=tty0 console=ttyS1,115200n8` | 附加内核参数 |
| `LCM_PXE_DHCP_PROXY_ENABLED` | `true` | 是否启用内置 ProxyDHCP |
| `LCM_PXE_DHCP_PROXY_ADDR` | `:4011` | ProxyDHCP 监听地址 |
| `LCM_PXE_BOOT_SERVER_HOST` | 自动探测 | 对外通告 boot server 地址 |
| `LCM_PXE_DHCP_BOOTFILE` | `undionly.kpxe` | 非 iPXE 客户端首跳 boot file；UEFI 场景应显式覆盖 |
| `LCM_PXE_DHCP_IPXE_SCRIPT_URL` | `http://<boot-host>:<http-port>/ipxe` | iPXE 客户端跳转脚本 |

---

## 4. 镜像与模板准备

### 4.1 准备 TFTP 根目录

在 Satellite 主机上创建目录，并放入现场使用的 UEFI iPXE 二进制：

```bash
mkdir -p /var/lib/lcm/tftpboot /var/lib/lcm/images
cp /path/to/ipxe.efi /var/lib/lcm/tftpboot/
```

如果现场使用的文件名不是 `ipxe.efi`，记得同步设置 `LCM_PXE_DHCP_BOOTFILE`。

### 4.2 准备安装源

- 如果直接使用默认 Rocky Linux 9 BaseOS 安装树，不需要额外配置 kernel/initrd URL。
- 如果使用内网镜像站，建议成组配置：

```bash
export LCM_PXE_INSTALL_REPO_URL=http://mirror.local/rocky/9/BaseOS/x86_64/os
export LCM_PXE_BOOT_KERNEL_URL=http://mirror.local/rocky/9/BaseOS/x86_64/os/images/pxeboot/vmlinuz
export LCM_PXE_BOOT_INITRD_URL=http://mirror.local/rocky/9/BaseOS/x86_64/os/images/pxeboot/initrd.img
```

### 4.3 可选：自定义 kickstart 模板

- 未设置 `LCM_PXE_KICKSTART_TEMPLATE` 时，系统使用仓库内置的最小模板。
- 如果现场需要自定义 root 密码、分区、镜像仓库或后置脚本，请提供模板文件路径。
- Satellite 会在运行时加载模板文件；文件不存在时，HTTP PXE handler 启动即失败。

---

## 5. 启动方式

以下示例展示了与当前实现一致的最小运行参数：

```bash
env \
  LCM_CORE_ADDR=core.example.internal:9000 \
  LCM_CERTS_DIR=/etc/lcm/certs \
  LCM_PXE_BOOT_SERVER_HOST=10.0.0.15 \
  LCM_PXE_DHCP_BOOTFILE=ipxe.efi \
  LCM_PXE_INSTALL_REPO_URL=http://mirror.local/rocky/9/BaseOS/x86_64/os \
  ./satellite/satellite --cluster lab-a
```

本地 demo / 无 TLS 演练场景可以额外加：

```bash
export LCM_GRPC_PLAINTEXT=true
```

---

## 6. 验收步骤

### 6.1 无真实裸机条件下的必做检查

1. 检查 iPXE 脚本是否能正确渲染：

```bash
curl -sf "http://10.0.0.15:8090/ipxe?mac=AA:BB:CC:DD:EE:FF&hostname=node-01"
```

预期结果：
- 返回内容以 `#!ipxe` 开头
- 包含 `inst.repo=...`
- 包含 `inst.ks=http://10.0.0.15:8090/kickstart?...`

2. 检查 kickstart 是否按节点参数渲染：

```bash
curl -sf "http://10.0.0.15:8090/kickstart?mac=AA:BB:CC:DD:EE:FF&hostname=node-01"
```

预期结果：
- `hostname=node-01` 或对应模板中的 hostname 字段已替换
- repo / satellite address 与现场配置一致

3. 检查镜像 API：

```bash
curl -sf http://10.0.0.15:8090/api/images
```

预期结果：
- 返回 JSON 数组
- 已上传文件可见

### 6.2 真实裸机到位后的现场验收顺序

1. 裸机通过 DHCP 获取地址。
2. PXE 首跳拿到正确的 UEFI iPXE 引导文件。
3. iPXE 拉取 `/ipxe` 成功，并从 HTTP 安装源获取 kernel / initrd。
4. `/kickstart` 被访问并携带正确的 `mac` / `hostname`。
5. 安装流程完成后，再根据现场要求补充 Satellite 自注册与业务验证。

在真实裸机到位前，以上第 2-5 步只能写成“待现场验收”，不能写成“已验证通过”。

---

## 7. 失败回退

### 7.1 网络与引导回退

- 先从 DHCP / ProxyDHCP 侧撤销 PXE 引导信息：
  - 内置 ProxyDHCP：设置 `LCM_PXE_DHCP_PROXY_ENABLED=false` 后重启 Satellite
  - 外置 DHCP：移除或回退 Option 66 / 67
- 如需完全停止 PXE 服务，停止 Satellite 进程即可；当前 PXE 服务跟随 Satellite 生命周期。

### 7.2 安装源与模板回退

- 切回上一个确认可用的 `LCM_PXE_INSTALL_REPO_URL`、kernel URL、initrd URL。
- 取消 `LCM_PXE_KICKSTART_TEMPLATE`，回退到仓库内置默认模板。

### 7.3 TFTP / 镜像目录回退

- 从 `LCM_PXE_TFTP_ROOT` 移除本次试运行投放的 boot file。
- 从 `LCM_PXE_IMAGE_DIR` 删除错误上传的镜像文件，或通过 `DELETE /api/images/{name}` 清理。

---

## 8. 常见故障

### 8.1 `LCM_PXE_BOOT_SERVER_HOST` 自动探测到了错误网卡

**症状**：iPXE 脚本中的 host 指向容器网卡、管理网卡或不可达地址。

**处理**：显式设置 `LCM_PXE_BOOT_SERVER_HOST`，不要依赖自动探测。

### 8.2 UEFI 机器拿到了 `undionly.kpxe`

**症状**：客户端停在首跳引导阶段，或直接报 boot file 不兼容。

**处理**：把 `LCM_PXE_DHCP_BOOTFILE` 改成现场实际 UEFI iPXE 文件名，并确认该文件已放入 `LCM_PXE_TFTP_ROOT`。

### 8.3 `/ipxe` 正常，但 kernel / initrd 下载失败

**症状**：iPXE 脚本已返回，但后续 `vmlinuz` / `initrd.img` 404 或超时。

**处理**：
- 检查 `LCM_PXE_INSTALL_REPO_URL`
- 若镜像树不是默认 Rocky 目录结构，显式设置 `LCM_PXE_BOOT_KERNEL_URL` 与 `LCM_PXE_BOOT_INITRD_URL`

### 8.4 自定义 kickstart 模板导致 HTTP PXE 启动失败

**症状**：Satellite 启动后 PXE HTTP 服务没有起来，日志出现读取模板失败。

**处理**：
- 检查 `LCM_PXE_KICKSTART_TEMPLATE` 路径是否存在
- 先取消自定义模板，回退到内置模板确认主链路

### 8.5 误把 `/api/images` 当作安装源管理

**症状**：上传了 ISO 或镜像文件，但安装仍然失败。

**原因**：镜像 API 只做文件存储，不会自动挂载或暴露为 `inst.repo`。

**处理**：把安装源准备为真实可访问的 HTTP repo，再把 `LCM_PXE_INSTALL_REPO_URL` 指向该 repo。

### 8.6 期待 cloud-init 已具备生产模板

**症状**：现场按 `/cloud-init/user-data` 直接装机，但内容不符合生产要求。

**原因**：当前 `cloud-init` payload 仍是 demo 级静态内容。

**处理**：本阶段优先走 kickstart；`cloud-init` 只作为 readiness 资产保留，待后续单独收敛。
