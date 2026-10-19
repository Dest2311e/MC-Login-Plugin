# Forge / Fabric 模组使用指南

[项目首页](../README.md) · [版本与依赖](VERSIONS.md) · [安全说明](../SECURITY.md)

## 安装

1. 停服，备份世界、配置和原 MCLogin 账号库。
2. 按 [版本总览](VERSIONS.md) 选择游戏版本、加载器与 Java。Fabric 另装对应 Fabric API。
3. 将对应正式 JAR 放入服务端 `mods/`，重启。不要安装 `-sources` 或 `-dev` JAR。
4. 检查启动日志，进服执行注册或登录；用 `/mclogin status` 检查状态。

MCLogin 自身仅服务端安装，菜单和声音使用原版资源。原版客户端能否加入整个服务器还取决于其余模组。

## 配置与账号位置

| 平台 | 配置文件 | 账号库 |
| --- | --- | --- |
| Forge（含根工程 1.20.1） | `<世界目录>/serverconfig/mclogin-server.toml` | `config/accounts.yml` |
| Fabric | `config/mclogin-server.properties` | `config/accounts.yml` |
| Paper 插件（另见插件指南） | `plugins/MCLogin/config.yml` | `plugins/MCLogin/accounts.yml` |

Forge 使用 SERVER 类型配置，世界目录通常为 `world/`，不在全局 `config/`。账号库按需创建，首次启动没看到文件不一定是故障。不同平台的配置语法不同，不能直接改名互用。

以下为模组配置键；Forge 使用段名与键名，Fabric 使用去掉段名前缀的扁平键名，例如 `pbkdf2Iterations=600000`、`enableLoginUi=true`。完整可用键以启动生成的文件和各工程配置源码为准。

| 配置键 | 默认值 | 用途 |
| --- | --- | --- |
| `password.minPasswordLength` / `maxPasswordLength` | 6 / 64 | 密码长度限制 |
| `password.pbkdf2Iterations` | 600000 | PBKDF2 计算次数 |
| `restriction.loginTimeoutSeconds` | 120 | 未登录踢出时间，0 关闭超时踢出 |
| `restriction.allowMove` / `allowInteract` / `allowChat` | false | 未认证活动限制 |
| `bruteforce.maxFailedAttempts` / `lockSeconds` / `failWindowSeconds` | 5 / 300 / 600 | 账号失败阈值、锁定及衰减秒数 |
| `bruteforce.enableIpGuard` | true | IP 防爆破 |
| `bruteforce.ipMaxFailedAttempts` / `ipLockSeconds` / `ipFailWindowSeconds` | 20 / 300 / 600 | IP 独立阈值与时间 |
| `storage.autosaveIntervalSeconds` | 60 | 自动保存周期 |
| `storage.storeLastIp` | true | 是否持久化最近 IP |
| `session.enableSessionAutoLogin` / `sessionTimeoutMinutes` | false / 5 | 仅正版验证模式允许的同 IP 限时免登录 |
| `general.language` | zh_cn | 服务端提示语言，可用 en_us |
| `ui.enableLoginUi` | true | 登录界面与未登录关闭后重开 |
| `sounds.enableSounds` / `soundVolume` / `soundPitch` | true / 1.0 / 1.0 | 原版提示音效 |

旧配置中显式保存的值会继续使用，升级后不要假定已自动采用新默认值。改配置后完整重启；`/mclogin reload` 用于模组账号库重载，不作为配置热重载保证。Paper 插件的同名命令语义不同，见其指南。

## 玩家与管理命令

| 命令 | 说明 |
| --- | --- |
| `/register <密码> <确认密码>` | 未注册玩家创建账号，成功后登录 |
| `/login <密码>` | 已注册玩家验证密码 |
| `/changepassword <旧密码> <新密码>` | 已登录玩家修改密码 |
| `/unregister <密码>` | 已登录玩家删除账号 |
| `/mclogin` | 打开账号对应界面 |
| `/mclogin status` | 管理员查看状态，权限等级 3 |
| `/mclogin reload` | 管理员重载账号数据，权限等级 3 |

命令中的密码不允许空格。默认未登录限制移动、聊天、交互、攻击、拾取/丢弃与非认证命令。异步计算期间重复请求会被限流；“繁忙”不计入密码错误次数。

找回流程默认没有验证器。管理员需接入已绑定身份的外部核验服务，验证通过后才能两次输入新密码；一次授权限时有效，重置后还要使用新密码登录。见[模组找回 API](PASSWORD_RESET_API.md)。

## 升级与迁移

停服后备份 `accounts.yml`，在 UUID 体系不变的前提下复制到目标 MCLogin 账号路径；使用目标平台新生成的配置，逐项迁移设置。先用备份副本在隔离环境验证登录，再切换正式服。不要让两个服务器同时写同一文件。

改变正版/离线模式、代理转发或基岩身份规则可能改变 UUID。文件格式相同不表示玩家身份仍能对应。不能直接导入 AuthMe 等其他认证插件的密码库。已有账号按自身保存的迭代次数验证，成功登录后按当前配置重新派生；没有玩家明文密码，无法瞬时批量转换所有哈希。

## 运维与排错

- **界面反复打开**：未登录时的默认行为，先完成认证；需要调整时修改 UI 配置后重启。
- **找不到配置**：检查 Forge 世界目录或 Fabric 全局配置路径，确认安装了正确平台 JAR。
- **账号库加载错误**：停止写入，保留原文件和 `.bak`、`.failed-*`、`.unparsable-*` 等副本，修复后重启。不要通过删除文件掩盖损坏，否则玩家可能被当作未注册。
- **需要计划性清空测试账号**：先停服备份，使用同一行的 `accounts: {}` 显式空库标记；生产环境清空会使全部账号需要重新注册。
- **保存周期与崩溃**：原子替换降低半写文件风险，强制终止或断电仍可能丢失尚未落盘的数据。定期验证备份能恢复。
- **代理或共享出口误锁**：先核对实际 UUID/IP 转发，再按共享出口人数调整 IP 守卫阈值。部署边界见[安全说明](../SECURITY.md)。
