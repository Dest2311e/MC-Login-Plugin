# 移植维护与验证工具

这些工具只在开发与隔离测试时使用，发布 JAR 不依赖 Python 或 Node.js。

## 重新生成工程

```bash
python3 tools/generate_ports.py                       # 全部 12 个工程
python3 tools/generate_ports.py --version 1.21.11 --loader Forge
```

生成器从根目录 Forge 1.20.1 基线与 `port-templates/fabric/` 生成独立工程，会替换目标目录的源码和构建说明。修改共用逻辑时先改根源码，修改加载器差异时改生成器/模板；在生成之前停止对应版本的构建和服务器。`run/` 中的测试数据会保留，不要在生产服务器目录运行。

## 批量构建与成品收集

```bash
python3 tools/build_ports.py --jdk17 /path/to/jdk17 --jdk21 /path/to/jdk21 --jdk25 /path/to/jdk25
```

默认使用各工程 Gradle Wrapper；可以用 `--gradle8 /path/to/gradle8/bin/gradle --gradle9 /path/to/gradle9/bin/gradle` 选择已安装工具。`--version` 和 `--loader` 可重复，用于筛选。每次调用覆盖 `dist/manifest.json` 和 `dist/SHA256SUMS`，完整交付请构建全部工程。

日志保存在 `.codex-work/build-logs/`。安全回归通过 `check -> securityChecks` 执行，共 49 项断言；故意损坏存储文件时的 ERROR 是测试预期。

## 隔离连服测试

需要 Node.js 22 或更新版本，以及 Python 3。安装固定依赖：

```bash
npm ci --prefix tools/integration
python3 tools/integration/run.py 1.21.1 Fabric --port 25611
```

若依赖安装在其他目录，设置 `NODE_PATH=/path/to/node_modules`。可用 `--gradle` 指定 Gradle。ForgeGradle 7 的开发启动另外需要指定 `--jdk8 /path/to/jdk8 --jdk25 /path/to/jdk25`；运行 Gradle 的 `JAVA_HOME` 应指向 JDK 21。Forge 1.20.4 的 `JAVA_HOME` 使用 JDK 17。

测试器只绑定回环地址、设定和平模式，以临时账号验证菜单、注册、重连登录、错误口令、命令限制、移动及物品限制。它会在工程 `run/` 中写测试配置、接受测试服务端 EULA、生成测试世界和账号，并在结束后停止服务器。已有未标记的服务器配置会拒绝覆盖。不要给它生产世界。

也支持安装成品后的独立服务器；先在空目录安装相应加载器、Fabric API（Fabric 需要）以及待测 JAR：

```bash
python3 tools/integration/run.py 1.21.11 Fabric \
  --production-dir /path/to/isolated-test-server --port 25611 \
  --launch java -Xmx1G -jar fabric-server-launch.jar nogui
```

`--launch` 必须是最后一个选项。Forge 可使用 `--launch bash run.sh nogui`，并确保 `PATH` 中的 Java 是对应游戏版本需要的版本。

原始服务器日志、协议检查日志与结果 JSON 位于 `.codex-work/runtime/`。汇总证据见 `docs/port-validation.json`，测试范围及限制见 `docs/PORTING.md`。协议测试不模拟真人视觉、整合包兼容或生产压力。


## Bukkit 插件构建与联调

```bash
cd Paper-Spigot
./gradlew build
```

插件工程使用 Java 17 工具链，构建自动执行安全回归 49 项、兼容策略与日志保护断言 36 项。`Paper-Spigot/src/` 中的账号模型、存储、密码哈希、防爆破、异步执行器、找回票据和原有测试复制自根工程，公共逻辑变更后应逐文件同步并重新验证。

`tools/integration/bukkit-fixture/` 是测试专用插件，提供控制台状态观察、外部传送 / 背包 / 方块事件探针，以及可人工批准的模拟身份验证器。它只用于本机隔离联调，不能发布到正式服，也不能混入 MCLogin 交付包。先构建 MCLogin，再在此目录执行 Gradle `build`。

在空的隔离 Paper 目录放入 MCLogin、测试夹具以及对应游戏版本的 LuckPerms、EssentialsX、Vault、PlaceholderAPI、WorldEdit、WorldGuard。跨版本插件选择见 `docs/plugin-validation.json`；固定下载来源及 SHA-256 保存在 `tools/integration/bukkit-artifacts.json`。然后运行：

```bash
MCLOGIN_BUKKIT_TESTS=1 python3 tools/integration/run.py 1.21.11 Paper   --production-dir /path/to/isolated-paper-server --port 26012   --launch java -Xmx1400M -jar paper.jar --nogui
```

这会运行完整的 56 项协议与插件检查，包括权限缓存更新、占位符、Vault 服务、WorldGuard 保护事件、虚拟物品清理与外部找回授权撤销。测试只绑定回环地址，RCON 密钥每次随机生成，测试期间会调整测试世界的全局 WorldGuard `block-break` 标志。禁止对现有服务器运行。

另外支持 `--scenario conflict`：在独立目录只放 MCLogin 和真实 AuthMe，检查认证冲突锁定及拒绝连接。支持 `--scenario minimal` 配合不设置 `MCLOGIN_BUKKIT_TESTS`：只安装 MCLogin，验证可选依赖全部缺失时的 18 项基础流程。不同场景使用各自的日志与结果文件名。

验收完成后运行 `python3 tools/package_plugin.py` 汇总证据和生成独立插件包；工具会核对最终 JAR 与每份测试记录的 SHA-256，并拒绝打包失败或不匹配的结果。不包含第三方插件与测试夹具，不覆盖原 12 模组的 ZIP / 清单。

## 整理 GitHub Release 文件

```bash
python3 tools/package_plugin.py
python3 tools/package_release.py
```

第二步不重跑服务端，会检查记录中的源码指纹和 13 个正式 JAR 的 SHA-256，更新含各版本说明的模组 ZIP，再收集 13 个 JAR、两个 ZIP 和总校验清单到 `dist/release/`。这一步不能替代构建与运行验收。发布说明保存在 `docs/releases/1.1.0.md`，用于 GitHub Release 时需将相对文档链接转换成仓库 `main` 分支链接。
