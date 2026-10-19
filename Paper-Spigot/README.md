# MCLogin Paper / Spigot 插件版 1.1.0

[项目首页](../README.md) · [版本总览](../docs/VERSIONS.md) · [安全说明](../SECURITY.md)

此工程将 MCLogin 的账号存储、PBKDF2、异步验证、防爆破与一次性找回授权接到 Bukkit API。它是独立插件，原有 Forge / Fabric 工程继续使用各自的模组 JAR。

## 安装

1. 关闭服务器，备份世界与账号库。
2. 将 `mclogin-paper-spigot-1.1.0.jar` 放入 `plugins/`，重启服务器。首次运行生成 `plugins/MCLogin/config.yml` 与账号库。
3. 根据游戏版本选择 Java：1.20.4 的测试环境使用 Java 17，其余五个目标版本使用 Java 21。插件字节码目标为 Java 17。
4. 控制台执行 `mclogin status` 和 `mclogin compat` 查看账号库健康状态与已启用的集成。

Paper 的目标版本为 1.20.4、1.20.6、1.21.1、1.21.4、1.21.8、1.21.11，具体实测结果见 [兼容记录](../docs/PLUGIN_COMPATIBILITY.md)。Spigot 使用 1.20.4 公共 API 编译，尚未单独运行 Spigot 服务端验收。Paper 的公共 `openAnvil` API 可用时提供原版箱子与铁砧界面；没有此 API 的 Spigot 环境回退到命令。客户端无需安装模组或资源包。

## 命令与权限

建议统一使用 `/mclogin` 前缀，命令冲突时使用 `/mclogin:mclogin` 或 `/mclogin:login`。

| 命令 | 行为 |
| --- | --- |
| `/mclogin`、`/mclogin menu` | 打开当前账号的登录或注册界面 |
| `/mclogin register <密码> <确认密码>` | 创建账号并登录 |
| `/mclogin login <密码>` | 验证当前账号 |
| `/mclogin logout` | 退出认证，立即重新施加限制 |
| `/mclogin changepassword <旧密码> <新密码>` | 已登录玩家验证旧密码后修改密码 |
| `/mclogin unregister <密码>` | 已登录玩家验证密码后删除账号 |
| `/mclogin reset` | 请求已安装验证器的外部身份核验 |
| `/mclogin reset <新密码> <确认密码>` | 消耗已通过核验的一次性授权 |
| `/mclogin status`、`compat`、`reload` | 管理功能，需要 `mclogin.admin` |

保留 `/login`、`/register`、`/changepassword`、`/unregister`、`/logout` 与 `/mlogin` 别名。密码长度为 8–128 字符，命令形式不支持包含空格的密码；Paper 界面更适合输入。插件不提供口令补全。`mclogin.admin` 默认授予 OP，可由 LuckPerms 等 Bukkit 权限插件管理；管理员与 OP 仍须登录。

`reload` 只更新经校验的配置快照，不重新读取账号库，也不解除认证冲突锁定。自动保存定时器的间隔在启动时确定，调整 `storage.autosave-seconds` 后需重启。插件增删、代理接入和集成变更均需完整重启，不支持 PlugMan 等热卸载工具。

## 登录限制与集成

未认证时阻止移动、聊天、传送、交互、攻击、物品操作、外部背包和非 MCLogin 认证命令。登录后不改写其他保护插件的事件结果。第三方插件不能通过授予管理权限让玩家跳过认证。

- LuckPerms：使用 Bukkit 权限检查，无自建权限缓存。
- EssentialsX、WorldEdit、WorldGuard：通过 Bukkit 命令和事件共同工作。
- Vault：保持权限与经济服务的原有提供者；MCLogin 不创建经济或权限提供者。
- PlaceholderAPI：自动注册 `%mclogin_authenticated%`、`%mclogin_registered%`、`%mclogin_status%`、`%mclogin_bedrock%`。
- Floodgate：只读取是否为基岩玩家，基岩玩家仍须 MCLogin 认证。Geyser/Floodgate 同时启动的验证不等于真实基岩客户端已验收。
- 登录状态服务、同步事件及外部找回接口见 [Bukkit API](../docs/BUKKIT_API.md)。未安装验证器时不显示找回按钮，找回默认不可用。

检测到 AuthMe、LoginSecurity、nLogin、OpeNLogin、FastLogin 或 LimboAuth 时锁定认证并拒绝入服，避免两套系统互相放行。保留一种认证系统后重启。此行为不代表支持导入这些插件的密码库。

## 数据与部署

账号保存在 `plugins/MCLogin/accounts.yml`，沿用模组版的 UUID 主键和文件格式。迁移时先停服、备份，再复制原 MCLogin 账号文件；必须保证玩家 UUID 生成及代理转发方式一致。不要把 Forge TOML 或 Fabric properties 配置改名成 YAML，插件版应按自己的 `config.yml` 配置。其他认证插件的账号文件不能直接复制。

账号库损坏时阻止验证和写入，修复后重启。密码仅存加盐 PBKDF2 结果，默认 600000 次迭代，哈希通过受限后台执行器计算。日志过滤器抑制标准服务端日志中的口令命令；第三方审计、SocialSpy、代理、录屏或自定义日志仍可能记录命令，推荐使用 Paper 界面并检查这些插件的配置。

UUID 必须来自可信身份验证或正确配置的可信代理。使用代理时限制后端端口只允许代理访问，并验证 UUID / IP 转发；Floodgate 配置应遵循其官方说明。IP 防爆破读取服务端实际获取的玩家地址，共享代理或 NAT 应按部署调整。原项目的 [部署前提](../SECURITY.md) 同样适用。

不声明支持 Folia、Forge+Bukkit 混合核心、跨服共享会话或任意第三方插件组合。测试夹具 `MCLoginTestFixture` 只能用于隔离测试，禁止放入正式服。

## 构建

```bash
cd Paper-Spigot
./gradlew build
```

需要可用的 JDK 17 工具链及 Gradle Wrapper 运行环境；可用 `-Porg.gradle.java.installations.paths=/path/to/jdk17` 指定工具链。`build` 自动执行 49 项安全回归与 36 项命令归属 / 日志保护断言。无需将 Spigot API、PlaceholderAPI 或日志库打入成品。第三方插件均为可选依赖。
