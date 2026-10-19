# MCLogin 1.1.0 · Minecraft 1.21.11 / Forge

[返回项目首页](../README.md) · [全部版本](../docs/VERSIONS.md) · [模组安装与配置](../docs/MOD_GUIDE.md)

服务端登录验证模组，提供注册、登录、改密、注销、原版菜单与铁砧输入。仅在服务端安装；MCLogin 本身不要求客户端模组或资源包，其他服务端模组可能有各自的客户端要求。

## 版本与文件

| 项目 | 本工程 |
| --- | --- |
| Minecraft | **1.21.11** |
| 固定验证依赖 | Forge **61.2.4** |
| 游戏运行 Java | **21** |
| 交付文件 | `mclogin-1.21.11-forge-1.1.0.jar` |
| 安装目录 | 服务端 `mods/` |
| 配置文件 | `<世界目录>/serverconfig/mclogin-server.toml` |
| 账号库 | `config/accounts.yml` |

只选择与 Minecraft 和加载器同时对应的 JAR；不推断相邻游戏补丁版本兼容。Fabric 需要另外安装上述对应版本的 Fabric API。

## 安装与使用

1. 停服并备份世界、配置及账号库。
2. 安装表中的服务端加载器和依赖，将 `mclogin-1.21.11-forge-1.1.0.jar` 放入 `mods/`。
3. 重启，按需调整生成的配置。配置路径中的世界目录由服务端世界设置决定。
4. 玩家进服后使用菜单或 `/register <密码> <确认密码>`、`/login <密码>` 完成认证；管理命令为 `/mclogin status` 与 `/mclogin reload`。

未登录时默认限制移动、聊天、交互和非认证命令，默认 120 秒超时。密码找回须接入外部身份验证器，默认不可用。完整命令、配置和迁移步骤见[使用指南](../docs/MOD_GUIDE.md)，部署身份要求见[安全说明](../SECURITY.md)。

## 构建

在本目录运行，先安装可被 Gradle 检测到的 JDK 21：

```bash
./gradlew build --console=plain
# Windows: gradlew.bat build --console=plain
```

成品为 `build/libs/mclogin-1.21.11-forge-1.1.0.jar`，不要安装 `-sources` 或 `-dev` 文件。`build` 包含 49 项安全回归断言。

ForgeGradle 7 构建还需 JDK 25，开发启动器另外解析 JDK 8；游戏服务器运行仍使用 Java 21。详见[构建工具链](../docs/PORTING.md#构建)。

## 验证范围

已完成构建、安全断言及开发服务端协议检查。本版本另已完成正式成品 JAR 的独立服务端协议检查。各项检查、依赖指纹和边界见[移植与验证说明](../docs/PORTING.md)及[验证数据](../docs/port-validation.json)。协议测试不替代人工界面、音效或所有模组组合的验收。

本目录由 [generate_ports.py](../tools/generate_ports.py) 生成。修改公共逻辑时应同步生成器或模板；重新生成前先停止构建与测试服务端。
