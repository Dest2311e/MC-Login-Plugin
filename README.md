# MCLogin —— Minecraft Forge 服务端登录验证模组

服务端侧（server-side）登录验证模组：玩家进服后必须 `/register` 或 `/login` 才能行动。
密码使用 **PBKDF2WithHmacSHA256**（随机 salt + 高迭代次数）加密存储，账号数据落在 **YAML 文件**。

---

## ⚠ 部署前提（务必先读）

1. **必须跑在 `online-mode=true`（正版验证）的服务端上。**
   本模组以玩家 **UUID** 为账号主键。离线模式（`online-mode=false`）下 UUID 由玩家名派生、
   **人人可自算**：改名即换 UUID（旧账号行成孤儿，按 UUID 存的账号维度失败计数可被轮换绕过）；
   反过来，知道目标用户名就能算出同一 UUID → 抢注与身份预占，并与 ops.json、白名单、权限组、
   领地/箱子锁、其它模组的玩家数据**叠加继承**。
   准确表述是「**身份不可信，口令仍是唯一有效门槛**」——
   不是"登录体系失效"，而是"身份层不可信，能挡住的只剩密码"。由此两条硬约束：
   - **离线服下，"未登录限制"这一层是唯一的身份屏障。** 任何限制漏网，或运维为了
     "让人先看看"而打开 `restriction.allowMove / allowInteract / allowChat`，
     在离线服里等价于**把账号让给抢注者**（在正版服里只是"提前活动"）。
   - 真正有效的缓解是**流程**：由管理员在玩家首次进服前预置账号行、或发一次性初始密码，
     把"先到先得"改成"先审后发"。可信代理转发经验证的 UUID 亦可，但硬条件是
     后端端口必须只对代理开放，否则可绕过代理直连并自选用户名。

2. **代理未转发真实 IP（或共享 NAT 出口）时，请调高或关闭 `enableIpGuard`。**
   IP 维度取的是 TCP 对端地址。若所有玩家都来自代理 IP，或整栋宿舍/网吧共用一个出口，
   任意若干次失败会把该出口下**所有玩家一起锁住**（"一个人输错密码，全楼不能登录"）。
   本版已把 IP 维度的阈值/锁定时长/衰减窗口独立成
   `bruteforce.ipMaxFailedAttempts / ipLockSeconds / ipFailWindowSeconds`
   （默认 20 次 / 300 秒 / 10 分钟，比账号维度宽松），共享出口场景请继续调高，
   或直接 `enableIpGuard = false`（账号维度仍生效）。
   **纯离线服下不建议关闭**：离线服里账号维度计数可按 UUID 轮换，IP 维度是唯一真正有效的一半。

3. **`password.pbkdf2Iterations` 默认 600000（OWASP 现行水位），PBKDF2 已异步化。**
   G8-1 三件套（**异步化 + 有界线程池 + 到达率限制**）已落地：全部派生在专用有界线程池
   （`max(1, min(2, cpus/2))` 个守护线程、队列 64、拒绝策略 `AbortPolicy`）内执行，
   主线程零阻塞；同 UUID 在途去重兼作 ≥500ms 冷却（防 DoS 放大），池满/队满时拒绝并提示
   "操作过于频繁"，**绝不回退主线程**。旧配置里显式写的 64000 仍会沿用（显式值优先），
   老账号会在下次登录成功时自动收敛到配置值（见「迭代数语义与迁移窗口」）。
   迭代数最终仍应由"最弱目标机型 + 有界池能承受的到达率"**实测反推**，而不是照抄 OWASP。

4. **`accounts.yml` 是敏感文件**，请设置合适的文件系统权限并定期备份。
   写入采用"临时文件 + 原子移动"，写盘失败时**不删除**临时文件，而是保留为
   `accounts.yml.failed-<ts>`（内容是最新全量数据，既是救援副本也是事故证据）；
   `/mclogin reload` 在存在未落盘改动时会先导出 `accounts.yml.rescue-<ts>` 再重载。
   用 `/mclogin status` 可直接查看"是否存在未落盘改动 / 连续保存失败次数 /
   磁盘内容疑似不可解析"。

5. **会话免登录（U1）默认关闭，且只在 `online-mode=true` 时真正生效。**
   开启 `session.enableSessionAutoLogin` 后，同 IP 且距上次成功登录低于
   `session.sessionTimeoutMinutes`（默认 5，`0 = 关闭`）的玩家重连**无需再 /login**。
   ⚠ **固有代价**：其全部安全性押在"同出口 IP 的其他人拿不到你的用户名"上——
   网吧 / 校园网 / 运营商 CGNAT / 未转发真实 IP 的反代下，"同 IP"等价于
   "Tab 列表里的任何人"，他们可以**免密进入你的账号**（服务器 Tab 列表就是公开的
   用户名清单）。改密/注销会立即使会话失效；自动放行不会延长会话时限；
   离线服检测到后强制停用并打 WARN。详见「会话免登录（U1）」一节。

---

- **目标平台**：Minecraft **1.20.1** / Forge **47.x** / Java **17**（Mojang 官方映射 official-1.20.1）
- **modid**：`mclogin`
- **包名**：`com.dsh.mclogin`
- **依赖**：**零第三方依赖**。密码学只用 JDK 内置 `javax.crypto`（PBKDF2WithHmacSHA256）；
  YAML 读写由 `data/YamlStorage.java` 内置的极简 writer/parser 完成。
  （QA 已核实：MC 1.20.1 官方 `version.json` 的 88 条 libraries 中**不含** `org.yaml:snakeyaml`，
  早期版本误以为"运行时自带"而以 `compileOnly` 声明，会导致开服 `NoClassDefFoundError`，已移除。）

---

## 一、功能清单

| 功能 | 说明 |
| --- | --- |
| `/register <密码> <确认密码>` | 注册账号，注册成功即视为已登录 |
| `/login <密码>` | 登录，成功后清零失败计数 |
| `/changepassword <旧密码> <新密码>` | 修改密码（需已登录） |
| `/unregister <密码>` | 注销账号（需已登录且密码正确） |
| `/mclogin reload` | 重新加载 accounts.yml（权限等级 3） |
| `/mclogin status` | 查看账号数/已登录数/受监控 IP 数（权限等级 3） |
| 密码加密 | PBKDF2WithHmacSHA256 + 每账号 16 字节随机 salt + **600000** 次迭代（OWASP 水位，异步执行）+ 常量时间比较 |
| PBKDF2 异步化（G8-1） | 派生在专用有界线程池执行，主线程零阻塞；同 UUID 在途去重 + ≥500ms 冷却；池满拒绝并提示繁忙 |
| 反暴力破解 | 账号维度（持久化）+ IP 维度（内存），N 次失败锁定 M 秒，锁定期间提示剩余时间，成功清零 |
| 会话免登录（U1） | 同 IP + 距上次成功登录 < 时限自动放行；**默认关闭**，仅正版服生效，改密/注销立即失效 |
| 未登录限制 | 禁止移动（位置回退）、禁止交互/破坏/放置、禁止聊天、禁止除登录相关以外的一切命令 |
| 多语言（U2+R8） | 全部玩家消息**服务端解析**为字面文本发送（`general.language`，默认 `zh_cn`，回退 `en_us`），原版客户端无需装模组即可见配置语言 |
| 提示音效（R8） | 6 个事件点播放原版音效（进服/注册/登录/失败/锁定/超时警告），`[sounds]` 可调音量音调或关闭，主线程播放 |
| 超时踢出 | 进服 N 秒未登录则断开连接（可配置为 0 = 不踢） |

---

## 二、目录结构

```
mc-login-forge/
├── build.gradle                  # Forge 1.20.1 MDK 标准构建脚本（ForgeGradle 6.x）
├── settings.gradle
├── gradle.properties             # mc_version / forge_version / mod 元信息
├── .gitignore
├── README.md
└── src/main/
    ├── resources/
    │   ├── META-INF/mods.toml    # 模组元信息与依赖声明
    │   ├── pack.mcmeta
    │   └── assets/mclogin/lang/  # U2 多语言：zh_cn.json / en_us.json
    └── java/com/dsh/mclogin/
        ├── MCLoginMod.java                    # @Mod 主类：注册配置/命令/事件，服务器启停时加载落盘
        ├── config/LoginConfig.java            # ForgeConfig 配置（生成 mclogin-server.toml）
        ├── data/PlayerAccount.java            # 账号模型：hash/salt/iterations/失败计数/锁定到期/IP/时间
        ├── data/YamlStorage.java              # accounts.yml 读写：内存缓存 + 原子写 + 定时异步落盘
        ├── security/PasswordHasher.java       # PBKDF2WithHmacSHA256 哈希与常量时间校验
        ├── security/HashExecutor.java         # G8-1：PBKDF2 专用有界线程池（去重+冷却+Abort）
        ├── security/BruteForceGuard.java      # 失败计数、锁定窗口、剩余时间计算（账号+IP 双维度）
        ├── session/SessionManager.java        # 在线玩家登录态、进服时间、冻结坐标
        ├── command/LoginCommands.java         # /register /login /changepassword /unregister /mclogin
        └── listener/
            ├── PlayerConnectionListener.java  # 进出服/重生：初始化会话、提示、清理
            └── LoginRestrictionListener.java  # 未登录限制：移动/交互/聊天/命令/超时踢出
```

---

## 三、编译

前置条件：**JDK 17**（Forge 1.20.1 强制要求），且能访问 Maven/Forge 仓库。

```bash
cd E:\DSH\mc\mc-login-forge
gradlew build          # Windows
# 或： ./gradlew build
```

> **注意**：Gradle Wrapper（`gradlew.bat` 等）已生成并可用（Gradle 8.1.1）。
> 真实构建已验证通过（D3，Forge 1.20.1-47.4.10，`BUILD SUCCESSFUL`）：
> ```powershell
> $env:JAVA_HOME = '<JDK17 路径>'      # ForgeGradle 6 不支持 JDK 21+，必须 JDK 17
> $env:GRADLE_USER_HOME = '<Gradle 缓存目录>'
> .\gradlew.bat build --no-daemon
> ```

产物：`build/libs/mclogin-1.0.0.jar`

常用任务：

```bash
gradlew build        # 编译 + 打包
gradlew runServer    # 启动测试服务端（首次会要求同意 EULA）
gradlew runClient    # 启动测试客户端
```

---

## 四、安装

1. 服务端安装 **Forge 1.20.1-47.x**（`forge-1.20.1-47.x.x-installer.jar` → `java -jar ... --installServer`）。
2. 启动一次服务端生成目录结构。
3. 把 `mclogin-1.0.0.jar` 放进服务端的 `mods/` 目录。
4. 重启服务端，首次启动后会生成：
   - **配置文件**：`<服务端目录>/world/serverconfig/mclogin-server.toml`
     ⚠ 注意：Forge 的 SERVER 类型配置是 **per-world** 的，生成在 `world/serverconfig/` 下，
     **不是** `config/`（这是本模组与很多教程不同的地方，真机验证实测确认）。
   - **账号库**：`<服务端目录>/config/accounts.yml` —— 采用**惰性创建**：
     开服时不生成，首次有玩家注册/登录/失败计数等数据写入时才创建。
     开服后看不到这个文件**属正常**，不代表故障。

---

## 五、配置说明

配置文件：`<服务端目录>/world/serverconfig/mclogin-server.toml`（per-world，见上）

```toml
[bruteforce]
    # 连续登录失败达到该次数后锁定账号/IP
    maxFailedAttempts = 5
    # 锁定持续时长（秒）
    lockSeconds = 300
    # 失败计数衰减窗口（秒）：该窗口内无新失败则清零计数
    failWindowSeconds = 600
    # 是否同时按 IP 维度统计失败次数
    enableIpGuard = true
    # IP 维度允许的最大连续失败次数（独立于账号维度；共享出口场景请调高）
    ipMaxFailedAttempts = 20
    # IP 维度锁定持续时长（秒）
    ipLockSeconds = 300
    # IP 维度失败计数衰减窗口（秒）
    ipFailWindowSeconds = 600

[restriction]
    # 进服后必须完成登录的时限（秒），0 表示不踢出
    loginTimeoutSeconds = 120
    # 未登录玩家是否允许移动
    allowMove = false
    # 未登录玩家是否允许交互（破坏/放置/使用物品）
    allowInteract = false
    # 未登录玩家是否允许聊天
    allowChat = false

[password]
    minPasswordLength = 6
    maxPasswordLength = 64
    # PBKDF2WithHmacSHA256 迭代次数（越大越慢越安全），区间 64000~1000000
    # G8-1 后派生已在专用线程池异步执行，默认提到 OWASP 水位 600000
    pbkdf2Iterations = 600000

[storage]
    # 账号数据自动落盘间隔（秒）
    # 崩溃/kill -9/断电路径不会触发关服落盘，本值即最坏丢失窗口
    autosaveIntervalSeconds = 60
    # 是否把玩家 lastIp 明文写入 accounts.yml（G8-9）
    # false = 不落盘（仅影响持久化，内存中的 IP 守卫/失败计数/锁定不受影响）
    storeLastIp = true

[session]
    # U1 会话免登录：同 IP 且距上次成功登录低于时限时，重进服免 /login
    # ⚠ 默认 false；仅 online-mode=true 生效（离线服强制停用并 WARN）
    # ⚠ 共享出口（网吧/校园网/CGNAT/反代）下同 IP 者可免密进入他人账号，请权衡后开启
    enableSessionAutoLogin = false
    # 会话免登录时限（分钟），0 = 关闭（不是"永不过期"）
    sessionTimeoutMinutes = 5

[general]
    # R8 服务端提示语言：zh_cn（默认）/ en_us。
    # 消息由服务端解析为字面文本发送（原版客户端无需装本模组也能看到对应语言）；
    # 语言文件在模组 jar 内 assets/mclogin/lang/<language>.json，缺失键回退 en_us，再回退键名。
    # 改动需重启服务端生效。
    language = "zh_cn"

[sounds]
    # R8 提示音效（原版音效，无需客户端 mod）：进服提示 / 注册成功 / 登录成功 /
    # 登录失败 / 账号锁定 / 超时前 30 秒警告。全部在主线程播放。
    enableSounds = true
    # 音量（0.0~1.0）与音调（0.5~2.0）；锁定音效固定在配置音调基础上乘 0.6（低音调）
    soundVolume = 1.0
    soundPitch = 1.0
```

> **配置变更提示（升级时注意）**
> - 新增 `[general].language` 与 `[sounds]` 段（R8 中文提示 + 音效），旧配置文件首次启动自动补上。
> - `password.pbkdf2Iterations` **默认由 64000 提到 600000**（G8-1 异步化已落地，主线程不再阻塞）：
>   旧配置文件里显式写的 64000 会继续沿用（显式值优先），需要 OWASP 水位请手工改或删掉该行；
>   老账号会在下次登录成功时自动收敛到配置值。
> - 新增 `[session]` 段（U1 会话免登录）：`enableSessionAutoLogin`（默认 **false**）与
>   `sessionTimeoutMinutes`（默认 5，区间 0~15，**0 = 关闭**）。旧配置文件首次启动时自动补上。
> - `bruteforce` 新增三个 IP 维度键（`ipMaxFailedAttempts / ipLockSeconds / ipFailWindowSeconds`），
>   旧配置文件会在首次启动时自动补上默认值。
> - `password.pbkdf2Iterations` 的**下界由 10000 抬到 64000**：旧配置里若写了更低的值，
>   ForgeConfig 会按越界处理（回落默认值并打日志），不会静默生效。
> - `storage.autosaveIntervalSeconds` 默认由 300 缩短为 60（下界 30 → 10）：
>   崩溃 / `kill -9` / 断电 / OOM-Kill 不会触发关服落盘，本值即最坏丢失窗口。
>   旧配置文件里的 300 会被继续沿用（显式值优先），需要更短窗口请手工改。

### 迭代数语义（`0 = 未指定`）与迁移窗口

- `PlayerAccount.iterations` 与 `accounts.yml` 里缺失 `iterations:` 行时**均为 0**，
  表示"未指定"，不再由数据层拍板一个策略值；
  实际使用的迭代数由 `PasswordHasher.verify(密码, 账号, 当前配置值)` 决定：
  **账号存量值 &gt; 0 用它，否则用当前配置值**。
- 登录成功后若发现 `账号存量值 != 当前配置值`，会**就地收敛**（用玩家刚输入的明文重算哈希）。
  改密（`/changepassword`）同样会写入当前配置值。
- ⚠ **迁移窗口内"抹平"只能是收敛，不能瞬时精确**：重算哈希必须有明文，
  而明文只在该玩家登录的那一刻存在。因此修改 `pbkdf2Iterations` 之后、
  尚未成功登录过的老账号仍带有枚举侧信道的残差（账号不存在分支烧的是配置值，
  老账号烧的是存量值）。这是密码学的固有约束，不是可以靠代码修掉的缺陷。
  想彻底消除只能让所有玩家各自登录一次，或离线批量重置全部账号（会同时作废旧密码）。

### 会话免登录（U1，默认关闭）

同 IP 且距上次**真实登录**低于 `session.sessionTimeoutMinutes` 的玩家重连时自动放行，
无需再 `/login`（AuthMeReloaded 同构方案）。实现遵守六条硬约束：

1. **仅 `online-mode=true` 生效**：离线服下 UUID 由玩家名派生，同 IP 攻击者用受害者
   用户名进服即被自动放行，口令这道"唯一有效门槛"被整体绕过——检测到离线模式时
   强制停用并在开服打 WARN；
2. **总开关默认 `false`**（与 AuthMe 出厂一致）；
3. **自动放行绝不刷新 `lastLoginAtMillis / lastIp`**——否则时限形同虚设、可无限续期
   （这是 D5 调研标注"最容易做错"的一条；本实现走只置登录态的专用路径）；
4. 放行同步 `markLoggedIn + clearFrozenPosition`（登录态与冻结点必须同步，B3 教训）；
5. **改密/注销立即使会话失效**（改密置 `lastLoginAtMillis = 0`；注销删除账号行）——
   否则同 IP 者可借免登录窗口 `/changepassword` 永久夺号；
6. 默认时限 5 分钟（区间 0~15）；**`0 = 关闭`**，不是"永不过期"
   （与 AuthMe 的 `timeout` 语义相反，配置时注意）。

⚠ **共享出口 IP 风险（开启前必读）**：本功能的全部安全性押在"同出口 IP 的其他人
拿不到你的用户名"这一个假设上。网吧 / 校园网 / 运营商 CGNAT / 未转发真实 IP 的
反代下，该假设不成立——同 IP 的任何人都可能**免密进入他人账号**（Tab 列表就是
公开的用户名清单；反代最坏情形下"同 IP"恒真，等于对所有人开放全部账号）。
D5 调研另推荐过更安全的"半登录/访客"形态（自动放行只给移动+聊天，破坏/开箱/
改密/注销仍需口令），本版未实现，作为后续可选项登记在待办。

> **IP 边界（D5/DSH 字节码结论）**：`getIpAddress()` 返回裸地址（不含端口）；
> 非 INET 连接返回字面量 `<unknown>`——本模组把 `<unknown>` 同时视为
> "IP 守卫里所有玩家共用的一个桶"和"永不满足会话免登录"，两类场景均已设防。

### 账号文件格式（`config/accounts.yml`）


```yaml
version: 1
accounts:
  550e8400-e29b-41d4-a716-446655440000:
    uuid: 550e8400-e29b-41d4-a716-446655440000
    username: Steve
    hash: 'PBKDF2 派生结果（Base64）'
    salt: '随机盐（Base64）'
    iterations: 64000
    keyLength: 256
    failedAttempts: 0
    lockUntilMillis: 0
    lastFailedMillis: 0
    lastIp: 127.0.0.1
    registeredAtMillis: 1700000000000
    lastLoginAtMillis: 1700000100000
```

> **运维提示**：`accounts.yml` 属于敏感文件，请设置合适的文件系统权限，并定期备份。
> 本模组写入时采用"临时文件 + 原子移动"，正常不会写坏；但仍建议备份。
>
> **隐私（G8-9）**：`lastIp` 为玩家个人信息，合规场景可设 `storage.storeLastIp = false`
> 关闭落盘（文件里已有的行会在下次全量保存时消失；内存中的 IP 守卫不受影响）。
> `username` 保留明文是有意为之：UUID 对服主不可读，没有用户名就无法做封禁核查与
> 数据核对，而本文件本就仅限服主访问；如需进一步隐藏，请用文件系统权限而非删列。

### 如何清空账号库（运维必读）

模组对"账号库被读空"有防护：**读到 `accounts` 段却一条账号都解析不出来时，会判定为
解析异常并保留内存中的原有账号**，而不是把它当成空库。这是为了防止"不小心改坏了缩进
→ 整个账号库被静默删除"（该故障已实测复现过）。

因此**只删掉账号条目、保留 `accounts:` 行是清不掉的**（会被判为可疑并保留内存态）。
请使用下面三种等价做法之一：

1. **删除 `accounts.yml` 后重启服务端**；
2. **删除整个 `accounts:` 段（含 `accounts:` 这一行）**，然后执行 `/mclogin reload`；
3. **把该行写成显式空库标记 `accounts: {}`**，然后执行 `/mclogin reload`：

   ```yaml
   version: 1
   accounts: {}
   ```

   必须是**同一行上的** `{}` 字面量（`{ }` 也可以）；
   单独一行 `accounts:`（后面换行再跟内容）**不算**显式空库。

判定为"解析异常"时，模组会在同目录生成 **`accounts.yml.unparsable-<时间戳>`** 留档 ——
那是读不懂的磁盘原文，也是唯一一份原始数据。运维可据此核对恢复；确认无误后可自行删除该留档文件。

同时 `/mclogin status` 会显示
`⚠ 磁盘内容疑似不可解析，已保留内存态（请核对 accounts.yml 缩进与备份）`，
日志中也会打出 WARN。

---

## 六、使用流程

```
玩家进服
  ├─ 未注册 → 提示 "/register <密码> <确认密码>"，同时被限制移动/交互/聊天/命令
  ├─ 已注册 + 会话免登录命中（同 IP + 时限内，默认关闭）→ 自动放行
  └─ 已注册 → 提示 "/login <密码>"
          ├─ 密码正确 → 解除限制，失败计数清零
          └─ 密码错误 → 计数 +1，达到阈值 → 锁定 lockSeconds 秒（响应文案不区分
              "账号不存在 / 密码错误"，防账号枚举）
超时未登录 → 断开连接
```

> **密码规则（G8-6/U3）**：四个命令的密码参数已统一为单个"单词"（Brigadier `word()`），
> **密码不允许包含空格**。含空格的输入在解析层直接报错，不会进入失败计数；
> 这消除了旧版"/register 设不出、/login 却能输入"的自相矛盾。

> **异步与限流（G8-1/G8-10）**：`/login` 等命令提交后立即返回"正在验证"，
> PBKDF2 派生在专用线程池完成（600k 迭代实测约 100ms，真机数据见记录第二十五节），
> 结果回主线程后给出。同一玩家 500ms 内重复提交、上一笔尚未完成、或池/队列已满时，
> 会得到"操作过于频繁，请稍后再试"——这是防滥用限流，**不计入失败次数、不影响锁定状态**。
> 另外，登录失败提示已**不再显示"剩余尝试次数"**（它会向撞库者确认账号存在；
> 锁定时的剩余秒数提示保留）。
>
> **提示语言（R8）**：全部玩家消息由**服务端**按 `general.language`（默认 `zh_cn`）
> 解析成字面文本发送——原版客户端无需安装本模组也能看到对应语言，与客户端语言设置无关
> （语言文件在模组 jar 内 `assets/mclogin/lang/<language>.json`，缺失键回退 `en_us`，
> 再回退键名本身）。本文档中的提示文案均为 `zh_cn` 下的显示效果。
>
> **提示音效（R8）**：进服提示（音符盒"叮"）/ 注册成功（升级音）/ 登录成功（经验球）/
> 登录失败（村民拒绝）/ 账号锁定（铁砧落地，低音调）/ 超时前 30 秒警告（帽音），
> 原版客户端可直接听到、无需安装任何客户端 mod；`[sounds]` 段可调音量/音调或整体关闭。
> mineflayer 等 bot 收不到声音包，听觉效果请以真实客户端为准。

---

## 七、设计要点

1. **密码安全**
   - 只保存 `salt + PBKDF2 派生值`，绝不保存明文或裸 SHA。
   - 每账号独立 16 字节随机 salt，迭代次数默认 **600000**（OWASP 水位，G8-1 异步化后启用），密钥长度 256 bit。
   - 比较使用 `MessageDigest.isEqual`，避免时序侧信道。
   - `PBEKeySpec.clearPassword()` + `Arrays.fill` 主动清理内存中的密码字符数组。
   - 全代码路径**不打印密码/哈希**（日志只记玩家名与 IP）。

2. **PBKDF2 异步执行（G8-1）**
   - 专用有界线程池 `security/HashExecutor`：`max(1, min(2, cpus/2))` 个守护线程，
     队列容量 64，拒绝策略 **`AbortPolicy`**（禁用 `CallerRunsPolicy`——那会把任务
     退回主线程，等于没异步化）。
   - **同 UUID 在途去重 + ≥500ms 冷却**共用一张状态表（正数=在途，负数=完成时刻），
     防同一玩家并发重入与高频连发（G8-10 冷却；与锁定互不干扰）。
   - **回主线程改状态**：工作线程只做纯派生；`markLoginSuccess / recordFailure /
     markDirty / applyPrecomputed` 等状态变更一律在 `server.execute(...)` 回调里做。
     提交点会复查"玩家仍在线、账号未被 reload/注销、尚未登录"，陈旧结果一律丢弃。
   - **`burnTime` 与 `verify` 同池同优先级**（B4 时序抹平的成败点）；注册/改密的新口令
     派生、登录收敛重算也在同一池内。
   - **注册不再先建行**：旧流程 `getOrCreate` 后再算哈希，异步化会让"hash 为空的
     僵尸账号行"有机会落盘；现改为派生完成后在提交点 `getOrCreate` + 成组写入。

3. **存储可靠性**
   - 全量内存缓存（`ConcurrentHashMap`），读写 O(1)。
   - 写入走 `accounts.yml.tmp` → `ATOMIC_MOVE`（不支持时退化为 `REPLACE_EXISTING`）；
     tmp 与备份文件一律放在**账号文件同目录**，避免跨目录移动退化。
   - **代际式 dirty**：`markDirty()` 自增代际，写盘成功后记录"已落到第 N 代"。
     旧实现 `dirty.compareAndSet(true,false)` 先消费标记再写盘，会把写盘期间到来的
     新改动一并吞掉（内存有新值、文件没有、且不会再自动重试）；代际式不会丢，
     下一周期发现代际更大就再写一次（全量写幂等）。
   - `saveNow()` **返回 boolean**；失败时保留代际，下周期自然重试。
     仅对 `AccessDeniedException`（文件被其它句柄占用）做 3×200ms 短退避，
     且只在自动落盘线程里做；关服路径最多一次短重试，不长时间阻塞关服。
   - 失败时**保留现场**：tmp 重命名为 `accounts.yml.failed-<ts>`（旧实现无条件删掉，
     恰好毁掉唯一的救援副本）；`/mclogin reload` 前导出 `accounts.yml.rescue-<ts>`。
   - 后台守护线程按 `autosaveIntervalSeconds`（默认 60s）批量落盘，主线程零阻塞；
     关服时 `saveNow()` 同步兜底，并且关服日志**依据落盘结果**输出（不再假成功）。
   - `/mclogin status` 暴露"是否存在未落盘改动 / 连续保存失败次数"。

4. **线程安全**
   - 会话、账号缓存、IP 状态全部使用并发容器。
   - 文件写入用 `synchronized` 串行化。
   - **账号级加锁（[C-N1]）**：`PasswordHasher.applyNewPassword / applyPrecomputed` 在
     `synchronized (account)` 内一次性写回 salt/hash/iterations/keyLength，
     `YamlStorage.dumpAccounts` 序列化每个账号时同样加锁 —— 否则落盘可能读到
     「新 salt + 旧 hash」的中间态，写出一个**重启后永久无法登录**的账号
     （内存里却是好的，故障只在重启后暴露）。
   - G8-1 之后：池内工作体只读账号（持同一把锁）、不写；写回只发生在主线程提交点。
     `failedAttempts / lockUntilMillis / lastFailedMillis` 仍是无锁字段（见待办 G8-5）。

5. **会话免登录（U1）**
   - 见「会话免登录（U1，默认关闭）」一节：六条硬约束 + 共享出口风险。
   - 会话依据就是账号行已有的 `lastIp + lastLoginAtMillis`，**未引入 sessionToken**：
     纯服务端模组没有客户端载体来出示 token，加了也只是永不参与判定的摆设
     （AuthMe 的 `has_session` 布尔列在本方案中由 `lastLoginAtMillis > 0` 承担）。

6. **多语言（U2+R8）**
   - 全部玩家可见消息经 `message/MessageService` 在**服务端**解析：按 `general.language`
     读入 jar 内 `assets/mclogin/lang/<language>.json`（约 38 键，带参数的
     （锁定剩余秒数、status 输出、救援副本路径等）用占位符），解析成
     `Component.literal` 发送。回退链：配置语言 → en_us → 键名。
   - 为什么不是 translatable：翻译在客户端解析，纯服务端模组的语言文件不下发到客户端，
     原版客户端会显示键名原文（真机实证，见记录第二十四~二十六节与 R8 任务书）。
   - JSON 解析为 MessageService 内置的零依赖扁平解析器（R5 红线）。
   - 日志仍为字面量（面向运维，不随语言配置变化）。

7. **提示音效（R8）**
   - `message/SoundService`：原版音效常量集中在服务端播放（`playNotifySound`），
     原版客户端自带全部音效资源，无需客户端 mod。
   - 6 个事件点：进服提示（NOTE_BLOCK_PLING）/ 注册成功（PLAYER_LEVELUP）/
     登录成功（EXPERIENCE_ORB_PICKUP）/ 登录失败（VILLAGER_NO）/
     锁定（ANVIL_LAND，音调 ×0.6）/ 超时前 30 秒警告（NOTE_BLOCK_HAT，每会话一次，
     旗标在 SessionManager、随登出清理）。
   - ⚠ 1.20.1 mojmap 常量名注意：这组音效**无** `ENTITY_/BLOCK_` 前缀
     （`PLAYER_LEVELUP` 而非 `ENTITY_PLAYER_LEVELUP`），且 NOTE_BLOCK_* 是
     `Holder.Reference<SoundEvent>` 需 `.value()` —— 两处均已由真实编译证实。
   - 播放全部在主线程（命令提交点/登录事件/tick），异步派生线程不发声音。

8. **最小依赖（零第三方库）**
   - 不引入 BCrypt / Argon2 / 数据库驱动 / SnakeYAML，只用 JDK 内置 `javax.crypto` 与 `java.nio.file`。
   - `accounts.yml` 的序列化/解析由 `YamlStorage` 自带的约 60 行缩进 writer + parser 完成，
     格式固定（顶层 `version` 标量 + `accounts` 两层 Map，值全为标量），手写解析足够且无依赖风险。

---

## 本轮已落地的修复（摘要）

| 问题号 | 内容 | 位置 |
| --- | --- | --- |
| P0-1 | 代际式 dirty（写盘期间的新改动不再被吞） | `data/YamlStorage.java`（generation / savedGeneration） |
| P0-2 | `saveNow()` 返回 `boolean`；失败保留代际待重试 | 同上（`writeOnce` / `saveWithRetry`） |
| P0-3 | 关服文案依据落盘结果，消除假成功 | `MCLoginMod.onServerStopped` |
| P1-4 | `/mclogin reload` 前导出 `accounts.yml.rescue-<ts>` | `command/LoginCommands.doReload` |
| P1-5 | 写盘失败保留 tmp → `accounts.yml.failed-<ts>` | `YamlStorage.preserveFailedTmp` |
| P1-6 | `/mclogin status` 暴露未落盘改动 / 连续失败次数 | `command/LoginCommands.doStatus` |
| P2-7 | 仅 `AccessDeniedException` 的 3×200ms 短退避 | `YamlStorage.saveWithRetry` |
| P2-8 | 自动落盘间隔默认 300s → 60s（下界 30 → 10） | `config/LoginConfig` |
| P2-9 | [C-N1] 账号级加锁（salt/hash 错配竞态） | `security/PasswordHasher.applyNewPassword` + `YamlStorage.dumpAccounts` |
| G1(ii) | 迭代数四步对齐（`0 = 未指定`、verify 增参、登录后收敛） | `YamlStorage` / `PlayerAccount` / `PasswordHasher` / `LoginCommands` |
| G1(iii) | Base64/PBEKeySpec 抛点收口 + `verify` fail-closed | `security/PasswordHasher` |
| [C-N2] | IP 维度阈值/锁定/窗口独立成配置项 | `config/LoginConfig` + `security/BruteForceGuard` |
| G8-2c | IP 维度定向清理（堵死 B2 绕过链） | `security/BruteForceGuard.reset` |
| G8-9 | `storeLastIp` 隐私开关（默认 true；关闭后 `accounts.yml` 不再写 `lastIp`，IP 守卫不受影响） | `config/LoginConfig` + `data/YamlStorage.dumpAccounts` |
| G8-6 | 四命令密码参数统一 `word()`（密码不允许空格，解析层直接报错） | `command/LoginCommands` |
| G8-2 | 未登录开容器 UI 兜底：`PlayerContainerEvent.Open` 取消（右键开箱之外的第二道） | `listener/LoginRestrictionListener` |
| G8-7 | 含密码命令原文处加"禁止打印 raw"显式注释 | `listener/LoginRestrictionListener.onCommand` |
| G8-1 | **PBKDF2 异步三件套**：专用有界线程池（Abort）+ 同 UUID 在途去重兼 ≥500ms 冷却 + `server.execute` 回主线程改状态；`burnTime` 与 `verify` 同池同优先级 | `security/HashExecutor` + `command/LoginCommands`（全部 commit 点）+ `security/PasswordHasher.applyPrecomputed` |
| G8-10 | /login 冷却落地（同 UUID ≥500ms + 在途去重）；IP 维度由 IP 守卫（20 次锁定）兜底 | `security/HashExecutor` |
| 迭代数 | 默认 64000 → **600000**（OWASP 水位，异步化后启用）；旧护栏（>200k 同步执行 WARN）随异步化移除 | `config/LoginConfig` + `MCLoginMod` |
| B4 收口 | 登录失败文案**去掉"剩余尝试次数"**（它会向撞库者确认账号存在，G8-3(a) 残留通道）；锁定预检前移（锁定中不烧 PBKDF2、文案统一） | `command/LoginCommands` |
| U1 | **会话免登录**（同 IP + 时限，默认 false，六条硬约束，改密/注销即失效） | `listener/PlayerConnectionListener.trySessionAutoLogin` + `config/LoginConfig [session]` + `command/LoginCommands`（改密失效） |
| U2 | **中英双语 i18n**：全部玩家消息 `Component.translatable` + `assets/mclogin/lang/{zh_cn,en_us}.json`（含 status/锁定秒数/救援路径等占位符） | 全部命令/监听器 + `resources/assets/mclogin/lang` |
| R8-A | **中文提示真显示**：`message/MessageService` 服务端按 `general.language` 把键解析成字面文本（translatable 对纯服务端模组只显示键名——真机实证）；零依赖扁平 JSON 解析器；回退链 zh→en→键名 | `message/MessageService` + `config/LoginConfig [general]` |
| R8-B | **提示音效**：6 事件点原版音效（`SoundEvents` 1.20.1 真名已由真编译证实，音符盒为 Holder 需 `.value()`）；`[sounds]` 音量/音调/总开关；全部主线程播放 | `message/SoundService` + `config/LoginConfig [sounds]` + 各提交点/进服/超时警告接入 |
| R10-A | **铁砧输入提示字外置**：carrier 物品名清空（改名栏保持空白，提示字预填会被打字追加混入密码——用户实测），提示改走 **action bar**（打开即发 + 每 2.5s 刷新 + 关闭清除，物理隔离）；4 个 `hint.*` 语言键 | `ui/AnvilInput` + `lang/*.json` |
| R10-B | **全部提示词加粗**：`MessageService.component()` 唯一出口 `setStyle(BOLD)` 一处改动全量生效；`§` 颜色码与加粗叠加互不冲突；日志不受影响 | `message/MessageService` |
| R10-C | **@Cancelable 安全核查**（R9 规则）：用真实 forge jar `javap` 逐类验证——`PlayerContainerEvent.Open` 非 @Cancelable（DSH 已修为延后一 tick 关闭）、`PlayerInteractEvent` 基类亦非（真机靠子类多态未炸）；交互处理器补 `isCancelable()` 护栏 | `listener/LoginRestrictionListener.onPlayerInteract` |
| — | （已移除）启动护栏 `pbkdf2Iterations > 200000` WARN —— G8-1 异步化后主线程不再阻塞，护栏失去意义 | — |

## 待办 / 交由下游确认

以下点属于**我没有把握**或**刻意留白**的地方，请 DeepSeek / GLM 重点审查：

- [ ] **版本 API 差异（1.20.1 → 1.21.x / NeoForge）**
      - 1.20.1 使用 `net.minecraft.*` 官方映射；1.20.6+ / 1.21.x 大量类名变更（如 `ServerChatEvent` 迁移、
        `PlayerInteractEvent` 拆分、`Commands`/`CommandSourceStack` 包名调整）。
      - NeoForge 迁移要点（DSH D1 以 1.21.x 源码核实）：`net.minecraftforge.*` → `net.neoforged.neoforge.*`、
        eventbus 换 `net.neoforged.bus.api`、`TickEvent` 拆分为 Pre/Post、
        `ForgeConfigSpec` → `ModConfigSpec`、配置注册改走 `ModContainer#registerConfig`。
        ★ 更正旧说法：`RegisterCommandsEvent` 在 NeoForge 中**仍在游戏总线**（非 mod 总线），
        旧记录"总线归属改变"不成立。
      - 结论：本项目**只保证 1.20.1 / Forge 47.x**（已按 47.4.10 real-build 通过），更高版本需单独适配分支。

- [x] **事件类名/包名已核对（对照 Forge/NeoForge 1.20.1 源码，可直接编译）**
      - `net.minecraftforge.event.ServerChatEvent` ✅ 存在、`@Cancelable`、`getPlayer()` 返回 `ServerPlayer`
      - `net.minecraftforge.event.CommandEvent` ✅ 存在、`@Cancelable`、
        `getParseResults()` 返回 `ParseResults<CommandSourceStack>`
      - `TickEvent.PlayerTickEvent#player` ✅ 是 `public final Player player`（**公开字段，无 getter**）
      - `AttackEntityEvent` ✅ 继承 `PlayerEvent`，`getEntity()` 返回 `Player`
      - `BlockEvent.BreakEvent#getPlayer()` ✅ 返回 `Player`

- [ ] **仍需实测的点**
      - `PlayerEvent.PlayerRespawnEvent` 在 1.20.1 的构造形态（仅用于刷新冻结坐标，出问题不影响主流程）。
      - `ServerPlayer#getIpAddress()` 的**方法可见性**：方法本身已在 1.20.1 官方映射中确认存在
        （映射文件不体现 public/private）。若编译报不可见，备选
        `player.connection.getConnection().getRemoteAddress().toString()`，或直接关闭 `enableIpGuard`。
      - 未登录限制是否有漏网行为（切旁观模式、骑乘实体、向漏斗/容器内塞物品等）。

- [x] **已修复的一处真实编译错误（重要，供下游避免踩坑）**
      - 1.20.1 的 `CommandSourceStack` **只有** `sendSuccess(Supplier<Component>, boolean)`，
        **没有** `sendSuccess(Component, boolean)` 重载（已核对官方 `server.txt`）。
        初版代码写成了 `sendSuccess(Component.literal(...), true)`，会直接编译失败，
        现已全部改为 `sendSuccess(() -> Component.literal(...), true)`。
      - 同一轮核对也确认了：`absMoveTo(double,double,double,float,float)`、`setDeltaMovement(Vec3)`、
        `Vec3.ZERO`、`disconnect(Component)`、`sendFailure(Component)`、`hasPermission(int)`、
        `sendSystemMessage(Component)`、`ServerPlayer#getIpAddress()` 在 1.20.1 均存在。

- [x] **会话免登录（IP + 时限自动登录）—— U1，已落地（GLM 2026-09-27）**
      - 已实现：同 IP + 距上次成功登录 < `session.sessionTimeoutMinutes` 自动放行；
        默认关闭、仅正版服生效、放行不刷新时间戳、改密/注销即失效（六条硬约束见
        「会话免登录」一节）。
      - 未引入 `sessionToken`（纯服务端模组无客户端载体，token 无法参与判定），
        会话依据为账号行 `lastIp + lastLoginAtMillis`。

- [x] **多语言文件（i18n）—— U2，已落地（GLM 2026-09-27）**
      - 全部玩家提示改为 `Component.translatable("mclogin.xxx")`，
        自带 `assets/mclogin/lang/zh_cn.json` 与 `en_us.json`；
        动态消息（锁定秒数 / status / 救援副本路径等）使用占位符，未退化回 literal。
      - D5 推荐的"半登录/访客"形态（免登录只给移动+聊天）未实现，登记如下待办。

- [x] **密码是否允许空格（G8-6，已落地）**
      - 四个命令已统一为 `StringArgumentType.word()`：**密码不允许包含空格**。
      - 旧版 `/login`、`/unregister` 用 `greedyString()`（可含空格），`/register`、
        `/changepassword` 用 `word()`×2（不可含空格），存在"注册设不出的密码登录却能输入"
        的矛盾；统一后含空格输入在解析层直接报错，不进入失败计数。

- [ ] **账号库位置**
      - 当前放在全局 `<服务端目录>/config/accounts.yml`（`FMLPaths.CONFIGDIR`）。
      - 若希望"每个存档独立账号库"，改用 `MinecraftServer#getServerDirectory()`（代码内已标注 TODO）。

- [ ] **Gradle Wrapper 未随仓库交付**
      - 首次构建需先执行 `gradle wrapper --gradle-version 8.1.1`。

- [ ] **配置热重载**
      - `/mclogin reload` 目前只重载账号数据与 IP 状态；ForgeConfig 的热重载需监听
        `net.minecraftforge.fml.event.config.ModConfigEvent.Reloading`（未实现）。

- [x] **`pbkdf2Iterations` 调大后的兼容性（本轮已补）**
      - 已在 `PlayerAccount` 中保存每账号的 `iterations`，支持新旧账号平滑共存；
      - 本轮补上"登录成功后自动收敛到当前配置值"（`LoginCommands` 成功分支）与
        "账号行未指定时按配置值兜底"（`PasswordHasher.verify` 第三参）；
      - 残余影响见上文「迭代数语义与迁移窗口」：迁移窗口内只能收敛，不能瞬时精确。

- [x] **★ 异步化 + 有界线程池 + 到达率限制（G8-1）—— 已落地（GLM 2026-09-27）**
      - 已按 R3/R5 方案完整落地：`security/HashExecutor`（有界池 `max(1, min(2, cpus/2))`
        + 守护线程 + 队列 64 + **`AbortPolicy`**，未用 `CallerRunsPolicy`）；
        同 UUID 在途去重（`putIfAbsent`）兼 ≥500ms 冷却；**回主线程再改状态**
        （`markLoginSuccess / recordFailure / markDirty` 均在 `server.execute` 回调里）；
        **`burnTime` 与 `verify` 同池同优先级**。默认迭代数已提到 **600000**，
        旧护栏（>200k 同步执行 WARN）随异步化移除。
      - 残余（如实标注）：注册命令的派生未按 IP 维度限流（同 UUID 冷却已有；
        离线服下换名可换 UUID 绕过冷却，但队列有界 + 过载拒绝，最坏是"繁忙"风暴
        而非主线程卡死）；"最弱目标机型 + 到达率"的实测反推仍待真机。

- [ ] **半登录/访客形态的会话免登录（D5 推荐，未实现）**
      - 免登录只给「可移动、可聊天」，破坏/开箱/`/changepassword`/`/unregister` 仍需口令，
        把最坏后果从账号失守降到信息泄露。需引入登录等级枚举并改动限制层，作为后续可选项。

- [ ] **崩溃路径的最后一批改动（G8-4 遗留）**
      - 已做：落盘间隔 300s → 60s；失败保留代际、下周期重试。
      - 未做：① 真正的 debounce（"最后 10s 无新 dirty 就写一次"）；② 更早的
        `ServerStoppingEvent` 再兜一次；③ JVM `Runtime.addShutdownHook(...)`。
        `kill -9` / 断电 / OOM-Kill 仍会丢掉最后一个落盘周期内的改动。

- [ ] **`PlayerAccount` 的失败计数仍是裸字段（G8-5 遗留一半）**
      - 本轮只给 `salt/hash/iterations/keyLength` 这四个"写坏即致命"的字段加了账号级锁；
        `failedAttempts / lockUntilMillis / lastFailedMillis` 仍由 `BruteForceGuard.recordFailure`
        在主线程无锁改写、由落盘线程无锁读取。最坏后果是**漏记一次计数**（下个周期补上），
        不会导致账号不可用；彻底解决需把整个对象改为不可变快照或全面加锁。

- [ ] **未登录限制是否足够严格（G4 复核后更新，GLM 2026-09-27）**
      - 目前覆盖：移动回退（逐 tick 位置回退到冻结点）、`PlayerInteractEvent`（含开容器/箱子 UI
        —— 右键方块被取消即打不开）、`PlayerContainerEvent.Open` 兜底（其他模组/命令直接
        打开容器 UI 的旁路，G8-2 已补）、`BlockEvent.BreakEvent` 破坏、聊天、命令、攻击实体、
        `ItemTossEvent` 丢弃（取消后把物品塞回背包，避免物品凭空消失）、
        `EntityItemPickupEvent` 拾取。
      - **G4 复核结论**：切旁观模式 / 骑乘实体 / 末影珍珠等**位移类**绕过在
        `allowMove=false`（默认）下已被逐 tick 位置回退**中和**——无论以何种方式位移，
        未登录玩家坐标每 tick 被拉回冻结点；残差仅为 1 tick 内的视觉穿透（外观级，
        不构成数据风险）。`allowMove=true` 时这些行为随配置放开，属运维显式选择
        （离线服README 顶部已警告其含义）。
      - **G8-2b 丢弃处理复核结论**：维持工程师现有方案（cancel + 塞回背包 + 满时脚下掉落）。
        `ItemTossEvent` 在物品**已离开背包后**触发，Forge 事件模型不存在"取消且不移除背包"
        的更干净路径（丢物品走 `ServerboundPlayerActionPacket`，Forge 未为其提供取消事件）；
        `allowInteract=true` 时直接放行（现有代码即如此），宽松模式等同正常游戏语义，一致。
      - 事件名订正：不存在 `DropItemEvent` 这个类，丢弃物品的正确事件是
        `net.minecraftforge.event.entity.item.ItemTossEvent`。
