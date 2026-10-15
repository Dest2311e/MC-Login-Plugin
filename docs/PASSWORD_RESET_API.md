# 密码重置验证接口（1.1.0）

默认没有验证实现，菜单不显示重置按钮。服务端对接模组注册 `PasswordResetVerifier` 后，已注册且未登录的玩家才会看到入口。配置开关、客户端数据包、账号名、UUID 或 IP 本身均不能授权重置。

## 注册

在服务端主线程或服务端启动前调用：

```java
PasswordResetVerifier verifier = new PasswordResetVerifier() {
    @Override
    public CompletionStage<Boolean> verify(UUID playerId, UUID requestId) {
        // 对接方实现：只向此前绑定到此 UUID 的可信渠道发起验证。
        // 必须立即返回，不要在服务器线程执行 HTTP、数据库等待或验证码轮询。
        // 结果必须属于本次 requestId，不能把旧验证码/旧成功状态用于新请求。
        return recoveryBackend.verifyBoundIdentity(playerId, requestId);
    }

    @Override
    public void cancel(UUID requestId) {
        recoveryBackend.cancel(requestId); // 快速、非阻塞；允许请求已结束。
    }
};
MCLoginMod.getInstance().getPasswordReset().registerVerifier(verifier);
```

`recoveryBackend` 是示例占位，须由对接方提供真实实现，不存在默认允许的验证器。无绑定、拒绝、超时、异常均不得返回 true。通过可信服务端回调完成返回的 Future，不能信任客户端自报的“验证通过”。对接方负责发送验证说明、校验验证码、尝试次数限制及外部请求限流。

仅一个验证器可注册。停用对接时，在服务器线程调用：

```java
MCLoginMod.getInstance().getPasswordReset().unregisterVerifier(verifier);
```

注销立即撤销未完成的授权。验证器注册保留到显式注销，对接方须管理自身生命周期。不得将依赖运行期服务器实例的验证器跨服务端生命周期复用。

## 流程与约束

1. 玩家点击重置，服务器创建唯一请求 ID，绑定当前连接、账号对象、原密码凭据和验证器。同一连接两次请求至少间隔 10 秒；对接方仍需实施验证码及跨连接限流。
2. 外部验证阶段最长 2 分钟。只有 `Boolean.TRUE` 才进入设置密码界面；false、null 或异常均拒绝。
3. 验证通过后获得新的 2 分钟输入期限；输入新密码、再次确认。
4. 授权只消费一次；PBKDF2 在既有有界池执行。结果提交前再次检查连接、账号、凭据、验证器和期限。
5. 成功后失效会话免登录、清除本账号失败计数，要求使用新密码登录。不会直接赋予已登录状态。

断线、账号重载/删除/改密、登录成功、超时和对接停用都会使原授权失效。队列满或计算失败后需要重新验证。原有进服登录超时仍生效，不会因反复请求重置而延长。请按真实验证耗时设置 `restriction.loginTimeoutSeconds`。

菜单使用原版容器。验证期间若有自定义容器，可实现 `LoginUiMenu` 标记让原有容器限制识别；也可以完全在绑定的外部渠道完成验证。普通聊天及非登录命令仍受未登录限制，不应依赖它们绕过限制。

## UI 与验证状态

`ui.enableLoginUi=true` 时，未登录玩家关闭界面后会在约 0.5 秒内重新打开，死亡界面除外。已注册显示登录；只有对接后才显示重置；未注册只显示注册。计算和外部验证期间显示等待状态。玻璃边框、说明文字、倒计时 BossBar 均使用原版资源。按钮左键有效，装饰和菜单物品不可拿走。

音效继续遵循 `[sounds]` 的开关、音量及音调。自动重开不重复播放点击音，以免刷屏或扰人。

## 验证

JDK 17 执行 `gradlew.bat build --no-daemon`，其中 `securityChecks` 检查授权重放、跨 UUID、断线后旧回调、过期、消费中的过期及账号库损坏/空库往返。

运行验收：原版客户端注册与登录、Esc 自动重开、UI 确认步骤、成功后关闭、倒计时及音效；接入真实验证服务后检查通过、拒绝、断线、重载、过期和注销验证器的分支。未连接真实验证服务时不能宣称完成端到端找回测试。
