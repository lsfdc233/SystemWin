package com.systemwin.service;

import com.systemwin.I18n;
import com.systemwin.util.ProcessRunner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Talks to the Windows Service Control Manager.
 *
 * <p>Queries use {@code Get-CimInstance Win32_Service} (locale-independent
 * schema values). Mutating operations use {@code sc.exe} and {@code
 * New-Service} through PowerShell's {@code -EncodedCommand}, which avoids all
 * command-line quoting problems.
 */
// =====================================================================
// 【类的中文解释】这个类到底是干什么的？慢慢听我讲，别着急。
// =====================================================================
// 在 Windows 操作系统里，有一个很厉害的系统组件，名字叫"服务控制管理器"
// （Service Control Manager，简称 SCM）。你可以把它想象成 Windows 世界的
// "大管家"：电脑上那些在后台默默运行的服务程序（比如数据库服务、打印服务、
// 远程桌面服务等等）都由它统一登记、统一管理。谁想查一个服务的信息？谁想
// 把一个服务启动起来或者停掉？谁想删除一个服务？都得找这个大管家。
//
// 而我们这个 SystemWin 程序（一个跨平台的 Java 程序）偏偏需要做这些事：
// 查服务、列服务、启动、停止、删除、改启动方式、创建服务。问题来了——Java
// 语言本身是跨平台的，它压根不知道 Windows 服务是个什么东西，更不会说
// Windows 的"方言"。那怎么办呢？聪明的做法就是"曲线救国"：我们不直接去
// 调用 Windows 的底层 API，而是把要做的事写成一段 PowerShell 脚本，然后
// 借 ProcessRunner 这个工具类去执行 PowerShell，让 PowerShell 替我们去跟
// SCM 打交道，我们只要把"脚本"拼好发出去，再把结果读回来就行了。
//
// 这就好比：你想让一个只说中文的人帮你去跟一个只说英语的人沟通，你自己
// 不会英语，于是你雇了一个翻译（PowerShell），你把想说的话告诉翻译，翻译
// 再帮你转达给对方（Windows），最后翻译再把对方的回答翻回来给你听。
//
// 具体的技术选型（为什么这么选，都是踩过坑总结出来的）：
// 1. 【查询类操作】用 Get-CimInstance Win32_Service 这个 PowerShell 命令。
//    为什么用它而不用更老的 Get-Service 呢？因为 CIM 查询返回的字段名是
//    "与系统语言无关"的，不管用户电脑是中文系统、英文系统还是日文系统，
//    返回的字段永远叫 Name、DisplayName、State 这些英文名，我们 Java 这边
//    解析起来就不用担心"这台机器上叫 State，那台机器上叫别的名字"的坑。
// 2. 【修改类操作】用 sc.exe（Windows 自带的老牌服务管理命令行工具）和
//    New-Service（PowerShell 里创建服务的命令）。而且关键是：整个脚本是
//    通过 PowerShell 的 -EncodedCommand 参数传进去的，意思是先把脚本内容
//    编码（转成 Base64）再传给 PowerShell 去执行，这样就彻底避免了命令行
//    里引号、空格、反斜杠、特殊字符互相打架的转义地狱。以前直接拼命令行
//    的时候，服务名里只要带个空格或者引号就全乱套了，现在再也不用担心了。
// =====================================================================
public final class WindowsServiceManager {

    // =================================================================
    // 【ActionResult 记录类型的中文解释】
    // 这个 record（记录类）是 Java 16 之后新增的语法糖，专门用来装"一个
    // 操作的结果"。它有三个字段，我们来一个个看：
    //  - ok      : 布尔值，这个操作到底成没成功。true 就是成功，false 就是
    //              失败。这是给调用方（比如界面上）判断用的，最直观。
    //  - code    : 整数，Windows 返回的"错误码/退出码"。比如 0 表示成功，
    //              5 表示"拒绝访问"（权限不够），1060 表示"服务不存在"。
    //              为什么要保留它呢？因为界面上要给用户显示具体的错误原因，
    //              光知道"失败"是不够的，得知道"为什么失败"。
    //  - detail  : 字符串，补充的详细描述信息。创建服务失败的时候，这里会
    //              放上 PowerShell 抛出的异常消息，方便用户排查问题。
    // 为什么要用一个 record 而不是写一个普通的类呢？因为 record 会自动帮
    // 我们生成 equals、hashCode、toString 这些样板方法，代码又短又干净，
    // 还不会出错，对于"就是装几个值、没有复杂逻辑"的数据载体来说再合适
    // 不过了。
    // =================================================================
    public record ActionResult(boolean ok, int code, String detail) {
    }

    // 下面这一串都是 Windows 的错误码常量。为什么要把它们定义成常量呢？
    // 因为直接写数字的话，过几天你自己都看不懂 1056 是什么意思了，而给
    // 每个数字起一个清清楚楚的名字，代码的可读性一下子就上来了。另外后面
    // 的 errorMessage() 方法要根据这些错误码去查对应的本地化错误文案，
    // 所以这些常量必须跟 Windows 官方定义的错误码数值一一对应，不能写错，
    // 写错了就查不到正确的错误信息了。下面逐个解释：
    //
    // ERROR_ACCESS_DENIED = 5：拒绝访问。通常是权限不够，比如没有用
    //   管理员身份运行程序就去操作服务，Windows 就会甩给你这个错误码，
    //   意思就是"你没资格，一边待着去"。
    //
    // ERROR_INVALID_PARAMETER = 87：参数无效。你传给 sc.exe 的参数不对，
    //   比如启动类型写了一个不存在的值，Windows 就会回这个错，意思是
    //   "你给的参数我听不懂"。
    //
    // ERROR_SERVICE_ALREADY_RUNNING = 1056：服务已经在运行了。这个错误
    //   码在 start() 方法里特别重要——我们启动一个本来就运行着的服务时，
    //   Windows 就会返回 1056。但我们把这种情况当作"成功"处理，因为从
    //   用户的角度看，目标已经达到了（服务在跑着），没必要报错吓人。
    //
    // ERROR_SERVICE_DISABLED = 1058：服务被禁用了。如果服务的启动类型是
    //   "禁用"（Disabled），你去启动它，Windows 就会拒绝并返回这个码。
    //
    // ERROR_SERVICE_DOES_NOT_EXIST = 1060：服务不存在。你想启动、停止或
    //   删除一个根本不存在的服务，Windows 就会回这个码，意思是"你找的
    //   这个服务我听都没听说过"。
    //
    // ERROR_SERVICE_NOT_ACTIVE = 1062：服务当前没有在运行。这个错误码在
    //   stop() 方法里很重要——我们去停一个本来就没在运行的服务时，Windows
    //   会返回 1062，但我们也把它当作"成功"处理，道理和 1056 一样：
    //   反正服务已经不在运行了，用户想要的结果已经达成了。
    //
    // ERROR_SERVICE_MARKED_FOR_DELETE = 1072：服务已被标记为删除。当你
    //   删了一个服务但它的进程还没完全退出时，Windows 会把这个服务标记为
    //   "待删除"，这时候你再对它做操作就会收到这个错误码。
    private static final int ERROR_ACCESS_DENIED = 5;
    private static final int ERROR_INVALID_PARAMETER = 87;
    private static final int ERROR_SERVICE_ALREADY_RUNNING = 1056;
    private static final int ERROR_SERVICE_DISABLED = 1058;
    private static final int ERROR_SERVICE_DOES_NOT_EXIST = 1060;
    private static final int ERROR_SERVICE_NOT_ACTIVE = 1062;
    private static final int ERROR_SERVICE_MARKED_FOR_DELETE = 1072;

    // i18n 这个字段是"国际化"（internationalization）的缩写，它负责给程序
    // 提供各种语言的翻译文案。我们程序里不能把错误提示写死成中文或英文，
    // 而是写一个"消息键"（比如 "sc.err.5"），然后交给 i18n 去翻译成当前
    // 用户界面语言的文字。这样同一个程序，中文用户看到中文，英文用户看到
    // 英文，非常灵活。这个字段用 final 修饰，说明它在构造函数里赋值之后就
    // 再也不能被修改了，保证了这个类的线程安全性和不可变性。
    private final I18n i18n;

    // 构造函数：很简单，就是把外面传进来的 I18n 对象保存到自己的字段里。
    // 为什么要通过构造函数传进来而不是在类里面 new 一个呢？这叫"依赖注入"
    // （Dependency Injection）思想——对象自己不去创建依赖，而是由外部把
    // 依赖送上门。这样做的好处是测试的时候可以很方便地传入一个假的 i18n
    // 对象，也可以让整个程序共享同一个 i18n 实例，不会重复创建浪费资源。
    public WindowsServiceManager(I18n i18n) {
        this.i18n = i18n;
    }

    // ------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------
    // 【分区标题：查询类操作】
    // 从这往下的几个方法都属于"只读"操作，也就是说它们只负责"看"，不负责
    // "改"——查一个服务的详细信息、列出所有服务、判断服务是否存在。这类
    // 操作是安全的，不会对系统造成任何影响，可以放心大胆地调用。
    // ------------------------------------------------------------------

    /** Returns the service info, or null if the service does not exist. */
    // 【中文翻译】返回服务的详细信息；如果这个服务不存在，就返回 null。
    //
    // 这个方法是我们整个类里最核心的"查询"方法，很多其他方法（比如
    // exists()）都是建立在它之上的。它的工作流程是：
    // 第一步：用 header() 方法拿到脚本的"开头部分"（主要是设置一些 PowerShell
    //         的全局偏好，比如关闭进度条显示、强制 UTF-8 编码输出，避免中文
    //         乱码），然后拼接一段查询脚本。脚本的大意是：用 Get-CimInstance
    //         按照服务名去查 Win32_Service，如果查到了（$s 不为空），就把
    //         服务的一堆属性（名字、显示名、状态、启动方式、进程ID、可执行
    //         文件路径、退出码、描述）一行一行以 "KEY=值" 的格式打印出来。
    // 第二步：如果这个服务还有一个真实的进程 ID（大于 0），就顺便去查一下
    //         Win32_Process，拿到这个服务进程的启动时间（CreationDate），
    //         格式化成 ISO 8601 标准时间字符串打印出来。为什么要查进程的
    //         启动时间呢？因为服务本身不记录"什么时候启动的"，但用户界面上
    //         往往想显示这个信息，所以只能绕道去进程表里查。
    // 第三步：把 PowerShell 的输出交给 parseKeyValues() 方法解析成
    //         Map<String, String>（键值对）。如果解析出来是空的，或者里面
    //         连 NAME 都没有，说明服务不存在，直接返回 null。
    // 第四步：把键值对里的各个字段组装成一个 ServiceInfo 对象返回。注意
    //         PID 和 EXITCODE 是数字，所以要用 parseInt() 做安全转换，因为
    //         输出里的这些值可能是空字符串，直接 Integer.parseInt 会抛异常。
    //
    // 为什么用 -Filter 'Name=''xxx''' 这种奇怪的写法？因为 PowerShell 的
    // -Filter 参数要求字符串里用两个单引号（''）来表示一个单引号，而我们的
    // escQ() 方法正是干这个的——把服务名里可能出现的单引号全部翻倍转义，
    // 防止服务名里带单引号时把整个脚本搞坏。
    public ServiceInfo query(String name) {
        String script = header()
                + "$s = Get-CimInstance Win32_Service -Filter 'Name=''" + escQ(name) + "'''\n"
                + "if ($s) {\n"
                + "  'NAME=' + $s.Name\n"
                + "  'DISPLAY=' + $s.DisplayName\n"
                + "  'STATE=' + $s.State\n"
                + "  'STARTMODE=' + $s.StartMode\n"
                + "  'PID=' + $s.ProcessId\n"
                + "  'PATH=' + $s.PathName\n"
                + "  'EXITCODE=' + $s.ExitCode\n"
                + "  'DESC=' + $s.Description\n"
                + "  if ($s.ProcessId -gt 0) {\n"
                + "    $p = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $s.ProcessId) -ErrorAction SilentlyContinue\n"
                + "    if ($p) { 'STARTED=' + $p.CreationDate.ToString('o') }\n"
                + "  }\n"
                + "}\n";
        Map<String, String> kv = parseKeyValues(ps(script).output());
        if (kv.isEmpty() || kv.get("NAME") == null) {
            return null;
        }
        return new ServiceInfo(
                kv.get("NAME"),
                kv.get("DISPLAY"),
                kv.get("STATE"),
                kv.get("STARTMODE"),
                parseInt(kv.get("PID")),
                kv.get("PATH"),
                parseInt(kv.get("EXITCODE")),
                kv.get("DESC"),
                kv.get("STARTED"));
    }

    /** Lists all Windows services (name, display name, state, start mode). */
    // 【中文翻译】列出 Windows 上所有的服务（名字、显示名、状态、启动方式）。
    //
    // 这个方法用来给用户界面填充"服务列表"，用户一打开界面就能看到整台
    // 电脑上所有的服务。它的实现思路也挺有意思的：
    // 第一步：拼一段 PowerShell 脚本，用 Get-CimInstance Win32_Service
    //         取出所有服务，按名字排序（Sort-Object Name，这样列表看起来
    //         是整齐的字母顺序），然后用 ForEach-Object 遍历每一个服务，
    //         每个服务打印 4 行 "KEY=值" 格式的内容（NAME、DISPLAY、STATE、
    //         STARTMODE）。
    // 第二步：在 Java 这边按行读取输出，用一个 cur（current，当前的）Map
    //         来累积"正在组装的那个服务"的属性。关键的技巧在 if 判断：
    //         一旦发现新的一行是 "NAME=" 开头、而且 cur 里面已经有过 NAME
    //         了，就说明"上一个服务已经读完，现在开始的是下一个服务"，
    //         于是先把 cur 转成一个 ServiceInfo 对象放进结果列表，再开一个
    //         新的空 Map 继续累积。这个"检测到新记录就结算上一条"的模式，
    //         就像在流水线上数零件：看到一个新零件来了，就把手上这个数完
    //         装箱，再拿起新的。
    // 第三步：把每一行的 "KEY=值" 拆开（以第一个 '=' 为界，左边是键，右边
    //         是值）放进 cur。注意用 indexOf('=') 而不是 split("=")，因为
    //         值里面完全有可能也包含 '=' 字符，比如描述文字里有个等号，用
    //         split 就会把值截断，而 indexOf 只找第一个等号，后面的都原封
    //         不动地留给值，这才是正确的做法。
    // 第四步：循环结束后，别忘了最后一批服务还没结算呢（因为循环里只有
    //         "遇到新 NAME 才结算"），所以循环外面还要再补一次结算。
    public List<ServiceInfo> list() {
        String script = header()
                + "Get-CimInstance Win32_Service | Sort-Object Name | ForEach-Object {\n"
                + "  'NAME=' + $_.Name\n"
                + "  'DISPLAY=' + $_.DisplayName\n"
                + "  'STATE=' + $_.State\n"
                + "  'STARTMODE=' + $_.StartMode\n"
                + "}\n";
        List<ServiceInfo> out = new ArrayList<>();
        Map<String, String> cur = new LinkedHashMap<>();
        for (String line : ps(script).output().split("\\r?\\n")) {
            if (line.startsWith("NAME=") && cur.containsKey("NAME")) {
                out.add(toInfo(cur));
                cur = new LinkedHashMap<>();
            }
            int eq = line.indexOf('=');
            if (eq > 0) {
                cur.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        if (cur.containsKey("NAME")) {
            out.add(toInfo(cur));
        }
        return out;
    }

    // 判断一个服务是否存在，实现上非常简单粗暴：直接调用上面的 query()
    // 方法去查一次，查得到（返回的不是 null）就说明存在，查不到就说明
    // 不存在。为什么不自己再去写一段脚本呢？因为 query() 已经帮你把活都
    // 干完了，这里"站在巨人的肩膀上"复用一下就好，代码又短又不容易出
    // bug。这就是"组合优于重复"的简单体现——同一个功能只写一遍。
    public boolean exists(String name) {
        return query(name) != null;
    }

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------
    // 【分区标题：修改类操作】
    // 从这往下的方法都属于"写"操作，会对系统产生实际影响——删除服务、
    // 启动服务、停止服务、修改启动类型、创建服务。这类操作都要特别小心，
    // 因为它们不可撤销（比如删除了一个服务，想恢复就得重新创建）。
    // 注意这里的几个方法都用了 sc.exe 这个命令行工具，并且用了一个非常
    // 巧妙的写法：'exit $LASTEXITCODE'——意思是让 PowerShell 把 sc.exe
    // 的退出码原样传出去作为整个脚本的退出码，这样 Java 这边就能通过
    // r.exitCode() 拿到 sc.exe 的真实返回码（0 成功，其他数字是各种错误
    // 码），再配合上面定义的那些错误码常量来精确判断失败原因。
    // ------------------------------------------------------------------

    // 删除一个服务。命令就是 sc.exe delete '服务名'，这个命令非常直接：
    // 告诉 SCM"把这个服务从登记册上划掉"。删除之后这个服务就再也查不到了。
    // 注意：删除操作是不问"你在不在运行"的，就算服务正在运行，Windows
    // 也会先把它标记为待删除，等进程退出后再真正清理掉（这就是前面说的
    // 1072 错误码出现的原因）。这个方法没有做任何额外的容错处理，只要
    // sc.exe 返回 0 就算成功，否则把退出码原样记下来，让调用方去判断。
    public ActionResult delete(String name) {
        ProcessRunner.Result r = ps("sc.exe delete '" + escQ(name) + "'\nexit $LASTEXITCODE\n");
        return new ActionResult(r.exitCode() == 0, r.exitCode(), "");
    }

    // 启动一个服务。命令是 sc.exe start '服务名'。
    // 这里有一段很重要的容错逻辑，值得我们掰开揉碎了讲：
    // 正常情况下 sc.exe start 返回 0 就是启动成功，但有一个特殊情况——
    // 如果这个服务本来就已经在运行了，sc.exe 会返回错误码 1056（服务已在
    // 运行，对应常量 ERROR_SERVICE_ALREADY_RUNNING）。可是你想想，用户
    // 点"启动"按钮，结果服务早就跑得好好的，这算失败吗？当然不算！用户
    // 想要的结果（服务在运行）已经达成了，所以我们必须把 1056 也当作成功
    // 处理，否则用户每次对运行中的服务点启动都会看到一条吓人的红色报错。
    // 这种"重复执行也安全、结果一样"的操作就叫幂等（idempotent），跟
    // Linux 上 systemctl start 的行为一模一样（systemctl 也是这么干的）。
    public ActionResult start(String name) {
        ProcessRunner.Result r = ps("sc.exe start '" + escQ(name) + "'\nexit $LASTEXITCODE\n");
        int code = r.exitCode();
        // 1056 = already running: treat as success (idempotent, like systemctl).
        return new ActionResult(code == 0 || code == ERROR_SERVICE_ALREADY_RUNNING, code, "");
    }

    // 停止一个服务。命令是 sc.exe stop '服务名'。
    // 逻辑和上面的 start() 完全对称：正常情况下 sc.exe stop 返回 0 就是
    // 停止成功，但特殊情况下——如果这个服务本来就没在运行——sc.exe 会
    // 返回错误码 1062（服务未运行，对应常量 ERROR_SERVICE_NOT_ACTIVE）。
    // 同样地，用户点"停止"按钮，结果服务早就停了，这当然也不能算失败，
    // 所以把 1062 也当作成功处理，保证操作幂等，跟 systemctl stop 的行为
    // 保持一致。你看，start() 和 stop() 一对照，是不是有种"镜像对称"的
    // 美感？一个容忍 1056，一个容忍 1062，因为"已经在目标状态"对两个
    // 方向来说都是成功。
    public ActionResult stop(String name) {
        ProcessRunner.Result r = ps("sc.exe stop '" + escQ(name) + "'\nexit $LASTEXITCODE\n");
        int code = r.exitCode();
        // 1062 = not running: treat as success (idempotent, like systemctl).
        return new ActionResult(code == 0 || code == ERROR_SERVICE_NOT_ACTIVE, code, "");
    }

    /** Sets the start type: auto | demand | disabled. */
    // 【中文翻译】设置服务的启动类型：auto（自动）| demand（手动）| disabled（禁用）。
    //
    // 启动类型决定了"开机时这个服务要不要自己跑起来"：自动（auto）就是
    // 开机就启动，手动（demand）就是需要的时候才由系统或用户手动启动，
    // 禁用（disabled）就是完全不允许启动（连手动都不行，除非先改回别的
    // 类型）。这个方法的作用就是把服务的启动类型改成用户想要的那个。
    //
    // 这里有一个"容错默认值"的设计值得注意：如果传入的 startType 是 null
    // （调用方忘了传）或者传了一个我们不认识的值，switch 表达式的 default
    // 分支会把它当成 "auto"（自动）来处理。为什么要这样"兜底"呢？因为
    // 与其让程序因为一个空值直接崩溃，不如给一个最安全、最常见的默认值，
    // 保证功能永远可用。这就像你问朋友"喝什么"，朋友没回答，你就默认给
    // 他倒杯白开水，总比干等着强。
    //
    // 执行上用的是 sc.exe config 命令：sc.exe config '服务名' 'start=' 'auto'，
    // 注意 'start=' 后面必须有个空格再接值，这是 sc.exe 的老传统写法，很
    // 多人第一次写都会漏掉这个空格然后发现命令不生效，这里我们已经写对了。
    public ActionResult setStartType(String name, String startType) {
        String t = switch (startType == null ? "auto" : startType) {
            case "demand" -> "demand";
            case "disabled" -> "disabled";
            default -> "auto";
        };
        ProcessRunner.Result r = ps("sc.exe config '" + escQ(name) + "' 'start=' '" + t
                + "'\nexit $LASTEXITCODE\n");
        return new ActionResult(r.exitCode() == 0, r.exitCode(), "");
    }

    /** Localized description of a Windows error code. */
    // 【中文翻译】根据 Windows 错误码，返回一段已经本地化（翻译成当前界面
    // 语言）的错误描述文字。
    //
    // 这个方法是一个"翻译官"：输入一个数字（错误码），输出一段人能看懂的
    // 话。为什么需要它？因为上面那些操作失败时，我们拿到的只是冷冰冰的
    // 数字（比如 5、1060），用户可看不懂"1060"是什么意思。所以我们用
    // switch 表达式把已知的错误码一一映射到对应的消息键（比如 5 对应
    // "sc.err.5"），再交给 i18n.msg() 去翻译成当前语言的完整句子。
    // 那些我们没预料到的错误码呢？走 default 分支，用一个通用文案
    // "sc.err.generic"，并把错误码本身作为参数嵌进去，至少让用户知道
    // 出错的编号，方便去网上搜索或者反馈问题。
    // 这就是软件工程里常说的"防御性编程"：永远要为意料之外的情况留一条
    // 后路，不能让用户面对一个"未知错误"就彻底抓瞎。
    public String errorMessage(int code) {
        return switch (code) {
            case ERROR_ACCESS_DENIED -> i18n.msg("sc.err.5");
            case ERROR_INVALID_PARAMETER -> i18n.msg("sc.err.87");
            case ERROR_SERVICE_ALREADY_RUNNING -> i18n.msg("sc.err.1056");
            case ERROR_SERVICE_DISABLED -> i18n.msg("sc.err.1058");
            case ERROR_SERVICE_DOES_NOT_EXIST -> i18n.msg("sc.err.1060");
            case ERROR_SERVICE_NOT_ACTIVE -> i18n.msg("sc.err.1062");
            case ERROR_SERVICE_MARKED_FOR_DELETE -> i18n.msg("sc.err.1072");
            default -> i18n.msg("sc.err.generic", code);
        };
    }

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------
    // 注意！这里又出现了一个一模一样的"actions"分区标题。这其实是原代码里
    // 就有的一个小疏漏（应该是笔误，本来想写 create 之类的标题），我们
    // 保留它原样不动，因为任务要求不能改动任何原有内容。大家看到两个相同
    // 的标题不要奇怪，下面的 create()、buildBinPath()、setServiceParameters()
    // 等方法和上面那些方法一样，都属于"修改类操作"，只是放到了这个分区
    // 下面而已。功能上完全不受影响。
    // ------------------------------------------------------------------

    /**
     * Creates a service. {@code binPath} is the full command line of the
     * service binary. {@code startType} is auto|demand|disabled.
     */
    // 【中文翻译】创建一个 Windows 服务。binPath 是服务可执行程序的完整
    // 命令行；startType 是启动类型，取值是 auto|demand|disabled。
    //
    // 创建服务是这些操作里最复杂的一个，因为它牵涉到好几个参数，而且
    // PowerShell 脚本的逻辑也相对长。我们一步步拆解：
    //
    // 第一步：归一化启动类型。用户可能传 "auto"、"demand"、"disabled"，
    // 也可能传 "Manual"、"Disabled" 这种带大小写的变体（甚至 null），我们
    // 通过 switch 表达式把它们统统归一到 PowerShell 认识的三种写法：
    // "Automatic"（自动）、"Manual"（手动）、"Disabled"（禁用）。注意
    // demand 和 Manual 都会变成 Manual，disabled 和 Disabled 都会变成
    // Disabled，其余一概当 Automatic。这样不管调用方怎么传，脚本都不会
    // 因为参数格式问题而失败。
    //
    // 第二步：拼 PowerShell 脚本。脚本的开头用 header()（全局偏好设置），
    // 然后设置 $ErrorActionPreference = 'Stop'——这个设置非常关键，它告诉
    // PowerShell"只要有任何命令出错，立刻抛出异常并停止执行"，而不是默默
    // 吞掉错误继续往下跑。紧接着用 try { ... } catch { ... } 把创建逻辑
    // 包起来：成功就打印一行 'OK'，失败就把异常消息以 'ERR=xxx' 的格式
    // 打印出来。这种"成功/失败标志"的设计让 Java 这边解析结果变得极其
    // 简单可靠。
    //
    // 第三步：真正的创建命令是 New-Service，参数有 -Name（服务名）、
    // -BinaryPathName（服务程序命令行）、-DisplayName（显示名，可选）、
    // -StartupType（启动类型）。如果调用方还提供了描述文字，就用 sc.exe
    // description 命令补上服务的描述（描述是单独设置的，New-Service 本身
    // 不支持直接传描述）。所有字符串值都要经过 escQ() 转义，防止引号破坏
    // 脚本。命令后面的 | Out-Null 表示"输出扔掉"，我们不需要 New-Service
    // 打印的那些花里胡哨的确认信息，只要后面的 'OK' 标志。
    //
    // 第四步：解析输出。以 "OK" 开头就是成功，返回 ok=true、code=0；
    // 以 "ERR=" 开头就是失败，把 'ERR=' 前缀剥掉，剩下的就是 PowerShell
    // 的异常消息，作为 detail 返回给用户看，让他们知道到底哪里出了问题。
    public ActionResult create(String name, String binPath, String displayName,
                               String description, String startType) {
        String type = switch (startType == null ? "auto" : startType) {
            case "demand", "Manual" -> "Manual";
            case "disabled", "Disabled" -> "Disabled";
            default -> "Automatic";
        };
        StringBuilder script = new StringBuilder(header());
        script.append("$ErrorActionPreference = 'Stop'\n");
        script.append("try {\n");
        script.append("  New-Service -Name '").append(escQ(name))
                .append("' -BinaryPathName '").append(escQ(binPath)).append("'");
        if (displayName != null && !displayName.isEmpty()) {
            script.append(" -DisplayName '").append(escQ(displayName)).append("'");
        }
        script.append(" -StartupType '").append(type).append("' | Out-Null\n");
        if (description != null && !description.isEmpty()) {
            script.append("  sc.exe description '").append(escQ(name))
                    .append("' '").append(escQ(description)).append("' | Out-Null\n");
        }
        script.append("  'OK'\n");
        script.append("} catch { 'ERR=' + $_.Exception.Message }\n");
        String out = ps(script.toString()).output().trim();
        if (out.startsWith("OK")) {
            return new ActionResult(true, 0, "");
        }
        String detail = out.startsWith("ERR=") ? out.substring(4) : out;
        return new ActionResult(false, -1, detail);
    }

    /**
     * Builds the service binary command line.
     *
     * <p>Without a working directory or environment the command line is simply
     * the (quoted) executable plus its arguments. When a working directory or
     * environment variables are requested, the command is wrapped in
     * {@code cmd.exe /c set ... && cd /d <dir> && <exe> <args>} so the service
     * process starts with that working directory and environment.
     */
    // 【中文翻译】构造"服务程序命令行"（也就是服务要运行的那条完整命令）。
    //
    // 这里要解释一下背景：创建 Windows 服务的时候，你必须告诉 Windows
    // "这个服务启动时要执行什么命令"，这个命令字符串就是 binPath。它不
    // 只是可执行文件路径那么简单，还可以带上命令行参数。而我们的服务是
    // 一个"宿主程序"（host），它启动的时候可能需要切换到某个工作目录、
    // 需要设置一些环境变量，这些需求都得想办法塞进 binPath 里。
    //
    // 方法的逻辑分两种情况：
    // 情况一：既没有工作目录，也没有环境变量。这时候命令最简单，就是
    // "可执行文件（必要时加引号）+ 空格 + 各个参数"，比如
    // "C:\MyApp\app.exe --port 8080"。
    // 情况二：有工作目录或者环境变量。这时候命令就得"包装"一下，变成
    // cmd.exe /c set "VAR=值" && set "VAR2=值" && cd /d "工作目录" && 程序 参数
    // 的意思。为什么要用 cmd.exe /c 包一层呢？因为 Windows 服务启动进程
    // 的时候，默认的工作目录是 system32 之类的系统目录，环境变量也是
    // 服务管理器给的，我们没法直接改；但是借 cmd.exe 的 set 和 cd /d
    // 命令，就可以在真正启动程序之前先把环境和工作目录准备好。这就好比
    // 你出门前先在门口把鞋穿好、把包背上，然后再出发。
    //
    // 整个方法用 StringBuilder 一点一点拼字符串，最后返回拼好的完整命令
    // 行。里面还调用了 quoteIfNeeded() 给包含空格的部分加引号，因为
    // cmd.exe 解析命令时，不带引号的空格会被当作参数分隔符，路径里有
    // 空格就必须用引号包起来，否则程序根本启动不了。
    public static String buildBinPath(String executable, List<String> args,
                                      String workingDirectory, List<String> environment) {
        String exe = executable == null ? "" : executable.trim();
        StringBuilder sb = new StringBuilder();
        boolean wrap = (workingDirectory != null && !workingDirectory.isEmpty())
                || (environment != null && !environment.isEmpty());
        if (wrap) {
            sb.append("cmd.exe /c ");
            if (environment != null) {
                for (String env : environment) {
                    if (env != null && !env.isEmpty()) {
                        sb.append("set \"").append(env).append("\" && ");
                    }
                }
            }
            if (workingDirectory != null && !workingDirectory.isEmpty()) {
                sb.append("cd /d \"").append(workingDirectory).append("\" && ");
            }
        }
        sb.append(quoteIfNeeded(exe));
        if (args != null) {
            for (String a : args) {
                sb.append(' ').append(quoteIfNeeded(a));
            }
        }
        return sb.toString();
    }

    // 一个很小的工具方法：如果字符串里包含空格、而且开头还没有引号，就给
    // 它套上一对双引号；否则原样返回。为什么要有这个判断呢？因为命令行
    // 里空格是"分隔符"，一个带空格的路径如果不加引号，cmd.exe 就会把它
    // 拆成两个参数，程序就找不到了。但如果它开头已经有引号了（说明调用方
    // 自己处理过了），我们就不能再画蛇添足地再加一对，否则会变成
    // ""xxx"" 这种嵌套引号，照样出错。这个小方法虽不起眼，却是命令行
    // 拼接里最容易出 bug 的地方之一，值得单独抽出来写好、写对。
    private static String quoteIfNeeded(String s) {
        if (s.contains(" ") && !s.startsWith("\"")) {
            return "\"" + s + "\"";
        }
        return s;
    }

    /**
     * Writes the nssm-style Parameters registry values for a hosted service
     * (read by the SystemWin service host at start time).
     */
    // 【中文翻译】把"nssm 风格的 Parameters 注册表值"写入注册表，这些值
    // 会在 SystemWin 服务宿主启动的时候被读取。
    //
    // 这一段话信息量很大，我们慢慢讲。nssm 是一个很有名的第三方工具，它的
    // 作用是把一个普通的 exe 程序包装成 Windows 服务来运行。它有个约定：
    // 把"要运行哪个程序、带什么参数、工作目录在哪、环境变量是什么、日志
    // 文件写到哪里"这些配置全部写到注册表的
    // HKLM:\SYSTEM\CurrentControlSet\Services\服务名\Parameters 键下面。
    // 我们 SystemWin 借鉴了 nssm 的这套约定：我们的服务宿主程序启动时，
    // 会自己到这个注册表位置去读取这些配置，然后按照配置去启动真正的
    // 应用程序。这样做的好处是：用户改配置不需要重新创建服务，只要改注册
    // 表里的值再重启服务就行了，灵活又方便。
    //
    // 这个方法就是负责把配置写进注册表的"搬运工"。它拼接一段 PowerShell
    // 脚本，脚本做的事情是：
    //  - 用 New-Item 创建（或强制覆盖）Parameters 这个注册表键；
    //  - 用 New-ItemProperty 一条一条地写入各个配置项。注意每个属性都指定
    //    了 -PropertyType：Application、AppParameters、AppDirectory 等是
    //    String（字符串），AppEnvironmentExtra 是 MultiString（字符串数组，
    //    所以脚本里用 @('a', 'b') 的数组语法），AppRestartDelay 是 DWord
    //    （32 位整数，单位是毫秒）。
    //  - 每个 New-ItemProperty 后面都跟了 -Force，意思是"如果这个值已经
    //    存在就直接覆盖"，保证重复调用这个方法也不会报错，天然幂等。
    //  - 成功打印 'OK'，失败（因为有 $ErrorActionPreference = 'Stop'）
    //    会被 catch 住，打印 'ERR=异常消息'。
    //
    // 参数里那些可空的（directory、environment、stdoutLog、stderrLog、
    // hostLog）都是"有才写、没有就跳过"——用 if 判空来避免写一堆空的
    // 注册表值，保持注册表干净。而 Application、AppParameters、AppExit
    // 这些"必须有"的项，用 nvl() 把 null 安全地转成空字符串再写入，绝对
    // 不能因为传了 null 就让脚本拼接出 null 字符串把整个脚本弄崩。
    public ActionResult setServiceParameters(String name,
                                             String application, String parameters,
                                             String directory, List<String> environment,
                                             String stdoutLog, String stderrLog,
                                             String hostLog, String appExit,
                                             int restartDelayMs) {
        StringBuilder s = new StringBuilder(header());
        s.append("$ErrorActionPreference = 'Stop'\n");
        s.append("try {\n");
        s.append("  $key = 'HKLM:\\SYSTEM\\CurrentControlSet\\Services\\")
                .append(escQ(name)).append("\\Parameters'\n");
        s.append("  New-Item -Path $key -Force | Out-Null\n");
        s.append("  New-ItemProperty -Path $key -Name 'Application' -Value '")
                .append(escQ(nvl(application))).append("' -PropertyType String -Force | Out-Null\n");
        s.append("  New-ItemProperty -Path $key -Name 'AppParameters' -Value '")
                .append(escQ(nvl(parameters))).append("' -PropertyType String -Force | Out-Null\n");
        if (directory != null && !directory.isEmpty()) {
            s.append("  New-ItemProperty -Path $key -Name 'AppDirectory' -Value '")
                    .append(escQ(directory)).append("' -PropertyType String -Force | Out-Null\n");
        }
        if (environment != null && !environment.isEmpty()) {
            s.append("  New-ItemProperty -Path $key -Name 'AppEnvironmentExtra' -Value @(");
            for (int i = 0; i < environment.size(); i++) {
                if (i > 0) {
                    s.append(", ");
                }
                s.append("'").append(escQ(environment.get(i))).append("'");
            }
            s.append(") -PropertyType MultiString -Force | Out-Null\n");
        }
        if (stdoutLog != null && !stdoutLog.isEmpty()) {
            s.append("  New-ItemProperty -Path $key -Name 'AppStdout' -Value '")
                    .append(escQ(stdoutLog)).append("' -PropertyType String -Force | Out-Null\n");
        }
        if (stderrLog != null && !stderrLog.isEmpty()) {
            s.append("  New-ItemProperty -Path $key -Name 'AppStderr' -Value '")
                    .append(escQ(stderrLog)).append("' -PropertyType String -Force | Out-Null\n");
        }
        if (hostLog != null && !hostLog.isEmpty()) {
            s.append("  New-ItemProperty -Path $key -Name 'AppHostLog' -Value '")
                    .append(escQ(hostLog)).append("' -PropertyType String -Force | Out-Null\n");
        }
        s.append("  New-ItemProperty -Path $key -Name 'AppExit' -Value '")
                .append(escQ(nvl(appExit))).append("' -PropertyType String -Force | Out-Null\n");
        s.append("  New-ItemProperty -Path $key -Name 'AppRestartDelay' -Value ")
                .append(restartDelayMs).append(" -PropertyType DWord -Force | Out-Null\n");
        s.append("  'OK'\n");
        s.append("} catch { 'ERR=' + $_.Exception.Message }\n");
        String out = ps(s.toString()).output().trim();
        if (out.startsWith("OK")) {
            return new ActionResult(true, 0, "");
        }
        String detail = out.startsWith("ERR=") ? out.substring(4) : out;
        return new ActionResult(false, -1, detail);
    }

    // 一个"防空"的小工具：如果传入的字符串是 null，就返回空字符串 ""，
    // 否则原样返回。nvl 是 "null value"（空值）的缩写，在拼接脚本的时候
    // 经常要用到它：比如用户没填某个配置项，参数传进来是 null，如果我们
    // 直接把 null 拼进 PowerShell 脚本，脚本里就会出现一个 "null" 字样，
    // 轻则写进去一个错误的字符串，重则整个脚本语法错误跑不起来。有了
    // nvl() 这个"安全网"，null 永远变成空字符串，脚本永远安全。类似的
    // 工具在别的语言里也都有，比如 C# 的 ?? 运算符、Python 的 or ""。
    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** systemctl-like enable state from a Win32_Service StartMode. */
    // 【中文翻译】把 Win32_Service 的 StartMode（启动方式）翻译成
    // 类似 systemctl 的"启用状态"。
    //
    // 这句话又引出了一个概念：systemctl 是 Linux 上管理服务的工具，它把
    // 服务的"开机自启状态"分成 enabled（已启用，开机自启）、disabled
    // （已禁用，禁止启动）、static（静态，不直接参与启停）等几种。而我们
    // SystemWin 程序是跨平台的，既要支持 Linux 又要支持 Windows，界面上
    // 显示服务状态的时候最好用同一套"语言"，这样用户在两套系统上看到的
    // 状态表述是一致的。
    //
    // Windows 的 StartMode 有 Auto（自动）、Manual（手动）、Disabled（禁用）
    // 等取值，这个方法就是做"翻译"的：
    //  - auto / automatic -> enabled（开机自启，对应 Linux 的"已启用"）
    //  - manual / demand  -> static（手动启动，对应 Linux 的"静态"，即
    //    不会自动启动，但也不能简单地说它被禁用了）
    //  - disabled         -> disabled（禁用）
    //  - 其他没见过的情况  -> 原样小写返回，宁可给用户看原始值，也不要
    //    瞎猜一个错误的状态。
    // 另外开头还有个判空保护：StartMode 是 null 就返回 "unknown"（未知），
    // 因为查询失败或者服务状态异常时确实可能拿不到这个字段，不能让程序
    // 因为 null 调用 toLowerCase() 而抛空指针异常。
    public static String enableState(String startMode) {
        if (startMode == null) {
            return "unknown";
        }
        return switch (startMode.toLowerCase(Locale.ROOT)) {
            case "auto", "automatic" -> "enabled";
            case "manual", "demand" -> "static";
            case "disabled" -> "disabled";
            default -> startMode.toLowerCase(Locale.ROOT);
        };
    }

    /** systemctl-like active state from a Win32_Service State. */
    // 【中文翻译】把 Win32_Service 的 State（运行状态）翻译成类似
    // systemctl 的"活动状态"。
    //
    // 如果说上面的 enableState() 回答的是"这个服务开机自不自启"，那
    // activeState() 回答的就是"这个服务现在活没活着"。Windows 的服务
    // 状态字段 State 可能的值有 Running（运行中）、Stopped（已停止）、
    // Start Pending（启动中）、Stop Pending（停止中）、Paused（已暂停）
    // 等。Linux 的 systemctl 也有对应的表述，比如 active (running)
    // （活动，运行中）、activating（激活中）、deactivating（停用中）、
    // inactive (dead)（不活动/死亡）。
    //
    // 这个方法就是把这些 Windows 状态翻译成 systemctl 风格：
    //  - running                -> active (running) 活动且运行中
    //  - start pending          -> activating (start) 正在启动
    //  - stop pending           -> deactivating (stop) 正在停止
    //  - paused                 -> active (paused) 活着但是暂停了
    //  - continue pending 和 pause pending -> activating（在激活的过渡状态）
    //  - 其他所有没列出来的（比如 stopped）-> inactive (dead) 不活动
    // 注意 default 分支把"没列出的"统统当成不活动，包括 stopped。这样
    // 界面上的显示就能跟 Linux 保持一致，用户无论在哪台机器上看，状态
    // 的"画风"都是统一的。
    public static String activeState(String state) {
        if (state == null) {
            return "unknown";
        }
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "running" -> "active (running)";
            case "start pending" -> "activating (start)";
            case "stop pending" -> "deactivating (stop)";
            case "paused" -> "active (paused)";
            case "continue pending", "pause pending" -> "activating";
            default -> "inactive (dead)";
        };
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------
    // 【分区标题：私有辅助方法】
    // 从这往下的方法都是 private（私有）的，也就是说它们只在类的内部使用，
    // 外部代码看不到也调不到。它们是这个类的"幕后工作者"，负责一些零零碎
    // 碎的小事：拼脚本开头、执行 PowerShell、转义引号、安全解析数字、解析
    // 键值对输出、把键值对转成 ServiceInfo 对象。把这些细节藏起来，外面
    // 的方法才能写得干净清爽。
    // ------------------------------------------------------------------

    // 所有 PowerShell 脚本共用的"开头部分"。里面做了两件重要的事：
    // 1. $ProgressPreference = 'SilentlyContinue'：把 PowerShell 的进度条
    //    显示关掉。如果不关，Get-CimInstance 这种命令在慢的时候会往输出里
    //    混入进度条信息，把我们的 "KEY=值" 输出搅乱，解析就会出错。
    // 2. [Console]::OutputEncoding = [Text.Encoding]::UTF8：强制把控制台
    //    的输出编码设为 UTF-8。这一步至关重要！Windows 默认的控制台编码
    //    可能是 GBK 或其他本地编码，如果服务描述里有中文，不强制 UTF-8
    //    的话，Java 这边读回来的就会是乱码，用户界面上就是一堆"锟斤拷"。
    //    有了这一行，无论什么语言的文字都能正确编码传输。
    private static String header() {
        return "$ProgressPreference = 'SilentlyContinue'\n"
                + "[Console]::OutputEncoding = [Text.Encoding]::UTF8\n";
    }

    // 一个极简的"转发"方法：把脚本交给 ProcessRunner.runPowershell() 去
    // 执行。为什么明明可以直呼 ProcessRunner，还要包这么一层呢？一是让
    // 类里其他方法写起来更短（ps(...) 比 ProcessRunner.runPowershell(...)
    // 短一截，而且少打几个字就少犯几个错）；二是万一以后想换执行方式
    // （比如改成直接调用别的工具），只需要改这一个地方就行，其他代码
    // 一概不动。这种"集中出口"的设计叫门面模式（Facade）的简单应用。
    private static ProcessRunner.Result ps(String script) {
        return ProcessRunner.runPowershell(script);
    }

    // 转义单引号的方法：把字符串里所有的单引号 ' 替换成两个单引号 ''。
    // 为什么要这样做呢？因为我们的 PowerShell 脚本里，字符串都是用单引号
    // 包起来的（比如 '服务名'），如果服务名本身带了一个单引号（比如
    // "It's Service"），不转义的话，脚本就会变成 'It's Service'，PowerShell
    // 会把中间那个单引号当成字符串的结束符，整个语法就乱了套。而 PowerShell
    // 的约定是"两个单引号表示一个单引号"，所以把每一个 ' 翻倍成 ''，
    // 脚本就能正确理解我们想表达的意思了。这是"注入攻击"（比如把服务名
    // 做成一段恶意 PowerShell 代码）最直接的防御手段，虽然我们这里只是
    // 本地操作，但养成转义的好习惯永远没错。
    private static String escQ(String s) {
        return s.replace("'", "''");
    }

    // 安全地把字符串解析成整数。为什么叫"安全"呢？因为输入可能来自
    // PowerShell 的输出，可能为空字符串（比如服务的 PID 字段没有值），
    // 甚至可能混入一些意外字符。如果直接用 Integer.parseInt()，遇到空
    // 字符串或非法格式就会抛出 NumberFormatException 异常，把整个调用
    // 链炸掉。所以这个方法先把空值判断掉（返回 0 作为默认值），再用
    // try-catch 兜底：解析失败也返回 0，绝不让异常冒出去。0 在这里是
    // 一个"无害的默认值"，对调用方来说，PID 是 0 就表示"查不到进程"，
    // 语义上也说得通。
    private static int parseInt(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Parses "KEY=value" lines; lines without '=' are ignored. */
    // 【中文翻译】解析 "KEY=值" 格式的行；不包含 '=' 的行直接忽略。
    //
    // 这是整个类的"数据解码器"：PowerShell 那边打印出来的是一行一行的
    // "NAME=xxx"、"STATE=Running" 这种文本，Java 这边要把它变成能直接
    // 按键取值的 Map 结构。实现思路很朴素：逐行扫描，每一行找第一个 '='
    // 的位置（indexOf），'=' 左边是键，右边是值，放进一个 LinkedHashMap。
    // 用 LinkedHashMap 而不是 HashMap 是有讲究的：LinkedHashMap 会保持
    // 插入顺序，也就是说 Map 里键的顺序和输出文本里的顺序一致，这在调试
    // 的时候非常有用，而且 list() 方法解析多条记录时依赖这个稳定顺序。
    // 那些找不到 '=' 的行（eq <= 0，比如空行、无意义的提示文字）直接被
    // 忽略掉，不会让它们污染数据。
    private static Map<String, String> parseKeyValues(String output) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : output.split("\\r?\\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                kv.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return kv;
    }

    // 把"键值对 Map"转换成 ServiceInfo 对象。这个方法专供 list() 方法
    // 使用——list() 在遍历输出时积累了一个服务的所有属性（都在 Map 里），
    // 凑齐之后就调这个方法把它变成一个正式的服务信息对象。注意和 query()
    // 方法不同的是，这里 PID、PATH、EXITCODE、DESC、STARTED 全部写死为
    // 0 或 null，因为 list() 的脚本里本来就没查这些字段（列表只需要名字、
    // 显示名、状态、启动方式四样就够显示了，查太多反而拖慢速度），所以
    // 这里就不装模作样地塞假数据了，直接留空。
    private static ServiceInfo toInfo(Map<String, String> kv) {
        return new ServiceInfo(
                kv.get("NAME"),
                kv.get("DISPLAY"),
                kv.get("STATE"),
                kv.get("STARTMODE"),
                0, null, 0, null, null);
    }
}
