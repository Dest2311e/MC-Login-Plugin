# MCLogin 主流插件兼容记录

插件版工程为 `Paper-Spigot/`，交付 JAR 为 `mclogin-paper-spigot-1.1.0.jar`，放在 `plugins/`。Forge / Fabric 的 12 个模组工程继续独立使用。两者是不同平台的构建，不应互换文件夹安装。

## 平台与依赖

所有主版本组合使用同一份 Java 17 字节码 JAR。编译基线为 Spigot API `1.20.4-R0.1-SNAPSHOT`，本次解析到 `20240423.152506-123`；可选 PlaceholderAPI 编译版本为 2.11.6。构建使用 Gradle 8.14.3。

| Minecraft | Paper build | 游戏 Java | WorldEdit | WorldGuard | 主插件组合检查 |
| --- | --- | --- | --- | --- | --- |
| 1.20.4 | 499 | 17 | 7.2.20 | 7.0.9 | 56 项 |
| 1.20.6 | 151 | 21 | 7.3.9 | 7.0.10 | 56 项 |
| 1.21.1 | 133 | 21 | 7.3.9 | 7.0.12 | 56 项 |
| 1.21.4 | 232 | 21 | 7.4.0 | 7.0.13 | 56 项 |
| 1.21.8 | 60 | 21 | 7.4.0 | 7.0.14 | 56 项 |
| 1.21.11 | 132 | 21 | 7.4.0 | 7.0.15 | 56 项 |

每个组合均安装 LuckPerms 5.5.71、EssentialsX 2.22.0、Vault 1.7.3-b131、PlaceholderAPI 2.11.6，以及表中的 WorldEdit / WorldGuard。1.21.11 另外安装 Geyser 2.12.0 build 1265、Floodgate 2.2.5 build 141 和 ViaVersion 5.12.1。完整来源、SHA-256、测试名称及日志摘要指纹见 [机器可读验证记录](plugin-validation.json)。这是固定版本的测试结果，不表示其他版本或任意配置均已验证。

Spigot 完成公共 API 编译兼容，未单独启动 Spigot 核心验收。其缺少 Paper 公共 `HumanEntity.openAnvil` API 时使用命令认证。Paper 上的原版箱子、铁砧输入和确认流程均有协议测试。

## 各插件实际检查范围

| 插件 | MCLogin 的处理 | 本次验证 |
| --- | --- | --- |
| LuckPerms | 实时查询 Bukkit 权限，`mclogin.admin` 仅用于管理 | 授权后可管理、撤销后拒绝；未登录时即使持有管理权限仍被拦截 |
| EssentialsX | 未认证拦截命令与传送，认证后恢复普通事件 | `/essentials:home` 未登录被拦截，登录后普通命令可达；与 Vault 经济服务共同加载 |
| Vault | 不抢占权限或经济服务 | `vault-info` 同时解析到 LuckPerms 权限与 Essentials 经济提供者 |
| PlaceholderAPI | 内建 expansion 自动注册，无须 eCloud 下载 | 未登录 `false`、登录后 `true`、登出恢复 `false` |
| WorldEdit | 使用公共 Bukkit 事件，不依赖内部类 | 正常启用，版本命令可用，认证后普通方块事件放行 |
| WorldGuard | 登录后保留保护插件的取消结果 | 全局 `block-break deny` 时方块事件仍被取消，移除标志后正常放行 |
| Geyser / Floodgate | 只读基岩身份标识，不自动放行 | 1.21.11 同服启用，Geyser 监听启动，Java 玩家全流程通过；未用真实基岩客户端连服 |
| ViaVersion | 作为 Geyser 测试环境的协议依赖共同加载 | 1.21.11 启用并与认证测试共存；未测试跨版本客户端组合 |
| AuthMe | 检测重复认证系统并锁定，拒绝入服 | 真实 AuthMe 6.0.1 Spigot Legacy 与 MCLogin 同服时，冲突状态与连接拒绝共 4 项检查 |

WorldGuard 的检查通过另一个插件触发真实 Bukkit 方块事件并观察保护结果，未覆盖所有领地标志、成员规则、世界与旁路权限组合。外部传送与背包检查也由测试夹具调用公共 Bukkit API，并非穷举所有传送、背包插件。

## 安全与扩展行为

- 未认证玩家的移动、聊天、命令、交互、攻击、物品操作、外部传送和背包被限制。通过命令所属插件与命名空间校验，避免其他插件的同名命令被当成 MCLogin 认证命令放行。
- 使用 Bukkit ServicesManager 发布 `LoginService`，同步发送认证状态事件；注册、重连、登录、退出认证及注销账号有联调覆盖。开发入口见 [Bukkit API](BUKKIT_API.md)。
- 密码找回默认关闭。外部验证器确认事先绑定身份后签发限时一次性授权；测试覆盖拒绝、允许、重置、旧密码失效、重新登录、撤销验证器后已有授权失效及原密码保持有效。重置不自动登录。
- 原核心安全回归 49 项、命令归属与日志过滤断言 36 项通过。账号存储、哈希、执行器、防爆破和找回票据的 7 个核心文件与根工程逐字节一致。
- 无第三方插件时另测 18 项基础流程，验证可选依赖缺失不影响 MCLogin 启动和认证。
- 标准服务端日志中检查不到测试口令，虚拟铁砧物品与密码文本未进入玩家背包。第三方 SocialSpy、独立审计、代理日志、录屏和自定义持久化不在日志保护保证范围内，优先使用界面。

主版本组合共 336 项联调检查，另有最小依赖 18 项和认证冲突 4 项，总计 358 项协议 / 集成检查。构建断言 85 项单独统计。证据仅针对最终交付 JAR，打包脚本会核对其 SHA-256。

## 安装、账号迁移与边界

按 [插件版安装说明](../Paper-Spigot/README.md) 部署，先停服和备份。原 MCLogin `accounts.yml` 格式一致，可在 UUID 体系保持一致的前提下复制到 `plugins/MCLogin/accounts.yml`。更换正版 / 离线模式、代理 UUID 转发方式或 Floodgate 身份规则后，不能假定旧 UUID 与新玩家仍对应。插件配置另用 YAML，不能直接复制 Forge TOML 或 Fabric properties。

不导入 AuthMe / nLogin 等第三方密码库。AuthMe、LoginSecurity、nLogin、OpeNLogin、FastLogin、LimboAuth 被识别为重复认证系统；保留一种后完整重启，不通过热卸载或 `/mclogin reload` 解除锁定。

不声明支持 Folia、Arclight / Mohist 等混合核心、跨服共享会话、生产压力、所有第三方插件或真实基岩设备。此处的 Geyser / Floodgate 启用验证不能替代真实基岩端的菜单、铁砧输入和键盘验收。

## 重现与打包

隔离测试步骤见 [工具说明](../tools/README.md)。测试程序绑定回环地址、随机生成 RCON 密钥、使用离线测试身份和临时测试世界；不要用于正式世界。测试夹具拥有人工批准找回的能力，只能用于隔离环境，交付 ZIP 不包含它或任何第三方插件。

完成全部检查后执行 `python3 tools/package_plugin.py`，生成：

- `dist/Paper-Spigot/mclogin-paper-spigot-1.1.0.jar`
- `dist/MCLogin-1.1.0-plugin-compat.zip`
- `dist/plugin-manifest.json` 与 `dist/PLUGIN-SHA256SUMS`
- `docs/plugin-validation.json`

插件包与原有 `MCLogin-1.1.0-ports.zip` 分开保存，避免覆盖 12 份模组的清单。
