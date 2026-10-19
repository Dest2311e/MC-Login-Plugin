# MCLogin 跨版本移植

本次以仓库根目录的 Forge 1.20.1、MCLogin 1.1.0 源码为基线，分别提供 `版本号+Forge`、`版本号+Fabric` 独立工程。根目录仍是原 1.20.1 工程。此批覆盖 1.20.4、1.20.6、1.21.1、1.21.4、1.21.8、1.21.11 六条版本线；不承诺其他补丁版本、NeoForge 或 26.x 兼容。

## 原项目分析

这实际是**服务端模组**，不是 Bukkit/Paper 插件。客户端使用原版协议即可连接，箱子菜单、铁砧输入、BossBar、提示文字与声音均由服务端提供。

核心逻辑可分为四层：

- `security/`：PBKDF2WithHmacSHA256、随机盐、常量时间比较、有限队列异步计算；账号和 IP 分别记录失败次数、锁定和衰减窗口。密码找回通过 `PasswordResetVerifier` 扩展点接入外部身份核验，默认不提供找回能力。
- `data/` 与 `session/`：以 UUID 为主键，保留 YAML 账号格式、原子替换、备份、加载失败时禁止覆盖的策略；在线登录态与账号数据分开。会话免登录默认关闭，仅在正版验证模式下允许。
- `command/` 与 `ui/`：注册、登录、改密、注销和管理员命令；界面依据注册状态显示可用操作，密码输入采用两次确认，哈希计算结束后才改变认证状态。
- 加载器适配：Forge 生命周期、命令注册、配置、玩家与交互事件是主要耦合点。移植保留前三层，替换或调整这一层。

账号文件加载失败后仍保持拒绝注册和覆盖，移植没有改成“读取失败即空库”。密码找回票据的一次有效、连接绑定、限时和账号变化检查也保持原实现。

## 版本与依赖

| Minecraft | Forge | Fabric Loader | Fabric API | 游戏运行 JDK |
|---|---|---|---|---|
| 1.20.4 | 49.2.0 | 0.19.5 | 0.97.3+1.20.4 | 17 |
| 1.20.6 | 50.2.1 | 0.19.5 | 0.100.8+1.20.6 | 21 |
| 1.21.1 | 52.1.2 | 0.19.5 | 0.116.17+1.21.1 | 21 |
| 1.21.4 | 54.1.6 | 0.19.5 | 0.119.4+1.21.4 | 21 |
| 1.21.8 | 58.1.26 | 0.19.5 | 0.136.1+1.21.8 | 21 |
| 1.21.11 | 61.2.4 | 0.19.5 | 0.141.6+1.21.11 | 21 |

表中为工程固定并验证的依赖组合，未将“可构建”推断为其他依赖版本都兼容。Fabric 服务端还要安装对应 Minecraft 版本的 Fabric API；Forge 成品不要放入 Fabric，反之亦然。

## 接口适配

- 1.20.4 使用物品 NBT 设置名称和 Lore；1.20.6 起改用 `DataComponents.CUSTOM_NAME` 与 `ItemLore`，避免编译通过后界面文字失效。
- Forge 1.20.4 成品执行 `reobfJar`；1.20.6 起设置 `reobf=false`，适配 Forge 的正式运行映射。Fabric 成品使用 Loom 的 `remapJar`。
- 1.21.4 起适配声音注册项的 `location()`。
- Forge 1.21.8/1.21.11 使用 ForgeGradle 7 和 EventBus 7；取消监听返回 `boolean`，交互事件按具体子类订阅。1.21.11 tick 事件改为 `Pre`/`Post` 与 record 访问器。
- Forge 开发启动将类与资源合并到同一输出目录，确保加载器能同时发现 `@Mod` 和 `mods.toml`。
- Fabric 用服务端生命周期、玩家加入/离开/重生、tick 和命令回调连接原服务。配置改为 Java properties，避免把 Forge 配置类带进 Fabric。
- Fabric 在原版处理移动、使用、破坏、实体交互与菜单数据包之前拦截；物品拾取与丢弃另有实体 Mixin，登录菜单单独放行。命令从 Brigadier 执行入口校验白名单，聊天通过 Fabric 事件拦截。
- Fabric 铁砧通过 Accessor 设置费用槽，不调用仅 Forge 提供的扩展方法。较新版本的三参数 `drop` 已移动到 `ServerPlayer`，对应 Mixin 也随之改变。

源码生成入口为 `tools/generate_ports.py`，Fabric 加载器适配模板在 `tools/port-templates/fabric/`。生成器只替换目标工程的 `src/`、构建与说明文件，保留 `run/` 和 `build/`；请先停止构建和测试服务器再生成。单目录同样可以独立构建，不依赖运行时加载根目录源码。

## 构建

在目标目录执行：

```bash
./gradlew build --console=plain
# Windows: gradlew.bat build --console=plain
```

`build` 会执行 `securityChecks`。该任务运行普通 Java 主类中的断言，不依赖 JUnit 测试发现；Gradle 9 的空 JUnit 发现错误已单独关闭，实际回归任务仍为必跑。

- 1.20.4 使用 JDK 17 编译；Fabric 的 Gradle 进程可运行在 JDK 21，Java toolchain 仍需能找到 JDK 17。
- 其余工程使用 JDK 21 编译。
- Forge 1.21.8/1.21.11 额外需要 **JDK 25** 运行 ForgeGradle 7 的 Minecraft Mavenizer。它与游戏运行所需 JDK 21 是两回事。若 Gradle 未自动检测到，可传入：

```bash
./gradlew build -Porg.gradle.java.installations.paths=/path/to/jdk-21,/path/to/jdk-25
```

ForgeGradle 7 的 `runServer` 开发启动器另会解析 JDK 8 toolchain，本次开发连服验证同时提供 JDK 8、21、25。这个要求来自开发启动器；安装成品的游戏服务器使用表中的 JDK，独立开服验证不使用 Gradle。

首次构建需下载 Gradle、Minecraft、映射、加载器和依赖；Forge 较新工具链还会反编译并重建游戏依赖，需要较多内存。不要在小内存机器同时运行全部工程。

成品名为 `build/libs/mclogin-<Minecraft>-forge-1.1.0.jar` 或 `mclogin-<Minecraft>-fabric-1.1.0.jar`。安装时不要选择 `-dev`、`-sources` 或 `build/devlibs/` 下的文件。

## 配置与账号迁移

Forge 继续使用 `<世界>/serverconfig/mclogin-server.toml`。Fabric 首次启动生成 `<服务端>/config/mclogin-server.properties`，示例：

```properties
language=zh_cn
loginTimeoutSeconds=120
allowMove=false
allowInteract=false
allowChat=false
enableLoginUi=true
pbkdf2Iterations=600000
```

以实际生成的 23 项配置为准。键名和默认策略与原配置对应，但 **TOML 文件不能直接改名当 properties 使用**；Fabric 配置当前在开服时读取，修改后重启，非法数值会阻止启动并报错。

两个加载器均使用 `<服务端>/config/accounts.yml`。迁移前停服，备份账号库及 `.bak` 文件，再复制账号库到新服务器相同位置。不要同时让两个进程写同一份文件。UUID 身份来源必须保持一致；从离线模式改为正版验证模式会改变 UUID，不会自动合并账号。Minecraft 世界升级是另一项操作，跨大版本回滚须使用原世界备份。

Fabric 与 Forge 的密码找回接口签名保留，扩展实现若引用加载器类型，需要随目标加载器重新构建。外部短信/邮件/绑定服务未在本次测试中接入。接口约定见 [PASSWORD_RESET_API.md](PASSWORD_RESET_API.md)。

## 验证范围

本次 12 个工程全部构建成功，每个工程均通过 49 项安全回归（重置票据 20 项、存储 29 项）。故意损坏账号文件的用例会输出拒绝保存的 ERROR，这属于预期断言路径。以下数字是实际完成的检查数。

| Minecraft | Forge 构建 / 安全回归 | Forge 开发连服 | Fabric 构建 / 安全回归 | Fabric 开发连服 |
|---|---|---|---|---|
| 1.20.4 | 通过 / 49 | 通过 / 18 | 通过 / 49 | 通过 / 17 |
| 1.20.6 | 通过 / 49 | 通过 / 17 | 通过 / 49 | 通过 / 17 |
| 1.21.1 | 通过 / 49 | 通过 / 18 | 通过 / 49 | 通过 / 17 |
| 1.21.4 | 通过 / 49 | 通过 / 18 | 通过 / 49 | 通过 / 17 |
| 1.21.8 | 通过 / 49 | 通过 / 18 | 通过 / 49 | 通过 / 17 |
| 1.21.11 | 通过 / 49 | 通过 / 18 | 通过 / 49 | 通过 / 18 |

此外，在独立安装加载器的空白服务器中直接加载最终发布 JAR，完成四组开服及协议连服检查；测试使用的 JAR SHA-256 与交付清单逐一一致。

| 成品 | 游戏 JDK | 结果 |
|---|---|---|
| 1.20.4 + Forge | 17 | 通过 / 18 |
| 1.20.4 + Fabric | 17 | 通过 / 18 |
| 1.21.11 + Forge | 21 | 通过 / 18 |
| 1.21.11 + Fabric | 21 | 通过 / 18 |

17 项检查的早期脚本与 18 项检查的后续脚本覆盖同一认证流程，后者另外记录重连后的世界与命令同步。检查包括未认证菜单、关闭重开、命令与移动/物品限制、铁砧两次输入注册、异步哈希、重连认证、错误密码拒绝和成功登录后解除限制。四组成品测试均使用后续脚本。

构建、依赖、源码指纹、成品哈希、检查名称与日志摘要见 [机器可读验证记录](port-validation.json)。原始测试日志保存在本机 `.codex-work/build-logs/` 与 `.codex-work/runtime/`，不随源码提交。记录对应本次受测组合；其他补丁与加载器版本需另行验证。

协议客户端固定为 `minecraft-protocol 1.68.0`。其中 1.20.6/1.21.1 的上游配方解码器无法完整读取原版配方同步包，测试仅将该包保留为原始字节；本次不验证配方，但菜单、物品与认证相关包仍按协议解码。较新版本测试会应答客户端加载完成与区块批次，并使用对应的菜单物品格式。

本地协议测试使用隔离的回环地址服务器与临时账号，验证菜单、注册、重连登录、错误密码、命令限制及移动/物品限制。它不能代替真人检查界面美观、声音效果，也不涵盖大型整合包、代理转发、生产并发压力、外部身份验证器或全部配置组合。
