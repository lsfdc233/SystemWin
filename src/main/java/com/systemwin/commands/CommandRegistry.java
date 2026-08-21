package com.systemwin.commands;

import com.systemwin.I18n;
import com.systemwin.cli.CliException;

import java.util.List;

/**
 * Maps a command name to its implementation.
 *
 * 中文翻译：这个类的职责是"把命令的名字（一个字符串）映射（mapping）到对应的命令实现对象（Command）"。
 *
 * 说人话就是：用户在命令行里敲了一个单词，比如 "start"，我们程序拿到这个字符串之后，
 * 总得有人告诉系统"哦，start 这个单词应该对应哪个类来处理吧？"——这个工作就交给 CommandRegistry 来做。
 *
 * 你可以把 CommandRegistry 想象成一个"查表员"或者"翻译官"：
 * 它手里有一张"命令名 -> 命令类"的花名册，谁来找它，它就能告诉你这个命令该由谁来干。
 *
 * 为什么要把这个逻辑单独抽出来做一个类呢？
 * 因为如果不在一个地方统一管理，将来每增加一个新命令，就得在好多地方改来改去，很容易漏改、改错。
 * 现在集中在这里，新增命令时只需要动这一个文件（以及对应的命令类），维护起来非常省心。
 *
 * 另外注意，这个类被声明成了 final，意思是"不允许别人继承我"。
 * 为什么？因为它里面的方法全都是 static 的（静态方法），根本不需要创建对象，
 * 也不存在"子类重写父类行为"的需求，所以干脆用 final 把继承这条路堵死，
 * 防止有人不小心（或者故意）继承它，搞出一些莫名其妙的行为。这是一种常见的防御性编程习惯。
 */
public final class CommandRegistry {

    /**
     * 这个列表就是上面说的"花名册"：把系统支持的所有命令名字都列在这里。
     * 它有两个用处：
     *   1) 作为"这份名单里到底有哪些命令"的权威记录，方便将来统一查看；
     *   2) 更重要的是，它被下面的 suggest() 方法用来做"拼写纠错"——
     *      当用户敲错了命令名时，我们就在这个列表里找一个和用户输入最接近的单词，猜他到底想敲什么。
     *
     * 注意 List.of(...) 这个方法：它创建出来的列表是"不可变列表"（immutable），
     * 也就是说一旦创建，就不能再往里面加元素、删元素或者改元素了。
     * 为什么要用不可变的呢？因为这份名单在程序运行期间是固定的（命令集合不会动态变化），
     * 用不可变列表可以保证它永远不会被别处的代码意外修改，安全又省心。
     *
     * 顺带一提：这些命令名看起来是不是很眼熟？没错，它们模仿的是 Linux 上 systemctl 的风格，
     * 比如 is-active、is-enabled、daemon-reload、list-unit-files 这些名字，
     * 用过 systemctl 的用户看到这些命令会觉得非常亲切，上手几乎没有学习成本。
     */
    private static final List<String> KNOWN_COMMANDS = List.of(
            "help", "version", "language", "install", "uninstall", "start", "stop",
            "restart", "status", "enable", "disable", "is-active", "is-enabled",
            "list-units", "list-unit-files", "daemon-reload");

    /**
     * 这是一个"私有构造方法"（private constructor）。
     *
     * 新手可能会疑惑：构造方法不是用来创建对象的吗？怎么还藏起来不让人调用？
     * 这是因为 CommandRegistry 这个类里的方法全部是静态方法，根本不需要"实例"（对象）就能使用，
     * 也就是说我们永远不需要 new 一个 CommandRegistry 出来。
     * 既然如此，干脆把构造方法设为 private，谁也别想 new 它——
     * 这样既能避免有人白白 new 出没用的对象浪费内存，也表达了一个设计意图：
     * "这个类只是一个工具类（utility class），请不要实例化我，直接用我的静态方法就好了。"
     * 这是 Java 里非常经典的一种写法：看到 private 构造方法 + 全是静态方法，基本就可以断定这是工具类。
     * 像 java.util.Collections、java.util.Arrays 这些 JDK 自带的工具类也都是这么干的。
     */
    private CommandRegistry() {
    }

    /**
     * 工厂方法（Factory Method）：根据传入的命令名字，创建并返回对应的命令对象。
     *
     * 为什么叫"工厂"呢？因为工厂的作用就是"批量生产东西"——
     * 这里我们根据不同的名字"生产"出不同类型的 Command 对象，
     * 调用者（比如命令解析器）完全不用关心到底 new 的是哪个具体类，
     * 只要把名字丢进来，就能拿到一个能干活儿的 Command。这就是"工厂模式"的核心思想：
     * 把"创建对象"这件事集中到一处，调用方和具体实现类解耦，将来想换实现只改这一个地方。
     *
     * 参数说明：
     *   - name：用户输入的命令名，例如 "install"、"status"、"daemon-reload" 等等。
     *   - ctx ：CommandContext，可以理解成一个"上下文袋子"，里面装着各种命令干活时需要的信息，
     *           比如国际化文案对象 i18n（负责翻译提示信息）、一些运行环境信息等。
     *           每个命令创建出来的时候都会拿到同一个 ctx，这样命令之间共享的信息就有地方放了，
     *           不用每个命令都自己去到处找这些依赖。
     *
     * 返回值：Command 接口的实现对象。Command 是"命令"的统一接口（抽象），
     * 具体的每一个命令（比如 StartCommand、StopCommand）都是它的实现类，
     * 所以这里返回类型写成 Command，调用者拿到的就是一个"命令"，至于具体是谁，不用关心。
     *
     * 关于 switch 表达式：注意这里用的是 Java 14+ 的"switch 表达式"（带箭头 -> 的那种），
     * 它和传统的 switch 语句不一样：传统 switch 是"执行完一个分支继续往下掉"（fall-through），
     * 很容易忘了写 break 而出 bug；而带箭头的 switch 表达式每个分支独立，写完即返回，干净利落。
     * 而且它本身是"表达式"，可以直接作为方法的返回值，所以 create 方法直接 return switch (...) 就可以了，
     * 连临时变量都不用声明，代码读起来一目了然。
     *
     * 最后那个 default 分支是"兜底"逻辑：如果用户输入的名字上面所有 case 都不匹配，
     * 就说明这个命令根本不存在（或者用户打错了），此时我们不能默默返回 null，
     * 而是要走"纠错 + 报错"的流程（具体见下面的说明），给用户一个友好的提示。
     */
    public static Command create(String name, CommandContext ctx) throws CliException {
        return switch (name) {
            case "help" -> new HelpCommand(ctx);
            case "version" -> new VersionCommand(ctx);
            case "language" -> new LanguageCommand(ctx);
            case "install" -> new InstallCommand(ctx);
            // 注意这里：uninstall 和 remove 是同一个命令的两个别名（alias），
            // 也就是说用户无论敲 "uninstall" 还是 "remove"，最终都会创建 UninstallCommand。
            // 为什么要留两个名字？因为不同用户习惯不同，有人习惯 systemctl 风格的 uninstall，
            // 有人习惯单词 remove，两个都认，用户体验更好。反正底层干活的是同一个类，
            // 多一个别名只是多一行 case 的事，成本极低，收益却很实在。
            case "uninstall", "remove" -> new UninstallCommand(ctx);
            case "start" -> new StartCommand(ctx);
            case "stop" -> new StopCommand(ctx);
            case "restart" -> new RestartCommand(ctx);
            case "status" -> new StatusCommand(ctx);
            case "enable" -> new EnableCommand(ctx);
            case "disable" -> new DisableCommand(ctx);
            case "is-active" -> new IsActiveCommand(ctx);
            case "is-enabled" -> new IsEnabledCommand(ctx);
            case "list-units" -> new ListUnitsCommand(ctx);
            case "list-unit-files" -> new ListUnitFilesCommand(ctx);
            case "daemon-reload" -> new DaemonReloadCommand(ctx);
            default -> {
                // 走到这里，说明用户输入的命令名在 KNOWN_COMMANDS 名单里找不到，
                // 大概率是打错字了。所以我们先调用 suggest(name) 试着猜一个最接近的已知命令。
                // 注意：suggest 是我们自己写的静态方法，它内部会遍历整个名单做编辑距离比较。
                String suggestion = suggest(name);

                // 拼写纠错的结果有两种情况：
                // 1) suggestion 为 null：说明用户输入的字和所有已知命令都差得太远（编辑距离超过 2），
                //    完全猜不出来他想干嘛，那就只报一个"未知命令"的错误，不带任何建议。
                // 2) suggestion 不为 null：说明猜到了一个足够接近的命令，
                //    这时候错误信息里就要附带"你是不是想敲 xxx？"这样的提示，用户体验会好很多。
                //
                // 这里用到了 i18n（国际化）机制：提示信息不是写死的字符串，
                // 而是通过 ctx.i18n.msg(...) 去查对应的翻译文案，key 是 "err.unknown.command" 之类。
                // 这样同一个程序，界面语言是中文就显示中文提示，是英文就显示英文提示，非常灵活，
                // 而且将来想加新语言的提示，只需要在资源文件里加翻译，完全不用动这里的代码。
                String msg = suggestion == null
                        ? ctx.i18n.msg("err.unknown.command", name)
                        : ctx.i18n.msg("err.unknown.command.suggest", name, suggestion);

                // 找到问题了就要抛异常！CliException 是我们自己的"命令行异常"类型，
                // 抛出后会被上层（比如主程序）捕获，然后打印成一条友好的错误信息给用户看，
                // 而不是让程序直接崩溃退出。第二个参数是"用法提示"（usage.hint），
                // 会一起显示，告诉用户"如果你不知道怎么用，可以敲 help 看看帮助"。
                // 这种"带提示信息的异常"设计，比返回 null 或者返回错误码要贴心得多，
                // 因为调用方拿到异常时连"该怎么教用户"的文案都一并拿到了。
                throw new CliException(msg, ctx.i18n.msg("usage.hint"));
            }
        };
    }

    /**
     * Suggests the closest known command for a likely typo (edit distance <= 2).
     *
     * 中文翻译：给疑似打错的命令名，从已知命令里找出最接近的那个（编辑距离不超过 2 才算数）。
     *
     * 这个方法的思路非常朴素，就是一个"暴力对比"（brute force）：
     * 1. 先把"当前最好的候选"（best）设成 null，把"目前最小的距离"设成无穷大（Integer.MAX_VALUE），
     *    表示"我还没找到任何候选"，这样第一个比较对象一定会胜出，逻辑上不会漏。
     * 2. 然后把用户输入的名字和 KNOWN_COMMANDS 里的每一个已知命令都算一遍编辑距离（levenshtein），
     *    谁的距离更小，谁就更可能是用户真正想敲的命令，就更新 best 和 bestDist。
     * 3. 全部比完之后，如果最小距离不超过 2，就说明"足够接近，值得推荐"，返回这个候选；
     *    否则返回 null，表示"差太远了，没法猜"。
     *
     * 为什么阈值选 2 而不是 1 或者 3？
     * 阈值太小（比如 1）容易漏掉真正的拼写错误——用户可能一口气敲错两个字母；
     * 阈值太大（比如 3、4）又容易瞎推荐——毕竟命令之间本来就长得像，
     * 距离一大，推荐的候选可能跟用户想敲的完全不是一回事，反而误导人。
     * 距离不超过 2 是一个实践经验值：既能覆盖常见的"手滑敲错一两个字母"的场景，又不会过度猜测。
     *
     * 复杂度方面：这个方法要遍历整个名单，对每个名字都算一次编辑距离，
     * 而名单长度是固定的（十几个命令），所以总耗时非常有限，完全不用担心性能问题。
     */
    private static String suggest(String name) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String known : KNOWN_COMMANDS) {
            int d = levenshtein(name, known);
            if (d < bestDist) {
                bestDist = d;
                best = known;
            }
        }
        return bestDist <= 2 ? best : null;
    }

    /**
     * 计算两个字符串之间的"编辑距离"（Levenshtein distance，也叫莱文斯坦距离）。
     *
     * 什么是编辑距离？简单说就是：把字符串 a 变成字符串 b，最少需要做多少次"单字符操作"，
     * 其中允许的操作只有三种：插入一个字符、删除一个字符、替换一个字符。
     * 比如 "cat" 变成 "cut" 只需要把中间的 'a' 替换成 'u'，一次替换，距离就是 1。
     * 距离越小，说明两个字符串越像——这正是我们判断"用户是不是打错字"的数学依据。
     * 这个算法是拼写纠错领域的经典算法，在很多工具（比如 vim 的拼写检查、搜索引擎的"你是不是想搜"）里都在用。
     *
     * 算法原理（动态规划，Dynamic Programming）：
     * 我们维护两个数组 prev 和 cur，它们都表示"一行"的距离数据：
     *   - prev[j] 表示"a 的前 i-1 个字符"和"b 的前 j 个字符"的编辑距离（上一行的结果）；
     *   - cur[j]  表示"a 的前 i 个字符"和"b 的前 j 个字符"的编辑距离（当前正在算的这一行）。
     * 为什么要搞两行而不是一个完整的二维表格？因为递推公式里，
     * 当前位置 (i, j) 只依赖三个邻居：左边 cur[j-1]、上边 prev[j]、左上角 prev[j-1]，
     * 也就是只需要"上一行"和"当前行"这两行数据就够了，前面的行用完就可以丢掉。
     * 这样空间复杂度从 O(m*n) 降到了 O(n)，只用了两行数组，省内存又清爽，
     * 这正是动态规划里常见的"滚动数组"（rolling array）优化技巧。
     *
     * 具体递推公式（这是整个算法最核心的一句，务必多看几遍）：
     *   cur[j] = min( 删除：cur[j-1] + 1,     // 在 a 里删掉一个字符，代价加 1
     *                  插入：prev[j] + 1,      // 在 a 里插入一个字符，代价加 1
     *                  替换：prev[j-1] + cost ) // 替换；如果两个字符相同 cost=0（根本不用动），不同 cost=1
     * 一句话概括：到达 (i,j) 的最省力路线，必然是从左边、上边、左上角三个方向之一走过来的，
     * 我们取三条路线里代价最小的那个，这就是"最优子结构"思想的体现——
     * 大问题的最优解，可以由子问题的最优解拼出来，所以可以一层一层往上推。
     *
     * 初始化：prev 这一行先填上 0, 1, 2, ..., b.length()，
     * 意思很直白："a 还是空字符串的时候，要变成 b 的前 j 个字符，只能靠逐个插入，插 j 次"。
     * 同理循环里 cur[0] = i 表示："b 是空字符串时，要把 a 的前 i 个字符删光，得删 i 次"。
     * 边界情况都想清楚之后，剩下的就是照公式一行一行往下推，写完基本不会出 bug。
     *
     * 最后结果存在 prev[b.length()] 里——注意循环结束时 prev 和 cur 已经交换过位置
     * （看循环末尾的 tmp 交换），所以最后一行数据其实在 prev 里，千万别取错数组了！
     */
    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            // 这一轮 cur 已经算完了，下一轮循环它要扮演"上一行"的角色，
            // 所以把 prev 和 cur 的引用交换一下（只是换指针，不是复制整行数据，开销很小），
            // 然后下一轮继续在"新的 cur"里填数据。这个交换是滚动数组优化的关键一步，
            // 少了它，下一轮就会把上一轮的数据覆盖掉，结果就全错了。
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    /**
     * Commands that modify the Service Control Manager and need Administrator rights.
     *
     * 中文翻译：会修改 Windows 服务控制管理器（Service Control Manager，SCM）的命令需要管理员权限。
     *
     * 背景知识（对新手非常有用）：在 Windows 上，安装服务、卸载服务、启停服务这些操作，
     * 本质上是去和操作系统的"服务控制管理器"打交道，而 SCM 属于系统级资源，
     * 普通权限的进程是没有资格动它的，必须让进程以"管理员权限"运行才行。
     * 所以程序在真正执行这些命令之前，得先判断一下"我这个命令要不要管理员权限"，
     * 如果要，就先触发 UAC 提权（弹出"是否允许此应用对你的设备进行更改"的确认框），
     * 等拿到管理员权限再干活，否则命令会直接失败，用户还会一脸懵。
     *
     * 这个方法就是干这个判断的：用一个 switch 表达式把"需要提权"的命令名列出来，
     * 命中就返回 true（需要管理员权限），没命中就返回 false（普通权限就够了）。
     *
     * 注意观察哪些命令需要提权：install / uninstall / start / stop / restart / enable / disable
     * 都是"会改变系统状态"的命令；而 status、is-active、list-units 这类只读查询命令就不需要，
     * 因为"看一眼"不影响系统，权限要求自然低。设计上"读操作宽松、写操作严格"，
     * 这是很合理的权限划分思路：能少打扰用户就少打扰用户，
     * 毕竟每次弹 UAC 确认框都会打断用户的操作节奏，能不弹就不弹。
     *
     * 另外注意：这个方法没有用到 CommandContext 或任何实例字段，
     * 所以设计成 static 静态方法，任何地方想判断都可以直接 CommandRegistry.needsElevation(...) 调用，非常方便。
     * 它和 create() 一"问"一"造"的分工也很清晰：先问要不要提权，再动手创建命令，
     * 两个职责分开，各自都好测试、好维护。
     */
    public static boolean needsElevation(String command) {
        return switch (command) {
            case "install", "uninstall", "start", "stop", "restart", "enable", "disable" -> true;
            default -> false;
        };
    }
}
