package com.systemwin;

import com.systemwin.cli.CliException;
import com.systemwin.util.ProcessRunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Internationalization support.
 * <p>国际化（i18n）支持类。i18n 是 "internationalization" 这个英文单词的缩写，
 * 因为它的第一个字母 i 和最后一个字母 n 之间正好隔着 18 个字母，所以大家习惯
 * 把它简写成 i18n。这个类的职责用大白话说就是：让我们的 SystemWin 程序
 * "会说话"——既能给英文用户显示英文提示，也能给中文用户显示中文提示，
 * 而且还能自动判断"这位用户大概是什么语言背景"，省得用户手动去设置。
 * <p>这个类里所有的逻辑，说到底都是在回答两个问题：
 * 第一，"现在到底该用哪种语言？"（这就是"语言解析"的部分）；
 * 第二，"确定了语言之后，各种提示文字和帮助文本长什么样？"
 * （这就是"资源加载"的部分）。把这两个问题看明白了，整个类就没什么秘密了。
 *
 * <p>Language resolution order:
 * 语言的解析顺序（也就是"决定用哪种语言"时依次尝试的步骤）如下，
 * 每一步的优先级从高到低：先试第一步，成功了就直接用；失败了才轮到下一步，
 * 依此类推。这种"一级一级往下降"的做法，在软件工程里叫作"兜底链"
 * （fallback chain），目的是保证无论运行环境多奇葩，程序总能得到一个能用的
 * 语言，绝不会因为"查不到语言"这种小事而崩溃：
 * <ol>
 *   <li>a language explicitly chosen with {@code systemwin language <lang>}
 *       (stored in {@code %APPDATA%\SystemWin\config.properties});
 *       也就是"用户通过命令行显式指定的语言"。用户一旦执行过
 *       {@code systemwin language zh_CN} 之类的命令，这个选择就会被
 *       保存到配置文件里（Windows 上位于
 *       {@code %APPDATA%\SystemWin\config.properties}，展开后通常是
 *       C:\Users\你的用户名\AppData\Roaming\SystemWin\config.properties）。
 *       因为这是用户自己亲口指定的，所以它的优先级最高，谁的意见
 *       都比不上用户本人的意见。</li>
 *   <li>the Windows UI language read from the registry
 *       (HKCU\Control Panel\International);
 *       如果用户从来没有显式指定过语言，那就去 Windows 注册表里查
 *       当前登录用户的系统界面语言。注意路径是 HKCU（HKEY_CURRENT_USER，
 *       即"当前用户"的注册表根键）而不是 HKLM（本地机器），因为语言
 *       这类个人偏好本来就是每个用户各存一份的。这一步的潜台词是：
 *       用户的 Windows 是什么语言，我们就默认他/她更习惯什么语言。</li>
 *   <li>the JVM default locale (system locale).
 *       如果注册表也查不到（比如程序跑在非 Windows 系统上，或者注册表
 *       查询意外失败），那就退到最后一步：看 JVM 的默认 Locale，也就是
 *       Java 虚拟机从操作系统那里拿到的系统语言环境。这是整条链上
 *       最后一根救命稻草，再不行就用下面的 DEFAULT_LANGUAGE 兜底。</li>
 * </ol>
 *
 * <p>The help text is compiled into the distribution from the
 * {@code OUTPUT-en_US.txt} / {@code OUTPUT-zh_CN.txt} resources, and the
 * runtime messages come from the {@code messages_*.properties} bundles.
 * 另外还要交代清楚一件事：这个类要用到两类资源文件。
 * 一类是"帮助文本"——打包发布的时候，构建工具会把
 * {@code OUTPUT-en_US.txt}（英文帮助）和 {@code OUTPUT-zh_CN.txt}
 * （中文帮助）这两个文件原封不动地编译进发布包；
 * 另一类是"运行时消息"——来自 {@code messages_*.properties} 这样的
 * 键值对资源文件，每种语言一个文件（比如 messages_zh_CN.properties）。
 * 帮助文本是整段整段的说明文字，适合一次性展示；运行时消息则是
 * 一条一条的短句子，还支持 {0}、{1} 这种占位符，方便把文件名、
 * 数量之类的动态内容嵌进去。
 */
public final class I18n {

    // 先看类声明：public（公开，别的包也能用）+ final（不可被继承）。
    // 为什么禁止继承？因为这个类所有的实例都必须经过 load() /
    // switchLanguage() 这两个工厂方法严格初始化，如果允许别人随便
    // 子类化再 new 一个"野路子"实例，那些校验和资源加载就全被绕过了。
    // 同时构造函数是 private（私有）的，外面根本 new 不了，只能走
    // 这两个静态方法——这种"不让你直接 new、只给你工厂方法"的套路，
    // 在 Java 里叫"工厂方法模式"，专门用来保证对象一定是"完整体"。

    // 默认语言：美式英语。注意写法是 en_US，中间是下划线而不是横杠，
    // 这是 Java 的 Locale 惯例：小写语言代码_大写国家/地区代码。
    // 之所以要精确到"国家/地区"，是因为光说"英文"其实不够精确——
    // 英式英语和美式英语在一些单词拼写上就不一样；中文更是有简体
    // （zh_CN）和繁体（zh_TW）之分。精确到国家/地区，将来才能加载
    // 到最贴切的翻译资源。
    public static final String DEFAULT_LANGUAGE = "en_US";

    // 程序支持的所有语言代码列表。用 List.of(...) 创建出来的列表是
    // 不可变的（immutable）：创建之后就再也加不进、删不掉任何元素了。
    // 这样做是故意的——防止其他代码无意中把"支持的语言"这个全局事实
    // 改得面目全非。目前只支持两种语言：en_US（美式英语）和
    // zh_CN（简体中文）。
    public static final List<String> AVAILABLE_LANGUAGES = List.of("en_US", "zh_CN");

    // 这是一个 Java 16 引入的 record（记录类），一行就定义了一个
    // 只装数据的"小盒子"：code 字段存语言代码（比如 "en_US"），
    // displayName 字段存这门语言"用母语写出来的显示名"
    // （比如简体中文用中文写就是"简体中文"，英文就写"English"）。
    // record 会自动生成 equals、hashCode、toString 这些样板方法，
    // 写起来非常省事。注意 displayName 特意用"语言的母语名"而不是
    // "翻译成当前界面语言的名字"，这是国际化界的一条约定俗成：
    // 语言选择列表里，每种语言都得用自己的母语名字展示，这样用户
    // 不管界面当前是什么语言，都能一眼认出自己的母语选项。
    private record LangMeta(String code, String displayName) {
    }

    // 所有支持语言的元信息清单。以后要新增语言（比如日语 ja_JP），
    // 只需要在这里加一行 LangMeta，再准备对应的资源文件即可，
    // 其他代码几乎不用动——这就是"数据驱动"的好处。
    private static final List<LangMeta> LANGS = List.of(
            new LangMeta("en_US", "English"),
            new LangMeta("zh_CN", "简体中文"));
    // 上面直接写的是真中文"简体中文"四个字。以前这里用的是 \u7b80\u4f53...
    // 一类的 Unicode 转义写法，虽然能防"编码不一致导致乱码"，但可读性太差、
    // 维护者根本看不懂那一串转义是什么意思。由于本项目编译时已明确指定
    // 源码按 UTF-8 编码（build.gradle 里的 options.encoding = 'UTF-8'），
    // 所以直接写汉字是完全安全的，也不会乱码，就这样改成了人话。

    // 配置文件所在的目录名。注意这里只存"目录名"，不存完整路径，
    // 完整路径由 configDir() 方法负责拼装。
    private static final String CONFIG_DIR_NAME = "SystemWin";

    // 配置文件的文件名。整个程序目前只需要在配置文件里保存一个
    // 设置项：用户选择的语言。
    private static final String CONFIG_FILE_NAME = "config.properties";

    // 配置文件里保存"语言设置"时使用的键名（key）。
    // 键名起得越直白越好，因为配置文件是明文文本，用户可能
    // 会直接打开看甚至手改，键名清晰对大家都友好。
    private static final String KEY_LANGUAGE = "language";

    // ---------- 实例字段 ----------
    // 下面三个字段是"每个 I18n 实例各自独有"的状态：
    //   language —— 当前实例的语言代码，比如 "zh_CN"；
    //   messages —— 从 messages_语言.properties 加载出来的消息表
    //               （一个 Properties 键值对集合），查运行时消息全靠它；
    //   helpText —— 当前语言对应的整段帮助文本。
    // 为什么不把它们做成全局静态变量？因为程序里完全可能同时存在
    // 两个不同语言的 I18n 实例（比如先加载了英文的，接着又切到中文），
    // 各实例各管各的状态，互不干扰——这就是"实例字段"存在的意义，
    // 也是面向对象里"封装"思想的朴素体现。
    private final String language;
    private final Properties messages;
    private final String helpText;

    // 构造函数是 private 的，外部代码无法直接 new。
    // 它做的三件事，一句话概括就是"按语言把该准备的东西都备齐"：
    // 1) 记下语言代码；
    // 2) 加载消息表——优先加载"当前语言"的消息文件，万一这个文件
    //    不存在（资源没打包进去、语言代码写错等），就自动回退到
    //    英文的 messages_en_US.properties，保证程序永远有消息可用；
    // 3) 加载帮助文本——同样的套路，英文帮助兜底。
    // 这种"主资源优先、英文兜底"的设计贯穿整个类，是国际化代码的
    // 标准姿势：宁可显示英文，也绝不显示 null 或者让程序崩掉。
    private I18n(String language) {
        this.language = language;
        this.messages = loadProperties(
                "/messages_" + language + ".properties", "/messages_en_US.properties");
        this.helpText = loadText("/OUTPUT-" + language + ".txt", "/OUTPUT-en_US.txt");
    }

    /** Loads the internationalized context for the resolved language. */
    // 加载"国际化上下文"。上下文（context）这个词听着唬人，其实
    // 就是"一个已经按照当前环境把语言定好、资源备齐、随时可用的
    // I18n 对象"。这是外部代码最常用的入口：程序启动时调一次
    // I18n.load()，就能拿到一个语言选择完全正确的实例。
    // 至于"当前到底该用哪种语言"这个复杂的决策，被封装在
    // resolveLanguage() 方法里悄悄完成了，调用方完全不用关心
    // 决策细节——把复杂留给自己，把简单留给别人。
    public static I18n load() {
        return new I18n(resolveLanguage());
    }

    /**
     * Persists the given language and returns a context for it.
     * 把用户指定的语言保存（持久化）下来，并返回一个使用该语言的
     * 新实例。"持久化"就是把选择写入磁盘上的配置文件，这样下次
     * 程序启动时还能记得用户上次的偏好，不用每次都重新猜测。
     *
     * @throws CliException if the language is unknown or cannot be persisted
     *                      如果用户给的语言不在支持列表里，或者写入
     *                      配置文件失败（磁盘只读、权限不够等），就抛出
     *                      CliException（命令行异常），由上层命令处理
     *                      代码捕获后转成友好的错误提示展示给用户。
     */
    public static I18n switchLanguage(String requested) throws CliException {
        // 先把用户给的任意格式输入（"zh-CN"、"zh"、"en-US"、"english"、
        // 甚至带空格的大小写混合写法）归一化成标准的 en_US / zh_CN。
        String lang = normalizeLanguage(requested);
        // 归一化结果是 null，说明用户输入的根本不是我们能认出来的
        // 语言。这时候绝不能假装成功，必须明确报错，让用户知道
        // 自己说错了什么、以及到底有哪些语言可选。
        if (lang == null) {
            // 连"报错信息"本身也要国际化：先加载当前的默认语言环境，
            // 再用它翻译 "lang.unknown" 这条消息，把用户输入的原话
            // 和支持的语言列表一并塞进消息里，错误提示既准确又友好。
            I18n current = load();
            throw new CliException(current.msg("lang.unknown",
                    requested, String.join(", ", AVAILABLE_LANGUAGES)));
        }
        // 语言合法：先持久化（写配置文件），再基于这个语言创建并
        // 返回新实例。注意这两步的顺序是有讲究的——先保存再返回，
        // 万一保存失败抛了异常，调用方就不会拿到一个"半吊子"实例。
        persistLanguage(lang);
        return new I18n(lang);
    }

    // ------------------------------------------------------------------
    // resolution
    // ------------------------------------------------------------------
    // 以下是"语言解析（resolution）"专区，收的都是私有方法。
    // "解析"在这里的意思是：根据各种线索（用户设置、系统设置、
    // JVM 设置）最终拍板——"这次到底用哪种语言？"。这是全类最核心
    // 也最容易绕晕的逻辑，所以单独圈成一个区块，方便阅读和定位。

    // 语言解析的总入口。决策顺序（优先级从高到低）：
    //   1. 配置文件里用户显式保存过的语言 —— 用户说了算；
    //   2. Windows 注册表里的系统 UI 语言 —— 跟着系统走；
    //   3. JVM 默认 Locale —— 用 Java 运行时看到的系统语言；
    //   4. 全都不可用 —— 用 DEFAULT_LANGUAGE（en_US）硬兜底。
    // 这个"逐级降级"的思路在整个软件开发里都极其常见，核心思想是：
    // 永远给用户一个合理的结果，绝不让程序因为"查不到语言"这种
    // 小事崩溃，或者输出一堆乱码。
    private static String resolveLanguage() {
        String persisted = readPersistedLanguage();
        if (persisted != null) {
            // 配置文件里存了语言，而且 readPersistedLanguage 内部已经
            // 校验过它确实在支持列表里，直接采用，后面的步骤全部跳过。
            return persisted;
        }
        String ui = windowsUiLanguage();
        // 注册表里读出来的语言字符串通常长得比较随意（比如
        // "zh-Hans-CN"、"en-US"），必须先用 normalizeLanguage 归一化，
        // 归一化成功（非 null）才说明它确实是受支持的语言，才能采用。
        if (ui != null && normalizeLanguage(ui) != null) {
            return normalizeLanguage(ui);
        }
        String jvm = Locale.getDefault().getLanguage();
        // 最后一道关卡：看 JVM 默认语言是不是中文。getLanguage()
        // 返回的是两位小写的语言代码，比如中文是 "zh"、英文是 "en"。
        // 这里只判断"是不是以 zh 开头"——也就是说不管简体（zh_CN）
        // 还是繁体（zh_TW、zh_HK），只要系统语言是中文，我们就统一
        // 给简体中文。这是一个产品层面的取舍：目前翻译资源只有简体，
        // 繁体用户暂时也只能看到简体资源，等将来资源丰富了可以再细分。
        if (jvm != null && jvm.toLowerCase(Locale.ROOT).startsWith("zh")) {
            return "zh_CN";
        }
        // 前面所有线索都断了，认命，用默认语言（美式英语）兜底。
        return DEFAULT_LANGUAGE;
    }

    /** Maps any input ("zh-CN", "zh", "en-US", "english", ...) to a supported code. */
    // 把任意的用户输入（"zh-CN"、"zh"、"en-US"、"english"、甚至
    // "  EN-us  " 这种带空格又大小写混乱的）统一映射成受支持的标准
    // 语言代码。可以把这个方法想象成一个翻译官：用户说啥都行，
    // 翻译官只认两种标准答案（en_US / zh_CN），翻译不出来就返回
    // null，让调用方自己去处理"不认识的输入"。
    public static String normalizeLanguage(String raw) {
        if (raw == null) {
            // null 直接返回 null：这一步必不可少，否则下面调 raw.trim()
            // 时会抛出著名的 NullPointerException（空指针异常）。
            return null;
        }
        // 输入预处理三步曲：
        // 1. trim()：去掉首尾空白，用户可能手滑多敲了空格；
        // 2. toLowerCase(Locale.ROOT)：统一转小写，让 "EN" 和 "en"
        //    变成一回事。特意指定 Locale.ROOT（"根"语言环境）而不是
        //    默认环境，是为了避开某些地区语言的特殊大小写规则——
        //    比如土耳其语里大写 I 转小写会变成不带点的 ı，如果不指定
        //    ROOT，同样的代码在不同地区的系统上结果可能不一样；
        // 3. replace('-', '_')：把用户习惯写的横杠 - 换成 Java 习惯的
        //    下划线 _，这样 "zh-CN" 就能和标准的 "zh_CN" 对上了。
        String s = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        // 下面用"前缀匹配"：只看开头两个字母。以 zh 开头就当简体中文，
        // 以 en 开头就当美式英语。这种宽松匹配的好处是包容——用户写
        // "zh-Hans"、"zh_CN"、"zhongwen"（中文的拼音！）都能命中 zh。
        if (s.startsWith("zh")) {
            return "zh_CN";
        }
        if (s.startsWith("en")) {
            return "en_US";
        }
        // 两个前缀都不匹配，那确实不认识了，返回 null 表示"无法识别"。
        return null;
    }

    /** Reads the Windows UI language from the registry, or null. */
    // 从 Windows 注册表读取当前用户的界面语言，读不到就返回 null。
    // 这里借助了 ProcessRunner 工具类去执行外部命令 reg query——
    // 也就是调用 Windows 自带的注册表查询命令行工具。
    // 为什么不直接用 Java 的 API 读注册表？因为 JDK 标准库里压根
    // 没有跨平台的注册表访问 API，而 reg.exe 是 Windows 系统自带的、
    // 最可靠的工具，用 ProcessRunner 包一层就能在 Java 里轻松调用。
    // 另外注意本方法采用了"双保险"策略：先查一个键，没查到再查
    // 另一个键，尽可能提高读取成功的概率。
    private static String windowsUiLanguage() {
        // 第一次尝试：查询 "User Profile" 键下的 Languages 值。
        // 这个值是 REG_MULTI_SZ 类型（多字符串），列出了用户偏好的
        // 语言列表，通常第一项就是最主要的那门语言。命令行的
        // "/v Languages" 表示按值名查询，"Languages" 就是要查的值名。
        ProcessRunner.Result r = ProcessRunner.run("reg", "query",
                "HKCU\\Control Panel\\International\\User Profile", "/v", "Languages");
        if (r.ok()) {
            String lang = parseRegValue(r.output());
            if (lang != null && !lang.isEmpty()) {
                return lang;
            }
        }
        // 第二次尝试：如果上面没成功（比如某些精简版 Windows 没有
        // User Profile 这个注册表键），就退而求其次，查 International
        // 键下的 LocaleName 值，它直接给出了当前用户界面的区域名称，
        // 比如 "zh-CN"。
        ProcessRunner.Result r2 = ProcessRunner.run("reg", "query",
                "HKCU\\Control Panel\\International", "/v", "LocaleName");
        if (r2.ok()) {
            String lang = parseRegValue(r2.output());
            if (lang != null && !lang.isEmpty()) {
                return lang;
            }
        }
        // 两条路都走不通（比如在非 Windows 平台上根本没有 reg 命令，
        // 执行会失败，r.ok() 为 false），返回 null，让上层继续走
        // "JVM 默认语言"这条退路。
        return null;
    }

    /**
     * Extracts the first value from a reg query line such as
     * {@code "    Languages    REG_MULTI_SZ    zh-Hans-CN"}.
     * 从 reg query 命令的输出文本里，提取出第一个可用的值。
     * 命令输出的一行典型长这样：
     *     Languages    REG_MULTI_SZ    zh-Hans-CN
     * 也就是说每行大致是"名字 + 空白 + 类型(REG_xxx) + 空白 + 值"。
     * 这个方法只关心"类型之后的值"部分，其余一概不管。
     */
    private static String parseRegValue(String output) {
        // reg 命令的输出是多行的，我们逐行扫描。
        for (String line : output.split("\\r?\\n")) {
            // 先找 "REG_" 这个特征串。为什么拿它当定位锚点？因为无论
            // 输出长什么样，值类型那一段一定是 REG_ 开头的（REG_SZ、
            // REG_MULTI_SZ、REG_EXPAND_SZ 等），用它来定位最稳当。
            int idx = line.indexOf("REG_");
            if (idx < 0) {
                // 这行里没有 REG_，说明它不是"键值行"（可能是空行或
                // 命令自身的其他输出），跳过，继续看下一行。
                continue;
            }
            String rest = line.substring(idx).trim();
            // rest 现在形如 "REG_MULTI_SZ    zh-Hans-CN"，接下来要把
            // "类型"和"真正的值"用空格切开，空格后面的才是我们要的。
            int space = rest.indexOf(' ');
            // 找不到空格？说明类型后面直接就是值，整段 rest 就是值；
            // 找得到就取第一个空格之后的子串。
            String value = (space < 0) ? rest : rest.substring(space + 1);
            value = value.trim();
            if (value.isEmpty()) {
                // 抠出来的值是空的，说明这行没有有效数据，跳过。
                continue;
            }
            // REG_MULTI_SZ values are separated by \0; take the first one.
            // 注意：REG_MULTI_SZ 类型的值可以同时存放多个字符串，
            // 多个字符串之间用 \0（空字符）分隔。我们只需要第一个，
            // 所以一遇到 \0 就截断。这里检查的是已经抠出来的 value，
            // 而不是整行，因为值已经被单独提取出来了。
            int nul = value.indexOf('\0');
            if (nul >= 0) {
                value = value.substring(0, nul);
            }
            // 语言列表里还可能用逗号分隔多个语言（比如
            // "zh-Hans-CN, zh-Hant-TW"），同样只取第一个逗号之前的部分。
            int comma = value.indexOf(',');
            if (comma >= 0) {
                value = value.substring(0, comma);
            }
            if (!value.isEmpty()) {
                // 千辛万苦终于拿到一个非空的值，立刻返回！
                return value;
            }
        }
        // 整段输出都翻遍了也没找到可用值，返回 null，交给上层兜底。
        return null;
    }

    // ------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------
    // 以下是"持久化（persistence）"专区：把用户的语言选择写入磁盘，
    // 以及从磁盘读回来。这一节全是和文件系统打交道的基础设施代码。
    // 配置文件的位置固定在：
    //   %USERPROFILE%\AppData\Roaming\SystemWin\config.properties
    // 也就是 Windows 标准的"漫游配置文件"目录。放在 AppData\Roaming
    // 有个好处：在公司域环境里，这个目录的内容会跟随用户账号漫游到
    // 其他电脑，用户的设置就"随身携带"了。

    // 返回配置目录的 Path 对象。Path 是 Java NIO（New I/O）引入的
    // 路径抽象，比老式的 java.io.File 更现代、更安全、也更顺手。
    private static Path configDir() {
        // user.home 是 JVM 的系统属性，代表当前用户的主目录，
        // 在 Windows 上通常是 C:\Users\你的用户名。
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) {
            // 理论上几乎不会发生，但万一某些极简运行环境拿不到
            // user.home，就用 "."（当前目录）兜底，避免下面
            // Paths.get 因为空值而抛异常。
            home = ".";
        }
        // 把主目录和 AppData\Roaming\SystemWin 逐段拼接成完整路径。
        return Paths.get(home, "AppData", "Roaming", CONFIG_DIR_NAME);
    }

    // 配置文件的完整路径 = 配置目录 + 文件名。这个逻辑只有一行，
    // 单独抽成一个方法，是为了让调用处的语义更清晰、更可读。
    private static Path configPath() {
        return configDir().resolve(CONFIG_FILE_NAME);
    }

    // 从配置文件里读取用户之前保存的语言代码。
    // 返回值可能是 null，调用方（resolveLanguage）必须处理这种情况。
    private static String readPersistedLanguage() {
        Path cfg = configPath();
        // 先确认文件真实存在。用户要是从来没执行过 language 命令，
        // 配置文件就不存在，这时直接返回 null，绝不去读一个
        // 不存在的文件。
        if (!Files.isRegularFile(cfg)) {
            return null;
        }
        // Properties 是 Java 自带的"键值对"配置类，专门用来读写
        // .properties 这种格式的文件（每行一条"键=值"或"键: 值"）。
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(cfg)) {
            // 注意 try-with-resources 语法：流用完会自动关闭，
            // 不需要我们手动写 close()，既省心又不会漏关。
            p.load(in);
        } catch (IOException e) {
            // 读文件出错（文件被占用、权限不够、磁盘故障……）时，
            // 我们选择"静默失败"：返回 null，让上层按"没有配置"
            // 来处理。为什么不把异常往上抛？因为语言设置只是个
            // 锦上添花的功能，读不出来顶多就是用默认语言运行，
            // 完全不值得为这点小事让整个程序崩掉。
            return null;
        }
        // 取出 language 这个键的值。
        String v = p.getProperty(KEY_LANGUAGE);
        // 双重保险：不仅要求有值，还要求这个值真的在我们支持的
        // 语言列表里。万一配置文件被人手工改成了 "fr_FR"（法语），
        // 我们可不能傻乎乎地按法语去加载——根本没有法语资源！
        // 所以这里必须校验，不合法就当"没有配置"处理。
        return (v != null && AVAILABLE_LANGUAGES.contains(v)) ? v : null;
    }

    // 把用户选择的语言写入配置文件。方法声明了 throws CliException，
    // 因为写文件这件事（创建目录、打开文件、写入）每一步都可能失败。
    private static void persistLanguage(String lang) throws CliException {
        try {
            // 先确保目录存在。createDirectories 这个名字很形象：
            // 它会一次性把路径上缺失的所有目录都建出来——比如
            // AppData 存在但 Roaming\SystemWin 不存在时，它会连
            // Roaming 一起建好。而且目录已经存在时它也不会报错，
            // 所以可以放心地每次调用都执行一遍。
            Files.createDirectories(configDir());
            Properties p = new Properties();
            p.setProperty(KEY_LANGUAGE, lang);
            // 把键值对写入文件。store 的第二个参数是文件头注释，
            // 会以 "# SystemWin configuration" 的形式写在文件最顶部，
            // 方便人类直接打开配置文件就能看懂这是谁的配置文件。
            try (OutputStream out = Files.newOutputStream(configPath())) {
                p.store(out, "SystemWin configuration");
            }
        } catch (IOException e) {
            // 任何一步 IO 失败都汇总到这里，包装成带说明文字的
            // CliException 抛出去。包装异常的好处是：上层的命令处理
            // 代码只需要捕获 CliException 这一种异常类型就够了，
            // 完全不用关心底层到底是"目录创建失败"还是"文件写入失败"。
            throw new CliException("Failed to save the language setting: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // resource loading
    // ------------------------------------------------------------------
    // 以下是"资源加载（resource loading）"专区：从 classpath（类路径）
    // 里读取两类资源——消息表（.properties 文件）和帮助文本（.txt 文件）。
    // 注意这些资源是"编译进 jar 包"的，不在普通文件系统上，所以必须
    // 用 getResourceAsStream 这种"按类路径找资源"的方式读取，
    // 而不能用普通文件 API，否则打包后就找不到了。

    // 加载消息表，采用"英文打底 + 主语言覆盖"的合并策略。
    // 具体步骤是：先把英文表的所有条目装进来当底子，再把主语言表
    // 的条目覆盖上去。这样即使主语言表里漏翻译了某一条消息，
    // 那一条也还能显示英文原文，绝不会变成 null 或乱码。
    private static Properties loadProperties(String primary, String fallback) {
        Properties p = new Properties();
        Properties fb = loadResourceProperties(fallback);
        if (fb != null) {
            // putAll 把英文表的所有键值对拷进 p 作为底子。
            p.putAll(fb);
        }
        Properties pr = loadResourceProperties(primary);
        if (pr != null) {
            // 主语言表后加载，同名的键会把英文覆盖掉——"翻译生效"
            // 正是靠这一步实现的。注意"先兜底、后覆盖"的顺序
            // 至关重要，一旦写反，兜底就完全失效了。
            p.putAll(pr);
        }
        return p;
    }

    // 真正干活的底层读取方法：从类路径读取一个 .properties 文件并
    // 解析成 Properties 对象。资源不存在或读取失败时返回 null，
    // 由调用方（loadProperties）负责兜底。
    private static Properties loadResourceProperties(String path) {
        try (InputStream in = I18n.class.getResourceAsStream(path)) {
            if (in == null) {
                // getResourceAsStream 找不到资源时返回的是 null
                // 而不是抛异常，所以这里要显式判断一下。
                return null;
            }
            Properties p = new Properties();
            // 用 InputStreamReader 包一层，并明确指定 UTF-8 编码来读。
            // 这一点极其重要：properties 文件里可全是中文！如果让
            // Properties 用系统默认编码去读，在中文 Windows 上默认
            // 可能是 GBK，在其他系统上又是别的编码，读出来的中文
            // 全会变成乱码。强制 UTF-8 就能保证任何环境下解析结果
            // 完全一致。
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        } catch (IOException e) {
            // 读取过程中出错，同样静默处理：返回 null，交给上层兜底。
            return null;
        }
    }

    // 加载帮助文本，同样的"主语言优先、英文兜底"思路。
    // 帮助文本是整段整段的文字，不存在"逐条覆盖"的问题，所以
    // 逻辑更简单：主语言读不到就读英文，连英文都读不到就返回
    // 一句固定的提示语，保证 help 命令永远有东西可打印。
    private static String loadText(String primary, String fallback) {
        String t = readResourceText(primary);
        if (t == null) {
            t = readResourceText(fallback);
        }
        // 最后这个三元表达式是终极兜底：连英文帮助都没有（资源
        // 缺失的极端情况），至少让 help 命令输出一句像样的话，
        // 而不是打印 null 或者抛异常。
        return t == null ? "SystemWin help unavailable." : t;
    }

    // 从类路径读取一段文本（帮助文件）的底层方法，返回整个字符串。
    private static String readResourceText(String path) {
        try (InputStream in = I18n.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            // readAllBytes 一次性把整个文件读成字节数组，再按 UTF-8
            // 解码成字符串。帮助文本里同样可能有中文，所以编码也
            // 必须指定 UTF-8，理由和上面的消息表完全一样。
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // accessors
    // ------------------------------------------------------------------
    // 以下是"访问器（accessor）"专区：对外开放的只读接口，供命令行
    // 处理代码取用各种数据。这一节的方法都很短，属于典型的"一行
    // 方法"（one-liner），但每个都承担着清晰、单一的职责。

    /** Returns a localized message with {0}/{1}/... placeholders replaced. */
    // 返回一条本地化的消息，并把消息模板里的 {0}、{1}、{2}... 占位符
    // 依次替换成调用方传入的参数。
    // 为什么非得搞占位符这一套？因为不同语言的句子语序不一样——
    // 英文说 "File X not found"，中文说"找不到文件 X"，X 的位置
    // 一个在前一个在后。如果直接把整句翻译写死，动态内容（文件名、
    // 数量等）就没法插进句子里了。占位符机制让翻译文件可以自由
    // 安排参数的位置，这才是真正意义上的国际化。
    public String msg(String key, Object... args) {
        // Object... args 是可变参数（varargs）：调用方可以传 0 个、
        // 1 个甚至多个参数，用起来非常灵活。
        String tpl = messages.getProperty(key);
        if (tpl == null) {
            // 这条消息在语言表里不存在（多半是开发时漏配了），
            // 那就直接把 key 本身当消息返回。虽然不太美观，但至少
            // 用户和开发者都能看到是哪个 key 出了问题，方便排查。
            tpl = key;
        }
        if (args == null || args.length == 0) {
            // 没有参数需要替换，原样返回模板即可。
            return tpl;
        }
        String s = tpl;
        for (int i = 0; i < args.length; i++) {
            // 逐个替换 {0}、{1}、{2}...。注意 String.replace 是
            // "全量替换"：如果模板里 {0} 出现了多次，也会全部被换掉。
            // 另外这里用的是消息文件约定俗成的 {n} 花括号风格，
            // 跟 String.format 的 %s 风格不是一回事，两者别混用。
            s = s.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return s;
    }

    /** The compiled-in help text for the current language. */
    // 返回当前语言对应的帮助文本——就是用户在命令行输入
    // systemwin help 时打印出来的那一大段说明文字。
    public String help() {
        return helpText;
    }

    // 返回当前实例的语言代码，比如 "en_US" 或 "zh_CN"。
    // 程序的其他部分可能会用它来决定"要不要显示中文提示"之类的逻辑。
    public String currentLanguage() {
        return language;
    }

    // 根据语言代码，返回这门语言的"母语显示名"：传入 "zh_CN" 返回
    // "简体中文"，传入 "en_US" 返回 "English"。如果代码不在 LANGS
    // 列表里（理论上不该发生），就原样返回代码本身，宁可丑一点
    // 也不返回 null。
    public String languageDisplayName(String code) {
        for (LangMeta m : LANGS) {
            // 注意 record 的字段是通过 code()、displayName() 这样的
            // 方法访问的，不是 m.code 这种字段访问——这是 record
            // 语法规定，写惯了普通类的同学容易在这里踩坑。
            if (m.code().equals(code)) {
                return m.displayName();
            }
        }
        return code;
    }

    // 返回所有支持的语言代码列表。给"列出可选语言"之类的命令用，
    // 这样以后新增语言时，这个列表会自动带上新语言，不用改两处代码。
    public List<String> availableLanguages() {
        return AVAILABLE_LANGUAGES;
    }
}
