# MCLogin 1.1.0 · Minecraft 1.20.1 / Forge 原工程

[项目首页](../README.md) · [全部版本](VERSIONS.md) · [安装与配置](MOD_GUIDE.md)

仓库根目录保留最初 Forge 工程及历史提交；`src/`、根构建脚本和 Gradle Wrapper 均用于此版本。其他版本位于各自的 `版本+加载器/` 目录，插件版位于 `Paper-Spigot/`。

| 项目 | 基线 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.4.10 |
| Java | 17 |
| Gradle Wrapper | 8.1.1 |
| 产物 | `build/libs/mclogin-1.1.0.jar` |
| 安装位置 | 服务端 `mods/` |
| 配置 | `<世界目录>/serverconfig/mclogin-server.toml` |
| 账号库 | `config/accounts.yml` |

## 构建与安装

设置 `JAVA_HOME` 指向 JDK 17，在仓库根目录执行：

```bash
./gradlew build --console=plain
# Windows: gradlew.bat build --console=plain
```

首次构建需要访问 Gradle / Forge / Maven 依赖仓库。停服备份后把 `build/libs/mclogin-1.1.0.jar` 放入 Forge 服务端 `mods/`，重启生成配置。首次注册或状态写入时按需创建账号库。完整命令、配置、迁移和排错见[模组指南](MOD_GUIDE.md)。

## 功能与验证

基线 1.1.0 包括按注册状态显示的原版登录界面、未认证关闭后的自动重开、铁砧确认与提示、倒计时，以及需外部验证器的密码找回。密码重置默认不可用，重置后须重新登录。

2026-10-16 基线记录完成真实构建和 49 项安全断言，没有完成该版本的真实客户端 / 服务端验收；后续移植工程或 Paper 的测试不能作为本目录已运行验收的证据。详见[原始验证记录](VALIDATION_1.1.0.md)及[找回 API](PASSWORD_RESET_API.md)。
