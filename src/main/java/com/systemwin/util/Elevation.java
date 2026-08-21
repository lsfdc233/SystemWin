// ============================================================================
// 文件开头的话（废话时间）
// ----------------------------------------------------------------------------
// 这个文件叫 Elevation.java，是整个 SystemWin 项目里负责"权限提升"（elevation）
// 的工具类。什么叫权限提升呢？在 Windows 上，普通程序默认是以"标准用户"身份
// 运行的，很多系统级操作（安装服务、停止服务、修改系统设置）都需要"管理员"
// （Administrator）权限。普通用户身份干不了这些事，所以程序就必须想办法让自己
// 以管理员身份重新运行一遍——这个"想办法让自己变管理员"的过程，就叫做
// "自我提升"（self-elevation），也就是本文件名字里 Elevation 的意思。
//
// 打个比方：你家的大门有两道锁，普通钥匙只能开第一道，而管理员钥匙能开第二道。
// 现在你要进第二道门，但你手里只有普通钥匙，于是你按了一下门铃（UAC 弹窗），
// 让管家（管理员）帮你开门。这个文件干的就是"按门铃"这件事。
//
// 注意：本文件只负责"如何把自己提升为管理员"，至于提升之后具体要执行什么命令，
// 那是调用方（其他类）通过参数传进来的，本类不关心。
// ============================================================================
package com.systemwin.util;
// 这一行声明了当前类所属的"包"（package）。
// 包名 com.systemwin.util 的含义是：SystemWin 项目下 util（工具）这个子目录。
// Java 用包来给成千上万的类做分类，就像 Windows 用文件夹给文件分类一样，
// 这样类名就不会到处撞车，找起来也方便。
// 其他类要使用本类时，需要写 import com.systemwin.util.Elevation; 把它引进来。

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
// ====================== import 区（把工具请进门） ======================
// 下面这一长串 import，就是把 Java 标准库（JDK 自带的各种类）里的工具"请"进来用。
// 打个比方：就像大厨做菜之前，先把需要的调料、锅碗瓢盆都从柜子里拿出来摆好，
// 真正炒菜的时候才不至于手忙脚乱到处翻。
// 逐个说明一下，免得初学者看得一头雾水：
//   File        —— 表示一个"文件或目录"，比如 C:\xxx\a.txt 就是一个 File 对象。
//   IOException —— 输入/输出出错的异常类型。读文件失败了、磁盘满了，就会抛出它。
//                  后面好几个方法签名里都写着 throws IOException，意思就是
//                  "我这个方法可能会出错，出错时我不自己处理，抛给调用我的人处理"。
//   StandardCharsets —— 一个装字符编码常量的类。这里用它拿到 UTF_8 编码，
//                       保证读日志文件时中文不会变成乱码。
//   Files       —— 文件操作的"瑞士军刀"：判断文件是否存在、读全部字节、
//                  删除文件、创建临时文件，全都靠它。
//   Path / Paths —— 跟"文件路径"打交道。Path 表示一条路径，
//                   Paths.get(...) 就是把字符串变成 Path 对象。
//   ArrayList / List —— 变长列表。List 是接口（约定），ArrayList 是具体实现，
//                        这里用来装一长串命令行参数（每一条参数都是一个 String）。

/**
 * UAC self-elevation support.
 *
 * <p>Write commands (install/uninstall/start/stop/restart/enable/disable)
 * need an Administrator context. When the process is not elevated, SystemWin
 * relaunches itself elevated via a UAC consent prompt and waits for the
 * elevated child to finish, relaying the child's output back to the console.
 *
 * <p>The elevated child runs in a hidden window in a special internal mode
 * ({@code __elevated <logfile> ...}) that tees its output to a UTF-8 log;
 * after the child exits the parent reads and prints that log.
 */
// ---------- 上面的英文 Javadoc 到底在说什么？（中文翻译 + 解释） ----------
// 这是类的"说明书"（Javadoc），给用这个类的人看的：
// 1) 本类提供"UAC 自我提升"（UAC self-elevation）的功能。
//    UAC 就是 Windows 那个"是否允许此应用对你的设备进行更改？"的弹窗。
// 2) 系统里的"写操作"命令——安装(install)、卸载(uninstall)、启动(start)、
//    停止(stop)、重启(restart)、启用(enable)、禁用(disable)——全都需要管理员身份。
//    因为安装/卸载服务、启停服务都会动到系统核心区域，普通用户没这个权力。
// 3) 当当前进程发现自己没有管理员权限时，SystemWin 会通过 UAC 弹窗把自己
//    "重新启动"成一个管理员进程，然后父进程（原进程）一直等着，直到这个
//    管理员子进程干完活退出，再把子进程的输出原样转达给控制台（console）。
//    这样用户看起来就好像"同一个程序"完成了高权限操作，其实中间换了个马甲。
// 4) 那个被提升的子进程是在"隐藏窗口"里运行的，并且处于一种特殊的内置模式：
//    命令行里会带一个特殊的标记参数 __elevated 和一个日志文件路径，
//    子进程会把它的输出"分流"（tee，一路打印、一路写文件）写进一个
//    UTF-8 编码的日志文件。等子进程退出后，父进程再去读这个日志文件，
//    把内容打印出来给用户看。
//
// 简单总结整个流程：
//   没权限 ──► 弹 UAC 窗 ──► 用户点"是" ──► 悄悄启动一个管理员子进程
//        ──► 子进程干活并写日志 ──► 子进程退出 ──► 父进程读日志、打印出来。
public final class Elevation {
    // final 关键字：这个类不允许被继承（不能有子类）。
    // 因为这是一个纯工具类，所有方法都是静态的（static），根本不需要"多态"，
    // 所以直接把它封死，防止别人乱继承搞出幺蛾子。
    // 另外还特意把构造方法私有化了（见下面），连 new 都不让 new，
    // 保证这个类只能"静态调用"，不可能被实例化。

    // ============================ 内部记录 ElevResult ============================
    // record 是 Java 16 以后引入的一种"数据容器"写法。它跟一个普通类差不多，
    // 但专门用来"装几个值、然后原样传递"，不需要你手写 getter、equals、hashCode。
    // 你可以把它理解成一个"信封"：把 (exitCode, output, error) 这三样东西
    // 装进同一个信封里，方便一次性地从方法里返回给调用方。
    //   exitCode —— 子进程的退出码。0 通常代表成功，非 0 代表失败。
    //   output   —— 子进程的 stdout（标准输出）内容，也就是它干活时打印的话。
    //   error    —— 出错信息。一切顺利时这里是 null（没有错误）。
    // 之所以要搞这么个信封，是因为 Java 的方法一次只能返回一个值，
    // 而我们偏偏想一次返回三个值，那就只能把它们打包了。
    public record ElevResult(int exitCode, String output, String error) {
        // 注意：record 的构造方法、字段访问方法都是编译器自动生成的，
        // 所以我们这里什么都不用写，这个空的大括号就是占个位。
    }

    private Elevation() {
        // 私有构造方法：这个类是纯静态工具类，禁止外部 new Elevation()。
        // 如果你在外面写了 new Elevation()，编译器会直接报错，因为构造方法是 private 的。
        // 这是一种常见的"工具类设计惯例"——既然所有方法都是 static 的，
        // 实例化这个类就毫无意义，不如直接堵死这条路，让代码更清晰。
    }

    /** Returns true if the current process runs with Administrator privileges. */
    // ---- 中文翻译：这个方法用来判断"当前进程是不是以管理员身份在运行" ----
    // 返回 true 表示当前进程拥有管理员权限；返回 false 表示没有。
    // 这是整个 Elevation 类的"第一道检查"：先看看自己是不是管理员，
    // 如果不是，才需要走后面的 elevate() 提升流程；如果是，就直接干活，
    // 根本不用费劲弹窗了。
    public static boolean isElevated() {
        // static 方法：不用 new 对象，直接用 Elevation.isElevated() 就能调用。
        // 接下来的思路是：调用 PowerShell，让 PowerShell 去问 Windows
        // "当前这个进程的身份，是不是属于 Administrators（管理员组）？"
        // 之所以绕道 PowerShell，是因为直接用 Java 查 Windows 用户组权限
        // 非常麻烦，而 PowerShell 里恰好有一行现成的代码能干这事。
        ProcessRunner.Result r = ProcessRunner.runPowershell(
                "[Console]::OutputEncoding=[Text.Encoding]::UTF8\n"
                        + "([Security.Principal.WindowsPrincipal]"
                        + "[Security.Principal.WindowsIdentity]::GetCurrent())"
                        + ".IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)");
        // 上面这一长串字符串就是我们要执行的 PowerShell 脚本，拆开看：
        //   第一行 [Console]::OutputEncoding=...UTF8
        //       先把控制台输出编码设为 UTF-8，免得返回的"True"被读成乱码。
        //   第二行 WindowsIdentity::GetCurrent()
        //       拿到"当前 Windows 用户身份"这个对象（相当于问 Windows：我是谁？）。
        //   第三行 WindowsPrincipal(...).IsInRole(...Administrator)
        //       问 Windows：我这个身份，是不是属于"管理员"这个角色？
        //       答案是 True 就说明是管理员，False 就说明不是。
        // ProcessRunner 是项目里另一个工具类，专门负责"执行外部程序/命令"
        // 并收集它的输出。这里执行完 PowerShell 后，结果装进 r 这个对象。
        return r.ok() && r.output().trim().equalsIgnoreCase("True");
        // 返回条件有两个，缺一不可：
        //   1) r.ok()                    —— PowerShell 进程正常执行完，没崩溃；
        //   2) 输出去掉首尾空白后等于 "True"（忽略大小写，所以 "true" 也算）。
        // 两个都满足，才能下结论说"当前是管理员"。
        // 注意用了 equalsIgnoreCase 而不是 equals：因为 PowerShell 在不同
        // 系统/语言环境下可能输出 "True" 或 "true"，统一忽略大小写最稳妥。
    }

    /**
     * Relaunches the current program elevated via UAC and waits for completion.
     *
     * @return the exit code of the elevated process and its tee'd output, or
     *         exitCode -1 when elevation failed or was canceled
     */
    // ---- 中文翻译：这是本类的"核心大招"，负责真正执行权限提升 ----
    // 这个方法做的事是：把当前程序重新以管理员身份启动（会触发 UAC 弹窗），
    // 然后一直等着，直到那个管理员子进程跑完。
    // 参数 originalArgs：当前程序原本收到的所有命令行参数（字符串数组）。
    // 返回值（ElevResult 信封）：
    //   - 正常情况：返回子进程的退出码 + 子进程写进日志的输出内容；
    //   - 失败或被用户取消（比如 UAC 弹窗上点了"否"）：返回 exitCode = -1，
    //     并附上错误说明。
    // 整个方法的思路可以概括成四步：
    //   第一步：把参数里不该传给子进程的脏东西（--no-elevate）过滤掉；
    //   第二步：拼出"要重新启动的可执行程序 + 它的参数"；
    //   第三步：创建一个临时日志文件，让子进程把输出写进去；
    //   第四步：用 PowerShell 的 Start-Process -Verb RunAs 触发 UAC 提升，
    //           然后等待子进程结束，最后读日志、删日志、返回结果。
    public static ElevResult elevate(String[] originalArgs) {
        try {
            // try 块：文件操作（创建临时文件、读日志）可能抛 IOException，
            // 所以把核心逻辑都包在 try 里，出错统一在下面的 catch 处理。
            List<String> clean = stripNoElevate(originalArgs);
            // 第一步：先"洗"一下参数。把原始参数里的 --no-elevate 这个特殊
            // 参数剔除掉。为什么？因为 --no-elevate 的意思是"不要提升权限"，
            // 它只是给当前进程自己看的开关。如果把它原样传给提升后的子进程，
            // 子进程一看"哦，用户说了不要提升"，就又不提升了——这就闹鬼了，
            // 等于自己打自己脸。所以必须把它从参数列表里拿掉。
            List<String> cmd = buildElevatedCommand(clean);
            // 第二步：根据当前程序的运行方式（是打包好的 exe 还是 java -jar），
            // 拼出一条完整的"重新启动命令"。这条命令的格式大致是：
            //   [可执行程序, (JVM 参数...), 用户参数...]
            if (cmd == null) {
                // buildElevatedCommand 可能返回 null：意思是"实在搞不清楚
                // 当前程序到底是用什么方式启动的"（既找不到启动器 exe，
                // 也找不到 jpackage 打包路径，还找不到 java -jar 的 jar 包）。
                // 这种情况就没法自我提升了，只能老老实实告诉调用方"我做不到"。
                return new ElevResult(-1, null,
                        "Cannot determine how to relaunch this program elevated.");
                // 返回 exitCode=-1（失败），output=null（没有输出），
                // error=上面这句英文错误信息。
            }
            Path logFile = Files.createTempFile("systemwin-elev-", ".log");
            // 第三步：创建一个临时日志文件。createTempFile 会在系统的临时目录
            // （比如 C:\Users\xxx\AppData\Local\Temp）里生成一个文件，
            // 文件名形如 systemwin-elev-xxxxxxx.log，前缀是 systemwin-elev-，
            // 后缀是 .log，中间那段随机数是为了避免跟别的文件撞名。
            // 这个日志文件就是父进程和子进程之间的"传话窗口"：
            // 子进程把自己的输出写进去，父进程之后再读出来。
            Files.deleteIfExists(logFile);
            // 刚创建出来的是个"空文件"，先把它删掉，让文件"不存在"。
            // 为什么要多此一举？因为后面子进程要用"追加/新建"的方式写这个文件，
            // 如果文件已经存在，某些写入方式可能报错或者往里塞旧内容。
            // 先删掉，保证子进程是从零开始写，干干净净。

            // The child process argv is [exe, (jvm args...), __elevated, <log>,
            // user args...] — "__elevated" must be the first argument the JVM's
            // main() sees, so it is inserted after the JVM args (-jar <jar>).
            // ---------- 上面这段英文注释的中文解释 ----------
            // 子进程的完整命令行参数长这样：
            //   [exe, (jvm 参数...), __elevated, <日志文件>, 用户参数...]
            // 其中 __elevated 这个特殊的魔术参数，必须是 JVM 的 main() 方法
            // 看到的"第一个"参数（在 -jar <jar> 这种 JVM 参数之后插入），
            // 这样子进程启动后第一眼就能认出"哦，我是被提升的那个子进程"，
            // 从而知道自己该进入隐藏窗口 + 写日志的特殊模式。
            // 为什么必须是第一个？因为 main(String[] args) 收到的 args 数组里，
            // 程序通常按"第一个参数"来判断运行模式；如果不把 __elevated 放在
            // 最前面，子进程可能把它当成普通用户参数，就不会进入特殊模式了。
            int insertAt = cmd.size() - clean.size();
            // 计算插入位置：cmd 里前面是 [exe, (jvm 参数...)]，后面是用户参数。
            // cmd.size() - clean.size() 正好等于"JVM 参数区"的长度，
            // 也就是说 insertAt 指向"用户参数区"开始的位置，
            // 我们要把 __elevated 插在它前面。
            List<String> child = new ArrayList<>(cmd.subList(0, insertAt));
            // 先把 [exe, (jvm 参数...)] 这一截复制进新的列表 child。
            child.add("__elevated");
            // 插入魔术参数 __elevated，告诉子进程"你是我提升出来的特殊子进程"。
            child.add(logFile.toString());
            // 再插入日志文件路径，让子进程知道往哪个文件里写输出。
            child.addAll(cmd.subList(insertAt, cmd.size()));
            // 最后把用户参数（洗过的 clean 部分）也接上。
            // 至此，child 列表就是子进程要收到的完整命令行参数了。

            String script = "try {\n"
                    + "  $p = Start-Process -FilePath '" + esc(child.get(0)) + "'"
                    + " -ArgumentList '" + esc(quoteArgs(child.subList(1, child.size()))) + "'"
                    + " -Verb RunAs -WindowStyle Hidden -Wait -PassThru\n"
                    + "  'CODE=' + $p.ExitCode\n"
                    + "} catch { 'ERR=' + $_.Exception.Message }\n";
            // ---------- 上面这段 PowerShell 脚本在干什么？慢慢说 ----------
            // 我们要执行一段 PowerShell 脚本来真正触发 UAC 提升。逐行拆解：
            //   try { ... } catch { ... }   —— PowerShell 的异常处理：
            //      成功就执行 try 里的内容，出错就跳进 catch 打印错误。
            //   Start-Process -FilePath '可执行程序'
            //      用 PowerShell 的 Start-Process 命令启动一个进程，
            //      -FilePath 后面跟的是要运行的程序（比如 systemwin.exe）。
            //   -ArgumentList '参数们'
            //      传给那个程序的命令行参数（已经用 quoteArgs 拼接好了）。
            //   -Verb RunAs
            //      这一项是核心！-Verb RunAs 的意思就是"以管理员身份运行"，
            //      它会触发 Windows 的 UAC 弹窗。用户点"是"才继续，点"否"就报错。
            //   -WindowStyle Hidden
            //      子进程的窗口要隐藏起来，免得突然蹦出一个黑乎乎的
            //      控制台窗口吓到用户——反正它的输出会写进日志文件，不需要窗口。
            //   -Wait
            //      让 PowerShell 一直等着，直到被启动的进程结束才继续往下走。
            //   -PassThru
            //      把"被启动进程"这个对象返回出来，这样后面才能读 $p.ExitCode。
            //   'CODE=' + $p.ExitCode
            //      子进程结束后，把它的退出码拼成一行 "CODE=0" 这样的文本输出。
            //      父进程（也就是我们 Java 这边）就是靠扫描 "CODE=" 开头的行
            //      来拿到子进程退出码的。这相当于两个进程之间的"约定暗号"。
            //   catch { 'ERR=' + $_.Exception.Message }
            //      万一出错了（比如用户点了"否"、或者程序路径不存在），
            //      就把错误信息拼成 "ERR=xxx" 输出，同样是我们约定的暗号格式。
            // 另外注意 esc(...)：把单引号 ' 替换成两个单引号 ''，这是 PowerShell
            // 字符串的转义规则，防止路径里带单引号时把脚本语法搞坏。
            // The wait is bounded: the UAC prompt may be unanswered or the
            // session may be non-interactive (no consent UI available).
            // ---------- 英文注释翻译 ----------
            // 等待是有上限（超时）的：因为 UAC 弹窗可能一直没人点
            // （用户走开了），或者当前会话是非交互式的（比如无人值守的
            // 服务会话，根本没有桌面可以弹 UAC 窗）。
            // 如果不设超时，程序可能永远卡死在这里等一个永远不会来的点击，
            // 所以必须规定一个最长等待时间，超时就放弃。
            ProcessRunner.Result r = ProcessRunner.runPowershell(elevationTimeoutSeconds(), script);
            // 执行上面拼好的 PowerShell 脚本。注意第一个参数是超时秒数，
            // 由 elevationTimeoutSeconds() 计算（默认 180 秒，可用环境变量覆盖）。
            // 如果超时了，runPowershell 返回的 Result 里 timedOut() 会返回 true。

            String output = readAndDeleteLog(logFile);
            // 子进程应该已经写完了日志（无论成败它都会尽量写），
            // 这里把日志文件读出来，然后顺手把临时日志文件删掉，
            // 免得垃圾文件越堆越多。读到的内容就是子进程的完整输出。
            int code = -1;
            // code 先初始化为 -1（失败值）。如果后面在 PowerShell 的输出里
            // 找到了 "CODE=" 开头的行，就会用真正的退出码覆盖它。
            String err = null;
            // err 先初始化为 null（没有错误）。如果找到了 "ERR=" 开头的行就覆盖。
            for (String line : r.output().split("\\r?\\n")) {
                // 把 PowerShell 的输出按行拆开（\\r?\\n 能同时匹配
                // Windows 的 \r\n 和 Linux 的 \n 换行），逐行检查。
                if (line.startsWith("CODE=")) {
                    // 这一行是暗号 "CODE=xxx"：子进程的退出码。
                    code = parseInt(line.substring(5));
                    // substring(5) 就是把开头的 "CODE=" 这 5 个字符去掉，
                    // 剩下纯数字部分，再交给 parseInt 转成 int。
                } else if (line.startsWith("ERR=")) {
                    // 这一行是暗号 "ERR=xxx"：PowerShell 那边报错了，
                    // 错误信息就是去掉 "ERR=" 前缀后的内容。
                    err = line.substring(4);
                }
            }
            if (r.timedOut()) {
                // 如果 PowerShell 执行超时了：说明 UAC 弹窗一直没人理，
                // 或者这个会话根本弹不出 UAC 窗口。这时候不能继续等下去，
                // 直接按"失败"处理，把超时的原因告诉调用方。
                return new ElevResult(-1, output,
                        "Timed out waiting for the UAC prompt (no consent given, or the session cannot show one)");
                // 注意：这里仍然把已经读到的 output 传回去——
                // 就算超时，子进程可能也写了一点日志，能捞一点是一点。
            }
            if (code >= 0) {
                // 拿到了合法的退出码（>= 0 说明暗号解析成功，子进程确实跑完了）：
                // 这就属于"一切正常"的情况，把退出码和输出装进信封返回。
                return new ElevResult(code, output, null);
                // 错误信息是 null，因为没有错误。
            }
            return new ElevResult(-1, output, err == null ? r.output().trim() : err);
            // 走到这里说明：没超时，但也没拿到 CODE=（比如 PowerShell 脚本本身
            // 报错了，只输出了 ERR=）。这时候按失败处理：
            //   - 退出码固定 -1；
            //   - output 仍然带上（能有的信息都给你）；
            //   - 错误信息优先用解析出来的 err；如果连 err 都没有，
            //     那就把 PowerShell 的原始输出（去掉首尾空白）整个当错误信息，
            //     总比啥都没有强，好歹能让用户看到点线索。
        } catch (IOException e) {
            // 文件操作出错（比如临时日志文件创建失败、读日志失败）会走到这里。
            return new ElevResult(-1, null, e.getMessage());
            // 把异常的信息（e.getMessage()）塞进信封的 error 字段返回给调用方，
            // 让上层能看到"到底哪里出错了"。
        }
    }

    private static String readAndDeleteLog(Path logFile) throws IOException {
        // ---- 中文注释：这个小工具方法专门负责"读日志 + 删日志" ----
        // 干两件事：把子进程写好的日志文件内容读成字符串，然后删掉这个临时文件。
        // 参数 logFile：临时日志文件的路径。
        // 返回值：日志文件的全部内容；如果文件不存在则返回 null。
        // 注意 throws IOException：读文件可能失败（比如文件被占用），
        // 这个异常我们不在这里处理，抛给调用者去处理。
        if (!Files.isRegularFile(logFile)) {
            // isRegularFile 检查这个路径是不是一个"普通文件"（不是目录、不是符号链接）。
            // 如果文件不存在，就直接返回 null——没什么可读的，也别硬读报错。
            return null;
        }
        String output = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
        // Files.readAllBytes 把文件整个读成字节数组，再用 UTF-8 编码解码成字符串。
        // 必须显式指定 UTF-8：如果偷懒用默认编码，在中文 Windows 上很可能
        // 默认是 GBK，那样子进程写的中文日志就会变成一堆乱码。
        Files.deleteIfExists(logFile);
        // 读完就删：临时文件用完了就该清理，不留垃圾。
        // deleteIfExists 很贴心——就算文件已经被别人删了，它也不会报错。
        return output;
        // 把读到的内容返回给调用者。
    }

    /** Default 180s; override with SYSTEMWIN_ELEVATION_TIMEOUT (seconds). */
    // ---- 中文翻译：默认超时 180 秒；可以用环境变量 SYSTEMWIN_ELEVATION_TIMEOUT
    //      （单位：秒）覆盖这个默认值 ----
    // 这个方法的职责是：算出"等待 UAC 提升的超时时间"是多少秒。
    // 为什么超时时间要单独弄一个方法来算？因为它的取值规则值得单独说清楚：
    //   优先看环境变量（用户可以自定义），没设置或设置得不合法才用默认值 180。
    private static long elevationTimeoutSeconds() {
        String v = System.getenv("SYSTEMWIN_ELEVATION_TIMEOUT");
        // 去读操作系统的环境变量 SYSTEMWIN_ELEVATION_TIMEOUT。
        // 用户可以在系统设置里把它配成一个数字（单位是秒），
        // 比如配成 60 就表示"最多等 60 秒"。没配的话 getenv 返回 null。
        if (v != null) {
            // 只有当环境变量存在时才有解析的必要；不存在就直接用默认值。
            try {
                return Long.parseLong(v.trim());
                // 把字符串解析成 long 数字。先 trim() 去掉首尾空格，
                // 防止用户手滑在数字前后打了空格（" 60 " 也能被正确解析）。
            } catch (NumberFormatException e) {
                // fall through
                // （原英文注释保留）意思是"掉下去"、不处理，继续往下走。
                // 什么时候会走到这里？用户把环境变量配成了没法解析成数字的
                // 东西，比如 "abc" 或者空字符串。这种垃圾配置我们不理会，
                // 当作没配过，老老实实用下面的默认值 180。
            }
        }
        return 180;
        // 默认 180 秒（3 分钟）。为什么是 180？因为正常情况下用户点一下
        // UAC 弹窗只要几秒钟；但万一用户在看别的窗口，或者会话没法弹窗，
        // 给 3 分钟的余量比较合理——既不会让程序无限卡死，也不会因为
        // 时间太短而误杀正常的提升流程。
    }

    /**
     * Builds the full argv of the program that must be re-launched elevated:
     * {@code [exe, (jvm args...), user args...]}. The executable is the
     * single-exe launcher (syswin.launcher.path), the packaged systemwin.exe
     * (jpackage.app-path), or {@code java.exe -jar <systemwin.jar>} when
     * running from a jar.
     */
    // ---- 中文翻译：拼出"需要以管理员身份重新启动的程序"的完整命令行 ----
    // 返回的列表形如 [可执行程序, (JVM 参数...), 用户参数...]。
    // 到底用哪个可执行程序来重启，按优先级依次尝试三种情况：
    //   1) 单文件启动器（single-exe launcher）：系统属性 syswin.launcher.path；
    //   2) 打包好的 systemwin.exe：系统属性 jpackage.app-path（jpackage 打包后
    //      会把这个属性写进程序里，指向打包出来的 exe 的绝对路径）；
    //   3) 以上都没有时，退回用 java.exe -jar <systemwin.jar> 来跑。
    // 三种方案都试过还不行，就返回 null，让调用方报"无法确定如何重启"。
    // 为什么要搞这么多花活？因为同一个程序可能以好几种形态被启动：
    // 开发时用 IDE 跑、发布时打包成 exe、或者别人直接 java -jar 运行。
    // 程序得"随机应变"，认出自己现在是哪种形态，才能正确地重启自己。
    private static List<String> buildElevatedCommand(List<String> clean) {
        List<String> cmd = new ArrayList<>();
        // cmd 就是我们要拼出来的结果列表，先建一个空的，后面按情况往里塞东西。
        String launcher = System.getProperty("syswin.launcher.path");
        // 尝试方案 1：读取系统属性 syswin.launcher.path。
        // 如果程序是被"单文件启动器"（一个自定义的小 exe 包装器）启动的，
        // 启动器会把这个系统属性设成它自己的路径。
        if (launcher != null && !launcher.isEmpty()
                && Files.isRegularFile(Paths.get(launcher))) {
            // 判断条件：属性不是 null、不是空字符串、而且那个路径确实存在
            // （isRegularFile 返回 true 才是真文件）。三个条件都满足才算数。
            cmd.add(launcher);
            // 方案 1 成立：第一个参数就是启动器 exe 的路径。
            cmd.addAll(clean);
            // 把用户参数原样接在后面（注意：传进来的是已经去掉 --no-elevate 的 clean）。
            return cmd;
            // 拼好了，直接返回。
        }
        String appPath = System.getProperty("jpackage.app-path");
        // 尝试方案 2：读取系统属性 jpackage.app-path。
        // 如果程序是用 jpackage 工具打包成原生安装包的（比如 systemwin-1.0.exe
        // 安装后生成的 systemwin.exe），运行时这个属性会指向那个 exe 的路径。
        if (appPath != null && !appPath.isEmpty() && Files.isRegularFile(Paths.get(appPath))) {
            // 同样的三连检查：非 null、非空、文件真实存在。
            cmd.add(appPath);
            // 方案 2 成立：第一个参数就是打包好的 exe。
            cmd.addAll(clean);
            // 接上用户参数。
            return cmd;
        }
        String javaHome = System.getProperty("java.home");
        // 尝试方案 3 的准备：拿到 JVM 的安装目录（比如 C:\Program Files\Java\jdk-17）。
        String classPath = System.getProperty("java.class.path", "");
        // 拿到 classpath（类路径）。如果程序是用 java -jar 跑的，
        // classpath 里一般就只有一个 jar 包——也就是程序自己。
        String jar = null;
        // jar 先置为 null，表示"还没找到 jar 包"。
        for (String entry : classPath.split(File.pathSeparator)) {
            // 把 classpath 按路径分隔符（Windows 上是分号 ;）拆开，逐个检查。
            // classpath 里可能有很多条目，我们只需要找出"那个 jar 包"。
            if (entry.endsWith(".jar") && Files.isRegularFile(Paths.get(entry))) {
                // 找到第一个"以 .jar 结尾"且"确实是个文件"的条目，
                // 它就是我们要找的程序 jar 包。
                jar = entry;
                // 记下来。
                break;
                // 找到就立刻跳出循环，后面的条目不用看了。
            }
        }
        if (javaHome != null && jar != null) {
            // 方案 3 的条件：既知道 java 的安装目录，又找到了 jar 包，
            // 两者缺一不可——缺一个都没法拼出 java -jar 命令。
            cmd.add(Paths.get(javaHome, "bin", "java.exe").toString());
            // 用 Paths.get 把 java 安装目录、bin、java.exe 三段拼成完整路径：
            // 例如 C:\Program Files\Java\jdk-17\bin\java.exe。
            // 为什么不用裸的 "java" 命令？因为裸 java 依赖系统 PATH 环境变量，
            // 万一 PATH 里没有 java（比如用户只装了 JRE 没配 PATH），
            // 提升后的子进程就可能找不到 java 而启动失败。
            // 直接用绝对路径最保险，不依赖任何环境变量。
            cmd.add("-jar");
            // -jar 是 java 命令的参数：告诉 JVM "我要运行一个 jar 包"。
            cmd.add(jar);
            // jar 包的路径。
            cmd.addAll(clean);
            // 最后接上用户参数。
            return cmd;
        }
        return null;
        // 三种方案全都没戏，返回 null。调用方（elevate 方法）看到 null
        // 就知道"没法自我提升了"，会返回一条友好的错误信息。
    }

    private static List<String> stripNoElevate(String[] args) {
        // ---- 中文注释：过滤参数，把 --no-elevate 从参数列表里剔除 ----
        // 为什么要有这个过滤？因为 --no-elevate 是给"当前这个进程"看的开关，
        // 意思是"这次运行不要自我提升"。但当我们已经决定要提升时，
        // 这个开关就不能再传给子进程了——否则子进程会误以为用户不想提升，
        // 从而拒绝进入管理员模式，那整个提升就白费了。
        // 相当于：你决定要去开第二道门了，就不能再带着"我不进门"的纸条。
        List<String> out = new ArrayList<>();
        // 新建一个干净的列表，只装"保留下来"的参数。
        for (String a : args) {
            // 逐个检查传入的每个参数。
            if (!"--no-elevate".equals(a)) {
                // 如果当前参数不是 "--no-elevate" 这个特殊开关，就保留。
                // 注意写法是 "--no-elevate".equals(a) 而不是 a.equals("--no-elevate")：
                // 因为 a 有可能是 null，null.equals(...) 会直接抛空指针异常，
                // 而字符串常量调用 equals 永远不会出事——这是 Java 里一个
                // 防御性的小技巧，老手都这么写。
                out.add(a);
                // 保留：装进干净的列表。
            }
            // 如果参数正好是 "--no-elevate"，就什么都不做，等于把它丢掉了。
        }
        return out;
        // 返回过滤后的参数列表。
    }

    /** Joins args as a Start-Process -ArgumentList value, quoting each token. */
    // ---- 中文翻译：把参数列表拼成一个字符串，作为 PowerShell 的
    //      Start-Process -ArgumentList 的值，每个参数都用双引号包起来 ----
    // 举个例子就懂了：假设参数是 ["a", "b c", "d"]，
    // 拼出来的结果就是 "a" "b c" "d"。
    // 为什么每个参数都要加双引号？因为参数里可能带空格（比如路径
    // "C:\Program Files\xxx"），如果不加引号，PowerShell 就会把
    // "C:\Program" 和 "Files\xxx" 当成两个独立的参数，参数就错位了。
    private static String quoteArgs(List<String> args) {
        StringBuilder sb = new StringBuilder();
        // StringBuilder 是拼接字符串的"高效工具"。
        // 为什么不用 sb = sb + "..."？因为 String 是不可变的，每拼一次
        // 都会新建一个字符串对象，参数多了性能很差；StringBuilder 是
        // 可变的"积木"，拼起来又快又不浪费内存。这是性能上的讲究。
        for (String a : args) {
            // 遍历每一个参数。
            if (sb.length() > 0) {
                // 如果不是第一个参数（前面已经拼过东西了），
                // 就先加一个空格，把参数之间隔开。
                sb.append(' ');
            }
            sb.append('"').append(a).append('"');
            // 给参数前后各包一个双引号：开头一个 "，参数内容，结尾一个 "。
        }
        return sb.toString();
        // 把拼好的积木转成最终的字符串返回。
    }

    private static String esc(String s) {
        // ---- 中文注释：PowerShell 字符串转义工具 ----
        // 我们拼 PowerShell 脚本时，路径是用单引号 'xxx' 包起来的。
        // 但如果路径里本身含有单引号（虽然罕见，但 Windows 文件名确实
        // 允许单引号），就会把脚本语法搞坏。PowerShell 的规则是：
        // 单引号字符串里要表示一个单引号，就写两个单引号 ''。
        // 所以这里把所有 ' 替换成 ''，保证路径再奇葩也不会破坏脚本。
        return s.replace("'", "''");
        // 一行搞定：replace 返回替换后的新字符串。
    }

    private static int parseInt(String s) {
        // ---- 中文注释：把字符串安全地解析成整数 ----
        // 这个方法的特殊之处在于"安全"二字：普通的 Integer.parseInt
        // 遇到非数字内容（比如 "abc"）会直接抛 NumberFormatException，
        // 让程序崩溃。而我们这里解析的是子进程传回来的退出码，
        // 内容不可控（万一子进程乱写呢？），所以必须包一层保护：
        // 解析失败就返回 -1（当作失败处理），绝不让异常冒出去。
        try {
            return Integer.parseInt(s.trim());
            // 先去掉首尾空白再解析（防 "0 " 这种带空格的输入），
            // 成功就返回解析出来的整数。
        } catch (NumberFormatException e) {
            // 解析失败（s 不是合法数字）时捕获异常。
            return -1;
            // 返回 -1 表示"解析失败"。调用方看到 -1 就知道退出码不合法。
        }
    }
}
// ============================================================================
// 文件结尾的废话总结
// ----------------------------------------------------------------------------
// 把整个类再串一遍，帮助初学者建立整体印象：
//   1. isElevated()        —— 问：我现在是管理员吗？（通过 PowerShell 问 Windows）
//   2. elevate(args)       —— 答：不是的话，就把自己重启成管理员。
//                             流程：洗参数 → 拼启动命令 → 建临时日志 →
//                                   PowerShell 弹 UAC → 等子进程 → 读日志 → 返回。
//   3. 一堆 private 小帮手 —— stripNoElevate 洗参数、buildElevatedCommand 拼命令、
//      quoteArgs/esc 处理 PowerShell 字符串、parseInt 安全解析、readAndDeleteLog
//      读删日志、elevationTimeoutSeconds 计算超时。
// 整个设计思路就是"父进程与子进程通过一个临时日志文件 + 两行暗号（CODE=/ERR=）
// 来通信"，没有用复杂的进程间通信机制，简单可靠，也方便排查问题。
// ============================================================================
