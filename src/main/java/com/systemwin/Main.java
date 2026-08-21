package com.systemwin;

/*
 * 【包与导入说明——先认识一下这个项目的"零件清单"】
 * 这个文件所在的包是 com.systemwin，下面导入的这些类就是程序用到的各种
 * "零件"，先把零件认全，后面看代码就不容易迷路：
 *   - com.systemwin.cli.Args / CliException：
 *     命令行参数的解析结果对象，以及"参数解析出错"时抛出的异常类型。
 *   - com.systemwin.commands.Command / CommandContext / CommandRegistry：
 *     命令的统一接口、命令执行时共享的上下文（工具箱）、以及负责"按名字
 *     找到对应命令实现"的命令注册表（工厂）。
 *   - com.systemwin.util.Elevation：
 *     负责 Windows UAC 提权（以管理员身份重新启动自己）的工具类。
 *   - com.systemwin.util.TeePrintStream：
 *     三通输出流，一头进、两头出——内容既显示到控制台，又同时写进日志文件。
 *   - java.io.* / java.nio.* 系列：
 *     JDK 自带的输入输出、编码、文件路径等基础类。
 * 简单说：Main 是"前台迎宾"，其余这些类就是幕后干活的各部门。
 */
import com.systemwin.cli.Args;
import com.systemwin.cli.CliException;
import com.systemwin.commands.Command;
import com.systemwin.commands.CommandContext;
import com.systemwin.commands.CommandRegistry;
import com.systemwin.util.Elevation;
import com.systemwin.util.TeePrintStream;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * SystemWin entry point.
 *
 * <p>Every startup prints the ASCII-art banner ("SystemWin" in the figlet
 * Standard font), the version and the author. A bare invocation or
 * {@code systemwin help} prints the compiled-in help text
 * (OUTPUT-zh_CN.txt / OUTPUT-en_US.txt, selected by the active language). All
 * other commands execute after the banner.
 *
 * <p>Write commands (install/uninstall/start/stop/restart/enable/disable)
 * automatically request elevation via UAC when run without Administrator
 * privileges (suppress with {@code --no-elevate}). The internal
 * {@code __elevated <logfile>} mode is used by the elevated child process to
 * tee its output into a log that the parent relays back to the console.
 */

/*
 * ============================================================================
 * 【类的整体说明——为什么要有这个类？它到底负责什么？】
 * SystemWin 是一个用 Java 写的 Windows 服务管理小工具，可以把它想象成一个
 * "命令行版的服务管理器"。而眼前这个 Main 类，就是整个程序的"大门"，也
 * 就是俗称的入口点（entry point）。Java 程序启动时，JVM（Java 虚拟机）会
 * 专门去寻找带有 public static void main(String[] args) 方法的类，然后从
 * 那个方法的第一行开始执行。所以你可以把 Main 想象成电影院门口的检票员：
 * 所有观众（代码）都要从这里进场，先经过检票（初始化输出、解析参数），
 * 再被引导到各自的放映厅（各个 Command 命令实现类）里去观看电影（执行
 * 具体任务）。
 *
 * 具体来说，这个类只做了四件"大事"：
 *   1. 把标准输出 System.out 强制设置成 UTF-8 编码，保证中文提示和特殊
 *      符号在 Windows 控制台上不会显示成一堆乱码（setupStdout() 干的事）。
 *   2. 判断当前进程是不是"提权后的子进程"（elevated child）。如果是，
 *      就用 TeePrintStream 把子进程的输出同时写进日志文件，方便父进程把
 *      日志内容回显到用户眼前（run() 前半段干的事）。
 *   3. 打印那个很炫酷的 ASCII 艺术字横幅（Banner）——SystemWin 的大字
 *      标题、版本号和作者信息（run() 里调用 Banner.print 干的事）。
 *   4. 解析用户敲进来的命令行参数，根据参数找到对应的 Command 命令对象
 *      去执行，最后把执行结果（退出码）返回给操作系统（run() 后半段干的
 *      事）。退出码 0 表示成功，非 0 表示失败，这是整个程序统一的约定。
 *
 * 另外注意：这个类被声明成了 final，也就是说它不能被继承。为什么？因为
 * 入口类根本没有必要被继承——谁会给"大门"再开个"小门"呢？把它锁死可以
 * 防止别人（或者未来的自己）写出奇怪的子类破坏程序结构，这也算是一种
 * 简单实用的"防御性编程"习惯。
 * ============================================================================
 */
public final class Main {

    public static void main(String[] args) {
        // 【main 方法解释——一切开始的地方】
        // 每个 Java 程序的起点都是这里。JVM 启动后会自动调用这个方法，并把
        // 用户在命令行敲的所有参数（以空格分隔）打包成一个 String 数组传进来。
        // 这里的流程非常直白，就三步：
        //   第一步：setupStdout() 先把标准输出环境收拾好（UTF-8 编码 + 切换
        //           控制台代码页），不然后面打印的中文横幅和帮助信息在 cmd
        //           里可能全是乱码，那用户体验就太糟糕了。
        //   第二步：new Main() 创建一个 Main 对象，再调用它的 run() 方法，
        //           把参数数组原封不动传进去。run() 返回一个整数，也就是
        //           "退出码"（exit code）。
        //   第三步：System.exit(code) 把这个退出码交给操作系统，然后整个
        //           Java 进程就此结束。Windows 的批处理脚本或者 PowerShell
        //           可以通过 %ERRORLEVEL% / $LASTEXITCODE 拿到这个数字，
        //           从而判断程序到底成没成功，方便后续脚本做分支处理。
        // 为什么要 new Main() 再调 run()，而不是把逻辑全塞在 static 方法里？
        // 因为 run() 是非静态方法，可以访问实例状态，将来扩展（比如加成员
        // 变量）会更方便，而且把入口逻辑和"启动细节"分开，结构也更清爽。
        setupStdout();
        int code = new Main().run(args);
        System.exit(code);
    }

    /**
     * Forces System.out to UTF-8 and switches a real console to code page
     * 65001, so the ASCII-art banner and localized text render consistently in
     * cmd, Windows Terminal and pipes (same approach as the winSAB project).
     */
    // 【方法用途（英文注释的中文翻译 + 详细解释）】
    // 这个方法的作用是"把输出环境收拾利索"，具体干两件事：
    //   1. 把 System.out 强制包装成一个 UTF-8 编码的输出流；
    //   2. 如果当前确实连接着一个真实的交互式控制台，就额外执行
    //      cmd.exe 的 "chcp 65001" 命令，把控制台代码页切换成 UTF-8
    //      （代码页 65001 就是 UTF-8 的代号）。
    // 为什么要这么麻烦？因为 Windows 控制台的默认代码页可能是 936（简体
    // 中文 GBK）也可能是 437（英文），而我们的程序里既有中文翻译文本又有
    // ASCII 艺术字横幅。如果不统一编码，同一个程序在 cmd、Windows Terminal、
    // 以及被管道/文件重定向这三种场景下，显示效果会各不一样——有的正常、
    // 有的乱码，排查起来极其痛苦。所以干脆一上来就把编码钉死。
    // 这个方法参考了 winSAB 项目的做法——前人踩过的坑，我们直接绕过去。
    private static void setupStdout() {
        try {
            System.setOut(new PrintStream(
                    new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
            // 这一行值得拆开细讲：
            //   FileOutputStream(FileDescriptor.out) 表示直接往"标准输出"这个
            //   系统文件描述符里写数据（而不是往某个磁盘文件里写）。
            //   PrintStream 的第二个参数 true 表示 autoFlush（自动冲刷），
            //   也就是每次 println 之后自动把缓冲区里的数据真正推到控制台，
            //   保证用户能立刻看到输出，不会因为缓冲而"憋着"。
            //   StandardCharsets.UTF_8 指定了编码，这是最关键的参数——没有它，
            //   PrintStream 会按平台默认编码（很可能是 GBK）输出，中文就乱了。
        } catch (Exception e) {
            // keep the default stream
            // 【中文翻译】如果设置失败了，就保持默认的输出流不动。
            // 为什么失败也无所谓？因为这只是"锦上添花"的显示优化——就算编码
            // 没设置成功，程序顶多是显示乱码，绝不会因此崩溃。这里故意捕获
            // 的是宽泛的 Exception 而不是某个具体异常，意思是：任何失败都
            // 可以接受，不值得为它大动干戈。
        }
        if (System.console() != null) {
            // 【这里的判断逻辑】
            // System.console() 只有在程序确实连接着一个真实交互式终端时才会
            // 返回非 null 的对象。如果输出被重定向到了文件（比如
            // systemwin > out.txt），或者被管道接走（比如 systemwin | findstr x），
            // 这里就是 null——没有真实控制台，自然也不需要切换代码页了，
            // 因为文件里根本不存在"乱码"这种概念，字节是什么样就是什么样。
            try {
                new ProcessBuilder("cmd.exe", "/c", "chcp 65001 >nul")
                        .inheritIO()
                        .start()
                        .waitFor();
                // 解释这几行：
                //   ProcessBuilder 是 Java 用来启动外部进程的类，这四行代码
                //   等价于在 cmd 窗口里手动敲一句 "chcp 65001 >nul"。
                //   chcp 是 Windows 的"更改代码页"命令；">nul" 表示把命令
                //   的输出丢进"黑洞"（不显示 "Active code page: 65001" 那行
                //   无关紧要的提示）。
                //   inheritIO() 让子进程直接继承父进程的输入输出，这样 chcp
                //   的作用才会落到当前控制台上。
                //   waitFor() 是阻塞等待这个外部命令执行完毕，避免出现"程序
                //   都跑完了，控制台还没切好代码页"的竞态问题。
            } catch (Exception e) {
                // not a real console or no permission: ignore
                // 【中文翻译】可能不是真正的控制台，或者没有权限执行 chcp，
                // 直接忽略即可。
                // 比如在受限环境（服务账户、远程会话）里执行 cmd.exe 可能被
                // 系统拒绝，那就算了——反正这只是显示优化，不影响任何功能。
            }
        }
    }

    int run(String[] args) {
        // 【run 方法解释——这才是真正的主干逻辑！】
        // main 里只是简单地调了一下 run()，真正的"干活"全部在这里。注意这个
        // 方法没有 public 修饰符，是包级私有（package-private）的，这是有意的
        // 设计：只有同一个包里的代码（比如测试代码）才能调用它，外部世界只能
        // 通过 public 的 main 进入程序。这样一来入口就被收紧了，防止别人绕过
        // 主流程乱调内部逻辑。
        String[] realArgs = args;
        // realArgs 是"真正要交给命令处理的参数"。为什么要单独搞一个变量？
        // 因为下面可能会把开头的特殊内部参数 "__elevated" 和日志文件路径
        // 剥掉，剩下的才是用户真正敲的命令和参数——不能污染原始的 args。
        boolean elevatedChild = false;
        // elevatedChild 标记当前进程是不是"提权后的子进程"。提权（elevation）
        // 就是 Windows 的 UAC（用户账户控制）弹窗：比如用户没有管理员权限，
        // 却想执行 install 这种需要管理员身份的命令，程序就会重新以管理员
        // 身份启动一个子进程来干活，这个子进程就是"elevated child"。
        if (args.length >= 2 && "__elevated".equals(args[0])) {
            // 【识别特殊内部模式】
            // 约定：如果参数数组的第一个元素是 "__elevated"，说明当前进程
            // 是由父进程提权启动的子进程，第二个参数（args[1]）是日志文件的
            // 路径。这个内部参数用户是永远看不到的，它只存在于父子进程之间的
            // 悄悄话里。
            // 注意这里写的是 "__elevated".equals(args[0])，而不是
            // args[0].equals("__elevated")——把字面量放在前面是经典的防空指针
            // 写法：万一 args[0] 是 null，前一种写法会返回 false（安全），
            // 后一种写法会直接抛出 NullPointerException。这种细节就是
            // 老 Java 程序员口中的"防御性编程"。
            elevatedChild = true;
            try {
                System.setOut(new TeePrintStream(System.out, Paths.get(args[1])));
                // TeePrintStream 的"Tee"来自水管工的三通接头（T 形管）：
                // 一个入口、两个出口。放在这里的意思是：所有打印到 System.out
                // 的内容都会"兵分两路"——一路照常显示在子进程自己的控制台
                // （其实是被父进程接管），另一路同时写进日志文件。这样父进程
                // 就能把日志文件的内容读出来回显给用户，用户看起来就像"同一
                // 个程序在连续不断地输出"，体验非常顺滑。
            } catch (IOException e) {
                // run without the log relay if the log cannot be opened
                // 【中文翻译】如果日志文件打不开，那就干脆不搞日志回传了，
                // 直接正常运行。
                // 想想也是：日志打不开又不是天塌了，最多就是父进程那边少收到
                // 一些输出，程序本身该干嘛还干嘛，不能因为这点小事就罢工。
            }
            realArgs = Arrays.copyOfRange(args, 2, args.length);
            // Arrays.copyOfRange 从索引 2 一直复制到数组末尾，也就是把最前面
            // 那两个内部参数（__elevated 和日志路径）整个裁掉，剩下的才是
            // 用户真正想执行的命令和参数。
        }

        I18n i18n = I18n.load();
        // I18n 是 Internationalization（国际化）的缩写——因为单词太长，大家
        // 就简写成了 "i18n"（首字母 i + 中间 18 个字母 + 尾字母 n）。它的作用
        // 是根据系统的语言环境加载对应的翻译文本（中文的 OUTPUT-zh_CN.txt 或
        // 英文的 OUTPUT-en_US.txt），这样同一个程序就能"说人话"：中国用户看到
        // 中文提示，外国用户看到英文提示，代码里却不用写一堆 if 判断。
        // The elevated child's output is relayed back to the parent console,
        // which already printed the banner — so the child skips it.
        // 【中文翻译】提权子进程的输出会被回传到父进程的控制台，而父进程在
        // 启动时已经打印过横幅了——所以子进程这里必须跳过横幅，否则横幅就会
        // 被打印两遍，看起来就像个"复读机"。
        if (!elevatedChild) {
            Banner.print();
            // 只有"非提权子进程"才打印横幅。提权子进程是半路出家的，它的输出
            // 要拼接在父进程已经打印好的横幅后面，自己当然不能再打印一次。
            // 另外 Banner.print() 现在不带参数了：横幅里只剩 ASCII 艺术字，
            // 版本号/作者已由 OUTPUT-<lang>.txt 帮助文本承载，不需要 i18n。
        }
        try {
            Args parsed = Args.parse(realArgs, i18n);
            // 把字符串参数数组解析成一个结构化的 Args 对象（里面包含命令名、
            // 各种选项标志等信息）。解析失败会抛出 CliException，被下面的
            // catch 接住。把 i18n 也传进去，是为了让解析错误提示也能本地化。
            if (CommandRegistry.needsElevation(parsed.command)
                    && !Elevation.isElevated()
                    && !parsed.noElevate) {
                // 【这段判断是"要不要提权"的核心逻辑，逐条翻译一下】
                //   条件一：CommandRegistry.needsElevation(parsed.command)
                //           ——用户要执行的命令是"需要管理员权限"的写命令
                //           （install/uninstall/start/stop/restart/enable/
                //           disable 这些会改动系统状态的命令）。
                //   条件二：!Elevation.isElevated()
                //           ——当前进程本身不是管理员权限。
                //   条件三：!parsed.noElevate
                //           ——用户没有主动加 --no-elevate 来"拒绝提权"。
                // 三个条件必须同时成立才走提权流程，缺任何一个都不提权。
                // 比如用户加了 --no-elevate，那就是明说了"我不要 UAC 弹窗，
                // 没权限就算了"，我们必须尊重用户的这个选择。
                return elevateAndRun(i18n, realArgs);
                // 注意这里传的是 realArgs（剥掉内部参数之后的），而不是原始
                // 的 args——因为提权启动的子进程收到的应该是干净的用户参数，
                // 不应该再带上任何内部标记。
            }
            CommandContext ctx = new CommandContext(i18n);
            // CommandContext 是"命令的执行上下文"，你可以把它理解成一个装着
            // 各种公共设施的工具箱：目前里面主要放了国际化文本 i18n，将来
            // 需要共享的资源（配置、日志器等）都可以往这个箱子里塞，这样各个
            // 命令就不用自己去到处找依赖了，耦合度更低，也更好测试。
            Command cmd = CommandRegistry.create(parsed.command, ctx);
            // CommandRegistry 是一个"命令登记簿 + 工厂"：它知道每一种命令
            // （install、start、help……）对应哪个 Command 实现类，create()
            // 方法会根据命令名创建出对应的命令对象。这其实就是设计模式里的
            // "工厂模式"（Factory Pattern）——把"创建对象"这件事集中管理，
            // 调用方根本不需要知道具体类名，将来新增命令时也只改注册表一处。
            return cmd.run(parsed);
            // 找到命令对象后，把解析好的参数交给它执行，并返回退出码。
            // 每个命令的 run() 都约定返回 int：0 代表成功，非 0 代表失败，
            // 这个约定贯穿整个程序，最终会一路传回 main 里的 System.exit()。
        } catch (CliException e) {
            // CliException 是"命令行层面的错误"：比如用户拼错了命令名、少写
            // 了必填参数、给了非法的选项值等等。这类错误是"用户自己的问题"，
            // 所以提示信息要写得友好直白，还要把 hint（补救提示）也打出来，
            // 告诉用户该怎么改正，而不是甩一张冷冰冰的报错。
            System.out.println(e.getMessage());
            if (e.hint() != null) {
                System.out.println(e.hint());
            }
            return 1;
            // 命令行错误统一返回退出码 1，表示"这事没干成"。
        } catch (Exception e) {
            // 这是兜底的大网：除了 CliException 之外，任何没预料到的异常
            // （配置文件损坏、磁盘满了、反射出错……）都会掉进这里。这里用
            // i18n.msg("err.internal", ...) 输出一条本地化的"内部错误"消息，
            // 并把异常的详细信息拼在括号里，方便用户把报错内容复制出来反馈
            // 给开发者。为什么要兜底？因为绝不能让异常一路炸到 JVM 顶层，
            // 打印出一大坨吓人的堆栈信息——那对普通用户太不友好了。
            System.out.println(i18n.msg("err.internal",
                    e.getMessage() == null ? e.toString() : e.getMessage()));
            return 1;
        }
    }

    private int elevateAndRun(I18n i18n, String[] args) {
        // 【elevateAndRun 方法解释——"请求提权并运行"】
        // 能走到这个方法，说明用户要执行的命令需要管理员权限，而当前进程
        // 没有。接下来的完整流程是：
        //   1. 打印一句"正在请求管理员权限……"之类的提示（文本来自 i18n，
        //      所以会自动适配中英文）。
        //   2. 调用 Elevation.elevate(args) 触发 UAC 弹窗。用户在弹窗里点
        //      "是"，Windows 就会以管理员身份重新启动一个本程序的新进程
        //      （也就是子进程），并把参数传给它。这个子进程启动时会带着
        //      "__elevated" 特殊参数——还记得 run() 开头那个判断吗？对上了！
        //      子进程因此知道自己是"提权子进程"，从而跳过横幅、把输出回传
        //      给父进程。run() 里的逻辑和这里的逻辑是前后呼应的，它们是一套
        //      配合默契的组合拳。
        //   3. 如果父进程能拿到子进程的输出（er.output() 非空），就原样打印
        //      出来，让用户看到子进程的执行过程，感觉就像自己一直在前台操作。
        //   4. 如果拿到了退出码（er.exitCode() >= 0），就直接把这个退出码
        //      返回给上层——子进程成功就是成功，失败就是失败，父进程不擅自
        //      改判，保持结果原汁原味。
        //   5. 如果以上都没有（比如用户点了"否"拒绝 UAC，或者提权启动彻底
        //      失败），就打印一条失败提示，并返回退出码 1。
        System.out.println(i18n.msg("elev.requesting"));
        Elevation.ElevResult er = Elevation.elevate(args);
        if (er.output() != null && !er.output().isEmpty()) {
            // 子进程可能已经把干活的输出传回来了，这里原样转发给用户看。
            System.out.print(er.output());
        }
        if (er.exitCode() >= 0) {
            // 退出码 >= 0 说明子进程确实运行并正常退出了（哪怕退出码本身是
            // 失败的 1），父进程直接转达这个结果即可，不需要自己做任何加工。
            return er.exitCode();
        }
        // 走到这里，说明连子进程都没能跑起来——最典型的情况就是用户在 UAC
        // 弹窗里点了"否"，或者系统环境不允许提权。那就只能抱歉地告诉用户：
        // 提权失败了，请检查权限或稍后再试。er.error() 可能为空，所以用
        // 三目运算符兜底成空字符串，避免拼出 "null" 这种难看的字眼。
        System.out.println(i18n.msg("elev.failed", er.error() == null ? "" : er.error()));
        return 1;
    }
}
