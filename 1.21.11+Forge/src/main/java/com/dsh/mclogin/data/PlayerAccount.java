package com.dsh.mclogin.data;

import java.util.UUID;

/**
 * 玩家账号模型。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code hash} / {@code salt}：PBKDF2WithHmacSHA256 产出的 Base64 字符串。</li>
 *   <li>{@code iterations}：该账号哈希时使用的迭代次数；{@code 0 = 未指定}，
 *       由 {@code PasswordHasher.verify} 回落到当前配置值（支持平滑迁移，详见字段注释）。</li>
 *   <li>{@code failedAttempts}：连续失败计数，登录成功后清零。</li>
 *   <li>{@code lockUntilMillis}：锁定截止时间戳（毫秒，Unix epoch），{@code <= now} 表示未锁定。</li>
 *   <li>{@code lastFailedMillis}：最后一次失败时间戳，用于衰减窗口判定。</li>
 *   <li>{@code lastIp}：最后一次成功/尝试登录的 IP（脱敏存储可选）。</li>
 *   <li>{@code registeredAtMillis} / {@code lastLoginAtMillis}：注册时间与最后登录时间。</li>
 * </ul>
 *
 * <p>本类为纯数据对象（POJO），供 SnakeYAML 直接序列化为 {@code accounts.yml} 中的 Map。
 */
public final class PlayerAccount {

    /** 玩家 UUID 字符串（主键）。 */
    private String uuid = "";

    /** 玩家名（小写存档用，方便人工排查）。 */
    private String username = "";

    /** PBKDF2 哈希（Base64）。 */
    private String hash = "";

    /** 盐（Base64，每次注册/改密随机生成）。 */
    private String salt = "";

    /**
     * PBKDF2 迭代次数。
     *
     * <p><b>G1(ii)：0 表示"未指定"</b>，不再由数据层拍板一个策略值。
     * 旧实现默认 64000，等于把"配置默认值"复制进数据层，于是
     * "账号不存在"分支（用配置值烧时间）与"账号存在"分支（用存量值烧时间）
     * 在运维改过 {@code pbkdf2Iterations} 之后迭代数不一致 → B4 的耗时抹平反向失效，
     * 攻击者可用响应时间枚举"该账号是否注册过"。
     * 实际使用的迭代数由 {@code PasswordHasher.verify(密码, 账号, 当前配置值)} 决定；
     * 登录成功后还会按当前配置值就地收敛本字段。
     */
    private int iterations = 0;

    /** 密钥长度（bit），默认 256。 */
    private int keyLength = 256;

    /** 连续失败次数。 */
    private int failedAttempts = 0;

    /** 锁定截止时间戳（毫秒）。 */
    private long lockUntilMillis = 0L;

    /** 最后一次失败时间戳（毫秒）。 */
    private long lastFailedMillis = 0L;

    /** 最后一次登录/尝试的 IP。 */
    private String lastIp = "";

    /** 注册时间戳（毫秒）。 */
    private long registeredAtMillis = 0L;

    /** 最后一次成功登录时间戳（毫秒）。 */
    private long lastLoginAtMillis = 0L;

    /**
     * 默认构造（SnakeYAML 反序列化需要）。
     */
    public PlayerAccount() {
        // 字段均已在声明处初始化
    }

    /**
     * 便捷构造：注册新账号时使用。
     *
     * @param uuid              玩家 UUID
     * @param username          玩家名
     * @param hash              PBKDF2 哈希（Base64）
     * @param salt              盐（Base64）
     * @param iterations        迭代次数
     * @param keyLength         密钥长度（bit）
     * @param ip                登录 IP
     */
    public PlayerAccount(final UUID uuid,
                         final String username,
                         final String hash,
                         final String salt,
                         final int iterations,
                         final int keyLength,
                         final String ip) {
        this.uuid = uuid == null ? "" : uuid.toString();
        this.username = username == null ? "" : username;
        this.hash = hash == null ? "" : hash;
        this.salt = salt == null ? "" : salt;
        this.iterations = iterations;
        this.keyLength = keyLength;
        this.lastIp = ip == null ? "" : ip;
        this.registeredAtMillis = System.currentTimeMillis();
        this.lastLoginAtMillis = 0L;
        this.failedAttempts = 0;
        this.lockUntilMillis = 0L;
        this.lastFailedMillis = 0L;
    }

    /**
     * 当前是否处于锁定状态。
     *
     * @return true 表示仍被锁定
     */
    public boolean isLocked() {
        return lockUntilMillis > System.currentTimeMillis();
    }

    /**
     * 剩余锁定时间（秒），未锁定返回 0。
     *
     * @return 剩余秒数
     */
    public long getRemainingLockSeconds() {
        final long remain = lockUntilMillis - System.currentTimeMillis();
        return remain > 0 ? remain / 1000L : 0L;
    }

    /**
     * 清零失败计数与锁定（登录成功后调用）。
     */
    public void resetFailures() {
        this.failedAttempts = 0;
        this.lockUntilMillis = 0L;
        this.lastFailedMillis = 0L;
    }

    /**
     * 记录一次成功登录。
     *
     * @param ip 本次 IP
     */
    public void markLoginSuccess(final String ip) {
        this.lastIp = ip == null ? "" : ip;
        this.lastLoginAtMillis = System.currentTimeMillis();
        resetFailures();
    }

    /**
     * 获取玩家 UUID 字符串。
     *
     * @return UUID 字符串
     */
    public String getUuid() {
        return uuid;
    }

    /**
     * 设置玩家 UUID 字符串。
     *
     * @param uuid UUID 字符串
     */
    public void setUuid(final String uuid) {
        this.uuid = uuid == null ? "" : uuid;
    }

    /**
     * 获取玩家名。
     *
     * @return 玩家名
     */
    public String getUsername() {
        return username;
    }

    /**
     * 设置玩家名。
     *
     * @param username 玩家名
     */
    public void setUsername(final String username) {
        this.username = username == null ? "" : username;
    }

    /**
     * 获取哈希。
     *
     * @return Base64 哈希
     */
    public String getHash() {
        return hash;
    }

    /**
     * 设置哈希。
     *
     * @param hash Base64 哈希
     */
    public void setHash(final String hash) {
        this.hash = hash == null ? "" : hash;
    }

    /**
     * 获取盐。
     *
     * @return Base64 盐
     */
    public String getSalt() {
        return salt;
    }

    /**
     * 设置盐。
     *
     * @param salt Base64 盐
     */
    public void setSalt(final String salt) {
        this.salt = salt == null ? "" : salt;
    }

    /**
     * 获取迭代次数。
     *
     * @return 迭代次数
     */
    public int getIterations() {
        return iterations;
    }

    /**
     * 设置迭代次数。
     *
     * @param iterations 迭代次数
     */
    public void setIterations(final int iterations) {
        this.iterations = iterations;
    }

    /**
     * 获取密钥长度（bit）。
     *
     * @return 密钥长度
     */
    public int getKeyLength() {
        return keyLength;
    }

    /**
     * 设置密钥长度（bit）。
     *
     * @param keyLength 密钥长度
     */
    public void setKeyLength(final int keyLength) {
        this.keyLength = keyLength;
    }

    /**
     * 获取连续失败次数。
     *
     * @return 失败次数
     */
    public int getFailedAttempts() {
        return failedAttempts;
    }

    /**
     * 设置连续失败次数。
     *
     * @param failedAttempts 失败次数
     */
    public void setFailedAttempts(final int failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    /**
     * 获取锁定截止时间戳。
     *
     * @return 毫秒时间戳
     */
    public long getLockUntilMillis() {
        return lockUntilMillis;
    }

    /**
     * 设置锁定截止时间戳。
     *
     * @param lockUntilMillis 毫秒时间戳
     */
    public void setLockUntilMillis(final long lockUntilMillis) {
        this.lockUntilMillis = lockUntilMillis;
    }

    /**
     * 获取最后一次失败时间戳。
     *
     * @return 毫秒时间戳
     */
    public long getLastFailedMillis() {
        return lastFailedMillis;
    }

    /**
     * 设置最后一次失败时间戳。
     *
     * @param lastFailedMillis 毫秒时间戳
     */
    public void setLastFailedMillis(final long lastFailedMillis) {
        this.lastFailedMillis = lastFailedMillis;
    }

    /**
     * 获取最后 IP。
     *
     * @return IP 字符串
     */
    public String getLastIp() {
        return lastIp;
    }

    /**
     * 设置最后 IP。
     *
     * @param lastIp IP 字符串
     */
    public void setLastIp(final String lastIp) {
        this.lastIp = lastIp == null ? "" : lastIp;
    }

    /**
     * 获取注册时间戳。
     *
     * @return 毫秒时间戳
     */
    public long getRegisteredAtMillis() {
        return registeredAtMillis;
    }

    /**
     * 设置注册时间戳。
     *
     * @param registeredAtMillis 毫秒时间戳
     */
    public void setRegisteredAtMillis(final long registeredAtMillis) {
        this.registeredAtMillis = registeredAtMillis;
    }

    /**
     * 获取最后登录时间戳。
     *
     * @return 毫秒时间戳
     */
    public long getLastLoginAtMillis() {
        return lastLoginAtMillis;
    }

    /**
     * 设置最后登录时间戳。
     *
     * @param lastLoginAtMillis 毫秒时间戳
     */
    public void setLastLoginAtMillis(final long lastLoginAtMillis) {
        this.lastLoginAtMillis = lastLoginAtMillis;
    }

    /**
     * 生成调试用的安全摘要（不含哈希/盐）。
     *
     * @return 字符串摘要
     */
    @Override
    public String toString() {
        return "PlayerAccount{uuid=" + uuid
                + ", username=" + username
                + ", iterations=" + iterations
                + ", failedAttempts=" + failedAttempts
                + ", locked=" + isLocked()
                + ", lastIp=" + lastIp + '}';
    }
}
