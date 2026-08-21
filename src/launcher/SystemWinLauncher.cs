using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text;

/*
 * ============================================================================
 * 下面的内容是这个文件的"开场白"，也就是整个文件的说明文档。
 * 我们先把话说清楚：这个文件到底是干什么用的？
 *
 * SystemWin 最终交付给用户的是一个"单文件"的 systemwin.exe，
 * 也就是说用户手上只有这一个 exe，别的一堆 runtime、jar 什么的都没有。
 * 那这个 exe 里面到底装了些什么呢？它的物理结构是这样的（从前往后）：
 *
 *     [本启动器程序的机器码][zip 压缩包（里面是 runtime/ 和 app/）][文件尾标记]
 *
 * 其中"文件尾标记"（footer）是固定 12 个字节：
 *     前 4 个字节是魔法字符串 "SWX1"，用来确认"这确实是我们 SystemWin 打包出来的 exe"；
 *     后 8 个字节是一个 64 位整数，记录 zip 压缩包一共占了多少字节（payloadLen）。
 *
 * 为什么要这么设计呢？因为把 zip 包"附加"在 exe 后面是最简单的自解压方案——
 * exe 文件本身就是一个合法的可执行文件，Windows 会正常运行前面的机器码部分，
 * 而 zip 数据只是静静地躺在文件末尾，既不碍事也不会让 exe 无法运行。
 *
 * 第一次运行（或者发现 zip 内容变了）的时候，本启动器会把 zip 解压到
 * %LOCALAPPDATA%\SystemWin\run 这个目录下，并且写一个标记文件（.version）。
 * 以后再运行，就直接用解压出来的内置 Java 运行时（java.exe）去启动
 * app/systemwin.jar，命令行参数原封不动地透传过去，进程的退出码也原样返回。
 *
 * 说白了：这个启动器就是"负责把环境准备好 + 把 Java 程序拉起来"的门卫大爷。
 * ============================================================================
 */

/// <summary>
/// SystemWin single-exe launcher.
///
/// The deliverable is one self-contained systemwin.exe built as:
///   [this launcher][zip payload: runtime/ + app/][footer: "SWX1" + payloadLen(8)]
///
/// On the first run (or when the payload changes) the zip payload is extracted
/// to %LOCALAPPDATA%\SystemWin\run and a marker is written. Subsequent runs
/// launch the embedded Java runtime directly, passing every command-line
/// argument and the process exit code through unchanged.
/// </summary>
/*
 * 上面那段英文注释我们用中文再啰嗦一遍（顺便补充一些细节）：
 *
 * 1. 这个类是 SystemWin 的"单文件启动器"。
 * 2. 交付物是一个自包含的 systemwin.exe，结构是：
 *        [启动器本体][zip 载荷：runtime/ + app/][文件尾：魔法串 "SWX1" + 8 字节的载荷长度]
 * 3. 第一次运行（或者载荷内容发生变化）时，把 zip 解压到
 *    %LOCALAPPDATA%\SystemWin\run 目录，并写入一个标记文件（.version）。
 *    标记文件里存的是"这次解压出来的载荷长度"，下次一对比就知道要不要重新解压。
 * 4. 之后的每一次运行都直接启动内置的 Java 运行时，
 *    把用户在命令行敲的所有参数原封不动地传给 Java 程序，
 *    等 Java 程序跑完后，把它返回的退出码原样返回给操作系统。
 *
 * 记住一个核心思想：这个类不干任何"业务活"，它只负责两件事——
 * 解压环境 + 拉起子进程。真正的业务逻辑全在 systemwin.jar 里面。
 */
class SystemWinLauncher
{
    // 魔法字符串：写在文件末尾前 4 个字节，用来识别"这是我们打包出来的 exe"。
    // 为什么叫 MAGIC？因为程序里检查文件格式时通常用一个固定字符串当"暗号"，
    // 对不上暗号就说明这个文件不是我们想要的文件，直接报错。
    private const string MAGIC = "SWX1";

    // 文件尾总共占 12 个字节 = 4 字节的 MAGIC + 8 字节的载荷长度（long）。
    // 这个数字在好几个地方都要用，所以提出来做成常量，避免到处写魔法数字 12。
    private const int FOOTER_SIZE = 12;

    /*
     * AppDir()：算出"解压目标目录"到底在哪。
     *
     * 我们没有把解压目录随便放在 exe 旁边，而是放在 Windows 的
     * %LOCALAPPDATA% 目录下面（对普通用户来说通常是
     * C:\Users\<用户名>\AppData\Local）。
     *
     * 为什么放这里？因为：
     * 1. exe 可能被装在 Program Files 里，普通用户没有写权限，解压会失败；
     * 2. 这个目录是"每用户"的，不会影响别的用户，也不需要管理员权限；
     * 3. 它是专门给程序存放"运行期生成的数据"用的标准位置，约定俗成。
     *
     * 返回值是 ...\Local\SystemWin\run 这个完整路径。
     * 注意：这个方法只是"算出路径"，并不保证目录已经存在，
     * 目录的创建是在 ExtractPayload 里面做的。
     */
    private static string AppDir()
    {
        return Path.Combine(
            // 先取系统的 LocalApplicationData 目录（就是 %LOCALAPPDATA%）
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "SystemWin", "run");
    }

    /*
     * PayloadLength(self)：读取当前 exe 文件末尾的 footer，返回 zip 载荷的长度。
     *
     * 参数 self 就是"当前正在运行的这个 exe 文件的完整路径"。
     * 实现思路：
     * 1. 打开自己这个文件；
     * 2. 定位到"文件末尾往前数 FOOTER_SIZE(12) 个字节"的位置；
     * 3. 把最后 12 个字节读出来；
     * 4. 检查前 4 个字节是不是魔法串 "SWX1"，不是就抛异常（说明这个 exe
     *    不是我们打包的，或者文件被破坏了）；
     * 5. 如果是，就把第 4 到第 11 个字节（共 8 个字节）当成一个 long 读出来，
     *    这个 long 就是 zip 压缩包在文件里占的字节数。
     *
     * 有个小细节：文件的写入端（打包脚本）和读取端（这里）必须用同样的
     * 字节序（小端序，BitConverter 在 x86/x64 上默认就是小端），
     * 否则读出来的长度就是错的，解压的时候会乱套。
     */
    private static long PayloadLength(string self)
    {
        using (FileStream fs = File.OpenRead(self))
        {
            // 分配一个刚好能装下整个 footer 的缓冲区（12 字节）
            byte[] tail = new byte[FOOTER_SIZE];
            // 把文件指针挪到"最后 12 个字节"的起始位置
            fs.Seek(fs.Length - FOOTER_SIZE, SeekOrigin.Begin);
            // 把最后 12 个字节读进缓冲区
            fs.Read(tail, 0, FOOTER_SIZE);
            // 前 4 个字节必须是 "SWX1"，否则说明这不是一个合法的 SystemWin 打包文件
            if (Encoding.ASCII.GetString(tail, 0, 4) != MAGIC)
            {
                throw new InvalidDataException("SystemWin launcher: payload header not found.");
            }
            // 从第 4 个字节开始，连续 8 个字节拼成一个 long，这就是载荷长度
            return BitConverter.ToInt64(tail, 4);
        }
    }

    /*
     * NeedsExtract(appDir, payloadLen)：判断"这次要不要重新解压"。
     *
     * 规则很简单：
     * 1. 如果解压目录里没有 .version 标记文件（说明从没解压过），返回 true，要解压；
     * 2. 如果 .version 文件存在但读不出来（比如文件被占用、损坏），保守起见也返回 true；
     * 3. 如果读出来了，但内容跟当前的载荷长度不一致，说明 exe 换过新版本了
     *    （zip 大小变了），返回 true，要重新解压；
     * 4. 只有"标记文件存在且内容正好等于 payloadLen"时才返回 false，跳过解压。
     *
     * 为什么用"载荷长度"当版本号，而不是用一个真正的版本号字符串？
     * 因为打包脚本不需要额外维护版本号——exe 文件只要重新打过包，zip 的大小
     * 几乎必然变化（哪怕只有 1 个字节不同），长度一变就自然触发重新解压。
     * 这是最简单的"内容指纹"方案，够用且不易出错。
     */
    private static bool NeedsExtract(string appDir, long payloadLen)
    {
        // 标记文件的完整路径：<appDir>\.version
        string marker = Path.Combine(appDir, ".version");
        // 情况一：标记文件根本不存在 → 肯定没解压过，必须解压
        if (!File.Exists(marker))
        {
            return true;
        }
        string saved = "";
        try
        {
            // 把标记文件里的内容读出来，顺便去掉首尾空白字符
            saved = File.ReadAllText(marker).Trim();
        }
        catch (IOException)
        {
            // 情况二：文件读不出来（可能正被别的进程锁着）。
            // 这里选择"宁可信其有不可信其无"，干脆重新解压，
            // 反正 ExtractPayload 里对已存在的目录有清理逻辑。
            return true;
        }
        // 情况三/四：比较"上次记录的载荷长度"和"这次 exe 里的实际载荷长度"。
        // 注意这里是比较字符串（saved != payloadLen.ToString()），
        // 因为存的时候就是按字符串存的，直接比较字符串最省事。
        return saved != payloadLen.ToString();
    }

    /*
     * ExtractPayload(self, appDir, payloadLen)：真正干重活的方法——解压。
     *
     * 步骤拆开讲：
     * 1. 在系统临时目录（%TEMP%）里造一个临时 zip 文件名，
     *    名字里带一个 Guid 随机数，保证不会跟别人的临时文件撞车；
     * 2. 打开自己这个 exe，算出 zip 数据从哪里开始：
     *       文件总长度 - 12(文件尾) - payloadLen(载荷长度) = zip 起始偏移
     *    然后把从偏移到文件末尾之间的 payloadLen 个字节，原样复制到临时 zip 文件里。
     *    为什么要先复制出来？因为 ZipFile.ExtractToDirectory 需要一个"zip 文件路径"，
     *    它没法直接从 exe 文件的中间一段去读，所以只能先抠出来存成临时文件；
     * 3. 如果目标目录 appDir 已经存在，先尝试整体删除（里面可能是旧版本的文件）；
     *    如果删不掉（比如 SystemWin 还在这个目录里运行，文件被锁住），就忽略这个错误，
     *    让后面的 ExtractToDirectory 失败时给出清晰的报错信息；
     * 4. 重新创建 appDir，把临时 zip 解压进去；
     * 5. 最后写 .version 标记文件，记下"这次的载荷长度"，下次就不用再解压了；
     * 6. 无论成功还是失败，finally 里都会尝试删除临时 zip 文件，不留垃圾。
     *
     * 一个值得注意的点：解压前先删掉旧目录，是为了避免"新旧文件混在一起"。
     * 如果只是覆盖式解压，旧版本里多出来的文件会残留下来，可能导致程序用错文件。
     */
    private static void ExtractPayload(string self, string appDir, long payloadLen)
    {
        // 在临时目录里生成一个唯一的 zip 文件名，Guid.ToString("N") 会得到
        // 32 位不带连字符的十六进制字符串，碰撞概率可以忽略不计
        string tmpZip = Path.Combine(Path.GetTempPath(),
            "systemwin-" + Guid.NewGuid().ToString("N") + ".zip");
        try
        {
            using (FileStream fs = File.OpenRead(self))
            {
                // 计算 zip 数据的起始偏移：文件长度 - 文件尾(12) - 载荷长度
                // 打个比方：一列火车，车头是启动器代码，车尾是 12 字节的 footer，
                // 中间夹着的 payloadLen 个字节就是我们要的 zip 车厢。
                long zipOff = fs.Length - FOOTER_SIZE - payloadLen;
                // 偏移量不可能是负数；如果是负数，说明 payloadLen 比文件本身还大，
                // 文件肯定被破坏了，直接报错，免得下面越界读取。
                if (zipOff < 0)
                {
                    throw new InvalidDataException("SystemWin launcher: bad payload size.");
                }
                // 把文件指针挪到 zip 数据开始的位置
                fs.Seek(zipOff, SeekOrigin.Begin);
                // 打开临时 zip 文件（不存在就创建，可写）
                using (FileStream outFs = new FileStream(tmpZip, FileMode.Create, FileAccess.Write))
                {
                    // 用 1MB 的缓冲区一块一块地拷贝，避免一次性读超大文件撑爆内存
                    byte[] buf = new byte[1 << 20];
                    long remaining = payloadLen;
                    // 循环拷贝，直到把 payloadLen 个字节全部拷完
                    while (remaining > 0)
                    {
                        // 每次最多读 buf.Length 个字节，但也不能超过"还剩多少"
                        int n = fs.Read(buf, 0, (int)Math.Min(buf.Length, remaining));
                        // n <= 0 表示读到了文件结尾（正常情况不该发生，防御性检查）
                        if (n <= 0)
                        {
                            break;
                        }
                        outFs.Write(buf, 0, n);
                        remaining -= n;
                    }
                }
            }
            // 目标目录若已存在（可能是上次运行留下的旧版本），先整体删掉
            if (Directory.Exists(appDir))
            {
                try
                {
                    // true 表示"连里面的子目录和文件一起递归删除"
                    Directory.Delete(appDir, true);
                }
                catch (IOException)
                {
                    // locked (SystemWin still running from this dir); extraction below fails with a clear message
                    // 目录被锁住删不掉（多半是 SystemWin 进程还从这个目录里跑着）。
                    // 这里故意吞掉异常：让下面的解压步骤自然失败，报错信息更明确，
                    // 总比在这里报一个"目录删不掉"让人摸不着头脑强。
                }
            }
            // 重新创建干净的目录，再把临时 zip 解压进去
            Directory.CreateDirectory(appDir);
            ZipFile.ExtractToDirectory(tmpZip, appDir);
            // 解压成功！写 .version 标记，记录这次的载荷长度。
            // 注意：标记文件必须最后写，只有"解压成功"才写，
            // 这样如果中途失败，下次运行会因为没有标记而重新解压，实现自动恢复。
            File.WriteAllText(Path.Combine(appDir, ".version"), payloadLen.ToString());
        }
        finally
        {
            // finally 保证无论成功失败都执行：清理临时 zip 文件，不留垃圾
            try
            {
                File.Delete(tmpZip);
            }
            catch (IOException)
            {
                // 删不掉就算了（可能被杀毒软件暂时占用），
                // 系统临时目录里的残留文件不影响正确性，下次清理系统时会处理。
            }
        }
    }

    /*
     * QuoteArg(a)：给命令行参数加上双引号，并且把参数里的双引号转义。
     *
     * 为什么要这么做？因为我们把所有参数拼成一个大字符串，然后用
     * ProcessStartInfo.Arguments 一次性传给子进程。如果某个参数里含有空格
     * （比如路径 "C:\Program Files\..."），不加引号的话，子进程会把一个参数
     * 拆成两个参数，程序就找不到文件了。
     *
     * 转义规则：把参数里的 " 全部替换成 \"，再整体包上一对 "。
     * 这跟大多数命令行工具的做法一致，能覆盖绝大多数正常参数。
     * （真正的完美转义其实是门玄学，Windows 的命令行解析规则非常绕，
     * 但对于我们这个场景——路径和普通参数——这个实现已经够用了。）
     */
    private static string QuoteArg(string a)
    {
        return "\"" + a.Replace("\"", "\\\"") + "\"";
    }

    /*
     * Main(args)：整个程序的入口，操作系统启动 systemwin.exe 后第一个执行的就是它。
     *
     * 整个流程可以概括为"三步走"：
     * 第一步：检查是不是"内部模式"（--host 参数）——如果是，就变身成服务宿主进程，
     *         把控制权交给 SystemWinHost.Run 去处理 Windows 服务逻辑；
     * 第二步：读取自己 exe 文件尾部的载荷信息，判断是否需要解压环境，
     *         需要的话就解压到 %LOCALAPPDATA%\SystemWin\run；
     * 第三步：拼好 java.exe 的命令行，启动 Java 进程（systemwin.jar），
     *         等它跑完，把它返回的退出码原样返回给系统。
     *
     * 返回值约定：0 表示成功，1 表示出错（环境准备失败 / 文件缺失 / 启动失败）。
     * 这样外部脚本（比如批处理、服务管理器）就可以根据退出码判断成败。
     */
    public static int Main(string[] args)
    {
        // Internal mode: run as the service host (started by the Service
        // Control Manager as: systemwin.exe --host <service-name>).
        // 内部模式：以"服务宿主"身份运行。
        // 当 Windows 服务管理器（SCM）要启动 SystemWin 服务时，
        // 会以 "systemwin.exe --host <服务名>" 的形式调用我们。
        // 此时我们不再扮演"普通启动器"的角色，而是直接进入服务托管逻辑，
        // 把服务名交给 SystemWinHost.Run 去处理（那是另一套代码了）。
        if (args.Length >= 2 && args[0] == "--host")
        {
            return SystemWinHost.Run(args[1]);
        }

        // 判断当前系统的 UI 语言是不是中文（zh）。
        // 用途：决定提示信息显示中文还是英文。
        // TwoLetterISOLanguageName 会返回类似 "zh"、"en" 这样的两位语言代码。
        bool zh = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "zh";

        // 拿到"当前正在运行的这个 exe"的完整路径。
        // 注意：Assembly.Location 在正常情况下返回 exe 的绝对路径，
        // 这是后面读取自身文件尾部、找 zip 数据的前提。
        string self = Assembly.GetExecutingAssembly().Location;
        // 算出解压目标目录（%LOCALAPPDATA%\SystemWin\run），此时目录还不一定存在
        string appDir = AppDir();

        // 读取 exe 尾部的载荷长度。如果失败（比如文件根本不是 SystemWin 打包的），
        // 打印错误信息并以退出码 1 结束——这时候什么都干不了，不如干脆退出。
        long payloadLen;
        try
        {
            payloadLen = PayloadLength(self);
        }
        catch (Exception e)
        {
            Console.Error.WriteLine(e.Message);
            return 1;
        }

        // 核心判断：需不需要解压？（首次运行 / 载荷变了 / 标记丢了 → 需要）
        if (NeedsExtract(appDir, payloadLen))
        {
            // 需要解压时，先告诉用户"我在准备环境"，避免用户以为程序卡死了。
            // 这里根据系统语言选择中文或英文提示。
            Console.WriteLine(zh
                ? "SystemWin: 正在准备运行环境（首次运行），请稍候..."
                : "SystemWin: preparing runtime on first run...");
            try
            {
                ExtractPayload(self, appDir, payloadLen);
            }
            catch (Exception e)
            {
                // 解压失败：可能是磁盘满了、目录被锁、zip 损坏等等。
                // 把具体原因告诉用户（按语言切换提示文案），然后以退出码 1 结束。
                Console.Error.WriteLine(zh
                    ? "SystemWin: 准备运行环境失败：" + e.Message
                    : "SystemWin: failed to prepare runtime: " + e.Message);
                return 1;
            }
        }

        // 解压完成（或本来就不用解压）之后，确定 Java 运行时和 jar 包的位置。
        // 内置 Java 的目录结构是固定的：runtime/bin/java.exe 和 app/systemwin.jar。
        string java = Path.Combine(appDir, "runtime", "bin", "java.exe");
        string jar = Path.Combine(appDir, "app", "systemwin.jar");
        // 防御性检查：万一这两个文件不存在（比如用户手贱把解压目录删了一半，
        // 或者杀毒软件误删了文件），就提示用户删掉整个目录重新来一次。
        // 提示里直接给出了解决办法："Delete that folder and run again"。
        if (!File.Exists(java) || !File.Exists(jar))
        {
            Console.Error.WriteLine("SystemWin: runtime files missing (" + appDir
                + "). Delete that folder and run again.");
            return 1;
        }

        // 开始拼 Java 的命令行参数。用 StringBuilder 而不是直接字符串相加，
        // 是因为要循环拼接多个参数，StringBuilder 效率更高（避免生成大量中间字符串）。
        StringBuilder sb = new StringBuilder();
        // 关键一步：把我们自己（启动器 exe）的路径通过系统属性
        // -Dsyswin.launcher.path 传给 Java 程序。为什么？
        // 因为 Java 程序可能需要知道"当初是从哪个 exe 启动的"，
        // 比如用于定位自身、自我更新、或者显示路径等场景。
        // 路径里有空格所以要套上引号（QuoteArg 干的活）。
        sb.Append("-Dsyswin.launcher.path=").Append(QuoteArg(self));
        // 用 -jar 方式启动 systemwin.jar，jar 路径同样要加引号
        sb.Append(" -jar ").Append(QuoteArg(jar));
        // 把用户在命令行给我们的每一个参数，原封不动地依次拼到 Java 的命令行后面。
        // 注意是"透传"：我们不做任何解释、过滤或改写，用户敲了什么就传什么。
        foreach (string a in args)
        {
            sb.Append(' ').Append(QuoteArg(a));
        }

        // 准备进程启动信息：指定要运行的程序（java.exe）和参数。
        ProcessStartInfo psi = new ProcessStartInfo();
        psi.FileName = java;
        psi.Arguments = sb.ToString();
        // UseShellExecute = false 表示"直接用 CreateProcess 启动"，不用经过
        // Windows 的 shell（explorer 那套）。好处是：
        // 1. 不会弹出多余的窗口/关联程序；
        // 2. 我们能拿到标准输出和退出码；
        // 3. 不会受"文件关联被改"之类的影响。
        psi.UseShellExecute = false;
        try
        {
            // 启动 Java 子进程，然后阻塞等待它结束。
            // 这一步相当于"我们把自己变成 Java 程序的替身"：
            // 用户在命令行敲 systemwin.exe 等到的结果，就是 Java 程序跑完的结果。
            Process p = Process.Start(psi);
            p.WaitForExit();
            // 把 Java 程序的退出码原样返回给系统。
            // 这样外层脚本判断 systemwin.exe 的成功失败，等价于判断 Java 程序的成功失败。
            return p.ExitCode;
        }
        catch (Exception e)
        {
            // 启动失败（比如 java.exe 被误删、权限不足等），
            // 打印错误信息并以退出码 1 结束。
            Console.Error.WriteLine("SystemWin: failed to start: " + e.Message);
            return 1;
        }
    }
}
