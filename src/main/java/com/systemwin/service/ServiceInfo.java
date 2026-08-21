package com.systemwin.service;

// ============================================================================
// 注意：下面这个类虽然看起来很小，但它其实是整个 SystemWin 程序里非常重要的一环。
// 它负责“描述”一个 Windows 服务长什么样——就好像给每个服务拍了一张身份证照片一样，
// 把服务的名字、状态、启动方式、进程号等等信息都装进一个盒子里。
// 为什么要单独搞一个类来装这些信息呢？因为程序里很多地方都需要用到服务的这些属性
// （比如界面上要显示、日志里要打印、逻辑里要判断它是不是正在运行），
// 如果每次都在用的时候临时去拼字符串、传一堆参数，代码会变得又臭又长、还容易出错。
// 所以把“一个服务都有哪些信息”这件事固定下来，做成一个统一的数据结构，
// 所有地方都用它，这样既清晰又好维护。这就是这个类的存在意义。
// ============================================================================

/**
 * A snapshot of a Windows service as reported by Win32_Service (via
 * Get-CimInstance). State/StartMode values are invariant English schema
 * constants, so they are locale-independent.
 *
 * 【中文翻译】这个类的每一个实例，都相当于“某一个时刻”对某个 Windows 服务拍下的一张
 * 快照（snapshot）。这些信息是从 Win32_Service 这个 WMI 类里查出来的，具体来说是通过
 * PowerShell 的 Get-CimInstance 命令拿到的。这里有个很关键的设计点：State（状态）和
 * StartMode（启动模式）这两个字段的值，是 WMI schema 里固定不变的英文常量，
 * 比如 "Running"、"Stopped"、"Auto"、"Manual" 这种，它们跟 Windows 系统的显示语言无关。
 * 也就是说，不管用户的系统是中文版还是英文版，从 Win32_Service 里读出来的这些原始值
 * 永远是英文的。正因为如此，我们的代码里才可以放心地用 equalsIgnoreCase("Running")
 * 这样的方式去判断，而不用担心语言环境不同导致判断失败——这一点非常重要，
 * 是下面 running() 方法能正确工作的前提，初学者一定要理解这个背景。
 */
// 【补充说明】下面是 record 的声明。record 是 Java 14 之后引入的语法糖，它的作用相当于
// “自动帮你生成一个只读的数据类”：你不用手动写构造方法、getter（取值方法）、equals、
// hashCode、toString 这些样板代码，编译器都会自动帮你生成。这里我们把一个服务需要
// 记录的 9 项信息都写在括号里，每个参数前面是这个信息的类型，后面是这个信息的名字。
// 用 record 而不是普通 class，是因为这个类纯粹就是用来装数据的，不需要任何可变逻辑，
// 用 record 表达“这是个数据载体”的意图最合适，代码也最简洁。
public record ServiceInfo(
        String name,             // 服务的“短名字”（也叫服务键名），比如 "wuauserv"
                                 // —— 这是在注册表/SCM 里真正用来唯一标识一个服务的名字，
                                 // 不是给人看的那种，通常是小写且没有空格。
        String displayName,      // 服务的“显示名称”，是给人看的友好名字，
                                 // 比如 "Windows Update" 或“Windows 更新”，
                                 // 会直接显示在服务管理界面里，可能包含空格和中文。
        String state,        // Running, Stopped, Start Pending, ...
                                 // 服务当前所处的状态：运行中、已停止、正在启动……
                                 // 注意这些值是 Win32_Service 的英文 schema 常量，
                                 // 不受系统语言影响（详见类顶部的说明）。
                                 // 程序里主要用 running() 方法来解读这个字段。
        String startMode,    // Auto, Manual, Disabled, ...
                                 // 服务的启动模式：自动、手动、禁用……
                                 // "Auto" 表示开机自动启动，"Manual" 表示需要手动或依赖
                                 // 触发才启动，"Disabled" 表示被禁用、无法启动。
                                 // 同样也是英文常量，不受语言环境影响。
        int processId,           // 服务对应的进程 ID。如果服务当前没有在运行，
                                 // 这个值通常是 0；如果正在运行，这就是它主进程的 PID，
                                 // 可以用它去关联任务管理器里看到的进程。
        String path,             // 服务可执行文件的完整路径，比如
                                 // "C:\Windows\System32\svchost.exe -k netsvcs"。
                                 // 从这能看出来服务到底跑的是哪个程序、带了什么参数。
        int exitCode,            // 服务的退出码。正常情况下（服务在运行或正常停止）一般是 0；
                                 // 如果服务启动失败或崩溃，这里会带上出错时的退出代码，
                                 // 可以用来排查服务为什么起不来。
        String description,      // 服务的文字描述，说明这个服务是干什么用的，
                                 // 通常来自服务安装时注册的描述信息，可能为空。
        String startedIso) { // ISO-8601 creation time of the main process, if any
                                 // 主进程的创建时间，格式是 ISO-8601 标准字符串
                                 // （类似 "2024-01-01T12:34:56.789Z"）。
                                 // 这个时间代表“服务主进程是什么时候被创建/启动的”，
                                 // 如果服务当前没有运行，这里可能是 null。
                                 // 之所以存成字符串而不是 Date 类型，是因为它本来
                                 // 就是从 PowerShell/WMI 那边以字符串形式拿回来的，
                                 // 直接存字符串可以避免时区转换和格式解析带来的麻烦。

    /**
     * Returns true if the service is currently running.
     *
     * 【中文翻译】判断当前这个服务是否处于“运行中”的状态，是就返回 true，否则返回 false。
     *
     * 【详细解释】这是这个类里唯一的一个业务方法，也是最常用的一个。
     * 为什么要专门写一个方法而不是让调用方直接去比较 state 字段呢？有两个原因：
     * 第一，把“什么算运行中”这个判断规则收拢到一处，将来如果判断规则变了
     * （比如还要兼容其他状态），只需要改这一个地方，所有调用方都跟着生效；
     * 第二，直接比较字符串容易写错（大小写、拼写），封装成方法后调用方写起来
     * 更简单也更不容易出错，比如直接写 serviceInfo.running() 就可以了。
     */
    public boolean running() {
        // 先判断 state 是不是 null。为什么要先判空？因为并不是每一个服务的
        // state 都一定有值（理论上可能查不到），如果 state 是 null 还直接去调用
        // equalsIgnoreCase，就会抛出可怕的 NullPointerException（空指针异常），
        // 程序会直接崩溃。所以这里的 null 检查是在“防守”，是非常必要的防御性编程。
        //
        // 然后再用 equalsIgnoreCase("Running") 去比较：equalsIgnoreCase 表示
        // “忽略大小写地比较字符串是否相等”，也就是说 "Running"、"running"、
        // "RUNNING" 都会被当成相等的，这样即使底层返回的大小写和预期不一致
        // 也能正确判断。因为我们已经知道 WMI 返回的 state 是固定的英文常量，
        // 所以这里直接跟 "Running" 比较是安全的（回顾类顶部关于
        // locale-independent 的说明，两者是呼应的）。
        return state != null && state.equalsIgnoreCase("Running");
    }
}
