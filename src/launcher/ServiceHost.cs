using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using Microsoft.Win32;

/// <summary>
/// SystemWin service host — an nssm-style service wrapper (service logic
/// modeled on the fightroad/nssm project).
///
/// The service binary is systemwin.exe itself, run as:
///   systemwin.exe --host &lt;service-name&gt;
///
/// When the Service Control Manager starts the service it launches this mode,
/// which:
///   1. registers the SCM control channel (StartServiceCtrlDispatcherW /
///      SetServiceStatus / RegisterServiceCtrlHandlerExW);
///   2. spawns the configured program as a child process with the working
///      directory, environment and stdout/stderr log redirection read from
///      HKLM\...\Services\&lt;name&gt;\Parameters (App* values, nssm-style);
///   3. supervises it: on unexpected exit it restarts the program
///      (AppRestartDelay), and on SERVICE_CONTROL_STOP it first sends a
///      console Ctrl-C (grace period) and then kills the process tree
///      (job object with KILL_ON_JOB_CLOSE, taskkill /T fallback).
///
/// Lifecycle events are appended to %ProgramData%\SystemWin\logs\&lt;name&gt;.host.log.
/// </summary>
/*
 * ================================================================
 * 下面是中文讲解（废话比较多，请耐心慢慢看，对理解很有帮助）：
 *
 * 这个类 SystemWinHost 是 SystemWin 的"服务宿主"（service host）。
 * 它的作用，简单说就是模仿 nssm 这个著名的开源工具：
 * 把任何一个普通的可执行程序（exe）包装成一个 Windows 服务来运行。
 *
 * 为什么要搞这么个东西呢？因为 Windows 服务（Service）是由
 * "服务控制管理器"（Service Control Manager，简称 SCM）统一管理的：
 * 系统开机时 SCM 自动拉起服务，关机时自动停止服务，服务挂了管理员
 * 还可以用 sc start / net start 手动重启。但是！普通的 exe 程序
 * 完全不懂 SCM 那套"协议"——它不知道怎么注册自己、怎么上报状态、
 * 怎么响应停止命令。所以我们需要一个"翻译官"或者说"中间人"，
 * 也就是这个宿主程序，替普通程序跟 SCM 打交道。
 *
 * 整个工作流程可以概括成三步：
 *   1. 注册 SCM 控制通道：调用 StartServiceCtrlDispatcherW、
 *      SetServiceStatus、RegisterServiceCtrlHandlerExW 这些 Win32 API，
 *      告诉 SCM"我在这儿，你可以给我发命令了"；
 *   2. 用 CreateProcessW 把配置里指定的程序当作子进程启动起来。
 *      子进程的工作目录、环境变量、标准输出/错误输出的日志重定向，
 *      全部从注册表 HKLM\...\Services\&lt;服务名&gt;\Parameters 里读取
 *      （就是 nssm 风格的 App* 那些键值，后面 HostConfig 类会细讲）；
 *   3. 监督这个子进程：如果它意外退出了，就按 AppRestartDelay 配置
 *      等一会儿再把它拉起来（相当于"自动重启"，保证服务一直活着）；
 *      如果 SCM 发来停止命令（SERVICE_CONTROL_STOP），就先给子进程的
 *      控制台发一个 Ctrl-C（给它一个"优雅退出"的宽限期），宽限期过了
 *      它还不退，就直接用作业对象（job object，带 KILL_ON_JOB_CLOSE
 *      标志）把整个进程树杀掉；万一作业对象这条路走不通，就退而求
 *      其次调用 taskkill /T 来杀（KillTree 方法）。
 *
 * 所有生命周期事件都会追加写到
 * %ProgramData%\SystemWin\logs\&lt;服务名&gt;.host.log 这个日志文件里，
 * 方便管理员事后排查问题（HostLog 方法负责写日志）。
 * ================================================================
 */
class SystemWinHost
{
    // service control codes
    /* 这些常量是"服务控制码"（Service Control Codes），
     * 也就是 SCM 给服务发送的控制命令编号，来自 Win32 头文件 winsvc.h。
     * 简单说：SCM 想让你停止，就发 0x00000001；想确认你还活着，
     * 就发 0x00000004 让你汇报状态；系统要关机了，就发 0x00000005。
     * 后面 Handler 方法里 switch 的就是这些数字。 */
    private const uint SERVICE_CONTROL_STOP = 0x00000001;
    private const uint SERVICE_CONTROL_INTERROGATE = 0x00000004;
    private const uint SERVICE_CONTROL_SHUTDOWN = 0x00000005;

    // service states
    /* 这些是"服务状态"（Service State）的枚举值。
     * 服务的一生大致是这样：先变成"启动中"（START_PENDING），
     * 然后变成"运行中"（RUNNING）；停止的时候先变成"停止中"
     * （STOP_PENDING），最后变成"已停止"（STOPPED）。
     * 宿主程序必须通过 SetServiceStatus 把这些状态实时上报给 SCM，
     * 否则 SCM 会以为服务卡死了，甚至可能把整个系统判定为启动失败。 */
    private const uint SERVICE_STOPPED = 0x00000001;
    private const uint SERVICE_START_PENDING = 0x00000002;
    private const uint SERVICE_STOP_PENDING = 0x00000003;
    private const uint SERVICE_RUNNING = 0x00000004;

    /* 服务类型：SERVICE_WIN32_OWN_PROCESS 表示"我这个服务是自己独占
     * 一个进程的"（而不是跟别人共享进程），这是最简单也最常见的类型。 */
    private const uint SERVICE_WIN32_OWN_PROCESS = 0x00000010;
    /* 这两个是"接受的控件"标志：告诉 SCM"我能接受停止命令"和
     * "我能接受关机命令"。如果不声明，SCM 就不会给你发对应的消息，
     * 那样服务就没法正常停止，系统关机时也没人通知你收尾了。 */
    private const uint SERVICE_ACCEPT_STOP = 0x00000001;
    private const uint SERVICE_ACCEPT_SHUTDOWN = 0x00000004;

    /* ERROR_FAILED_SERVICE_CONTROLLER_CONNECT（错误码 1063）是一个很经典
     * 的错误：当你在命令行直接运行一个服务程序、而不是由 SCM 启动它时，
     * StartServiceCtrlDispatcherW 就会返回这个错误。Run 方法就是靠它
     * 区分"我是被 SCM 正常启动的"还是"有人手贱直接在命令行跑了"，
     * 后者就打印一行友好的提示然后退出，免得用户一脸懵。 */
    private const uint ERROR_FAILED_SERVICE_CONTROLLER_CONNECT = 1063;
    /* WAIT_OBJECT_0 = 0 表示"等待的对象有信号了"（比如子进程退出、
     * 事件被 Set），WAIT_TIMEOUT 表示超时。这两个是 WaitForSingleObject
     * 的返回值，后面代码里大量使用，先记住含义。 */
    private const uint WAIT_OBJECT_0 = 0;
    private const uint WAIT_TIMEOUT = 0x00000102;
    /* STILL_ACTIVE = 259 是 Windows 的特殊"退出码"：GetExitCodeProcess
     * 返回它，说明进程"还活着"（根本没退出）。注意 259 本身是合法的
     * 退出码，所以判断进程是否结束，一定要跟它比较，不能简单认为
     * "非零就是出错了"。 */
    private const uint STILL_ACTIVE = 259;

    // CreateProcess flags
    /* CreateProcessW 的 dwCreationFlags 标志位：
     * CREATE_NEW_CONSOLE 表示给子进程新建一个自己的控制台窗口
     * （否则子进程会继承父进程的控制台，而服务进程通常没有控制台）；
     * STARTF_USESTDHANDLES 表示"我使用 STARTUPINFO 里给的标准句柄"，
     * 也就是让子进程的 stdin/stdout/stderr 指向我们开好的日志文件。 */
    private const uint CREATE_NEW_CONSOLE = 0x00000010;
    private const uint STARTF_USESTDHANDLES = 0x00000100;

    // file access
    /* 这些是 CreateFileW 需要的访问权限和共享模式参数。
     * GENERIC_WRITE / GENERIC_READ 是读写权限；FILE_SHARE_* 表示允许
     * 别人也来读/写/删这个文件——这一点对日志文件特别重要，不然别的
     * 工具（比如 tail -f、记事本）就没法同时打开日志了；
     * OPEN_ALWAYS 表示"文件不存在就创建、存在就直接打开"（配合追加写）；
     * FILE_ATTRIBUTE_NORMAL 是普通文件属性。 */
    private const uint GENERIC_WRITE = 0x40000000;
    private const uint GENERIC_READ = 0x80000000;
    private const uint FILE_SHARE_READ = 1;
    private const uint FILE_SHARE_WRITE = 2;
    private const uint FILE_SHARE_DELETE = 4;
    private const uint OPEN_ALWAYS = 4;
    private const uint FILE_ATTRIBUTE_NORMAL = 0x80;

    /* JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE 是作业对象（Job Object）的限制
     * 标志。它的含义是：当这个作业对象的最后一个句柄被关闭时，Windows
     * 会强制杀掉作业里所有的进程。我们正是靠这个实现"杀进程树"的：
     * 把子进程放进作业里，停止服务时直接关掉作业句柄，整个进程树
     * （包括子进程又派生的孙进程）就全没了，不会留下孤儿进程。 */
    private const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;

    // job info class
    /* SetInformationJobObject 的第一个参数是"信息类别"（Information
     * Class），JobObjectBasicLimitInformation = 2 表示我们要设置的是
     * JOBOBJECT_BASIC_LIMIT_INFORMATION 这个结构。 */
    private const int JobObjectBasicLimitInformation = 2;

    /* CTRL_C_EVENT = 0 是 GenerateConsoleCtrlEvent 的控件类型参数：
     * 表示向目标控制台发送 Ctrl-C 信号（等效于用户按了 Ctrl+C）。 */
    private const uint CTRL_C_EVENT = 0;

    /* INVALID_HANDLE_VALUE 是 Win32 里"无效句柄"的约定值（-1）。
     * CreateFileW 等函数失败时都会返回这个值，所以拿到句柄后
     * 一定要先跟它比较，确认不是它才能放心使用。 */
    private static readonly IntPtr INVALID_HANDLE_VALUE = new IntPtr(-1);

    /* SERVICE_TABLE_ENTRY 是 StartServiceCtrlDispatcherW 需要的"服务表"：
     * 每个条目把"服务名"和"服务入口函数"配对。注意表必须以一个
     * lpServiceName 为 null 的"哨兵条目"结尾，表示"没有更多服务了"。
     * 我们只有一个服务，所以表的大小就是 2（一个真条目 + 一个哨兵）。 */
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct SERVICE_TABLE_ENTRY
    {
        [MarshalAs(UnmanagedType.LPWStr)]
        public string lpServiceName;
        [MarshalAs(UnmanagedType.FunctionPtr)]
        public ServiceMainCallback lpServiceProc;
    }

    /* ServiceMainCallback 是"服务入口函数"的委托类型：SCM 启动服务时
     * 会回调这个函数，argc / argv 是 SCM 传过来的参数（一般用不到）。
     * 注意 [UnmanagedFunctionPointer(CallingConvention.Winapi)]：
     * 非托管回调必须明确声明调用约定，否则默认的 .NET 约定跟
     * Windows 的 stdcall 不一致，栈就乱套了。 */
    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate void ServiceMainCallback(int argc, IntPtr argv);

    /* ServiceControlHandler 是"控制处理函数"的委托类型：SCM 每次给服务
     * 发控制消息（停止、关机、查询状态等）都会回调它。四个参数分别是：
     * control（控制码）、eventType / eventData（关机时的附加信息）、
     * context（注册时传的上下文，我们传了 IntPtr.Zero 没用上）。 */
    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate uint ServiceControlHandler(uint control, uint eventType, IntPtr eventData, IntPtr context);

    /* SERVICE_STATUS 结构是服务向 SCM 上报状态的"载体"，字段含义：
     * dwServiceType      —— 服务类型（我们是 OWN_PROCESS）；
     * dwCurrentState     —— 当前状态（运行中/停止中/已停止...）；
     * dwControlsAccepted —— 能接受哪些控制消息；
     * dwWin32ExitCode / dwServiceSpecificExitCode —— 退出码；
     * dwCheckPoint / dwWaitHint —— 在"启动中/停止中"阶段告诉 SCM
     *   进度（checkpoint）和预计还要等多久（wait hint），防止 SCM
     *   等得不耐烦、误判服务启动失败。 */
    [StructLayout(LayoutKind.Sequential)]
    private struct SERVICE_STATUS
    {
        public uint dwServiceType;
        public uint dwCurrentState;
        public uint dwControlsAccepted;
        public uint dwWin32ExitCode;
        public uint dwServiceSpecificExitCode;
        public uint dwCheckPoint;
        public uint dwWaitHint;
    }

    /* STARTUPINFO 是 CreateProcessW 的"启动参数"结构，告诉 Windows
     * 新进程的窗口怎么显示、标准句柄用哪个等等。字段特别多，我们这里
     * 只用了三个：cb（结构体大小，必填！不填 Windows 不知道结构多大）、
     * dwFlags（标志位）和三个标准句柄。注意 CharSet.Unicode 和
     * [MarshalAs(UnmanagedType.LPWStr)]，因为 Win32 的宽字符版本
     * 函数要的是 UTF-16 字符串。 */
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct STARTUPINFO
    {
        public int cb;
        public IntPtr lpReserved;
        [MarshalAs(UnmanagedType.LPWStr)]
        public string lpDesktop;
        [MarshalAs(UnmanagedType.LPWStr)]
        public string lpTitle;
        public uint dwX;
        public uint dwY;
        public uint dwXSize;
        public uint dwYSize;
        public uint dwXCountChars;
        public uint dwYCountChars;
        public uint dwFillAttribute;
        public uint dwFlags;
        public ushort wShowWindow;
        public ushort cbReserved2;
        public IntPtr lpReserved2;
        public IntPtr hStdInput;
        public IntPtr hStdOutput;
        public IntPtr hStdError;
    }

    /* PROCESS_INFORMATION 是 CreateProcessW 的"输出参数结构"：调用
     * 成功后，里面放着新进程的句柄（hProcess）、主线程句柄（hThread）
     * 以及它们的 ID（dwProcessId / dwThreadId）。后面我们等子进程退出、
     * 杀进程树，靠的都是 hProcess 和 dwProcessId。 */
    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_INFORMATION
    {
        public IntPtr hProcess;
        public IntPtr hThread;
        public uint dwProcessId;
        public uint dwThreadId;
    }

    /* SECURITY_ATTRIBUTES 是安全属性结构。这里只有一个字段对我们重要：
     * bInheritHandle = 1 表示"这个句柄可以被子进程继承"。因为
     * CreateProcess 时我们传了 bInheritHandles=true，所以日志文件的
     * 句柄必须标记为"可继承"，子进程才能真的往我们的日志文件里写。 */
    [StructLayout(LayoutKind.Sequential)]
    private struct SECURITY_ATTRIBUTES
    {
        public int nLength;
        public IntPtr lpSecurityDescriptor;
        public int bInheritHandle;
    }

    /* 下面这一大串 [DllImport] 就是 P/Invoke（Platform Invoke，平台调用）：
     * 它让我们的 C# 代码能够直接调用 Windows 原生的 Win32 API。
     * advapi32.dll 是"高级 API"库，服务相关的函数都在里面；
     * kernel32.dll 是内核 API 库，进程、句柄、作业对象相关的都在里面。
     * 两个通用约定：CharSet.Unicode 表示字符串按 UTF-16（宽字符）传递；
     * SetLastError=true 表示出错时可以通过 Marshal.GetLastWin32Error()
     * 拿到对应的 Win32 错误码，方便记录日志。 */

    /* StartServiceCtrlDispatcherW：服务的"总入口"。服务程序一启动就要
     * 调用它，把自己注册到 SCM，之后 SCM 才会回调我们的 ServiceMain。
     * 重点：这个函数只有在被 SCM 启动时才返回 true；而且正常情况下
     * 它会一直阻塞到服务停止才返回，所以它后面的代码平时根本执行不到。 */
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool StartServiceCtrlDispatcherW([In] SERVICE_TABLE_ENTRY[] lpServiceStartTable);

    /* RegisterServiceCtrlHandlerExW：注册"控制消息处理函数"（就是我们的
     * Handler 方法）。返回值是"状态句柄"，之后每次 SetServiceStatus
     * 都要用到它；如果返回 IntPtr.Zero 说明注册失败（比如 SCM 已经不
     * 认这个服务了），那就只能记日志退出了。 */
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr RegisterServiceCtrlHandlerExW(
        string lpServiceName, ServiceControlHandler lpHandlerProc, IntPtr lpContext);

    /* SetServiceStatus：把 SERVICE_STATUS 结构上报给 SCM，告诉它
     * "我现在是运行中 / 停止中 / 已停止"。SCM 就是靠这个知道
     * 服务当前处于什么状态的。 */
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool SetServiceStatus(IntPtr hServiceStatus, ref SERVICE_STATUS lpServiceStatus);

    /* CreateProcessW：Windows 创建新进程的核心函数，没有之一。
     * 参数多到吓人，但记住几个关键的就行：lpCommandLine 是命令行、
     * bInheritHandles 决定子进程是否继承父进程的句柄、
     * dwCreationFlags 是创建标志、lpCurrentDirectory 是工作目录、
     * lpStartupInfo 是启动信息、lpProcessInformation 是输出参数
     * （返回新进程的句柄和 ID）。 */
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CreateProcessW(
        string lpApplicationName,
        StringBuilder lpCommandLine,
        IntPtr lpProcessAttributes,
        IntPtr lpThreadAttributes,
        bool bInheritHandles,
        uint dwCreationFlags,
        IntPtr lpEnvironment,
        string lpCurrentDirectory,
        ref STARTUPINFO lpStartupInfo,
        out PROCESS_INFORMATION lpProcessInformation);

    /* CreateFileW：打开/创建文件的核心函数。我们用它来打开日志文件，
     * 以及打开 NUL 设备（相当于 Unix 的 /dev/null，所有写入都被丢弃）。 */
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateFileW(
        string lpFileName, uint dwDesiredAccess, uint dwShareMode,
        IntPtr lpSecurityAttributes, uint dwCreationDisposition,
        uint dwFlagsAndAttributes, IntPtr hTemplateFile);

    /* CreateJobObjectW：创建"作业对象"（Job Object）。作业对象是
     * Windows 用来管理一组进程的"容器"，把进程放进去之后，就可以
     * 对整个容器统一控制（比如一键终止所有进程）。 */
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateJobObjectW(IntPtr lpJobAttributes, string lpName);

    /* SetInformationJobObject：设置作业对象的属性。我们用它来打开
     * KILL_ON_JOB_CLOSE 这个"关句柄即杀进程"的开关（详见 Spawn 方法）。 */
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetInformationJobObject(
        IntPtr hJob, int JobObjectInformationClass, IntPtr lpJobObjectInformation,
        uint cbJobObjectInformationLength);

    /* AssignProcessToJobObject：把某个进程放进某个作业对象里。
     * 注意：一个进程同时只能属于一个作业（除非开了嵌套作业支持），
     * 所以如果进程已经被别的作业管着，这个调用会失败——Spawn 里
     * 专门处理了这个情况，会退回到 taskkill /T 方案。 */
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AssignProcessToJobObject(IntPtr hJob, IntPtr hProcess);

    /* TerminateJobObject：直接终止作业对象里的所有进程（强杀，
     * 不给任何清理的机会）。这是停止服务时"最后的雷霆手段"。 */
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool TerminateJobObject(IntPtr hJob, uint uExitCode);

    /* AttachConsole：把自己"挂到"另一个进程的控制台上。优雅停止时，
     * 我们要给子进程的控制台发 Ctrl-C，就得先挂到它的控制台上。 */
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AttachConsole(uint dwProcessId);

    /* GenerateConsoleCtrlEvent：向控制台发送 Ctrl-C 或 Ctrl-Break 事件，
     * 模拟用户按键，让子进程有机会自己清理资源然后优雅退出。 */
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GenerateConsoleCtrlEvent(uint dwCtrlEvent, uint dwProcessGroupId);

    /* SetConsoleCtrlHandler：安装/移除 Ctrl-C 处理函数。我们传入
     * IntPtr.Zero 并 Add=true，意思是"忽略 Ctrl-C"——这样发给子进程
     * 的 Ctrl-C 就不会连累我们自己被关掉（这是常见的"误伤"坑）。 */
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetConsoleCtrlHandler(IntPtr HandlerRoutine, bool Add);

    /* FreeConsole：把自己从控制台脱离。用完 AttachConsole 之后
     * 要记得 FreeConsole，不然就"赖"在子进程的控制台上不走了。 */
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool FreeConsole();

    /* WaitForSingleObject：等待一个"内核对象"变成有信号状态。
     * 最常见的用法是等一个进程句柄：进程退出时句柄会变成有信号，
     * 函数立刻返回；第二个参数是超时毫秒数，传 0xFFFFFFFF 表示
     * 无限等待（我们一般传具体毫秒数做"分段等待"）。 */
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WaitForSingleObject(IntPtr hHandle, uint dwMilliseconds);

    /* GetExitCodeProcess：查询进程的退出码。如果进程还在运行，
     * 返回的退出码就是 STILL_ACTIVE (259)，这点前面已经强调过。 */
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetExitCodeProcess(IntPtr hProcess, out uint lpExitCode);

    /* SearchPathW：按 Windows 的搜索规则（当前目录 -> PATH 环境变量）
     * 查找一个可执行文件，返回完整路径。nssm 找程序用的就是这套逻辑，
     * 我们完全照搬。 */
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint SearchPathW(string lpPath, string lpFileName, string lpExtension,
        uint nBufferLength, StringBuilder lpBuffer, out IntPtr lpFilePart);

    /* CloseHandle：关闭句柄。句柄是有限的系统资源，用完必须关，
     * 否则会造成"句柄泄漏"（handle leak），长时间运行的服务尤其
     * 要小心——泄漏多了系统资源耗尽，谁都别想干活。 */
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr hObject);

    // ------------------------------------------------------------------
    // per-service state
    // ------------------------------------------------------------------
    /* ------------------------------------------------------------------
     * 下面这些 static 字段是"每个服务实例"的全局状态。
     * 因为我们这个宿主进程只服务一个服务（OWN_PROCESS 模式），
     * 一个进程一个服务，所以用静态字段就完全够用了，
     * 没必要再搞一个复杂的状态对象传来传去。
     *
     * 特别提醒：stopping 被声明为 volatile。为什么？
     * 因为它会被 SCM 派生的"控制回调线程"修改（Handler 里置 true），
     * 而主线程在监督循环里不停地读它。两个线程访问同一个变量，
     * 不加 volatile 的话，可能因为 CPU 缓存、指令重排等原因，
     * 主线程读到"过期"的值，导致停止命令没反应——这是多线程
     * 编程最经典的坑之一，这里提前防住了。
     * ------------------------------------------------------------------ */

    private static string serviceName;
    private static IntPtr statusHandle;
    private static SERVICE_STATUS status;
    private static volatile bool stopping;
    private static readonly AutoResetEvent stopEvent = new AutoResetEvent(false);
    private static IntPtr childProcess = IntPtr.Zero;
    private static IntPtr childJob = IntPtr.Zero;
    private static uint childPid;
    private static int restartDelayMs = 5000;
    private static int killGraceMs = 10000;
    private static bool gracefulStop = false;
    private static string hostLogPath;

    /* 这两个委托引用必须保存成静态字段！为什么？因为 .NET 的垃圾
     * 回收器（GC）可能会把"只被非托管代码引用"的委托对象回收掉，
     * 而 SCM 那边还保存着函数指针、等着将来回调呢。一旦被回收，
     * 回调发生的那一刻程序就崩了（AccessViolationException）。
     * 保存成静态字段，就保证它们永远"可达"、永远不会被回收。
     * 这是 P/Invoke 回调最容易踩的坑之一，务必牢记。 */
    private static readonly ServiceControlHandler handlerDelegate = Handler;
    private static readonly ServiceMainCallback mainDelegate = ServiceMain;

    // ------------------------------------------------------------------
    // entry
    // ------------------------------------------------------------------
    /* 这一节是宿主程序的"入口"：Run 方法由 Program/Main 调用，
     * 是整个服务生命周期的起点。从外面看，服务程序一启动，
     * 第一件事就是跑到这里来注册自己。 */

    public static int Run(string name)
    {
        /* Run 是宿主进程的入口点，参数 name 是服务名（注册表里
         * 那个服务键的名字）。返回值是进程退出码：0 表示正常结束，
         * 1 表示出错了。 */
        serviceName = name;
        // Protect the host from being terminated by the Ctrl-C we send to the
        // child's console during a graceful stop (the .NET runtime installs a
        // default Ctrl-C handler that would otherwise kill this process).
        /* 中文翻译 + 解释：下面这段是"自我保护"。
         * 优雅停止时，我们会往子进程的控制台发 Ctrl-C（见 StopChild）。
         * 可是 .NET 运行时默认会给自己装一个 Ctrl-C 处理器：一收到
         * Ctrl-C 就把当前进程干掉。如果宿主自己也被 Ctrl-C 杀了，
         * 那谁来负责收尾（杀进程树、上报状态）呢？所以这里挂一个
         * 事件处理器，把 e.Cancel 设为 true，意思是"这个 Ctrl-C 我
         * 看到了，但请别杀我"。注意：控制台事件是"广播式"的，
         * 宿主和子进程共享同一个控制台时会同时收到信号。 */
        Console.CancelKeyPress += delegate(object sender, ConsoleCancelEventArgs e)
        {
            e.Cancel = true;
        };
        /* 构造"服务表"（SERVICE_TABLE_ENTRY 数组）：SCM 启动服务时，
         * 会按照这个表找到对应的入口函数来调用。表的大小是 2：
         * 第一个条目填上我们的服务名和 ServiceMain 入口；
         * 第二个条目是"哨兵"，全部填 null，表示表到此结束。 */
        SERVICE_TABLE_ENTRY[] table = new SERVICE_TABLE_ENTRY[2];
        table[0].lpServiceName = name;
        table[0].lpServiceProc = mainDelegate;
        table[1].lpServiceName = null;
        table[1].lpServiceProc = null;

        /* 调用 StartServiceCtrlDispatcherW，把控制权"交给"SCM。
         * 注意：这个函数正常情况下是"不返回"的——它会一直阻塞，
         * 直到服务生命周期结束才返回。所以它后面的代码，只有在
         * "注册失败"或者"服务已停止"这两种情况下才会执行到。
         * 如果服务不是由 SCM 启动的（比如管理员直接在命令行运行
         * systemwin.exe --host xxx），它立刻失败并返回错误 1063。 */
        if (!StartServiceCtrlDispatcherW(table))
        {
            uint err = (uint)Marshal.GetLastWin32Error();
            if (err == ERROR_FAILED_SERVICE_CONTROLLER_CONNECT)
            {
                /* 错误 1063：经典的"你不在 SCM 的管辖范围内"。
                 * 给用户一个明确、友好的提示，告诉他应该用
                 * sc start 之类的正规方式启动服务，
                 * 而不是直接双击/命令行跑 exe。 */
                Console.Error.WriteLine("SystemWin host: service '" + name
                    + "' must be started by the Service Control Manager (error 1063).");
            }
            else
            {
                /* 其他错误：把错误码打出来，方便排查。 */
                Console.Error.WriteLine("SystemWin host: StartServiceCtrlDispatcherW failed: " + err);
            }
            return 1;
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // service main + handler
    // ------------------------------------------------------------------
    /* 这一节是服务的"心脏"：ServiceMain 是服务真正的主函数，
     * Handler 是 SCM 控制消息的回调。两者通过全局的 stopping 标志
     * 和 stopEvent 事件协作，一个负责"干活"，一个负责"接命令"。 */

    private static void ServiceMain(int argc, IntPtr argv)
    {
        /* ServiceMain 是服务的真正"主函数"：SCM 通过服务表回调它，
         * 一旦进入这里，就说明服务被正式启动了。注意它运行在 SCM
         * 派生的独立线程上，跟 Run 方法里的主线程不是同一个线程。
         * 整个服务的生命周期（启动、监督、停止）都在这一个函数里
         * 管理，所以它比较长，我们一步一步拆开看。 */
        try
        {
            /* 第一步：注册控制消息处理函数。SCM 之后要通知我们
             * "停止 / 关机 / 查询状态"时，就会调用我们注册的 Handler。
             * statusHandle 这个状态句柄一定要保存好——后面每次
             * SetServiceStatus 上报状态都靠它。 */
            statusHandle = RegisterServiceCtrlHandlerExW(serviceName, handlerDelegate, IntPtr.Zero);
            if (statusHandle == IntPtr.Zero)
            {
                /* 注册失败：说明 SCM 已经不认我们了，只能记日志退出。
                 * 注意这里直接 return，没有调用 SetStatusStopped——
                 * 因为状态句柄都没有，想上报也是白搭。 */
                HostLog("RegisterServiceCtrlHandlerExW failed: " + Marshal.GetLastWin32Error());
                return;
            }
            /* 先上报"启动中"状态，waitHint=30000 意思是"给我最多 30 秒"。
             * 如果 30 秒内还没变成 RUNNING，SCM 就可能认为服务
             * 启动失败，甚至会记录系统错误。 */
            SetStatus(SERVICE_START_PENDING, 30000);
            HostLog("service starting (host)");

            /* 第二步：从注册表加载服务的配置——要启动哪个程序、
             * 带什么参数、日志文件在哪、要不要自动重启等等。
             * 如果配置读不到（比如注册表键被删了），直接停止服务。 */
            HostConfig cfg = HostConfig.Load(serviceName);
            if (cfg == null)
            {
                HostLog("configuration missing for service " + serviceName);
                SetStatusStopped(1);
                return;
            }
            /* 把配置里的参数搬到全局字段里：因为 Spawn、StopChild 等
             * 方法都是静态方法，直接读写全局字段最方便，不用把
             * cfg 传来传去。 */
            restartDelayMs = cfg.RestartDelayMs;
            killGraceMs = cfg.KillGraceMs;
            gracefulStop = cfg.GracefulStop;
            hostLogPath = cfg.HostLogPath;

            /* 第三步：主监督循环。这个循环是整个宿主的核心逻辑：
             *   spawn 子进程 -> 等它退出（或收到停止请求）->
             *   子进程自己退了就按配置决定是重启还是结束服务。
             * 只要 stopping 还是 false，这个循环就会一直转下去，
             * 从而保证被监督的服务"永远活着"。 */
            while (!stopping)
            {
                if (!Spawn(cfg))
                {
                    /* 子进程启动失败（比如程序路径配错了、文件不存在），
                     * 没有子进程可监督，只能停止服务并返回错误码 1，
                     * 让 SCM 和管理员知道出问题了。 */
                    HostLog("failed to start program '" + cfg.Application + "'");
                    SetStatusStopped(1);
                    return;
                }
                SetStatus(SERVICE_RUNNING, 0);
                HostLog("program started (pid " + childPid + ")");

                // wait for the child to exit or a stop request
                /* 中文翻译：内层循环，等待子进程退出，或者收到停止请求。
                 * 为什么不直接用 WaitForSingleObject 无限等下去？
                 * 因为还要同时响应"停止请求"：如果 SCM 发来停止命令，
                 * Handler 会把 stopping 置为 true，内层循环的 while
                 * 条件就立刻不满足了，从而跳出。
                 * 这就是用 WaitForSingleObject(childProcess, 1000)
                 * 做"分段等待"的原因——一次最多等 1 秒，等完看一眼
                 * 有没有停止请求，没有就继续等，循环往复。 */
                while (!stopping)
                {
                    if (WaitForSingleObject(childProcess, 1000) == WAIT_OBJECT_0)
                    {
                        break;
                    }
                }

                // A stop request keeps the child/job handles alive: the stop
                // path below must be able to kill the process tree.
                /* 中文翻译：如果是因为收到停止请求而跳出内层循环的
                 * （stopping 为 true），就直接跳出外层循环，进入下面的
                 * "停止路径"。注意：这时候千万不能关掉 childProcess /
                 * childJob 句柄——停止路径还要靠它们去杀进程树呢！
                 * 如果提前关了，后面想杀都杀不掉了，进程就成孤儿了。 */
                if (stopping)
                {
                    break;
                }

                // the child exited on its own
                /* 中文翻译：走到这里，说明是子进程自己退出的
                 * （不是我们让它停的）。先取它的退出码记到日志里，
                 * 方便以后排查"程序为什么自己退了"。 */
                uint exitCode = 0;
                GetExitCodeProcess(childProcess, out exitCode);
                if (exitCode == STILL_ACTIVE)
                {
                    /* 理论上走到这里进程已经退出了，不该还是 STILL_ACTIVE；
                     * 但为了稳妥起见，万一取到 259 就当成 0 处理，
                     * 避免日志里出现误导性的数字。 */
                    exitCode = 0;
                }
                CloseChildHandles();
                HostLog("program exited (code " + exitCode + ")");

                if (!cfg.RestartOnExit)
                {
                    /* 配置说"退出后不要重启"（AppExit 值里带 Ignore），
                     * 那服务就到此为止，正常停止，退出码 0。 */
                    HostLog("restart disabled; stopping service");
                    SetStatusStopped(0);
                    return;
                }
                HostLog("restarting in " + restartDelayMs + " ms");
                // wait out the restart delay, abort if a stop arrives
                /* 中文翻译：在重启之前，先等 restartDelayMs 毫秒。
                 * 为什么要有这个延迟？防止"崩溃风暴"：程序一崩就立刻
                 * 重启、再崩再重启，疯狂空转浪费资源。等一会儿让局面
                 * 冷静一下，也给管理员留出干预的时间。
                 * 等待期间用 stopEvent 来"监听"停止请求：如果 Handler
                 * 收到停止命令会 Set 这个事件，那么这里会立刻返回
                 * WAIT_OBJECT_0，从而跳出循环、放弃重启，直接走停止。
                 * （用事件等待而不是普通 Sleep，就是为了能被及时唤醒。） */
                if (WaitForSingleObject(stopEvent.SafeWaitHandle.DangerousGetHandle(),
                        (uint)restartDelayMs) == WAIT_OBJECT_0)
                {
                    break;
                }
            }

            // stop path: kill the process tree (graceful Ctrl-C optional)
            /* 中文翻译：走到这里就是"停止路径"了——要么收到了停止请求，
             * 要么重启等待期间被打断。流程是：先上报"停止中"状态，
             * 然后调用 StopChild 去收拾子进程（能优雅就优雅，不能就
             * 强杀，详见 StopChild），最后上报"已停止"，服务生命周期
             * 到此结束。 */
            HostLog("stopping (grace " + killGraceMs + " ms)");
            SetStatus(SERVICE_STOP_PENDING, (uint)killGraceMs);
            StopChild();
            SetStatusStopped(0);
            HostLog("service stopped");
        }
        catch (Exception e)
        {
            /* 整个服务主体包在一个大 try-catch 里：万一宿主自己出了
             * 什么幺蛾子（配置异常、奇怪的返回值、意料外的崩溃等），
             * 绝对不能就这么静默地死掉——必须把子进程树也干掉，
             * 防止留下没人管的孤儿进程继续在后台跑。然后记日志、
             * 上报已停止、返回错误码 1。 */
            HostLog("host error: " + e);
            // defensive: kill the child tree so nothing is orphaned
            /* 中文翻译：防御性清理。先尝试通过作业对象整体终止
             * （TerminateJobObject），再尝试用 taskkill /T 兜底
             * （KillTree）。两个都包在各自的 try-catch 里，因为
             * 清理失败也不能再抛异常了——我们本来就在处理异常的路上，
             * 不能再节外生枝。 */
            try
            {
                if (childJob != IntPtr.Zero)
                {
                    TerminateJobObject(childJob, 1);
                }
                if (childProcess != IntPtr.Zero && childPid != 0)
                {
                    KillTree(childPid);
                }
            }
            catch (Exception)
            {
                // ignore
                /* 忽略：清理已经尽力了，失败就算了，不能让异常
                 * 把整个进程再带崩一次。 */
            }
            SetStatusStopped(1);
        }
    }

    private static uint Handler(uint control, uint eventType, IntPtr eventData, IntPtr context)
    {
        /* Handler 是"控制消息处理函数"，由 SCM 在独立线程上回调。
         * 它有一个重要的设计原则：要尽量快、尽量短！因为 SCM 在等它
         * 返回，处理太久会拖慢系统关机、服务管理器等操作。
         * 所以这里只做两件"轻量"的事：设置标志位 + 唤醒事件，
         * 真正的收尾工作（杀进程树等重活）交给 ServiceMain 里的
         * 主监督循环去做——这就是典型的"生产者/消费者"分工模式。 */
        switch (control)
        {
            case SERVICE_CONTROL_STOP:
            case SERVICE_CONTROL_SHUTDOWN:
                /* 停止 / 关机：这是最重要的两个消息，处理方式完全一样。
                 * 做法：把 stopping 置为 true（主循环看到它就会跳出、
                 * 进入停止路径），同时 Set 一下 stopEvent（如果主循环
                 * 正卡在"重启等待"里，这个 Set 会立刻把它唤醒，
                 * 从而取消重启、直接走停止流程）。
                 * 返回 0 即 NO_ERROR，告诉 SCM"我收到并接受了"。 */
                stopping = true;
                stopEvent.Set();
                return 0; // NO_ERROR
            case SERVICE_CONTROL_INTERROGATE:
                /* 查询状态：SCM 想知道我们现在是什么状态。
                 * 返回 0 就行——状态本身已经由 SetServiceStatus
                 * 定期上报给 SCM 了，这里只是"确认收到"。 */
                return 0;
            default:
                /* 其他不认识的控件：返回标准错误码
                 * ERROR_CALL_NOT_IMPLEMENTED（未实现），
                 * 礼貌地告诉 SCM"这个我不会，别指望我"。 */
                return 0x80004001; // ERROR_CALL_NOT_IMPLEMENTED
        }
    }

    // ------------------------------------------------------------------
    // child process management
    // ------------------------------------------------------------------
    /* 这一节是"子进程管理"：启动子进程（Spawn）、打开日志文件
     * （OpenLog）、构造环境块（BuildEnvironment）、停止子进程
     * （StopChild）、解析可执行文件路径（ResolveExecutable）等等。
     * 简单说，这节负责"把程序拉起来"和"把程序弄死"的所有细节。 */

    private static bool Spawn(HostConfig cfg)
    {
        /* Spawn 的任务：把配置里指定的程序启动为子进程。
         * 返回 true 表示启动成功，false 表示失败（调用者会据此
         * 决定是否停止服务）。这个方法做的事情比较多，
         * 我们一步一步拆开看，别着急。 */
        // Resolve the executable the way nssm does: an absolute path is used
        // as-is, a bare name (or .\name) is resolved against the working
        // directory first and then against PATH.
        /* 中文翻译：第一步，先解析出可执行文件的完整路径。
         * 规则跟 nssm 一模一样：给的是绝对路径就直接用；
         * 给的是裸文件名（比如 myapp.exe）或者 .\myapp.exe 这种
         * 相对路径，就先用"工作目录"去找，找不到再去 PATH 环境变量
         * 里翻。这个逻辑具体在 ResolveExecutable 方法里实现。 */
        string resolved = ResolveExecutable(cfg.Application, cfg.Directory);
        if (resolved == null)
        {
            HostLog("program not found: '" + cfg.Application
                + "' (searched working directory and PATH)");
            return false;
        }
        // command line: "program" args...
        /* 中文翻译：第二步，拼命令行。格式是"程序路径 参数..."。
         * 程序路径用 Quote 包一层引号，防止路径里有空格导致
         * CreateProcessW 按空格分词时把路径拆成两半。 */
        StringBuilder cmdline = new StringBuilder(Quote(resolved));
        if (!string.IsNullOrEmpty(cfg.Parameters))
        {
            cmdline.Append(' ').Append(cfg.Parameters);
        }

        // stdout/stderr -> log files (or NUL); the handles must be
        // inheritable because CreateProcess runs with bInheritHandles=true.
        /* 中文翻译：第三步，准备标准输入输出句柄。
         * stdout 和 stderr 都重定向到日志文件（没配置就写 NUL，
         * 也就是 Windows 版的 /dev/null，所有输出直接丢弃）。
         * 关键点：这些句柄必须设置 bInheritHandle=1（可继承），
         * 因为等会儿 CreateProcessW 要传 bInheritHandles=true，
         * 子进程才能真的用上这些句柄。 */
        SECURITY_ATTRIBUTES sa = new SECURITY_ATTRIBUTES();
        sa.nLength = Marshal.SizeOf(typeof(SECURITY_ATTRIBUTES));
        sa.bInheritHandle = 1;
        IntPtr psa = Marshal.AllocHGlobal(Marshal.SizeOf(sa));
        Marshal.StructureToPtr(sa, psa, false);

        /* 标准输入直接接到 NUL（服务场景下子进程一般不需要读输入），
         * 标准输出和标准错误输出分别接到各自的日志文件。 */
        IntPtr hIn = CreateFileW("NUL", GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
            psa, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, IntPtr.Zero);
        IntPtr hOut = OpenLog(cfg.StdoutLog, psa);
        IntPtr hErr = OpenLog(cfg.StderrLog, psa);

        /* 把三个句柄塞进 STARTUPINFO，并设置 STARTF_USESTDHANDLES
         * 标志，告诉 Windows："新进程的 stdio 就用我这三个句柄"。 */
        STARTUPINFO si = new STARTUPINFO();
        si.cb = Marshal.SizeOf(typeof(STARTUPINFO));
        si.dwFlags = STARTF_USESTDHANDLES;
        si.hStdInput = hIn;
        si.hStdOutput = hOut;
        si.hStdError = hErr;

        /* 如果有额外环境变量（AppEnvironmentExtra 配置），就构造一个
         * 环境块传给 CreateProcessW；没有就传 IntPtr.Zero，
         * 表示让子进程继承父进程（宿主）的完整环境。 */
        IntPtr envPtr = IntPtr.Zero;
        if (cfg.EnvironmentExtra != null && cfg.EnvironmentExtra.Count > 0)
        {
            envPtr = BuildEnvironment(cfg.EnvironmentExtra);
        }

        /* 核心一步：调用 CreateProcessW 创建子进程。
         * 几个参数的含义：
         *   lpApplicationName 传 null——可执行文件路径放在 cmdline 里；
         *   bInheritHandles 传 true——子进程要继承我们的日志句柄；
         *   dwCreationFlags 传 CREATE_NEW_CONSOLE——给子进程单独的
         *     控制台，这样优雅停止时才能给它发 Ctrl-C；
         *   lpCurrentDirectory 用配置里的 Directory（可为空）。
         * 输出参数 pi 里会拿到子进程的句柄和 PID。 */
        PROCESS_INFORMATION pi;
        bool ok = CreateProcessW(
            null,
            cmdline,
            IntPtr.Zero,
            IntPtr.Zero,
            true,
            CREATE_NEW_CONSOLE,
            envPtr,
            string.IsNullOrEmpty(cfg.Directory) ? null : cfg.Directory,
            ref si,
            out pi);
        int createError = ok ? 0 : Marshal.GetLastWin32Error();
        /* 创建完成之后，无论成功失败，都要把临时申请的非托管内存和
         * 文件句柄释放掉：envPtr（Marshal.FreeHGlobal）、三个文件句柄
         * （CloseHandle）、psa（Marshal.FreeHGlobal）。
         * 记住一条铁律：Win32 句柄和 AllocHGlobal 都属于"非托管资源"，
         * .NET 的 GC 不会帮我们回收，不手动释放就是泄漏。
         * 注意三个文件句柄要判空，因为 CreateFileW 失败会返回
         * INVALID_HANDLE_VALUE，乱关无效句柄会出问题。 */
        if (envPtr != IntPtr.Zero)
        {
            Marshal.FreeHGlobal(envPtr);
        }
        if (hIn != IntPtr.Zero && hIn != INVALID_HANDLE_VALUE)
        {
            CloseHandle(hIn);
        }
        if (hOut != IntPtr.Zero && hOut != INVALID_HANDLE_VALUE)
        {
            CloseHandle(hOut);
        }
        if (hErr != IntPtr.Zero && hErr != INVALID_HANDLE_VALUE)
        {
            CloseHandle(hErr);
        }
        Marshal.FreeHGlobal(psa);
        if (!ok)
        {
            HostLog("CreateProcessW failed (" + createError + ") for: " + cmdline.ToString()
                + " dir=" + (string.IsNullOrEmpty(cfg.Directory) ? "" : cfg.Directory));
            return false;
        }

        /* 启动成功了！把子进程的句柄和 PID 记到全局字段里，
         * 后面的监督循环和停止路径都要用它们。
         * 主线程句柄 pi.hThread 我们用不上，立刻关掉（否则泄漏）。 */
        childProcess = pi.hProcess;
        childPid = pi.dwProcessId;
        if (pi.hThread != IntPtr.Zero)
        {
            CloseHandle(pi.hThread);
        }

        // put the child (and its descendants) in a job that kills the whole
        // tree when the job is terminated or closed
        /* 中文翻译：第四步，创建作业对象（Job Object），把子进程
         * "装进去"。作业对象的好处：它可以管住子进程派生出来的
         * 所有孙进程（也就是整棵进程树）。等会儿停止服务时，
         * 只要终止作业对象，整棵树一起死，不会留下孤儿进程。 */
        childJob = CreateJobObjectW(IntPtr.Zero, null);
        if (childJob != IntPtr.Zero)
        {
            // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE is LimitFlags at offset 16 of
            // JOBOBJECT_BASIC_LIMIT_INFORMATION; build the buffer directly to
            // avoid struct-layout marshaling pitfalls.
            /* 中文翻译：设置 KILL_ON_JOB_CLOSE 标志。
             * 这个标志位于 JOBOBJECT_BASIC_LIMIT_INFORMATION 结构里
             * 偏移 16 字节处的 LimitFlags 字段上。这里故意不定义
             * C# 结构体去做 marshaling，而是直接开一个 64 字节的
             * 缓冲区、把标志值写到第 16 字节——因为这种老 C 结构的
             * 字段布局太容易踩坑（对齐、补位、pack 规则），
             * 直接按字节写最保险、最不容易出错。 */
            byte[] buf = new byte[64];
            byte[] flags = BitConverter.GetBytes((uint)JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
            Array.Copy(flags, 0, buf, 16, 4);
            IntPtr mem = Marshal.AllocHGlobal(64);
            Marshal.Copy(buf, 0, mem, 64);
            bool setOk = SetInformationJobObject(childJob, JobObjectBasicLimitInformation, mem, 64);
            Marshal.FreeHGlobal(mem);
            if (!setOk)
            {
                /* 设置失败也不致命：最多就是"关作业句柄时不会自动杀
                 * 进程"，我们还有 taskkill /T 兜底方案。记个日志继续跑。 */
                HostLog("SetInformationJobObject(KILL_ON_JOB_CLOSE) failed: "
                    + Marshal.GetLastWin32Error());
            }
            if (!AssignProcessToJobObject(childJob, childProcess))
            {
                // already in a job (e.g. managed by the OS): fall back to taskkill
                /* 中文翻译：把子进程放进作业失败了。最常见的原因是：
                 * 子进程已经被别的作业对象管着（比如某些系统服务、
                 * 或者被其他程序用作业包了）。Windows 规定一个进程
                 * 同时只能属于一个作业（没开嵌套支持的话），所以这里
                 * 只能放弃作业方案：关掉作业句柄，退回到 taskkill /T。 */
                HostLog("AssignProcessToJobObject failed (" + Marshal.GetLastWin32Error()
                    + "); will use taskkill /T on stop");
                CloseHandle(childJob);
                childJob = IntPtr.Zero;
            }
            else
            {
                HostLog("process assigned to job (kill-on-close)");
            }
        }
        return true;
    }

    private static IntPtr OpenLog(string path, IntPtr securityAttributes)
    {
        /* OpenLog 的作用：打开一个日志文件（用"打开或创建 + 追加"的
         * 方式），返回文件句柄，供子进程当 stdout/stderr 用。
         * 如果没配置路径（空字符串），就打开 NUL 设备——所有写入
         * 都被丢弃，等价于"不记日志"，这比真的开个文件还省事。 */
        if (string.IsNullOrEmpty(path))
        {
            return CreateFileW("NUL", GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                securityAttributes, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, IntPtr.Zero);
        }
        /* 如果日志路径带目录（比如 C:\logs\app.log 的 C:\logs 部分），
         * 先确保目录存在。注意：Directory.CreateDirectory 在目录
         * 已存在时不会报错，所以可以直接放心调用，不用先判断。 */
        string dir = Path.GetDirectoryName(path);
        if (!string.IsNullOrEmpty(dir))
        {
            try
            {
                Directory.CreateDirectory(dir);
            }
            catch (Exception)
            {
                // best effort
                /* 尽力而为：目录创建失败就算了，后面 CreateFileW
                 * 大概率也会失败，但至少不能在这里把宿主搞崩。 */
            }
        }
        return CreateFileW(path, GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
            securityAttributes, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, IntPtr.Zero);
    }

    private static IntPtr BuildEnvironment(List<string> extras)
    {
        /* BuildEnvironment 的作用：构造 CreateProcessW 需要的"环境块"
         * （environment block）。环境块是一种特殊的内存布局：一串
         * "KEY=VALUE\0" 字符串紧挨着排在一起，最后再以一个额外的 \0
         * 结尾表示"环境块到此为止"。
         * 步骤：先把父进程（宿主）的完整环境复制一份，再叠加上
         * 额外配置的键值对（后面的覆盖前面的），最后编码成环境块。 */
        Dictionary<string, string> env = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        /* 用 OrdinalIgnoreCase 做键比较：Windows 环境变量名不区分
         * 大小写（Path 和 PATH 是同一个变量！），字典必须按这个规则
         * 去重，否则同一个变量可能出现两份，行为就乱了。 */
        IDictionary raw = Environment.GetEnvironmentVariables();
        foreach (DictionaryEntry e in raw)
        {
            env[(string)e.Key] = (string)e.Value;
        }
        if (extras != null)
        {
            /* 处理额外配置：AppEnvironmentExtra 里每一项都是
             * "KEY=VALUE" 格式。找到第一个 '=' 把键值拆开，
             * 塞进字典（键相同就覆盖父进程的原有值）。
             * 注意 idx > 0 这个判断：键不能是空串，像 "=VALUE"
             * 这种畸形条目直接忽略，不写进环境。 */
            foreach (string kv in extras)
            {
                int idx = kv.IndexOf('=');
                if (idx > 0)
                {
                    env[kv.Substring(0, idx)] = kv.Substring(idx + 1);
                }
            }
        }
        /* 按环境块的内存布局拼字符串：每个 KEY=VALUE 后面跟一个 \0
         * （空字符），全部拼完后再多加一个 \0 表示环境块结束。
         * 最后用 StringToHGlobalUni 拷到非托管内存，返回指针——
         * 这个指针等会儿直接传给 CreateProcessW。 */
        StringBuilder block = new StringBuilder();
        foreach (KeyValuePair<string, string> kv in env)
        {
            block.Append(kv.Key).Append('=').Append(kv.Value).Append('\0');
        }
        block.Append('\0');
        return Marshal.StringToHGlobalUni(block.ToString());
    }

    private static void StopChild()
    {
        /* StopChild 是"停止路径"的核心：想办法让子进程（以及它的
         * 整个进程树）停下来。策略分两步走：先尝试优雅停止
         * （发 Ctrl-C，给宽限期让程序自己清理退出），不行就强杀。
         * 这个"先礼后兵"的设计，能让大多数正常程序体面地退出。 */
        if (childProcess == IntPtr.Zero)
        {
            /* 根本没有子进程句柄（比如还没 spawn 就收到停止命令），
             * 那就没什么可收拾的，直接返回。 */
            return;
        }
        // 1. optional graceful stop: console Ctrl-C (host ignores the event)
        /* 中文翻译：第一步，如果配置了优雅停止（AppGracefulStop），
         * 就给子进程的控制台发一个 Ctrl-C，模拟用户按键，
         * 让程序自己清理资源然后退出。 */
        if (gracefulStop && childPid != 0)
        {
            /* AttachConsole 把我们"挂到"子进程的控制台上——因为
             * Ctrl-C 只能发给"控制台"，我们得先跟它共享同一个控制台
             * 才能把信号送进去。 */
            bool attached = AttachConsole(childPid);
            if (attached)
            {
                /* 挂上之后，先让自己忽略 Ctrl-C（SetConsoleCtrlHandler
                 * 传 IntPtr.Zero 表示"忽略"），不然信号发出来我们自己
                 * 先倒了（Run 方法里挂的 CancelKeyPress 是双保险）。
                 * 然后 GenerateConsoleCtrlEvent(CTRL_C_EVENT, 0) 把
                 * Ctrl-C 广播给控制台上所有进程（0 表示"全体"）。
                 * 发完赶紧 FreeConsole 脱离，别赖在人家控制台上。 */
                SetConsoleCtrlHandler(IntPtr.Zero, true);
                GenerateConsoleCtrlEvent(CTRL_C_EVENT, 0);
                FreeConsole();
                /* 给子进程 killGraceMs 毫秒的宽限期：如果它收到
                 * Ctrl-C 后自己退出了，万事大吉，关闭句柄返回。 */
                if (WaitForSingleObject(childProcess, (uint)killGraceMs) == WAIT_OBJECT_0)
                {
                    HostLog("program exited gracefully");
                    CloseChildHandles();
                    return;
                }
                /* 宽限期到了还没退：说明程序不理会 Ctrl-C
                 * （或者它根本没有控制台交互），只能走第二步强杀。 */
                HostLog("grace period elapsed; killing process tree");
            }
            else
            {
                /* AttachConsole 失败（比如子进程没有控制台）：
                 * 跳过优雅停止这一步，直接进入强杀流程。 */
                HostLog("AttachConsole failed (" + Marshal.GetLastWin32Error() + "); killing process tree");
            }
        }
        // 2. kill the whole tree
        /* 中文翻译：第二步，强杀整棵进程树。优先用作业对象
         * （TerminateJobObject 一下，整棵树全没），作业对象不可用
         * 就用 taskkill /PID xxx /T /F 兜底。 */
        if (childJob != IntPtr.Zero)
        {
            HostLog("terminating job object");
            TerminateJobObject(childJob, 1);
        }
        else
        {
            HostLog("killing process tree via taskkill");
            KillTree(childPid);
        }
        /* 杀完之后再等等子进程句柄（最多 15 秒），确认它真的没了，
         * 然后关闭句柄、清理全局状态。这个等待是必要的：
         * 万一进程正在做最后的清理，等它一下更稳妥。 */
        WaitForSingleObject(childProcess, 15000);
        CloseChildHandles();
    }

    private static string SearchPath(string fileName)
    {
        /* SearchPath 是对 SearchPathW 的简单包装：按系统的搜索规则
         * （当前目录 -> PATH 环境变量里的各个目录）找一个文件，
         * 找到就返回完整路径，找不到返回 null。
         * 缓冲区开 32768 字节是给路径留足空间（Windows 长路径的
         * 上限是 32767 个字符），免得路径太长被截断。 */
        StringBuilder sb = new StringBuilder(32768);
        IntPtr filePart;
        uint len = SearchPathW(null, fileName, null, (uint)sb.Capacity, sb, out filePart);
        if (len > 0 && len < sb.Capacity)
        {
            /* 返回值大于 0 且小于缓冲区容量，说明找到了；
             * 如果等于容量，说明缓冲区不够大（结果被截断了），
             * 这种情况下宁可返回 null，也不给调用者一个残缺路径。 */
            return sb.ToString();
        }
        return null;
    }

    private static string ResolveExecutable(string exe, string directory)
    {
        /* ResolveExecutable：把用户配置的"程序"解析成可执行文件的
         * 完整路径。规则完全模仿 nssm，按优先级排列：
         *   1. 绝对路径（C:\...、\\server\...）直接返回；
         *   2. 相对路径（.\x.exe、sub\x.exe）在工作目录下找；
         *   3. 裸文件名（x.exe）先在工作目录找，再去 PATH 里找；
         *   4. 裸文件名没写扩展名的话，再补一个 .exe 试一遍。
         * 全部找不到就返回 null，让调用者去报错。 */
        if (string.IsNullOrEmpty(exe))
        {
            return null;
        }
        string name = exe.Trim();
        if (name.Length == 0)
        {
            /* 去掉首尾空格后是空串，等于没配置，返回 null。 */
            return null;
        }
        if (Path.IsPathRooted(name))
        {
            /* 绝对路径：直接信任用户，原样返回。
             * 注意：这里不检查文件是否存在——让 CreateProcessW
             * 去报错，那样错误信息更准确，也省得重复检查。 */
            return name;
        }
        bool hasSeparator = name.IndexOf('\\') >= 0 || name.IndexOf('/') >= 0;
        if (hasSeparator)
        {
            // relative path (.\x.exe, sub\x.exe): resolve against the working directory
            /* 中文翻译：路径里带了目录分隔符，说明是相对路径。
             * 相对路径只能相对于配置的工作目录解析，不去 PATH 里找
             * （相对路径和裸文件名的处理规则是不同的，别搞混）。 */
            if (!string.IsNullOrEmpty(directory))
            {
                string inDir = Path.Combine(directory, name);
                if (File.Exists(inDir))
                {
                    return inDir;
                }
            }
            /* 工作目录里没找到（或者根本没配置工作目录）：返回 null。 */
            return null;
        }
        // bare name: working directory first, then PATH
        /* 中文翻译：裸文件名，按"先工作目录、后 PATH"的顺序找。 */
        if (!string.IsNullOrEmpty(directory))
        {
            string inDir = Path.Combine(directory, name);
            if (File.Exists(inDir))
            {
                return inDir;
            }
        }
        string fromPath = SearchPath(name);
        if (fromPath != null)
        {
            return fromPath;
        }
        // bare name without extension: also try with .exe
        /* 中文翻译：裸文件名还没带扩展名（比如只写了 myapp，
         * 而不是 myapp.exe），就再补一个 .exe 试试——这是 Windows
         * 用户的老习惯，经常会省略扩展名，我们帮他补上。 */
        if (!Path.HasExtension(name))
        {
            string withExt = name + ".exe";
            if (!string.IsNullOrEmpty(directory))
            {
                string inDir = Path.Combine(directory, withExt);
                if (File.Exists(inDir))
                {
                    return inDir;
                }
            }
            string fromPathExt = SearchPath(withExt);
            if (fromPathExt != null)
            {
                return fromPathExt;
            }
        }
        /* 所有招都试过了还是找不到，返回 null，让调用者报错。 */
        return null;
    }

    private static void CloseChildHandles()
    {
        /* CloseChildHandles：清理"子进程相关的全部资源"。
         * 只有在子进程已经确认退出（或已被杀掉）之后才能调用。
         * 注意顺序：先关进程句柄，再关作业对象，最后把 PID 清零，
         * 保证全局状态一致——这样下次 Spawn 才能干净地重新开始，
         * 不会残留上一轮的句柄。 */
        if (childProcess != IntPtr.Zero)
        {
            CloseHandle(childProcess);
            childProcess = IntPtr.Zero;
        }
        CloseJob();
        childPid = 0;
    }

    private static void KillTree(uint pid)
    {
        /* KillTree：用 taskkill.exe 强杀进程树，是作业对象方案的
         * "兜底"。taskkill 的三个参数含义：
         *   /PID 指定要杀的进程；
         *   /T   连同子进程一起杀（整棵树）；
         *   /F   强制杀（不给程序清理的机会）。
         * 注意：这个方案是"尽力而为"的，如果目标进程有权限保护
         * 或已经变成僵尸，taskkill 也可能失败。 */
        if (pid == 0)
        {
            return;
        }
        try
        {
            Process p = new Process();
            p.StartInfo.FileName = "taskkill.exe";
            p.StartInfo.Arguments = "/PID " + pid + " /T /F";
            p.StartInfo.CreateNoWindow = true;
            p.StartInfo.UseShellExecute = false;
            p.Start();
            /* 等它跑完，最多等 15 秒（taskkill 自己也可能卡住）。 */
            p.WaitForExit(15000);
        }
        catch (Exception)
        {
            // ignore
            /* 失败就忽略：这已经是兜底方案了，再失败也没有别的招，
             * 至少不能让异常从这里冒出去把宿主搞崩。 */
        }
    }

    private static void CloseJob()
    {
        /* CloseJob：关闭作业对象句柄。这里有个"隐藏的威力"：
         * 因为我们开了 KILL_ON_JOB_CLOSE 标志，关掉这个句柄的瞬间，
         * 作业里所有还活着的进程都会被强杀。所以这个方法只能在
         * "确实想让进程树死掉"的时候调用——比如子进程已经退出、
         * 或者停止流程已经杀完进程之后。 */
        if (childJob != IntPtr.Zero)
        {
            CloseHandle(childJob);
            childJob = IntPtr.Zero;
        }
    }

    // ------------------------------------------------------------------
    // status + logging
    // ------------------------------------------------------------------
    /* ------------------------------------------------------------------
     * 这一节是两个"小工具"：SetStatus 系列负责把服务状态上报给 SCM，
     * HostLog 负责写宿主自己的日志。它们本身的逻辑很简单，但被整个
     * 文件到处调用，所以单独拎出来做成方法，避免到处重复代码。
     * ------------------------------------------------------------------ */

    private static void SetStatus(uint state, uint waitHint)
    {
        /* SetStatus：填充 SERVICE_STATUS 结构，然后通过 SetServiceStatus
         * 上报给 SCM。
         * state 是目标状态（START_PENDING / RUNNING / STOP_PENDING...），
         * waitHint 是"我预计还要多久才能完成状态切换"的毫秒数，
         * 只在 PENDING（过渡中）状态时有意义——告诉 SCM"别急，
         * 我还在忙，大概还要这么久"，防止 SCM 超时误判。 */
        status.dwServiceType = SERVICE_WIN32_OWN_PROCESS;
        status.dwCurrentState = state;
        /* 声明接受 STOP 和 SHUTDOWN 两种控制消息。注意：这个声明必须
         * 一直保持，否则 SCM 在系统关机时不会通知我们，子进程就
         * 来不及收尾了（数据可能没保存、日志可能没写完）。 */
        status.dwControlsAccepted = SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_SHUTDOWN;
        status.dwWin32ExitCode = 0;
        status.dwServiceSpecificExitCode = 0;
        status.dwCheckPoint = 0;
        status.dwWaitHint = waitHint;
        SetServiceStatus(statusHandle, ref status);
    }

    private static void SetStatusStopped(uint specificExitCode)
    {
        /* SetStatusStopped：快捷方法——直接把状态置为"已停止"。
         * 注意：specificExitCode 这个参数目前其实没被用到
         * （SERVICE_STOPPED 状态下 SCM 主要看 dwWin32ExitCode），
         * 保留它是为了将来扩展"服务特定退出码"的语义，先占个位。 */
        SetStatus(SERVICE_STOPPED, 0);
    }

    private static void HostLog(string message)
    {
        /* HostLog：往宿主自己的日志文件追加一行带时间戳的记录。
         * 整个方法体都包在 try-catch 里，因为"日志写失败绝对不能
         * 影响服务运行"——日志只是辅助手段，不是主功能，
         * 为了写日志把服务搞崩就本末倒置了。 */
        try
        {
            if (string.IsNullOrEmpty(hostLogPath))
            {
                /* 没配置日志路径：直接静默返回（不记日志）。
                 * 这也是一种合法的配置——有些人就不想要日志。 */
                return;
            }
            /* 和 OpenLog 一样，先确保日志目录存在。 */
            string dir = Path.GetDirectoryName(hostLogPath);
            if (!string.IsNullOrEmpty(dir))
            {
                Directory.CreateDirectory(dir);
            }
            /* 追加一行，带时间戳（yyyy-MM-dd HH:mm:ss 格式），
             * 方便以后按时间顺序排查问题。 */
            File.AppendAllText(hostLogPath,
                DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "  " + message + Environment.NewLine);
        }
        catch (Exception)
        {
            // never let logging break the service
            /* 中文翻译：日志写失败就吞掉异常，绝不因为日志问题
             * 让服务崩溃——这行注释本身就是一条重要的设计原则：
             * "日志可以丢，服务不能挂"。 */
        }
    }

    private static string Quote(string s)
    {
        /* Quote：给命令行参数加引号。规则：字符串里有空格、
         * 且开头还不是引号时，才包一层引号。
         * 为什么要加引号？因为 CreateProcessW 解析命令行时按空格
         * 分词，路径里有空格而不加引号，就会被拆成两个"词"，
         * 程序就找不到了——这是 Windows 命令行处理最经典的坑。 */
        if (s.Contains(" ") && !s.StartsWith("\""))
        {
            return "\"" + s + "\"";
        }
        return s;
    }

    // ------------------------------------------------------------------
    // configuration (registry Parameters, nssm-style)
    // ------------------------------------------------------------------
    /* ------------------------------------------------------------------
     * HostConfig：服务的配置模型。所有字段都来自注册表
     * HKLM\SYSTEM\CurrentControlSet\Services\&lt;服务名&gt;\Parameters
     * 下的键值，完全沿用 nssm 的命名风格（App* 前缀）。
     * 为什么要用注册表而不是配置文件？因为这是 nssm 的惯例，
     * 而且服务安装工具（sc create 等）本来就往注册表写东西，
     * 统一放注册表里，管理起来最顺。
     * ------------------------------------------------------------------ */

    private sealed class HostConfig
    {
        /* 下面每个字段对应注册表 Parameters 键下的一个值：
         *   Application      -> Application        （要启动的程序）
         *   Parameters       -> AppParameters      （命令行参数）
         *   Directory        -> AppDirectory       （工作目录）
         *   EnvironmentExtra -> AppEnvironmentExtra（额外环境变量，字符串数组）
         *   StdoutLog        -> AppStdout          （标准输出日志文件）
         *   StderrLog        -> AppStderr          （标准错误日志文件）
         *   HostLogPath      -> AppHostLog         （宿主自己的日志文件）
         *   RestartOnExit    -> AppExit            （含"Ignore"= 退出后不重启）
         *   RestartDelayMs   -> AppRestartDelay    （重启前等待的毫秒数）
         *   KillGraceMs      -> AppKillGraceMs     （优雅停止的宽限期毫秒数）
         *   GracefulStop     -> AppGracefulStop    （是否先发 Ctrl-C）
         * 所有字段的默认值都是"安全值"：就算注册表里什么都没配，
         * 服务也能用默认行为正常跑起来，不会因为缺配置就崩。 */
        public string Application;
        public string Parameters;
        public string Directory;
        public List<string> EnvironmentExtra = new List<string>();
        public string StdoutLog;
        public string StderrLog;
        public string HostLogPath;
        public bool RestartOnExit = true;
        public int RestartDelayMs = 5000;
        public int KillGraceMs = 10000;
        public bool GracefulStop = false;

        public static HostConfig Load(string name)
        {
            /* Load：从注册表读取配置并组装成 HostConfig 对象。
             * 找不到配置（键不存在或读取异常）就返回 null，
             * 调用方（ServiceMain）会据此停止服务。
             * 所有读取都包在 try-catch 里：注册表可能被意外改坏
             * （类型不对、权限不够），格式不对就吞掉异常返回 null
             * 或保持默认值，绝不能让宿主进程崩掉。 */
            try
            {
                using (RegistryKey key = Registry.LocalMachine.OpenSubKey(
                    "SYSTEM\\CurrentControlSet\\Services\\" + name + "\\Parameters"))
                {
                    if (key == null)
                    {
                        /* 注册表键不存在：说明服务没装好（缺少配套的
                         * 注册表配置），返回 null，让上层去处理。 */
                        return null;
                    }
                    HostConfig cfg = new HostConfig();
                    /* 读取各项配置。注意 GetValue 的第二个参数是
                     * "默认值"：键不存在时返回默认值而不是抛异常，
                     * 这样缺省配置也能正常跑。 */
                    cfg.Application = (string)key.GetValue("Application", "");
                    cfg.Parameters = (string)key.GetValue("AppParameters", "");
                    cfg.Directory = (string)key.GetValue("AppDirectory", "");
                    /* AppEnvironmentExtra 在注册表里是"多字符串"类型
                     * （REG_MULTI_SZ），读出来是 string[] 数组，
                     * 直接转成 List 存起来。 */
                    string[] env = (string[])key.GetValue("AppEnvironmentExtra");
                    if (env != null)
                    {
                        cfg.EnvironmentExtra.AddRange(env);
                    }
                    cfg.StdoutLog = (string)key.GetValue("AppStdout", "");
                    cfg.StderrLog = (string)key.GetValue("AppStderr", "");
                    cfg.HostLogPath = (string)key.GetValue("AppHostLog", "");
                    /* AppExit 决定程序退出后是否重启：按 nssm 的约定，
                     * 值里带 "Ignore" 就表示"退出后别管我"，
                     * 其他值（比如 "Default Restart"）表示要自动重启。 */
                    string appExit = (string)key.GetValue("AppExit", "Default Restart");
                    cfg.RestartOnExit = !appExit.Contains("Ignore");
                    /* 下面三个数值型配置都要单独 try-catch：
                     * 注册表里的值类型可能不对（比如管理员配成了字符串
                     * 而不是整数），强转 (int) 会抛 InvalidCastException，
                     * 这时候保持默认值就好，不影响大局。 */
                    object delay = key.GetValue("AppRestartDelay");
                    if (delay != null)
                    {
                        try
                        {
                            cfg.RestartDelayMs = (int)delay;
                        }
                        catch (Exception)
                        {
                            // keep default
                            /* 转换失败就保留默认值 5000 毫秒。 */
                        }
                    }
                    object grace = key.GetValue("AppKillGraceMs");
                    if (grace != null)
                    {
                        try
                        {
                            cfg.KillGraceMs = (int)grace;
                        }
                        catch (Exception)
                        {
                            // keep default
                            /* 转换失败就保留默认值 10000 毫秒。 */
                        }
                    }
                    object graceful = key.GetValue("AppGracefulStop");
                    if (graceful != null)
                    {
                        try
                        {
                            /* 注册表里没有 bool 类型，用整数存：
                             * 0 表示关（不优雅），非 0 表示开（先发 Ctrl-C）。 */
                            cfg.GracefulStop = ((int)graceful) != 0;
                        }
                        catch (Exception)
                        {
                            // keep default
                            /* 转换失败就保留默认值 false（不强求优雅）。 */
                        }
                    }
                    return cfg;
                }
            }
            catch (Exception)
            {
                /* 打开注册表本身失败（权限不足、注册表损坏等）：
                 * 返回 null，让上层停止服务并记录错误。 */
                return null;
            }
        }
    }
}
