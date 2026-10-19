package com.dsh.mclogin.security;

import com.dsh.mclogin.data.PlayerAccount;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * 密码哈希器：基于 Java 内置的 {@code PBKDF2WithHmacSHA256}。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>严禁明文/裸 SHA</b>：只保存 salt + PBKDF2 派生结果。</li>
 *   <li><b>每账号独立随机 salt</b>（16 字节，{@link SecureRandom}）。</li>
 *   <li><b>高迭代次数</b>（默认 64000，可在 {@code mclogin-server.toml} 调整）。</li>
 *   <li><b>常量时间比较</b>：使用 {@link MessageDigest#isEqual(byte[], byte[])}，
 *       避免因比较提前返回带来的时序侧信道。</li>
 *   <li><b>不记录日志</b>：本类任何方法都不打印密码或哈希。</li>
 * </ul>
 *
 * <p>不引入任何第三方库（BCrypt/Argon2 等），符合"只用 Java 内置能力"的约束。
 */
public final class PasswordHasher {

    /** 盐长度（字节）。 */
    public static final int SALT_BYTES = 16;

    /** 默认派生密钥长度（bit）。 */
    public static final int DEFAULT_KEY_LENGTH = 256;

    /** 默认迭代次数。 */
    public static final int DEFAULT_ITERATIONS = 64000;

    /** 算法名。 */
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    /** 安全随机源。 */
    private final SecureRandom random = new SecureRandom();

    /**
     * 默认构造。
     */
    public PasswordHasher() {
        // SecureRandom 已在字段初始化
    }

    /**
     * 生成随机盐（Base64 字符串）。
     *
     * @return Base64 盐
     */
    public String generateSalt() {
        final byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /**
     * 计算 PBKDF2 哈希。
     *
     * <p><b>G1(iii) 修复</b>：原实现的 try 从 {@code SecretKeyFactory.getInstance} 才开始，
     * 而两个抛点在 try 之外 ——
     * {@code Base64.getDecoder().decode(saltBase64)} 对非 Base64 输入抛
     * {@code IllegalArgumentException}，{@code new PBEKeySpec(...)} 对空 salt /
     * 非正 iterations / 非正 keyLength 同样抛 {@code IllegalArgumentException}。
     * 两者都会直接逃逸出 {@code verify}（其签名只声明 boolean），导致
     * "不计失败次数、不锁定、账号永久不可用、玩家无法改密自救"。
     * 现在把参数构造一并包进 try，并把 {@code IllegalArgumentException} 显式收口。
     *
     * @param password   明文密码（不为空）
     * @param saltBase64 Base64 盐
     * @param iterations 迭代次数
     * @param keyLength  派生密钥长度（bit）
     * @return Base64 编码的哈希
     * @throws IllegalStateException    当 JVM 不支持 PBKDF2WithHmacSHA256 时（理论上不会发生）
     * @throws IllegalArgumentException 当 salt 非法（非 Base64 / 空）或 iterations、keyLength 非正时
     */
    public String hash(final String password,
                       final String saltBase64,
                       final int iterations,
                       final int keyLength) {
        if (password == null || saltBase64 == null) {
            throw new IllegalArgumentException("password 与 salt 均不可为 null");
        }
        final char[] chars = password.toCharArray();
        PBEKeySpec spec = null;
        try {
            final byte[] salt = Base64.getDecoder().decode(saltBase64);
            spec = new PBEKeySpec(chars, salt, iterations, keyLength);
            final SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            final byte[] derived = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(derived);
        } catch (final NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 不可用或参数非法", ex);
        } catch (final IllegalArgumentException ex) {
            // Base64 解码失败 / PBEKeySpec 参数非法（空 salt、iterations<=0、keyLength<=0）
            throw new IllegalArgumentException("salt 或派生参数非法，无法计算哈希", ex);
        } finally {
            if (spec != null) {
                spec.clearPassword();
            }
            java.util.Arrays.fill(chars, '\0');
        }
    }

    /**
     * 使用默认参数计算哈希（便于 {@code /register} 直接调用）。
     *
     * @param password   明文密码
     * @param saltBase64 Base64 盐
     * @return Base64 哈希
     */
    public String hash(final String password, final String saltBase64) {
        return hash(password, saltBase64, DEFAULT_ITERATIONS, DEFAULT_KEY_LENGTH);
    }

    /**
     * 校验密码是否匹配账号记录（常量时间比较）。
     *
     * <p><b>G1(ii)</b>：账号行未指定迭代数（{@code iterations <= 0}）时，
     * 回落到调用方传入的<b>当前策略值</b>，而不是回落到本类的编译期常量。
     * 这样"账号存量值"与"配置值"是两个不同来源，攻击者无法用
     * "不存在的账号烧 N 次迭代 / 存在的账号烧 M 次迭代"的耗时差做账号枚举
     * （B4 的耗时抹平前提是两条分支迭代数一致）。
     *
     * @param password         明文密码
     * @param account          账号记录（含 hash/salt/iterations/keyLength）
     * @param policyIterations 当前配置的 PBKDF2 迭代次数（{@code <= 0} 表示调用方未提供）
     * @return true 匹配
     */
    public boolean verify(final String password, final PlayerAccount account, final int policyIterations) {
        if (password == null || account == null) {
            return false;
        }
        final String storedHash = account.getHash();
        final String storedSalt = account.getSalt();
        if (storedHash == null || storedHash.isEmpty() || storedSalt == null || storedSalt.isEmpty()) {
            return false;
        }
        final int iterations = resolveIterations(account.getIterations(), policyIterations);
        final int keyLength = account.getKeyLength() > 0 ? account.getKeyLength() : DEFAULT_KEY_LENGTH;
        final String candidate;
        try {
            candidate = hash(password, storedSalt, iterations, keyLength);
        } catch (final IllegalStateException | IllegalArgumentException ex) {
            // G1(iii) fail-closed：salt 非法 / 派生参数非法时绝不"放行"。
            // 一处收口（而不是在每个命令里 catch），并且只记录 UUID，绝不记录 salt/哈希/密码。
            // 旧实现在这里会让 IllegalArgumentException 直接逃逸 → 不计失败、不锁定、
            // 账号永久不可用且玩家无法改密自救。
            com.dsh.mclogin.MCLoginMod.LOGGER.error(
                    "[MCLogin] 账号 {} 的密码校验无法完成（salt 或派生参数非法），本次判定为失败",
                    account.getUuid(), ex);
            return false;
        }
        return constantTimeEquals(storedHash, candidate);
    }

    /**
     * 兼容入口：未提供策略迭代数时按 {@link #DEFAULT_ITERATIONS} 兜底。
     *
     * @param password 明文密码
     * @param account  账号记录
     * @return true 匹配
     */
    public boolean verify(final String password, final PlayerAccount account) {
        return verify(password, account, 0);
    }

    /**
     * 解析实际使用的迭代次数：优先账号存量值，其次调用方策略值，最后编译期默认值。
     *
     * @param storedIterations 账号行里的迭代数（{@code <= 0} 表示未指定）
     * @param policyIterations 当前配置值（{@code <= 0} 表示未提供）
     * @return 迭代次数
     */
    private static int resolveIterations(final int storedIterations, final int policyIterations) {
        if (storedIterations > 0) {
            return storedIterations;
        }
        return policyIterations > 0 ? policyIterations : DEFAULT_ITERATIONS;
    }

    /**
     * 常量时间字符串比较（先按字节比较，避免短路）。
     *
     * @param a 字符串 A
     * @param b 字符串 B
     * @return true 相等
     */
    public boolean constantTimeEquals(final String a, final String b) {
        if (a == null || b == null) {
            return a == b;
        }
        final byte[] left = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] right = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }

    /**
     * 空跑一次同等代价的 PBKDF2 并丢弃结果 —— 用于抹平"账号不存在"分支的耗时差。
     *
     * <p>背景（B4 修复②）：账号不存在时若不计算直接返回（~0ms），而账号存在时要跑
     * 64000 次迭代（~30-60ms），攻击者可通过测量命令响应耗时来枚举哪些玩家注册过。
     * 这里使用<b>固定的 dummy salt</b> 跑相同迭代次数，使两条分支耗时基本相当。
     *
     * <p>注意：dummy salt 是公开常量，仅用于耗时对齐，<b>不参与任何真实校验</b>。
     *
     * @param password   明文密码（可为任意值，结果被丢弃）
     * @param iterations 迭代次数，应与真实账号使用的次数一致
     */
    public void burnTime(final String password, final int iterations) {
        final String dummySalt = "MCLoginDummySaltForTimingEqualization00=";
        try {
            hash(password == null ? "" : password, dummySalt,
                    iterations > 0 ? iterations : DEFAULT_ITERATIONS, DEFAULT_KEY_LENGTH);
        } catch (final RuntimeException ex) {
            // 空跑失败不影响主流程：最坏情况是耗时侧信道重新出现，但功能不受影响
        }
    }

    /**
     * 生成新账号的 hash/salt 并写回账号对象。
     *
     * <p><b>[C-N1] 账号级加锁</b>：四个字段（salt / hash / iterations / keyLength）
     * 必须成组替换。PBKDF2 计算在锁<b>外</b>完成，只在赋值瞬间持锁，
     * 避免长时间阻塞落盘线程（{@code YamlStorage.dumpAccounts} 对同一对象按同一把锁读）。
     *
     * <p>G8-1：本方法仍可在主线程外的池内调用——但 LoginCommands 的异步流程把
     * "派生"与"落字段"拆开（派生在池内、四字段落盘在主线程提交点），
     * 此时请使用 {@link #applyPrecomputed}；本方法保留给同步路径与测试。
     *
     * @param account    账号对象（会被就地修改）
     * @param password   明文密码
     * @param iterations 迭代次数（{@code <= 0} 时使用 {@link #DEFAULT_ITERATIONS}）
     */
    public void applyNewPassword(final PlayerAccount account,
                                 final String password,
                                 final int iterations) {
        final String salt = generateSalt();
        final int iter = iterations > 0 ? iterations : DEFAULT_ITERATIONS;
        final String hash = hash(password, salt, iter, DEFAULT_KEY_LENGTH);
        applyPrecomputed(account, salt, hash, iter, DEFAULT_KEY_LENGTH);
    }

    /**
     * 把<b>已派生完成</b>的凭据成组写回账号对象（G8-1 配套）。
     *
     * <p>异步流程中，池内只做纯计算（{@link #hash}），拿到 salt/hash 后交由
     * 主线程提交点调用本方法落字段——保证"工作线程不写共享可变状态"（G8-1 约束 3），
     * 同时保持 [C-N1] 的账号级锁不变：四字段在 {@code synchronized (account)} 内
     * 一次性替换，与 {@code YamlStorage.dumpAccounts} 的读锁配对。
     *
     * @param account    账号对象（会被就地修改）
     * @param salt       Base64 盐（应来自 {@link #generateSalt()}）
     * @param hash       Base64 哈希（应来自 {@link #hash}）
     * @param iterations 迭代次数（必须与派生时一致）
     * @param keyLength  派生密钥长度（bit）
     */
    public void applyPrecomputed(final PlayerAccount account,
                                 final String salt,
                                 final String hash,
                                 final int iterations,
                                 final int keyLength) {
        synchronized (account) {
            account.setSalt(salt);
            account.setHash(hash);
            account.setIterations(iterations);
            account.setKeyLength(keyLength);
        }
    }
}
