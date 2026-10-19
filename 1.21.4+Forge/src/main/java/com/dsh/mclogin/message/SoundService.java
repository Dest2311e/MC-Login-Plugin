package com.dsh.mclogin.message;

import com.dsh.mclogin.MCLoginMod;
import com.dsh.mclogin.config.LoginConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * 音效服务（R8 任务 B）：用原版音效给玩家播放提示音。
 *
 * <p>原理：服务端向客户端发声音包，原版客户端自带全部原版音效资源，
 * <b>无需客户端安装本模组</b>。播放走 {@link ServerPlayer#playNotifySound}
 * （服务端通知音，不受玩家自身声音设置以外因素影响）。
 *
 * <p>线程约束（G8-1 口径一致）：<b>只允许在主线程调用</b>。
 * 现有调用点全部位于主线程上下文——命令提交点（{@code server.execute} 回调）、
 * 登录事件、tick 处理器；异步派生线程绝不直接发声音。
 *
 * <p>音量/音调可配置（{@code [sounds]} 段：总开关 / 音量 / 音调）；
 * 锁定音效在配置音调基础上乘 0.6（低音调，任务书建议）。
 * 音效选择与理由见 README「音效」一节；听觉效果需真实客户端确认
 * （mineflayer bot 收不到声音包，真机验证以"服务端日志无报错"为准）。
 */
public final class SoundService {

    // ============ 本模组用到的原版音效（1.20.1 mojmap 常量名，已由真实编译证实） ============
    // 注意：这一组在 1.20.1 中【不带】ENTITY_/BLOCK_ 前缀（如 PLAYER_LEVELUP 而非
    // ENTITY_PLAYER_LEVELUP）——首轮真编译实测纠错，勿改回带前缀的写法。

    /** 进服提示（登录/注册提示出现时）：清脆的音符盒"叮"。
     *  1.20.1 中 NOTE_BLOCK_* 常量是 Holder.Reference&lt;SoundEvent&gt;（真编译实测），取 .value()。 */
    public static final SoundEvent JOIN_HINT = SoundEvents.NOTE_BLOCK_PLING.value();

    /** 注册成功：升级音，仪式感强（注册 = 建号的大事件）。 */
    public static final SoundEvent REGISTER_SUCCESS = SoundEvents.PLAYER_LEVELUP;

    /** 登录成功：经验球拾取音，轻快不吵（比 CHIME 更短促，适合每次登录）。 */
    public static final SoundEvent LOGIN_SUCCESS = SoundEvents.EXPERIENCE_ORB_PICKUP;
    public static final SoundEvent UI_CLICK = SoundEvents.UI_BUTTON_CLICK.value();

    /** 登录失败：村民拒绝音，语义直白（"不行"）。 */
    public static final SoundEvent LOGIN_FAILED = SoundEvents.VILLAGER_NO;

    /** 账号锁定：铁砧落地（播放时音调 ×0.6，低沉警告）。 */
    public static final SoundEvent LOCKED = SoundEvents.ANVIL_LAND;

    /** 超时踢出前 30 秒警告：短促音符盒帽音（Holder.Reference 取 .value()，同上）。 */
    public static final SoundEvent TIMEOUT_WARNING = SoundEvents.NOTE_BLOCK_HAT.value();

    /** 锁定音效的音调乘数（任务书建议"低音调"）。 */
    private static final float LOCKED_PITCH_MULTIPLIER = 0.6F;

    /** 私有构造，避免实例化。 */
    private SoundService() {
        throw new UnsupportedOperationException("工具类不可实例化");
    }

    /**
     * 按配置音量/音调给单个玩家播放音效。
     *
     * <p>配置不可用（实例未就绪）时静默跳过——音效是纯体验增强，
     * 任何失败都不允许影响登录主流程。
     *
     * @param player 玩家（null 安全）
     * @param sound  原版音效事件
     */
    public static void play(final ServerPlayer player, final SoundEvent sound) {
        play(player, sound, 1.0F);
    }

    /**
     * 按配置音量/音调（乘以额外音调系数）给单个玩家播放音效。
     *
     * @param player          玩家（null 安全）
     * @param sound           原版音效事件
     * @param pitchMultiplier 音调乘数（1.0 = 不变；锁定用 0.6 低音调）
     */
    public static void play(final ServerPlayer player, final SoundEvent sound,
                            final float pitchMultiplier) {
        if (player == null || sound == null) {
            return;
        }
        final MCLoginMod mod = MCLoginMod.getInstance();
        final LoginConfig config = mod == null ? null : mod.getConfig();
        if (config == null || !config.isEnableSounds()) {
            return;
        }
        try {
            player.playNotifySound(sound, SoundSource.MASTER,
                    (float) config.getSoundVolume(),
                    (float) (config.getSoundPitch() * pitchMultiplier));
        } catch (final RuntimeException ex) {
            // 音效失败绝不影响主流程
            MCLoginMod.LOGGER.debug("[MCLogin] 播放音效失败（忽略）：{}", sound.location(), ex);
        }
    }

    /**
     * 播放锁定音效（固定低音调）。
     *
     * @param player 玩家
     */
    public static void playLocked(final ServerPlayer player) {
        play(player, LOCKED, LOCKED_PITCH_MULTIPLIER);
    }
}
