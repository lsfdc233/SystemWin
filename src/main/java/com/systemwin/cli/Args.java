package com.systemwin.cli;

import com.systemwin.I18n;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * ============================================================================
 * 这个类的“户口本”介绍（给完全没看过这段代码的新人看）：
 * ----------------------------------------------------------------------------
 * 我们写的这个程序叫做 SystemWin，它本质上是一个命令行工具（CLI，也就是
 * Command Line Interface 的缩写，翻译过来就是“命令行界面”）。用户在终端
 * （也就是那个黑乎乎的窗口，或者 Windows 的 PowerShell / CMD）里敲下一行
 * 命令，比如 "systemwin run -f --now myapp.jar" 这样的东西，然后回车。
 * 但是计算机本身是不认识这一整行字符串的，它需要先把这一长串文字“拆开”、
 * “读懂”，搞清楚用户到底想干什么，然后再去执行。
 *
 * 而这个拆开和读懂的过程，就叫“解析参数”（parse arguments）。Args 这个类
 * 就是专门负责这件事的“翻译官”。它把我们收到的原始字符串数组 argv 翻译成
 * 一个结构化的、好用的对象，里面清楚地记录了：
 *   1. 用户想执行什么命令（command，比如 run、install、help……）；
 *   2. 用户还带了哪些“开关/选项”（options，比如 --force、--name xxx）；
 *   3. 以及一些不带开关的“裸参数”（positional，比如文件名、路径之类）。
 *
 * 可以这样类比：你去餐厅点菜，跟服务员说“我要一碗牛肉面，加辣，不要香菜”。
 * 服务员（就是 Args 类）会把这句话拆成：主菜=牛肉面（command），加辣=一个
 * 选项（option），不要香菜=另一个选项。这样厨房（后面的业务代码）一看就懂，
 * 不用再费劲去猜你刚才那句话是什么意思了。
 *
 * 之所以要把解析逻辑单独抽成一个类，而不是塞在 main 方法里，是因为“解析
 * 命令行”这件事本身就很复杂、有很多边边角角的情况要处理（比如某个选项后面
 * 必须跟一个值、遇到不认识的开头带减号的参数要报错等等）。单独一个类，代码
 * 好读、好测、好维护，这就是“单一职责原则”（Single Responsibility Principle）
 * 的一种体现——每个类只专心干好一件事。
 * ============================================================================
 */

/** Parsed command line: a command, options, and positional (unit) arguments. */
/*
 * 上面这行英文注释的意思是：
 * “解析后的命令行：包含一个命令、若干选项，以及若干位置参数（unit 参数）。”
 * 简单说，这个类的对象就是“解析完的结果”，一个只读的“打包好的包裹”，
 * 后面所有代码都可以直接打开这个包裹拿数据用。
 *
 * 注意类名前面有 final 关键字，意思是这个类“不能被子类继承”。为什么不让
 * 继承呢？因为解析结果的数据结构已经固定死了，不需要、也不希望别人去扩展
 * 它，锁死它可以防止有人不小心写出乱七八糟的子类，也让代码更安全、更简单。
 * 另外，注意下面所有字段都是 public final 的——“final”表示一旦构造好就
 * 不能改了，这叫“不可变对象”（immutable object）。不可变对象有个大好处：
 * 多个线程可以放心地同时读它，不用担心数据被改坏，出 bug 的概率就小很多。
 */
public final class Args {

    // 用户要执行的命令名。比如 "run"、"install"、"help"、"version" 等等。
    // 如果没有显式指定命令，解析完以后会在这里填上一个默认值（见后面 parse
    // 方法末尾的逻辑），保证这个字段永远不为 null，调用方就不用老担心空指针。
    public final String command;

    // 位置参数列表（positional arguments）。什么叫“位置参数”呢？
    // 就是不带头“-”或“--”前缀、单纯摆在那里的参数。比如命令
    // "systemwin install C:\app.jar" 里的 "C:\app.jar" 就是位置参数。
    // 我们把它收集成一个 List 存起来，一个命令后面可以跟好几个这样的参数。
    public final List<String> positional;

    // 选项字典（options）。用一个 Map 来存，键是选项的名字（字符串），
    // 值是选项的值（字符串）。为什么用 LinkedHashMap 而不用 HashMap 呢？
    // 因为 LinkedHashMap 能记住插入顺序！这样后面如果希望按照用户输入的
    // 顺序来处理选项，就能保持顺序一致，行为更可预测、更好调试。
    // 有些选项只是“开关”，没有值，比如 --force，我们就给它存个 "1" 当作
    // “开了”的意思（1 在编程里常用来表示 true/真）。
    public final Map<String, String> options;

    // 是否带了 --now 开关。意思是“立即执行，不要排队/不要等待”。true=带了。
    public final boolean now;

    // 是否带了 --all 开关。意思是“对所有的目标都操作”，比如“删除全部”。
    // true=带了。
    public final boolean all;

    // 是否带了 --no-elevate 开关。elevate 是“提权”的意思，在 Windows 上
    // 通常指“以管理员身份运行”（UAC 弹窗那种）。带了 --no-elevate 就表示
    // 用户明确说“别给我弹管理员授权，普通权限跑就行”。true=带了。
    public final boolean noElevate;

    /*
     * 构造函数（constructor）：这就是“制造 Args 对象”的流水线。
     * 注意它是 private（私有）的！为什么私有？因为外面的代码不应该自己
     * 随便 new 一个 Args 出来——所有 Args 对象都应该通过下面那个 public
     * 的静态方法 parse() 来产生，保证每一个对象都是经过正规解析流程得到的，
     * 数据一定是合法、完整的。这就叫“封装”（encapsulation）：把创建对象
     * 的入口管住，只留一个正规大门。
     *
     * 参数解释（按顺序）：
     *   command   —— 命令名，比如 "run"；
     *   positional —— 位置参数的列表；
     *   options   —— 选项键值对的 Map；
     *   now / all / noElevate —— 三个布尔开关，true 表示用户开了对应开关。
     *
     * 方法体做的事情非常简单：把传进来的参数原封不动地存到自己的字段里。
     * 你可能会问，这不就是赋值吗，有什么好说的？是的，就这，构造函数最常
     * 干的就是这种事——“初始化”，把对象一开始的状态定下来。
     */
    private Args(String command, List<String> positional,
                 Map<String, String> options, boolean now, boolean all, boolean noElevate) {
        // 下面这一串 this.xxx = xxx 的写法要注意：左边的 this.command 指的是
        // “我这个对象的 command 字段”，右边的 command 指的是“参数里的
        // command”。因为两边名字一模一样，所以必须用 this 来区分，
        // 否则写成 command = command 就变成自己给自己赋值，什么都没存进去，
        // 这是新手最容易犯的错误之一！
        this.command = command;
        this.positional = positional;
        this.options = options;
        this.now = now;
        this.all = all;
        this.noElevate = noElevate;
    }

    /*
     * 下面这个 parse 方法就是整个类的“心脏”，是核心中的核心！
     *
     * 它是个静态方法（static），意味着不需要先 new 一个 Args 就能直接调用，
     * 调用方式是 Args.parse(...)。静态方法一般用来做“工具性质的、不依赖
     * 对象状态”的操作，这里就是把原始的 argv 字符串数组加工成 Args 对象。
     *
     * 参数：
     *   argv —— 原始的命令行参数数组。这是 Java 程序的标准入口，main 方法
     *           的签名是 main(String[] args)，这个数组就是操作系统传给
     *           JVM、JVM 再传给我们的。注意：数组里不包含程序自身的名字
     *           （也就是不包含 "systemwin.exe" 本身），只包含后面的参数。
     *   i18n —— 国际化（internationalization）对象，专门用来拿“翻译好的
     *           提示信息”。i18n 是 internationalization 这个长单词的缩写
     *           （i + 18 个字母 + n），这样程序就能根据用户的语言环境显示
     *           中文、英文等不同语言的报错信息了。
     *
     * 返回值：一个全新的 Args 对象，装着解析好的所有信息。
     * 异常：如果遇到不合法的参数（比如 --name 后面没跟值，或者出现了根本不
     *       认识的选项），就抛出 CliException 异常，把错误信息带给上层处理。
     *
     * 整体思路（先看大局，再看细节）：
     *   1. 先把所有“结果容器”初始化成默认值（命令为空、列表为空、开关全 false）；
     *   2. 然后从头到尾扫描 argv 数组里的每一个字符串，用一个 switch 判断
     *      它到底是哪个选项；
     *   3. 扫描结束后，根据有没有 help/version 标志，补全 command 的默认值；
     *   4. 最后用收集到的所有数据 new 出一个 Args 对象返回。
     */
    public static Args parse(String[] argv, I18n i18n) throws CliException {
        // ---- 第一步：准备工作，把所有局部变量都初始化为“默认值” ----
        // command 一开始是 null（还不知道用户要干嘛），后面扫完才知道。
        String command = null;
        // 位置参数列表，一开始是空的 ArrayList。ArrayList 就是一个“动态数组”，
        // 可以随时往里面加东西，不需要提前指定长度，非常方便。
        List<String> positional = new ArrayList<>();
        // 选项字典，一开始是空的 LinkedHashMap（前面说过，它能保持插入顺序）。
        Map<String, String> options = new LinkedHashMap<>();
        // 三个开关一开始都是 false（默认都没开），扫到对应参数再改成 true。
        boolean now = false;
        boolean all = false;
        boolean noElevate = false;
        // 这两个标志是“临时记录”用的：helpFlag 记录有没有见到 -h/--help，
        // versionFlag 记录有没有见到 -V/--version。为什么不直接马上把 command
        // 设成 "help" 呢？因为后面有个优先级逻辑要处理（见方法末尾），
        // 所以这里先记账，最后再统一算账，这样代码更清晰。
        boolean helpFlag = false;
        boolean versionFlag = false;

        // ---- 第二步：主循环，逐个扫描命令行参数 ----
        // 用 for 循环从 0 开始，一直扫到 argv 数组的最后一项。i 就是当前
        // 正在处理的参数在数组里的下标。注意：循环体里有些选项会“多吃”一个
        // 参数（比如 -n/--name 后面必须跟一个名字），处理完会把 i 手动加一，
        // 跳过已经被吃掉的那个参数，避免它又被当成独立参数处理一遍。
        for (int i = 0; i < argv.length; i++) {
            // 取出当前这个参数，存到局部变量 a 里，后面代码用 a 就行，
            // 不用老写 argv[i]，看起来更清爽。
            String a = argv[i];
            // switch 语句：根据 a 的内容（也就是用户敲的参数到底是什么），
            // 决定执行哪一段处理逻辑。这是“多分支判断”的标准写法。
            switch (a) {
                // 用户敲了 --now：把 now 开关置为 true。这种“只管开，不需要
                // 额外值”的选项，处理起来最省事。
                case "--now":
                    now = true;
                    break; // break 别忘了！没有 break 会“掉穿”到下一个 case，
                           // 也就是把下面所有 case 都执行一遍，那是大 bug！
                case "--all":
                    all = true;
                    break;
                case "--no-elevate":
                    noElevate = true;
                    break;
                // -f 和 --force 是同一个选项的两种写法（短写法/长写法）。
                // 我们把两种写法写在一起，共用同一段处理逻辑，省得写两遍。
                // force 选项没有值，所以我们给它塞一个 "1" 进去，代表
                // “值为真/开启”。后面代码只要检查 options 里有没有 "force"
                // 这个键，就知道用户有没有要求强制执行了。
                case "-f":
                case "--force":
                    options.put("force", "1");
                    break;
                // --direct 也是纯开关选项，直接塞 "1" 表示开启。
                case "--direct":
                    options.put("direct", "1");
                    break;
                // -h 和 --help：用户想查看帮助文档。这里只把 helpFlag 记成
                // true，先不急着处理，等扫描全部结束后统一决定 command 的值。
                case "-h":
                case "--help":
                    helpFlag = true;
                    break;
                // -V 和 --version：用户想看版本号。同样先记账（versionFlag）。
                // 注意这里是大写的 V！小写 v 通常另有用处，所以区分大小写。
                case "-V":
                case "--version":
                    versionFlag = true;
                    break;
                // -n 和 --name：这个选项“必须跟一个值”，也就是后面还得再跟
                // 一个参数，比如 --name myapp。所以我们调用 requireValue 方法
                // 去拿它后面的那个值（如果后面没有值，requireValue 会抛异常，
                // 提示用户“这个选项需要一个值”）。拿到值以后塞进 options 里，
                // 键是 "name"。然后 i++：把 i 再往后挪一位，因为那个值参数
                // 已经被我们“消费”掉了，不能让它再被循环当独立参数处理。
                case "-n":
                case "--name":
                    options.put("name", requireValue(argv, i, a, i18n));
                    i++;
                    break;
                case "-p":
                case "--path":
                    // working directory for install
                    // 上面这行英文注释的意思是：“这是安装（install）时使用的
                    // 工作目录”。也就是说 -p/--path 后面跟的值是安装时的工作
                    // 路径，安装程序会在这个目录下做事情。
                    // 处理逻辑和 --name 一模一样：取后面的值、存进 options、
                    // 然后 i++ 跳过一个参数。
                    options.put("path", requireValue(argv, i, a, i18n));
                    i++;
                    break;
                case "-e":
                case "--executable":
                    // -e/--executable 后面跟的是“可执行文件”的路径或名字，
                    // 比如某个 .exe 文件。同样是“带值选项”，套路一样：
                    // 取值 -> 存 options -> i++。
                    options.put("executable", requireValue(argv, i, a, i18n));
                    i++;
                    break;
                case "-c":
                case "--command":
                    // -c takes the REST of the command line as the arguments,
                    // so tokens starting with '-' (e.g. --enable-source-maps)
                    // are passed through untouched.
                    // 上面这段英文注释翻译过来是：
                    // “-c 会把命令行剩余的部分整体当作参数，所以以 '-' 开头
                    //  的记号（比如 --enable-source-maps）会原封不动地透传过去。”
                    // 也就是说，-c 是一个“贪婪”的选项：它后面的所有东西，
                    // 不管长什么样（哪怕看起来像别的选项），全都算作它的值。
                    // 这是特意设计的，因为用户可能想把自己的参数原样传给
                    // 内部的某个程序，我们绝不能“好心”去改写它们。
                    // 实现方式：用一个 StringBuilder 把剩下的参数全部拼接
                    // 起来，中间用空格隔开，拼成一个完整的字符串。
                    StringBuilder rest = new StringBuilder();
                    // 从 i+1（即 -c 后面的第一个参数）开始，一直扫到末尾。
                    // 注意这里用的是另一个循环变量 j，不会影响外层循环的 i。
                    for (int j = i + 1; j < argv.length; j++) {
                        // 如果不是第一个拼接进去的参数，就先加一个空格，
                        // 把参数之间隔开。rest.length() > 0 表示“已经拼过
                        // 至少一个参数了”，此时需要先补空格，否则所有单词
                        // 会黏在一起，变成一长串没法看的字符串。
                        if (rest.length() > 0) {
                            rest.append(' ');
                        }
                        rest.append(argv[j]);
                    }
                    // 把拼好的完整字符串存进 options，键是 "command"。
                    options.put("command", rest.toString());
                    // 把 i 直接跳到数组末尾（i = argv.length），这样外层循环
                    // 的 i++ 之后 i 就 >= argv.length，循环自然结束。意思是：
                    // “-c 已经把剩下的参数全包圆了，后面不用再看了。”
                    // 相当于提前终止循环的一种写法。
                    i = argv.length; // consume everything after -c
                    break;
                case "-s":
                case "--service-file":
                    // -s/--service-file 后面跟的是一个“服务配置文件”的路径。
                    // 又是老三样：取值 -> 存 options -> i++。
                    options.put("service-file", requireValue(argv, i, a, i18n));
                    i++;
                    break;
                // default 分支：上面所有 case 都没匹配上，说明这个参数不是
                // 我们认识的任何选项。这时候要分两种情况讨论：
                default:
                    // 情况一：这个参数以 "-" 开头，而且长度大于 1（也就是
                    // 不是单独的 "-"）。那就说明用户敲了一个我们根本不认识的
                    // 选项（比如手滑打了 --forcee）。这是错误！我们不能假装
                    // 没看见，应该立刻抛出异常，把“这是哪个未知选项”以及
                    // “怎么用这个程序”的提示一起告诉用户，让他自己看帮助。
                    if (a.startsWith("-") && a.length() > 1) {
                        throw new CliException(
                                i18n.msg("err.unknown.option", a),
                                i18n.msg("usage.hint"));
                    }
                    // 情况二：这个参数不以 "-" 开头，说明它是个普通的“裸
                    // 参数”。我们约定：整个命令行的第一个裸参数就是命令名
                    // （command），从第二个裸参数开始才算位置参数（positional）。
                    // 打个比方：命令是“动词”，位置参数是“宾语”，
                    // "systemwin run file.jar" 里 run 是动词（命令），
                    // file.jar 是宾语（位置参数）。
                    if (command == null) {
                        command = a;
                    } else {
                        positional.add(a);
                    }
            }
        }

        // ---- 第三步：算总账，决定 command 的最终值 ----
        // 扫描完了，现在根据之前记下的 flag 来定 command，规则从上到下依次
        // 判断，优先级由高到低：
        //   1. 如果用户要求了帮助（-h/--help），那不管他还敲了什么，都优先
        //      显示帮助文档。这符合直觉：你都要帮助了，其他操作先放一边。
        //   2. 否则，如果用户一个命令都没给，但是要了版本号（-V/--version），
        //      那就显示版本信息。
        //   3. 否则，如果用户什么都没给（连命令名都没有）——就是光秃秃地
        //      敲了个 "systemwin.exe" 回车——那就默认显示帮助。很多命令行
        //      工具都是这么设计的：你不告诉我干什么，我就把使用说明甩给你看。
        // 注意：下面这串 if / else if / else if 的写法是原封不动保留的，
        // 一行的 "} else if (" 链式结构，保证每个分支最多只有一个生效。
        if (helpFlag) {
            command = "help";
        } else if (command == null && versionFlag) {
            command = "version";
        } else if (command == null) {
            // Bare "systemwin.exe" shows the help.
            // 上面这行英文注释的意思是：“光秃秃地运行 systemwin.exe 就显示
            // 帮助。”跟前面解释的一模一样，这里再强调一遍。
            command = "help";
        }
        // 最后：把我们辛辛苦苦收集到的所有信息打包成一个新的 Args 对象返回。
        // 构造函数是 private 的，只有这里（同一个类内部）能 new，这正是
        // 我们前面说的“唯一正规大门”的体现。
        return new Args(command, positional, options, now, all, noElevate);
    }

    /*
     * 这个私有静态方法是 parse 方法的好帮手，专门负责“给带值选项取后面的值”。
     *
     * 参数：
     *   argv —— 完整的命令行参数数组；
     *   i    —— 当前选项在数组里的下标（比如 --name 所在的位置）；
     *   flag —— 当前选项的名字，比如 "--name"，用来拼错误提示信息；
     *   i18n —— 国际化对象，用来拿翻译好的错误提示文案。
     *
     * 返回值：选项后面的那个值，也就是 argv[i + 1]。
     *
     * 为什么要单独写一个方法而不是在 switch 里直接写
     * "options.put("name", argv[i+1])" 呢？因为“判断后面还有没有值”这件事
     * 在 -n、-p、-e、-s 好几个选项里都要用到，代码重复了四次。把它们抽成
     * 一个公共方法，就只写一遍判断逻辑，既减少重复（DRY 原则：Don't Repeat
     * Yourself，别重复你自己），也方便以后统一修改。这就是抽取方法的魅力。
     *
     * 注意边界情况：如果选项是命令行里最后一个参数，比如用户只敲了
     * "systemwin --name" 然后回车，那 --name 后面什么都没有。此时 i + 1 已经
     * 超出数组边界，如果直接访问 argv[i + 1] 就会抛出
     * ArrayIndexOutOfBoundsException（数组越界异常），程序直接崩溃，而且报错
     * 信息还很吓人、很不友好。所以我们先用 if 检查一下，越界就抛出一个
     * 语义明确的 CliException，告诉用户“这个选项需要一个值”，这才叫
     * 好的用户体验。
     */
    private static String requireValue(String[] argv, int i, String flag, I18n i18n)
            throws CliException {
        // 检查 i + 1 是否已经超出数组长度。argv.length 是数组的元素个数，
        // 合法下标范围是 0 到 argv.length - 1，所以 i + 1 >= argv.length
        // 就说明后面没东西了。
        if (i + 1 >= argv.length) {
            // 抛异常：用 i18n.msg(...) 去查对应语言的提示文案，
            // 里面会带上用户敲的那个选项名（flag），提示更具体。
            throw new CliException(i18n.msg("err.option.requires.value", flag));
        }
        // 一切正常：返回选项后面的那个值。argv[i + 1] 就是紧跟在选项后面的
        // 那个参数。注意调用方拿到这个值后还会自己执行 i++ 跳过去。
        return argv[i + 1];
    }
}
