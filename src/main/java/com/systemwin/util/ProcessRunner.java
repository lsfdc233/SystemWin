package com.systemwin.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Small helper to run external processes (sc.exe, reg.exe, powershell.exe).
 * 中文翻译：这是一个小小的"工具类"，专门用来帮我们运行外部的程序/命令，
 * 比如 Windows 系统自带的 sc.exe（服务控制命令）、reg.exe（注册表命令）
 * 以及 powershell.exe（PowerShell 脚本解释器）。
 *
 * 为什么要写这样一个类呢？
 * 因为我们的程序经常需要去调用一些"外面的"命令行工具来干活，
 * 比如查询服务状态、读写注册表、执行 PowerShell 脚本等等。
 * Java 自己虽然也有 ProcessBuilder 可以用，但是每次都要写一大堆
 * 样板代码（启动进程、读输出、处理超时、处理异常……），
 * 非常麻烦而且容易出错。所以我们就把它封装到这里，
 * 让调用方只需要一行代码就能完成"运行命令 + 拿到结果"这件事。
 *
 * 这个类被设计成 final，而且构造函数是私有的，
 * 意思是它不能被继承、也不能被 new 出来创建实例，
 * 里面全是静态方法，属于典型的"工具类"（Utility Class）写法。
 * 你可以把它想象成一个装满工具的箱子，用的时候直接开箱拿工具就行，
 * 不需要先"造一个箱子"再使用。
 */
public final class ProcessRunner {

    /**
     * 这是本工具类定义的"返回结果"类型，是一个 Java 16+ 的 record（记录类）。
     * record 是 Java 的一种简写语法，会自动帮我们生成构造方法、getter（比如 exitCode()）、
     * equals、hashCode 和 toString，省去我们手写一大堆重复代码。
     * 它里面装了两样东西：
     *   - exitCode：进程退出时的退出码。0 通常表示成功，非 0 表示出错。
     *   - output：进程打印出来的所有输出（标准输出和标准错误合并在一起）。
     * 这就像一个"成绩单"：退出码是"及格/不及格"，output 是"具体考了多少分、写了什么评语"。
     */
    public record Result(int exitCode, String output) {
        /**
         * 判断这次命令执行是否"成功"。
         * 在 Windows 的约定里，绝大多数命令成功退出时退出码都是 0，
         * 所以我们只要看看 exitCode 是不是 0 就行了。
         * 注意：这只是"进程正常退出且退出码为 0"，不代表业务上一定成功，
         * 有些程序即使业务失败也会返回 0，但作为通用约定已经够用了。
         */
        public boolean ok() {
            return exitCode == 0;
        }

        /**
         * 判断这次命令是否因为"超时"而被我们强制杀掉了。
         * 我们内部约定：只要超时被杀，就返回退出码 -2（一个约定俗成的哨兵值），
         * 这样调用方一看 exitCode 是 -2，就知道"哦，是超时了"，
         * 而不是把 -2 误当成普通的程序错误码去排查半天。
         * 为什么要用 -2 而不是别的数字？因为 -1 已经被我们用来表示"启动失败/IO 异常"了，
         * 0 表示成功，正数一般是程序自己的错误码，所以 -2 正好空出来做超时标记。
         */
        public boolean timedOut() {
            return exitCode == -2;
        }
    }

    /**
     * 私有构造函数：什么都不做，只是为了防止别人 new 出这个类的实例。
     * 因为这是一个纯工具类（全是静态方法），根本不需要实例。
     * 如果你不写这个私有构造方法，Java 会偷偷给你一个 public 的无参构造方法，
     * 那样别人就能 new ProcessRunner() 了——虽然也没啥大危害，
     * 但作为一个"洁癖"式的良好习惯，工具类都该把构造函数私有化，
     * 从语法层面杜绝这种无意义的实例化。
     */
    private ProcessRunner() {
    }

    /** Runs a command (no timeout) with merged stdout/stderr and UTF-8 decoded output. */
    /**
     * 中文翻译：运行一条命令（不设超时），标准输出和标准错误合并到一起，
     * 并且把输出按 UTF-8 编码解码成字符串返回。
     *
     * 这是 run 方法的一个"便捷重载"（overload）：调用方如果不想操心超时的事，
     * 只需要传命令本身即可，例如 ProcessRunner.run("sc.exe", "query", "spooler")。
     * 它内部其实就是调用了下面那个带超时参数的 run 方法，并把超时设为 -1（永不超时）。
     * 也就是说，这个方法是"偷懒版"，真正干活的是下面那个"完整版"。
     *
     * 注意这里用的是可变参数 String... command，意思是调用的时候可以传任意多个字符串，
     * 每一个字符串就是命令行里的一个"词"，Java 会自动帮我们组装成一个数组。
     * 为什么要传数组而不是直接传一整条命令字符串？
     * 因为如果传一整条字符串，就必须自己处理引号、空格、转义等一大堆恶心的问题；
     * 而用数组的方式，每个参数独立传递，ProcessBuilder 会自己处理好空格和引号，
     * 彻底避免了"引号地狱"。
     *
     * @param command 要执行的命令及其参数，例如 "sc.exe", "query", "spooler"
     * @return 执行结果，包含退出码和输出内容
     */
    public static Result run(String... command) {
        return run(-1, command);
    }

    /**
     * Runs a command with merged stdout/stderr.
     * 中文翻译：运行一条命令，标准输出和标准错误合并成一股流。
     *
     * 这是本类最核心、最"底层"的方法，其他方法（比如 run 的便捷重载、
     * runPowershell）最终都是调到它这里来干活的。
     * 它做的主要事情可以拆成几步：
     *   1. 用 ProcessBuilder 把命令包装起来，并设置"错误流重定向到输出流"；
     *   2. 启动这个外部进程；
     *   3. 开一个后台守护线程，把进程的输出全部读出来（防止缓冲区堵死，见下文说明）；
     *   4. 等待进程结束（或者等超时）；
     *   5. 把结果（退出码 + 输出文本）封装成 Result 对象返回；
     *   6. 如果中间出了任何意外（IO 错误、被中断、读线程抛异常），统一返回 -1 表示失败。
     *
     * 有几个"坑"值得初学者特别注意：
     * - 为什么要开一个单独的线程去读输出？
     *   因为操作系统的管道缓冲区是有限的，如果进程一直在往外输出而我们不去读，
     *   缓冲区满了之后进程就会"卡死"在那里写不进去，导致 waitFor 永远等不到结束，
     *   这就是著名的"管道死锁"（pipe deadlock）问题。所以必须一边等一边读。
     * - 为什么读输出的线程要是守护线程（daemon）？
     *   守护线程不会阻止 JVM 退出。万一主流程出错了，这个读线程就算还挂着，
     *   也不会让整个 Java 程序无法退出。
     * - 为什么合并 stdout/stderr（redirectErrorStream(true)）？
     *   合并之后我们只需要读一个流就够了，而且输出的先后顺序不会被搞乱，
     *   坏处是没法区分哪行是标准输出、哪行是标准错误——但对我们的用途来说无所谓。
     *
     * @param timeoutSeconds 最长等待秒数；-1 表示不限制等待时间
     *                       （如果超时了，进程会被强制杀死，返回的退出码是 -2）
     * @param command 要执行的命令及其参数
     * @return 执行结果：退出码 + 输出文本
     */
    public static Result run(long timeoutSeconds, String... command) {
        try {
            // 第一步：创建 ProcessBuilder 并设置参数。
            // ProcessBuilder 是 JDK 提供的"进程启动器"，比老旧的 Runtime.exec 更好用、
            // 更安全，能让我们方便地控制工作目录、环境变量、流重定向等。
            ProcessBuilder pb = new ProcessBuilder(command);
            // 把标准错误（stderr）重定向合并到标准输出（stdout）里，
            // 这样我们只需要读一个流，输出内容也不会丢失。
            pb.redirectErrorStream(true);
            // 启动进程！这一步是真正在操作系统层面 fork 出一个子进程。
            Process p = pb.start();

            // 第二步：准备一个"读输出"的任务。
            // FutureTask 是一个"未来才有结果"的任务容器：我们先把"读所有字节并转成
            // UTF-8 字符串"这段逻辑包装成 FutureTask，然后丢给一个后台线程去执行，
            // 最后在需要的时候通过 reader.get() 把结果取回来。
            // 注意我们用 readAllBytes() 一次性读完全部内容——这个写法最简单直接，
            // 缺点是会占用内存，但我们的命令输出一般都不大，完全够用。
            FutureTask<String> reader = new FutureTask<>(() -> {
                try (InputStream in = p.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            });
            // 把这个读任务放进一个线程里跑起来。
            // 线程名起个有意义的名字 "proc-out"，方便以后排查问题的时候
            // 在 jstack / jconsole 里一眼认出这是"进程输出读取线程"。
            Thread t = new Thread(reader, "proc-out");
            // 标记为守护线程：主程序退出时这个线程不会拖后腿（详见类上面的解释）。
            t.setDaemon(true);
            t.start();

            // 第三步：等待进程结束。
            // 这里是个三元表达式：如果 timeoutSeconds 小于 0，就调用 waitForever
            // （无限期等待，其实内部就是 p.waitFor()）；否则调用 waitFor(timeout, 秒)
            // 进行限时等待。waitFor(带时间) 会返回一个布尔值：true 表示进程在限时内
            // 结束了，false 表示超时了进程还在跑。
            boolean done = timeoutSeconds < 0
                    ? waitForever(p)
                    : p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                // 超时了！进程还在运行，我们不能再傻等了。
                // destroyForcibly() 是"强杀"：在 Windows 上等价于强制终止进程
                // （类似任务管理器里的"结束进程"），而不是温和地发关闭信号。
                p.destroyForcibly();
                // 强杀之后再等一次，确保进程真的死透了、资源被回收，
                // 避免留下僵尸进程占用资源。
                p.waitFor();
                // 按约定返回 -2 表示"超时"，输出为空字符串。
                return new Result(-2, "");
            }
            // 进程正常结束了：把读线程的结果取出来（这一步会阻塞等待读线程读完）。
            // 理论上到这里读线程肯定已经读完了，所以 get() 一般会立即返回。
            String out = reader.get();
            // 用 p.exitValue() 拿退出码，和输出一起封装成 Result 返回。
            return new Result(p.exitValue(), out);
        } catch (IOException e) {
            // 启动进程失败（比如命令不存在、路径错误、权限不足）时，ProcessBuilder.start()
            // 会抛 IOException。我们不想让异常往外抛把调用方搞崩溃，
            // 所以统一吞掉，返回退出码 -1 表示"启动失败"。
            return new Result(-1, "");
        } catch (InterruptedException e) {
            // 当前线程在等待时被 interrupt() 打断了。
            // 规范的做法是：恢复中断标志位（Thread.currentThread().interrupt()），
            // 让上层代码知道"我被中断过"，然后返回 -1 表示失败。
            // 把中断标志重新设置回去很重要，不然中断信息就悄悄丢失了。
            Thread.currentThread().interrupt();
            return new Result(-1, "");
        } catch (ExecutionException e) {
            // 读输出的后台线程内部抛了异常，FutureTask.get() 就会把那个异常
            // 包装成 ExecutionException 抛出来。这里同样是吞掉并返回 -1。
            return new Result(-1, "");
        }
    }

    /**
     * 无限期等待进程结束。
     * 这个方法其实是个"包装壳"：真正的逻辑只有一行 p.waitFor()，
     * 它会把当前线程阻塞住，直到被等待的进程退出为止。
     * 为什么不直接在主方法里写 p.waitFor() 而要多包一层？
     * 一是让主方法的逻辑读起来更清晰（一个方法名就表达了意图），
     * 二是把"无限等待"和"限时等待"这两种分支的差异集中在同一个表达式中，
     * 使三元表达式的两边类型一致、可读性更好。
     * 返回 true 表示"等到了，进程结束了"——因为是无限等待，所以只要没抛异常，
     * 走到这里就一定返回 true，永远不可能返回 false。
     *
     * @param p 要等待其结束的进程
     * @return 恒为 true，表示进程已经结束
     * @throws InterruptedException 等待过程中被中断时抛出
     */
    private static boolean waitForever(Process p) throws InterruptedException {
        p.waitFor();
        return true;
    }

    /**
     * Runs a PowerShell script. The script is passed via -EncodedCommand so no
     * quoting or escaping issues can occur. Scripts should set
     * {@code [Console]::OutputEncoding=[Text.Encoding]::UTF8} to produce UTF-8 output.
     * 中文翻译：运行一段 PowerShell 脚本。脚本是通过 -EncodedCommand 参数传递的，
     * 因此不会出现引号或转义方面的问题。脚本里应该设置
     * {@code [Console]::OutputEncoding=[Text.Encoding]::UTF8} 才能输出 UTF-8 编码的内容。
     *
     * 为什么要用 -EncodedCommand（Base64 编码命令）而不是直接 -Command 传原文？
     * 因为 PowerShell 的命令行解析规则非常"矫情"：引号、$ 符号、中文、特殊字符
     * 都可能被命令行解析器半路"吃掉"或转义错乱，写起来全是坑。
     * 而 -EncodedCommand 接收的是 Base64 编码后的 UTF-16LE 字节流，
     * 相当于把脚本"打包加密"成一段无特殊字符的字符串再传进去，
     * PowerShell 内部会原样解码执行，完美避开了所有引号转义问题。
     * 这是微软官方推荐的、也是最稳妥的传脚本方式。
     *
     * 另外提醒一句：脚本内部如果想输出中文或非 ASCII 内容，
     * 最好在脚本开头设置 [Console]::OutputEncoding = [Text.Encoding]::UTF8，
     * 否则 PowerShell 默认的输出编码可能和我们这边用 UTF-8 解码对不上，
     * 导致中文变成乱码。
     *
     * @param script 要执行的 PowerShell 脚本内容（纯文本，不需要引号包裹）
     * @return 执行结果，包含退出码和输出
     */
    public static Result runPowershell(String script) {
        return runPowershell(-1, script);
    }

    /**
     * 这是 runPowershell 的"完整版"：和上面那个便捷重载的区别就是多了一个
     * timeoutSeconds 超时参数（-1 表示不超时），其余逻辑完全一样。
     * 它干的事情分两步：
     *   1. 把脚本用 Base64 编码（编码用的字符集是 UTF-16LE，这是 PowerShell
     *      -EncodedCommand 要求的格式，注意是 UTF-16 小端，不是 UTF-8）；
     *   2. 拼出完整的 powershell.exe 命令行，交给前面写好的通用 run 方法去执行。
     *
     * 命令行里那一串参数分别是什么意思：
     *   -NoProfile       ：不加载用户配置文件，启动更快、更干净，也避免用户配置影响结果；
     *   -NonInteractive  ：非交互模式，不让 PowerShell 弹出窗口或等待输入，
     *                       因为我们是在后台调它，不需要人参与；
     *   -ExecutionPolicy Bypass：绕过执行策略限制。Windows 默认可能禁止运行 .ps1 脚本，
     *                       用 Bypass 可以保证我们的脚本不会被策略拦下来；
     *   -EncodedCommand  ：后面跟的就是 Base64 编码后的脚本本体。
     *
     * @param timeoutSeconds 最长等待秒数；-1 表示不限时
     * @param script 要执行的 PowerShell 脚本内容
     * @return 执行结果，包含退出码和输出
     */
    public static Result runPowershell(long timeoutSeconds, String script) {
        // 把脚本字符串按 UTF-16LE 编码成字节数组，再用 Base64 编码成一段纯 ASCII 字符串。
        // 这段字符串里只会有字母、数字和 +/ 等字符，绝对不会出现引号或空格问题。
        String encoded = Base64.getEncoder()
                .encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        // 拼好完整的命令行后，复用通用 run 方法执行——这就是"封装"的好处：
        // 底层那些读输出、等进程、处理超时的复杂逻辑，这里一行都不用重复写。
        return run(timeoutSeconds, "powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
    }
}
