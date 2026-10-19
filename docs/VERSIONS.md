# MCLogin 版本总览

[项目首页](../README.md) · [模组使用指南](MOD_GUIDE.md) · [插件说明](../Paper-Spigot/README.md)

所有工程当前项目版本均为 **1.1.0**。下表按 Minecraft 游戏版本区分，Java 指游戏运行环境；构建要求另列。依赖号为已固定验证的组合，并非最新版本声明。

| Minecraft | 平台 | 工程 | 固定依赖 | Java | 正式产物 |
| --- | --- | --- | --- | --- | --- |
| 1.20.1 | Forge | [根目录](FORGE_1_20_1.md) | Forge 47.4.10 | 17 | `mclogin-1.1.0.jar`（根工程构建） |
| 1.20.4 | Forge | [1.20.4+Forge](../1.20.4+Forge/) | Forge 49.2.0 | 17 | `mclogin-1.20.4-forge-1.1.0.jar` |
| 1.20.4 | Fabric | [1.20.4+Fabric](../1.20.4+Fabric/) | Loader 0.19.5 / API 0.97.3+1.20.4 | 17 | `mclogin-1.20.4-fabric-1.1.0.jar` |
| 1.20.6 | Forge | [1.20.6+Forge](../1.20.6+Forge/) | Forge 50.2.1 | 21 | `mclogin-1.20.6-forge-1.1.0.jar` |
| 1.20.6 | Fabric | [1.20.6+Fabric](../1.20.6+Fabric/) | Loader 0.19.5 / API 0.100.8+1.20.6 | 21 | `mclogin-1.20.6-fabric-1.1.0.jar` |
| 1.21.1 | Forge | [1.21.1+Forge](../1.21.1+Forge/) | Forge 52.1.2 | 21 | `mclogin-1.21.1-forge-1.1.0.jar` |
| 1.21.1 | Fabric | [1.21.1+Fabric](../1.21.1+Fabric/) | Loader 0.19.5 / API 0.116.17+1.21.1 | 21 | `mclogin-1.21.1-fabric-1.1.0.jar` |
| 1.21.4 | Forge | [1.21.4+Forge](../1.21.4+Forge/) | Forge 54.1.6 | 21 | `mclogin-1.21.4-forge-1.1.0.jar` |
| 1.21.4 | Fabric | [1.21.4+Fabric](../1.21.4+Fabric/) | Loader 0.19.5 / API 0.119.4+1.21.4 | 21 | `mclogin-1.21.4-fabric-1.1.0.jar` |
| 1.21.8 | Forge | [1.21.8+Forge](../1.21.8+Forge/) | Forge 58.1.26 | 21 | `mclogin-1.21.8-forge-1.1.0.jar` |
| 1.21.8 | Fabric | [1.21.8+Fabric](../1.21.8+Fabric/) | Loader 0.19.5 / API 0.136.1+1.21.8 | 21 | `mclogin-1.21.8-fabric-1.1.0.jar` |
| 1.21.11 | Forge | [1.21.11+Forge](../1.21.11+Forge/) | Forge 61.2.4 | 21 | `mclogin-1.21.11-forge-1.1.0.jar` |
| 1.21.11 | Fabric | [1.21.11+Fabric](../1.21.11+Fabric/) | Loader 0.19.5 / API 0.141.6+1.21.11 | 21 | `mclogin-1.21.11-fabric-1.1.0.jar` |
| 1.20.4 / 1.20.6 / 1.21.1 / 1.21.4 / 1.21.8 / 1.21.11 | Paper | [Paper-Spigot](../Paper-Spigot/) | Paper build 见[实测矩阵](PLUGIN_COMPATIBILITY.md) | 17 / 其余 21 | `mclogin-paper-spigot-1.1.0.jar` |

## 安装与下载对应关系

- 12 个移植模组在 `MCLogin-1.1.0-ports.zip` 中按 `版本+加载器/` 分目录；取对应 JAR 放入 `mods/`，Fabric 另装对应 Fabric API。
- Paper 六个版本共用插件 JAR，放入 `plugins/`。`MCLogin-1.1.0-plugin-compat.zip` 另附插件说明、兼容与验证清单。
- 根 Forge 1.20.1 使用原工程构建，未混入上述 12 个移植包。
- Spigot 只完成 API 编译兼容，未单独运行核心验收；缺少 Paper 公共铁砧 API 时回退命令。
- 不将表中某一补丁版本的结果扩展到整个系列，不将 Forge 成品用于 NeoForge 或混合核心。

## 构建要求

| 工程 | 编译 / 构建要求 |
| --- | --- |
| 根 Forge 1.20.1 | JDK 17，Gradle Wrapper 8.1.1 |
| 1.20.4 Forge / Fabric | Java toolchain 17；Fabric 的 Gradle 可运行于 Java 21 |
| 1.20.6、1.21.1、1.21.4 Forge / Fabric | JDK 21 |
| 1.21.8、1.21.11 Fabric | JDK 21 |
| 1.21.8、1.21.11 Forge | 编译 / 游戏 JDK 21；Mavenizer 额外 JDK 25；开发启动器还解析 JDK 8 |
| Paper-Spigot | Java toolchain 17，Gradle Wrapper 8.14.3 |

各目录有独立 Wrapper，进入对应目录执行 `./gradlew build`。批量构建和工具链参数见[工具说明](../tools/README.md)。

## 验证层级

根 Forge 1.20.1 保留 2026-10-16 基线构建与 49 项断言记录。12 个移植工程均有构建、49 项安全断言和开发协议检查；仅 1.20.4 与 1.21.11 的 Forge / Fabric 另有正式成品独立开服协议检查。Paper 六个固定组合、最小依赖和 AuthMe 冲突共 8 个运行场景、358 项检查，构建断言另计 85 项。

详细清单及边界：[根基线](VALIDATION_1.1.0.md)、[模组移植](PORTING.md)、[插件兼容](PLUGIN_COMPATIBILITY.md)。
