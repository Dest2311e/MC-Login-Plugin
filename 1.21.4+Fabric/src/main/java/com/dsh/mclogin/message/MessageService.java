package com.dsh.mclogin.message;

import com.dsh.mclogin.MCLoginMod;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息服务（R8 任务 A）：把语言键在<b>服务端</b>解析成字面文本再发送。
 *
 * <p><b>为什么不是 {@code Component.translatable}</b>：translatable 的翻译在<b>客户端</b>
 * 解析，而本模组 {@code mods.toml} 声明 {@code side="SERVER"}——玩家的原版客户端
 * 没有本模组的语言文件（jar 里的 {@code assets/mclogin/lang/} 不会下发），
 * 客户端查不到键就<b>直接显示键名原文</b>（真机实证，见记录第二十四~二十六节）。
 * 因此改为：服务端按配置语言读入 jar 内语言文件，把键解析成 {@code Component.literal}
 * 发送——任何原版客户端都能直接看到对应语言，与客户端自身语言设置无关。
 *
 * <p>数据源沿用 U2 的 {@code assets/mclogin/lang/zh_cn.json} 与 {@code en_us.json}
 * （内容不重写）。解析顺序：<b>配置语言 → en_us → 键名本身</b>；
 * {@code %s} 占位符按参数顺序替换后再发字面量，{@code §} 样式码由客户端渲染。
 *
 * <p>JSON 解析为本类自带的约 50 行扁平解析器（键值对均为字符串），
 * 不引入第三方依赖（R5 红线）；解析失败时该语言视为空映射并打 WARN，
 * 发送回退到 en_us / 键名——永不因文案问题阻断登录流程。
 * 字符串转义支持：双引号、反斜杠、斜杠、n/t/r/b/f 控制符，
 * 以及"反斜杠 + u + 4 位十六进制"的 Unicode 转义
 * （javadoc 不直接书写反斜杠加 u 的字面量——Java 的 unicode 预处理对注释同样生效）。
 */
public final class MessageService {

    /** 回退语言（语言文件随 jar 必在；en_us 同时作为解析失败时的兜底映射）。 */
    private static final String FALLBACK_LANGUAGE = "en_us";

    /** 语言资源在 jar 内的路径模板。 */
    private static final String RESOURCE_PATTERN = "/assets/mclogin/lang/%s.json";

    /** 当前语言（{@code ensureLanguage} 成功加载后非 null）。 */
    private static volatile String language = null;

    /** 当前语言的键值映射。 */
    private static volatile Map<String, String> templates = Collections.emptyMap();

    /** 回退语言（en_us）的键值映射。 */
    private static volatile Map<String, String> fallback = Collections.emptyMap();

    /** 私有构造，避免实例化。 */
    private MessageService() {
        throw new UnsupportedOperationException("工具类不可实例化");
    }

    /**
     * 确保语言已按配置加载（幂等；语言变化时重新读文件）。
     *
     * <p>在服务器启动（配置已就绪）时调用一次；此后不随配置热重载
     * （与 {@code /mclogin reload} 只重载账号数据的既有口径一致，见 README 待办）。
     *
     * @param configured 配置的 {@code general.language}（空/空白时按 en_us 处理）
     */
    public static synchronized void ensureLanguage(final String configured) {
        final String lang = configured == null || configured.isBlank()
                ? FALLBACK_LANGUAGE : configured.trim();
        if (lang.equals(language)) {
            return;
        }
        final Map<String, String> loaded = load(lang);
        final Map<String, String> fallbackLoaded = FALLBACK_LANGUAGE.equals(lang)
                ? loaded : load(FALLBACK_LANGUAGE);
        if (loaded.isEmpty() && fallbackLoaded.isEmpty()) {
            MCLoginMod.LOGGER.warn("[MCLogin] 语言文件加载失败（language={}）：提示将回退为键名原文", lang);
        } else {
            MCLoginMod.LOGGER.info("[MCLogin] 提示语言已加载：{}（{} 条，en_us 回退 {} 条）",
                    lang, loaded.size(), fallbackLoaded.size());
        }
        language = lang;
        templates = loaded;
        fallback = fallbackLoaded;
    }

    /**
     * 解析消息模板为字面文本（先参数替换，后发送）。
     *
     * <p>回退链：配置语言 → en_us → 键名本身（键名回退便于发现漏配的键）。
     * {@code %s} 按参数顺序替换；参数多于占位符时忽略多余参数，
     * 占位符多于参数时保留 {@code %s} 原样（便于排查），绝不发送半替换的乱序文本。
     *
     * @param key  语言键
     * @param args 占位符参数
     * @return 字面文本（可直接放入 {@link Component#literal}）
     */
    public static String format(final String key, final Object... args) {
        String template = templates.get(key);
        if (template == null) {
            template = fallback.get(key);
        }
        if (template == null) {
            return key;
        }
        if (args == null || args.length == 0) {
            return template;
        }
        final StringBuilder sb = new StringBuilder(template.length() + 32);
        int argIndex = 0;
        int from = 0;
        int placeholder;
        while ((placeholder = template.indexOf("%s", from)) >= 0) {
            sb.append(template, from, placeholder);
            if (argIndex < args.length) {
                sb.append(args[argIndex++]);
            } else {
                sb.append("%s"); // 占位符多于参数：保留原样，便于定位
            }
            from = placeholder + 2;
        }
        sb.append(template.substring(from));
        return sb.toString();
    }

    /**
     * 构造字面消息组件（服务端解析后的 {@code Component.literal}）。
     *
     * <p>R10-B：**全部**经本方法出口的消息统一加粗（用户要求"所有提示词加粗"）。
     * 只改这一处出口，不改 55 个语言键的 § 码——加粗是 Style 层叠加：
     * 文本内的 {@code §} 颜色码由客户端照常解析（颜色与加粗不冲突），
     * {@code %s} 替换进来的参数值是纯数据、不感知样式。服务端日志不走本方法，不受影响。
     *
     * @param key  语言键
     * @param args 占位符参数
     * @return 加粗的字面组件
     */
    public static Component component(final String key, final Object... args) {
        return Component.literal(format(key, args))
                .setStyle(Style.EMPTY.withBold(true));
    }

    /**
     * 从 jar 内读入一个语言文件（扁平 JSON：键与值均为字符串）。
     *
     * @param language 语言代码
     * @return 键值映射（文件缺失或解析失败时为空映射，不抛异常）
     */
    private static Map<String, String> load(final String language) {
        final String path = String.format(RESOURCE_PATTERN, language);
        try (final InputStream in = MessageService.class.getResourceAsStream(path)) {
            if (in == null) {
                MCLoginMod.LOGGER.warn("[MCLogin] 语言文件不存在：{}（回退链将处理）", path);
                return Collections.emptyMap();
            }
            final String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseFlatJson(json);
        } catch (final Exception ex) {
            // 文案解析失败绝不阻断登录流程：该语言视为空映射，发送侧回退 en_us / 键名
            MCLoginMod.LOGGER.warn("[MCLogin] 语言文件解析失败：{}", path, ex);
            return Collections.emptyMap();
        }
    }

    /**
     * 解析扁平 JSON（仅支持 {@code { "key": "value", ... }}，值全为字符串）。
     *
     * <p>字符级扫描：跳过花括号/逗号/冒号与空白；字符串支持 JSON 标准转义
     * （双引号、反斜杠、斜杠、n/t/r/b/f 控制符、Unicode 十六进制转义）。
     * 语言文件由本仓库自己维护、结构受控，该解析器足够；
     * 遇到结构外内容会抛异常并由 {@link #load} 兜底。
     *
     * @param json JSON 文本
     * @return 键值映射
     */
    private static Map<String, String> parseFlatJson(final String json) {
        final Map<String, String> map = new LinkedHashMap<>();
        String pendingKey = null;
        int index = 0;
        final int length = json.length();
        while (index < length) {
            final char c = json.charAt(index);
            if (c == '"') {
                final int[] cursor = {index};
                final String text = readString(json, cursor);
                // readString 返回的是【闭引号】下标：必须 +1 越过它，
                // 否则同一个引号会被再次当作下个字符串的开头，解析整体错位
                // （真机回归抓到的差一错误：表现为最后一串扫到 EOF 报 Unterminated string）。
                index = cursor[0] + 1;
                if (pendingKey == null) {
                    pendingKey = text;
                } else {
                    map.put(pendingKey, text);
                    pendingKey = null;
                }
                continue;
            }
            if (c == '{' || c == '}' || c == ',' || c == ':' || c == ' '
                    || c == '\t' || c == '\r' || c == '\n') {
                index++;
                continue;
            }
            // 受控格式之外的内容（数字/布尔/注释等）：本文件不应出现，交给异常兜底
            throw new IllegalArgumentException("Unexpected character '" + c + "' at " + index);
        }
        if (pendingKey != null) {
            throw new IllegalArgumentException("Dangling key without value");
        }
        return map;
    }

    /**
     * 从 {@code cursor[0]}（指向开引号）读取一个 JSON 字符串，处理标准转义。
     *
     * @param json   JSON 文本
     * @param cursor 进出参数：入 = 开引号下标，出 = 闭引号下标
     * @return 字符串内容（转义已还原）
     */
    private static String readString(final String json, final int[] cursor) {
        final StringBuilder sb = new StringBuilder(64);
        int index = cursor[0] + 1;
        final int length = json.length();
        while (index < length) {
            final char c = json.charAt(index);
            if (c == '\\') {
                index++;
                if (index >= length) {
                    break;
                }
                final char escape = json.charAt(index);
                switch (escape) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'u':
                        // "反斜杠 + u + 4 位十六进制"：四位合成一个字符
                        if (index + 4 >= length) {
                            throw new IllegalArgumentException("Bad unicode escape");
                        }
                        sb.append((char) Integer.parseInt(json.substring(index + 1, index + 5), 16));
                        index += 4;
                        break;
                    default:
                        sb.append(escape);
                }
                index++;
            } else if (c == '"') {
                cursor[0] = index;
                return sb.toString();
            } else {
                sb.append(c);
                index++;
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }
}
