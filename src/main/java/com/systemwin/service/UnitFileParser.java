/*
 * 这里是文件开头的包声明区。
 * 包名 com.systemwin.service 表示这个类属于 systemwin 项目的 service（服务）层。
 * 简单来说，"包"（package）就像是 Java 世界里给类分门别类的文件夹，
 * 大家约定俗成：业务逻辑、数据处理这类"干活"的类都放在 service 包里。
 * 这样将来其他同事一看包名，就知道这里是放服务逻辑的地方，不用翻半天目录。
 */
package com.systemwin.service;

/*
 * 下面这一大堆 import 语句，就是把"别人写好的现成工具"请进来。
 * 就好比你做饭之前，先把酱油、盐、味精都摆到灶台上，
 * 用到的时候伸手就能拿，不用现找。
 */
import java.io.IOException;              // IOException：读写文件出错时抛出的异常类型，比如文件不存在、没有权限等
import java.nio.charset.StandardCharsets; // StandardCharsets：里面定义了标准的字符编码常量，比如 UTF_8，保证读文件不乱码
import java.nio.file.Files;              // Files：Java 提供的文件操作工具类，读文件、写文件都靠它
import java.nio.file.Path;               // Path：表示文件或目录的"路径"对象，比直接写字符串路径更安全、更规范
import java.util.ArrayList;              // ArrayList：动态数组，可以随便往里面加元素，不用提前声明大小
import java.util.List;                   // List：列表接口，ArrayList 就是它的一个实现，我们平时习惯用接口类型声明变量

/**
 * Minimal parser for systemd unit files. It understands the sections that
 * matter for mapping a unit onto a Windows service:
 * [Unit] Description, [Service] ExecStart/WorkingDirectory/Restart,
 * [Install] WantedBy. Unknown keys are ignored.
 */
/*
 * 上面那段英文 javadoc 是原作者留下的类说明，翻译成中文大概是：
 * "这是针对 systemd 单元文件（unit file）的最小化解析器。
 *  它只关心把单元映射成 Windows 服务时用得到的几个小节（section）：
 *  [Unit] 小节里的 Description（服务描述）、
 *  [Service] 小节里的 ExecStart（启动命令）/WorkingDirectory（工作目录）/Restart（重启策略）、
 *  [Install] 小节里的 WantedBy（被谁启用）。
 *  遇到不认识、不关心的键（key），就直接忽略掉，不会报错。"
 *
 * 下面再用大白话补充几句：
 * 1. 什么是 systemd 单元文件？就是 Linux 系统里用来描述"一个服务怎么启动"的配置文件，
 *    通常放在 /etc/systemd/system/ 或者 /lib/systemd/system/ 目录下，文件名以 .service 结尾。
 * 2. 这个项目（SystemWin）的使命，是把 Linux 上的 systemd 服务"翻译"成 Windows 服务。
 *    既然要翻译，就得先读懂 systemd 的配置文件，这个类干的就是"读懂"这件事。
 * 3. 文件里用 [方括号] 把内容分成若干个小节，比如 [Unit] 描述服务的基本信息，
 *    [Service] 描述服务怎么运行，[Install] 描述服务怎么安装启用。
 *    每个小节里面是一行一行的"键=值"（key=value）配置项。
 * 4. 为什么叫"最小化"解析器？因为 systemd 的配置文件格式其实非常复杂，
 *    有很多花哨的语法和关键字，但我们只关心上面列出的那几个字段，
 *    其余的统统忽略，这样实现起来简单、也不容易出错。
 *
 * 另外注意：这个类被声明成了 final（不可继承），
 * 而且构造函数是 private（私有的）——也就是说别人不能 new 出这个类的对象，
 * 只能通过它提供的静态方法（static 方法）来干活。这是一种常见的"工具类"写法：
 * 这个类本身就是一套工具，不需要实例化，直接类名点方法名调用即可。
 */
public final class UnitFileParser {

    /*
     * 私有的空构造函数。
     * 为什么构造函数是空的、还是私有的？因为上面说了，这是个工具类，
     * 我们不希望有人闲着没事 new 一个 UnitFileParser() 出来玩，
     * 所以把构造函数藏起来（private），想 new 都 new 不了。
     * 这样整个类的所有功能都只能通过静态方法使用，设计意图一目了然。
     */
    private UnitFileParser() {
    }

    /*
     * ============================================================
     * 方法一：parse（解析）
     * ============================================================
     * 这是本类的核心入口方法：给它一个 systemd 单元文件的路径（Path file），
     * 它会把文件读进来、逐行分析，最后组装成一个 UnitFile 对象返回给调用者。
     * 声明 throws IOException 的意思是：读文件的时候可能出错
     * （比如文件不存在、没有读权限、磁盘坏了等等），
     * 这个方法自己处理不了，就把异常"抛"出去，交给调用方去决定怎么办。
     * 这是一种很常见的 Java 写法：自己处理不了的问题，别硬扛，向上汇报。
     */
    public static UnitFile parse(Path file) throws IOException {

        /*
         * 第一步：把整个文件按行读进来。
         * Files.readAllLines(file, StandardCharsets.UTF_8) 表示：
         * "把 file 这个文件的所有行读出来，存进一个 List<String>，一行是一个字符串。
         *  读取的时候用 UTF-8 编码解码，这样中文注释、中文配置内容都不会变成乱码。"
         * 为什么用 UTF-8？因为这是现在全世界最通用的文本编码，systemd 配置文件也是 UTF-8。
         * 如果编码用错了，读出来的字符串可能全是乱码，后面解析必然出错。
         */
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        /*
         * 下面这些局部变量，就是我们要从文件里"挖"出来的宝贝。
         * 一开始全都赋成默认值，等会在循环里一行一行地匹配、填充。
         *
         * 先解释一下这些变量各自代表什么（都是 systemd 配置里的概念）：
         * - section（小节名）：记录"当前正在处理哪个小节"，比如 "Unit"、"Service"、"Install"。
         *   因为同一个键在不同小节里含义不同，所以必须时刻记住我们在哪个小节里。
         * - description（描述）：[Unit] 小节里的 Description= 的值，就是这个服务是干什么的一句话说明。
         * - execStart（启动命令）：[Service] 小节里的 ExecStart= 的值，就是服务真正要执行的命令行。
         * - workingDirectory（工作目录）：[Service] 小节里的 WorkingDirectory= 的值，服务启动时先 cd 到哪个目录。
         * - restart（重启策略）：[Service] 小节里的 Restart= 的值，比如 always（挂了就自动重启）。
         * - wantedBy（被谁启用）：[Install] 小节里的 WantedBy= 的值，通常是 multi-user.target，
         *   表示系统进入多用户模式时自动启用这个服务。
         * - environment（环境变量列表）：[Service] 小节里的 Environment= 的值，
         *   一个服务可能配置好几行 Environment=，所以用一个 List 来收集，每行存一个。
         *
         * 注意 section 的初始值是空字符串 ""，表示"还没进入任何小节"。
         * 如果配置项出现在任何小节之外（文件开头），我们按约定不处理它。
         */
        String section = "";
        String description = null;
        String execStart = null;
        String workingDirectory = null;
        String restart = null;
        String wantedBy = null;
        List<String> environment = new ArrayList<>();

        /*
         * 这里准备了一个 StringBuilder 叫 pending，中文意思"待处理的东西"。
         * 它是专门用来处理 systemd 配置里"续行"（多行拼接）语法的。
         *
         * 什么是续行？在 systemd 配置文件里，如果一行以反斜杠 \ 结尾，
         * 就表示"这行还没写完，下一行是它的续写"。
         * 比如：
         *     ExecStart=/usr/bin/myapp \
         *              --port 8080
         * 实际等价于一行：ExecStart=/usr/bin/myapp --port 8080。
         * 我们先用 pending 把第一行（去掉末尾的 \）暂存起来，
         * 等读到下一行时再拼上去，这样就能正确处理这种写法了。
         */
        StringBuilder pending = new StringBuilder();

        /*
         * 主循环：一行一行地遍历整个文件的内容，逐行分析。
         * 这里的 raw 是"原始行"——注意！它可能带着行首行尾的空格、制表符等空白字符。
         * 空白字符在配置文件里通常是没意义的，所以下面第一件事就是 trim() 掉它们。
         */
        for (String raw : lines) {
            // 把这一行首尾的空白字符（空格、Tab 等）去掉，得到干净的一行。配置文件里多余的空格不该影响解析结果。
            String line = raw.trim();

            /*
             * 续行处理的第一种情况：
             * 如果 pending 里还存着上一行末尾没写完的内容（pending.length() > 0），
             * 说明上一行是以反斜杠结尾的，那么这一行就是它的续行。
             * 我们把续行内容直接接到 pending 暂存内容的后面，拼成完整的一行，
             * 然后把 pending 清空（setLength(0)），表示暂存区已经用掉了。
             */
            if (pending.length() > 0) {
                // continuation of the previous line (systemd "\" continuation)
                // 这是原作者留下的英文注释，意思是：这是上一行的延续（systemd 用反斜杠续行的语法）。
                // 中文再啰嗦一遍：上一行末尾有个 \，表示这行没完，下一行接着写。
                line = pending + line;
                pending.setLength(0); // 清空暂存区，免得残留旧内容影响后面
            }

            /*
             * 跳过"空行"和"注释行"。
             * 判断条件有三个，满足任何一个就 continue（跳到下一行，本行不处理）：
             * 1. line.isEmpty()：这行是空的（trim 之后什么都没剩下），没什么好解析的。
             * 2. line.startsWith("#")：以井号 # 开头，systemd 里 # 表示注释，整行忽略。
             * 3. line.startsWith(";")：以分号 ; 开头，同样也是注释的另一种写法。
             * 这两种注释符号 systemd 都认，就像 Windows 的 ini 文件里既可以用 ; 也可以用 # 一样。
             */
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }

            /*
             * 续行处理的第二种情况：
             * 如果这行（去掉空白后）是以反斜杠 \ 结尾的，说明这一行还没写完，
             * 后面还有续行。我们把这一行除末尾反斜杠以外的内容存进 pending 暂存区，
             * 然后用 continue 跳到下一行继续读。
             * line.substring(0, line.length() - 1) 的意思就是"从开头截到倒数第二个字符"，
             * 也就是把末尾那个反斜杠去掉。
             * 注意：如果上面已经拼接了上一行的续行内容，这里的判断依然成立，
             * 因为判断的是拼接后的整行是否以反斜杠结尾。
             */
            if (line.endsWith("\\")) {
                pending.append(line, 0, line.length() - 1);
                continue;
            }

            /*
             * 小节标题的判断：一行以 [ 开头并且以 ] 结尾，就是一个小节标题。
             * 例如 "[Service]" 这一行，表示从这里开始进入 Service 小节。
             * 我们把它中间的部分取出来（去掉首尾的方括号），去掉空白，存进 section 变量。
             * 这样后面再读到键值对时，就知道它属于哪个小节了。
             * 最后 continue 跳过本行——标题行本身不是键值对，不需要再往下解析。
             */
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }

            /*
             * 到这里，剩下的行应该都是"键=值"（key=value）形式的配置项了。
             * 我们用 indexOf('=') 找到第一个等号的位置，存在 eq 里。
             * 如果 eq 小于 0，说明这一行里根本没有等号，
             * 那它既不是小节标题、也不是键值对，是个格式不对的怪行，直接忽略。
             */
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }

            /*
             * 把这一行拆成"键"和"值"两部分：
             * - key：从行首截到等号之前（substring(0, eq)），再去掉空白。
             *   注意等号左边可能也有空格，比如 "  Description  = xxx"，所以要 trim。
             * - value：从等号之后截到行尾（substring(eq + 1)），同样去掉首尾空白。
             * 这样 "Description = 我的服务" 就被拆成了 key="Description"、value="我的服务"。
             */
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();

            /*
             * 根据当前所在的小节（section）来决定如何处理这个键值对。
             * 这里用的是 Java 14+ 的 switch 表达式语法（箭头 -> 那种），
             * 每个 case 是一个小节名，default 是"其他没列出来的小节"。
             * 逻辑很直白：不同小节里，我们对不同的键感兴趣，
             * 只把关心的键的值存进对应变量，其他的一律不管（忽略）。
             */
            switch (section) {
                /*
                 * [Unit] 小节：这里主要描述服务的元信息。
                 * 我们只关心 Description（描述），其他键比如 After=、Requires= 等一概不读。
                 */
                case "Unit" -> {
                    // 如果键是 Description，就把它的值记下来。以后 Windows 服务的描述信息就用它。
                    if (key.equals("Description")) {
                        description = value;
                    }
                }

                /*
                 * [Service] 小节：这里是整个文件里信息量最大、也最关键的小节，
                 * 因为"服务到底怎么跑"全部由这里决定。
                 * 我们关心四个键：ExecStart、WorkingDirectory、Restart、Environment。
                 */
                case "Service" -> {
                    if (key.equals("ExecStart")) {
                        // ExecStart：服务启动时真正执行的命令行，这是我们最关心的核心信息。
                        execStart = value;
                    } else if (key.equals("WorkingDirectory")) {
                        // WorkingDirectory：服务的工作目录。很多程序启动后要在自己目录下读写文件，没有它可能找不到相对路径。
                        workingDirectory = value;
                    } else if (key.equals("Restart")) {
                        // Restart：进程退出后的重启策略。比如 always 表示无论什么原因退出都自动重启。
                        restart = value;
                    } else if (key.equals("Environment")) {
                        // Environment：设置环境变量。一个服务可以配多行，所以用 add 往列表里追加。
                        environment.add(value);
                    }
                }

                /*
                 * [Install] 小节：描述服务"什么时候被启用"。
                 * 这里我们只关心 WantedBy，它通常的值是 multi-user.target，
                 * 意思是"系统进入多用户运行模式时就自动把这个服务拉起来"。
                 */
                case "Install" -> {
                    if (key.equals("WantedBy")) {
                        wantedBy = value;
                    }
                }

                /*
                 * 其他没列出来的小节（比如 [Socket]、[Timer]、[Path] 等），
                 * 以及本小节里我们不认识的键，统统走到这个 default 分支。
                 * 我们什么都不做，直接忽略——这就是"最小化解析"的体现：
                 * 不认识的配置就当不存在，绝不多管闲事。
                 */
                default -> {
                    // ignore
                    // 上面这句英文是原作者写的"忽略"，翻译过来就是：这里什么都不干，直接无视。
                }
            }
        }

        /*
         * 整个文件都遍历完了，所有感兴趣的字段也都收集齐了。
         * 最后一步：把收集到的这些零散信息打包成一个 UnitFile 对象返回。
         * 构造函数参数顺序是：(文件名, 描述, 启动命令, 工作目录, 重启策略, 被谁启用, 环境变量列表)，
         * 和上面声明的局部变量一一对应，正好是我们要的全部信息。
         * 拿到这个对象之后，调用方就可以基于它去创建 Windows 服务了。
         */
        return new UnitFile(file.toString(), description, execStart,
                workingDirectory, restart, wantedBy, environment);
    }

    /**
     * Splits a systemd command line into tokens, honoring double quotes.
     */
    /*
     * 上面那句英文 javadoc 的意思是：
     * "把一条 systemd 命令行按空白拆分成一个个 token（词元），
     *  并且要正确处理双引号包裹的内容。"
     *
     * 用大白话解释这个方法是干嘛的：
     * 命令行本质上就是一串字符串，比如：/usr/bin/myapp --port 8080 "hello world"
     * 但程序执行时，需要把这一整串拆成一个个独立的参数：
     *   第 1 个参数：/usr/bin/myapp
     *   第 2 个参数：--port
     *   第 3 个参数：8080
     *   第 4 个参数：hello world   （注意！因为有双引号，里面的空格不会被拆开）
     * 这个"拆分"的动作在计算机术语里叫"分词"（tokenize / split）。
     *
     * 为什么要特别强调"双引号"？因为如果参数里本身就包含空格
     * （比如上面那个 "hello world"），直接按空格拆就会拆成两个参数，语义就错了。
     * 所以引号里的空格要"保护"起来，不能拆。
     * 这就是本方法里 inQuote（是否在引号内）这个布尔变量的意义所在。
     *
     * 注意：这个方法是 public static 的，也就是说它也是个工具方法，
     * 不需要创建 UnitFileParser 对象就能直接调用：UnitFileParser.splitQuoted(...)。
     */
    public static List<String> splitQuoted(String s) {
        // out：存放拆分结果的列表，最后返回的就是它。
        List<String> out = new ArrayList<>();
        // cur：用来临时拼装"当前正在攒的这一个参数"，攒满一个就存进 out。
        StringBuilder cur = new StringBuilder();
        // inQuote：标记"当前是否处在一对双引号里面"。false 表示在引号外，true 表示在引号内。
        boolean inQuote = false;

        /*
         * 循环遍历字符串的每一个字符，一个一个地看。
         * 针对每个字符，只有三种情况需要处理：
         * 1. 是双引号：翻转 inQuote 状态（进入/离开引号），引号本身不算参数内容；
         * 2. 是空白字符且当前不在引号内：说明一个参数结束了，把攒好的内容存进 out；
         * 3. 其他普通字符：直接追加到 cur 里，属于当前参数的一部分。
         */
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i); // 取出当前位置的这一个字符

            if (c == '"') {
                // 遇到双引号：把 inQuote 取反。
                // 第一次遇到 " 表示"进入引号"，第二次遇到 " 表示"离开引号"，
                // 所以用 !inQuote（非运算取反）就能在这两个状态之间来回切换，非常巧妙。
                inQuote = !inQuote;
            } else if (Character.isWhitespace(c) && !inQuote) {
                // 遇到空白字符，并且当前不在引号内：说明这个参数已经攒完了。
                // 但还要检查 cur 里有没有内容（cur.length() > 0），
                // 避免连续多个空格时把空字符串也当成一个参数存进去。
                if (cur.length() > 0) {
                    out.add(cur.toString()); // 把攒好的参数存进结果列表
                    cur.setLength(0);        // 清空 cur，开始攒下一个参数
                }
                // 如果 cur 是空的（比如连续两个空格），什么都不做，继续看下一个字符。
            } else {
                // 既不是引号、也不是"引号外的空白"：
                // 要么是普通字符，要么是引号内的空格——这两种都属于当前参数的内容，直接追加。
                cur.append(c);
            }
        }

        /*
         * 循环结束后，有可能最后一个参数还攒在 cur 里没来得及存进 out
         * （因为"存进 out"这个动作是在遇到下一个空白时才触发的，
         *  而字符串末尾往往没有空白了）。
         * 所以这里要补一个收尾动作：如果 cur 里还有内容，就把它也存进去。
         */
        if (cur.length() > 0) {
            out.add(cur.toString());
        }

        // 返回拆分好的参数列表。
        return out;
    }

    /**
     * Parses an ExecStart= value. Strips systemd prefix modifiers
     * (@ - + ! !!) and returns the executable and its arguments.
     */
    /*
     * 上面这句英文 javadoc 的意思是：
     * "解析 ExecStart= 的值。先把 systemd 的前缀修饰符（@、-、+、!、!!）
     *  剥掉，然后返回可执行程序路径和它的参数列表。"
     *
     * 什么叫"前缀修饰符"？systemd 在 ExecStart= 的命令前面允许加一些特殊符号，
     * 用来控制命令的执行方式，常见的几个是：
     * - "@"：后面的第一个参数当作可执行文件（argv[0]），常用于脚本解释器场景；
     * - "-"：这个命令即使失败了也不影响服务整体状态（忽略失败）；
     * - "+"、"!"、"!!"：以不同的权限/环境来执行命令（比如提权、清空环境等）。
     * 这些符号对 Windows 服务来说没有任何意义，所以在解析时必须把它们剥掉，
     * 只留下真正要执行的命令行本体。
     *
     * 处理流程分三步：
     * 1. 处理 null 的情况（调用者可能根本没配置 ExecStart）；
     * 2. 用 while 循环把开头的修饰符一个一个剥掉；
     * 3. 用前面写好的 splitQuoted 把命令行拆成"可执行程序 + 参数列表"，
     *    组装成 UnitFile.ExecStart 对象返回。
     */
    public static UnitFile.ExecStart parseExecStart(String execStart) {
        // 防御性检查：如果调用者传进来的是 null（说明配置文件里没有 ExecStart= 这一项），
        // 就直接返回 null，让调用方自己处理"没有启动命令"的情况。
        // 如果不检查，下面调用 execStart.trim() 就会抛出 NullPointerException（空指针异常），把程序炸掉。
        if (execStart == null) {
            return null;
        }

        // 先去掉命令字符串首尾的空白，干干净净地开始处理。
        String s = execStart.trim();

        /*
         * 剥修饰符的循环：
         * 条件里 "-@+!".indexOf(s.charAt(0)) >= 0 的意思是：
         * "取 s 的第一个字符，看看它是不是 '-'、'@'、'+'、'!' 这四个字符之一。"
         * indexOf 找到就返回下标（>= 0），找不到就返回 -1。
         * 只要第一个字符是修饰符，就 substring(1) 把它砍掉，再看下一个字符，
         * 直到第一个字符不再是修饰符为止。
         * 这样 "!!/usr/bin/app" 这种多个修饰符叠着写的也能被剥干净，变成 "/usr/bin/app"。
         */
        while (!s.isEmpty() && "-@+!".indexOf(s.charAt(0)) >= 0) {
            s = s.substring(1);
        }

        /*
         * 剥完修饰符后，调用 splitQuoted 把命令行拆成一个个参数。
         * 比如 "/usr/bin/app --port 8080" 会被拆成 ["/usr/bin/app", "--port", "8080"]。
         */
        List<String> tokens = splitQuoted(s);

        // 如果拆完之后一个参数都没有（比如 ExecStart 的值是空的或者全是空白），
        // 那这条命令根本没有意义，直接返回 null，交给调用方判断。
        if (tokens.isEmpty()) {
            return null;
        }

        /*
         * 正常情况下，第一个参数就是"可执行程序"（executable），
         * 剩下的参数（tokens.subList(1, tokens.size())，即从下标 1 到末尾）都是传给它的参数。
         * 把它们打包成一个 UnitFile.ExecStart 对象返回。
         * subList 返回的是原列表的"视图"（共享底层数据），这里用法上没有问题，
         * 因为 ExecStart 对象拿到后只是读取用。
         */
        return new UnitFile.ExecStart(tokens.get(0), tokens.subList(1, tokens.size()));
    }
}
