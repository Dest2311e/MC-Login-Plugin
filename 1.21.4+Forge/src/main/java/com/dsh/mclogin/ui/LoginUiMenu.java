package com.dsh.mclogin.ui;

/**
 * 标记接口：本模组**自己的登录 UI 菜单**。
 *
 * <p><b>为什么需要它</b>：{@code LoginRestrictionListener.onContainerOpen} 会对
 * "未登录玩家打开容器"做兜底拦截（防其它模组/命令绕过右键直接开容器 UI）。
 * 而登录 UI 恰恰就是在玩家**未登录时**打开的容器菜单 —— 若不加豁免，
 * 这个兜底会把登录菜单自己立刻关掉，UI 直接不可用。
 *
 * <p>因此：兜底逻辑遇到实现了本接口的菜单时跳过；其余容器（箱子/熔炉/漏斗/其它模组）
 * 照常被拦。
 *
 * <p>注意本接口只用于"放行自己的 UI"，<b>不承载任何权限判断</b> ——
 * 是否已登录一律以 {@code SessionManager} 为准。
 */
public interface LoginUiMenu {
}
