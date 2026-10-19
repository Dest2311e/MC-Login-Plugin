# MCLogin Bukkit API

适用于 `Paper-Spigot` 插件版。将 MCLogin JAR 作为 `compileOnly` 依赖，并在接入插件的 `plugin.yml` 中声明 `depend: [MCLogin]`（可选接入用 `softdepend` 且先检查是否存在）。不要把 MCLogin 的类打包进自己的插件。

## 查询状态与监听事件

```java
LoginService service = Bukkit.getServicesManager().load(LoginService.class);
if (service == null) throw new IllegalStateException("MCLogin service unavailable");
boolean authenticated = service.isAuthenticated(player.getUniqueId());
boolean registered = service.isRegistered(player.getUniqueId());
```

类型位于 `com.dsh.mclogin.bukkit.api`。两个 UUID 查询可从异步任务调用；所有 Bukkit 玩家操作仍遵守服务端线程规则。服务只提供查询和验证器注册，不提供认证绕过或直接修改密码的方法。使用前后要处理 MCLogin 或自身插件停用。

```java
@EventHandler
public void onAuthentication(AuthenticationEvent event) {
    if (event.getState() == AuthenticationEvent.State.AUTHENTICATED) {
        // 在主线程恢复自己插件的玩家功能；不要记录任何密码。
    }
}
```

事件在主线程同步发送，包含玩家、状态和原因，不包含密码、哈希或授权令牌。状态为 `REQUIRED`、`AUTHENTICATED`、`LOGGED_OUT`；原因包括 `JOIN`、`REGISTER`、`LOGIN`、`LOGOUT`、`UNREGISTER`、`QUIT`、`SHUTDOWN`。其他插件应自行订阅事件并读取服务，不要根据聊天消息或菜单标题判断认证状态。

## 外部找回验证器

验证器类型复用 `com.dsh.mclogin.api.PasswordResetVerifier`，注册和注销必须在主线程：

```java
service.registerResetVerifier(this, new PasswordResetVerifier() {
    public CompletionStage<Boolean> verify(UUID playerId, UUID requestId) {
        // 在可信系统中核验该 UUID 事先绑定的邮箱 / 身份。
        // 返回异步结果；不可把玩家刚输入的联系方式当成已绑定身份。
        return identityBackend.verifyExistingBinding(playerId, requestId);
    }
    public void cancel(UUID requestId) {
        identityBackend.cancel(requestId); // 应支持重复或已完成请求
    }
});
```

`identityBackend` 是接入方实现的示意服务，此代码不提供真实邮件或身份验证。最多注册一个验证器；重复注册会失败。验证器所属插件必须已启用，注销时使用同一个 Plugin 对象：

```java
service.unregisterResetVerifier(this);
```

核验回调可在任意线程完成，MCLogin 回到主线程处理。授权限时 120 秒，一次有效，绑定玩家连接、账号对象和当前凭据；断线、退出认证、账号变化、验证器撤销或所属插件停用都会使相关授权失效。哈希任务执行期间也重新检查这些条件。核验成功后玩家仍须两次输入新密码；重置成功不会自动登录。

## PlaceholderAPI

无需另外下载 expansion，MCLogin 启用时自行注册：

| 占位符 | 输出 |
| --- | --- |
| `%mclogin_authenticated%` | `true` / `false` |
| `%mclogin_registered%` | `true` / `false` |
| `%mclogin_status%` | `authenticated` / `required` |
| `%mclogin_bedrock%` | Floodgate 标识，`true` / `false` |

占位符用于展示。第三方业务执行授权操作时应重新查询 LoginService，避免使用显示缓存作为授权依据。
