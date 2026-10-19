# MCLogin · Minecraft 服务端登录验证

MCLogin 为 Minecraft 服务端提供独立的账号密码验证。玩家通过原版菜单、铁砧输入或命令完成注册与登录，验证前限制活动；密码使用随机盐和 PBKDF2WithHmacSHA256 存储，计算在有界后台线程池中执行。

仓库包含 **Forge 1.20.1 原工程、6 个游戏版本 × Forge/Fabric 共 12 个独立工程，以及 Paper / Spigot API 插件工程**。MCLogin 本身无需客户端模组或资源包。

## 选择版本

| Minecraft | Forge 工程 / 固定依赖 | Fabric 工程 / 固定 API | Paper 插件 / 实测 build | 游戏 Java |
| --- | --- | --- | --- | --- |
| 1.20.1 | [根目录原工程](docs/FORGE_1_20_1.md) · 47.4.10 | — | — | 17 |
| 1.20.4 | [1.20.4+Forge](1.20.4+Forge/) · 49.2.0 | [1.20.4+Fabric](1.20.4+Fabric/) · 0.97.3+1.20.4 | [插件版](Paper-Spigot/) · 499 | 17 |
| 1.20.6 | [1.20.6+Forge](1.20.6+Forge/) · 50.2.1 | [1.20.6+Fabric](1.20.6+Fabric/) · 0.100.8+1.20.6 | [插件版](Paper-Spigot/) · 151 | 21 |
| 1.21.1 | [1.21.1+Forge](1.21.1+Forge/) · 52.1.2 | [1.21.1+Fabric](1.21.1+Fabric/) · 0.116.17+1.21.1 | [插件版](Paper-Spigot/) · 133 | 21 |
| 1.21.4 | [1.21.4+Forge](1.21.4+Forge/) · 54.1.6 | [1.21.4+Fabric](1.21.4+Fabric/) · 0.119.4+1.21.4 | [插件版](Paper-Spigot/) · 232 | 21 |
| 1.21.8 | [1.21.8+Forge](1.21.8+Forge/) · 58.1.26 | [1.21.8+Fabric](1.21.8+Fabric/) · 0.136.1+1.21.8 | [插件版](Paper-Spigot/) · 60 | 21 |
| 1.21.11 | [1.21.11+Forge](1.21.11+Forge/) · 61.2.4 | [1.21.11+Fabric](1.21.11+Fabric/) · 0.141.6+1.21.11 | [插件版](Paper-Spigot/) · 132 | 21 |

Fabric 固定验证 Loader 0.19.5，需另装对应 Fabric API。表中是实际验证的固定组合，不代表其他补丁版本均兼容。Paper 六版本使用同一份插件 JAR；**Spigot 仅完成公共 API 编译兼容，尚无独立核心运行验收**。完整文件名和构建要求见[版本总览](docs/VERSIONS.md)。

## 下载与安装

从 [GitHub Releases](https://github.com/Dest2311e/MC-Login-Plugin/releases) 选择平台：

- **Forge / Fabric**：下载 `MCLogin-1.1.0-ports.zip`，取出与游戏版本、加载器一致的 JAR，放入服务端 `mods/`。Forge 1.20.1 保留根工程构建方式，见[原工程指南](docs/FORGE_1_20_1.md)。
- **Paper**：下载 `mclogin-paper-spigot-1.1.0.jar`，放入 `plugins/`；也可下载带说明和校验记录的 `MCLogin-1.1.0-plugin-compat.zip`。
- 停服、备份后安装并重启，不同时运行多个认证系统。按发布的 SHA-256 校验文件核对下载内容。

模组与 Bukkit 插件属于不同平台，请按上面的目录安装。Paper 支持原版菜单和铁砧流程；缺少 Paper 公共铁砧 API 的 Spigot 环境回退到命令认证。

## 功能与集成

- 注册、登录、修改密码和注销账号；注册成功自动登录，密码重置成功后仍需重新登录。
- 原版界面、账号状态对应按钮、登录倒计时与提示；默认无找回验证器，不显示可用的找回入口。
- PBKDF2 默认 600000 次迭代，随机盐、常量时间比较、异步计算、有限队列和请求冷却。
- UUID 账号库、原子替换、备份与异常加载保护；账号库加载失败后阻止覆盖写入。
- 账号与 IP 防爆破，以及未登录活动和命令限制。
- 插件版接入 LuckPerms 权限和 PlaceholderAPI；与 EssentialsX、Vault、WorldEdit、WorldGuard 有固定版本联调。Geyser/Floodgate 仅检查共同启用及身份标志，未完成真实基岩客户端验收。
- 插件版提供 Bukkit 登录状态服务、同步认证事件和外部身份核验接口。AuthMe 等重复认证插件触发冲突锁定，不提供第三方密码库导入。

## 使用说明

| 需要做什么 | 入口 |
| --- | --- |
| 选择版本、确认依赖与文件名 | [版本总览](docs/VERSIONS.md) |
| 安装模组、配置、命令与账号迁移 | [Forge / Fabric 使用指南](docs/MOD_GUIDE.md) |
| 使用原 Forge 1.20.1 工程 | [原工程说明](docs/FORGE_1_20_1.md) |
| 安装插件、管理权限、处理冲突 | [Paper / Spigot 使用说明](Paper-Spigot/README.md) |
| 查看主流插件测试组合与边界 | [插件兼容记录](docs/PLUGIN_COMPATIBILITY.md) |
| 接入插件登录状态与找回验证 | [Bukkit API](docs/BUKKIT_API.md) |
| 接入模组密码找回验证 | [模组找回 API](docs/PASSWORD_RESET_API.md) |
| 了解部署身份、日志与备份要求 | [安全说明](SECURITY.md) |
| 构建、生成工程、重现集成检查 | [开发工具](tools/README.md) · [移植说明](docs/PORTING.md) |
| 查看此次交付内容 | [更新记录](CHANGELOG.md) |

## 验证范围

12 个移植工程均已构建并通过每工程 49 项安全断言及开发服务端协议检查；1.20.4 和 1.21.11 的 Forge / Fabric 另以正式成品 JAR 验证。根 Forge 1.20.1 的 1.1.0 基线记录为构建及 49 项断言，未进行该基线的真实客户端验收。

Paper 同一份最终 JAR 在六个固定版本组合中每组通过 56 项检查，另有最小依赖 18 项和 AuthMe 冲突 4 项，共 **358 项运行检查**；构建断言 **85 项** 单独统计。精确环境、检查名称、产物和日志指纹保存在 [模组验证记录](docs/port-validation.json) 和 [插件验证记录](docs/plugin-validation.json)。

未声明支持 NeoForge、Folia、混合核心、跨服共享会话、生产压力或全部第三方插件。协议检查不替代人工外观、声音及真实基岩设备验收。

## 仓库结构与构建

```text
src/                       Forge 1.20.1 原工程源码
1.20.4+Forge/ …             六个版本各自的 Forge 工程
1.20.4+Fabric/ …            六个版本各自的 Fabric 工程
Paper-Spigot/              Bukkit 插件工程
docs/                      安装、接口、兼容与验证说明
tools/                     生成、构建、协议检查与打包工具
```

各工程带独立 Gradle Wrapper，在对应目录执行 `./gradlew build`（Windows 使用 `gradlew.bat build`）。Forge 1.21.8 / 1.21.11 构建额外需要 JDK 25，开发启动器还解析 JDK 8；游戏运行使用 Java 21。详细命令见各目录说明。

账号文件、服务端运行目录、下载依赖、测试日志与 `dist/` 产物不进入源码提交。测试夹具仅供隔离环境，发布包不包含它及第三方插件。
