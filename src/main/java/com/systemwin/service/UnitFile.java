package com.systemwin.service;

/*
 * 首先声明一下：这个文件所在的包（package）叫做 com.systemwin.service，
 * 也就是"服务"相关的代码都放在这个包下面。
 * 在 Java 里，package 相当于文件系统的"文件夹"，
 * 用来把功能相近的类组织在一起，避免类名冲突，也方便别人一眼看出这个类是干什么的。
 * 大家可以理解为：package 就是给代码"分门别类"的标签。
 */
import java.util.List;

/*
 * ============================================================================
 * 下面这段是"类级注释"（Class-level Javadoc）。
 *
 * 先解释一下这个类到底是干嘛的，用大白话说：
 * 在 Linux 系统上，systemd 是负责开机启动服务、管理服务生命周期的"大管家"。
 * 每个由 systemd 管理的服务，都会对应一个以 .service 结尾的配置文件，
 * 比如 /etc/systemd/system/myapp.service。
 * 这个配置文件里面写的都是"键 = 值"格式的内容，例如：
 *
 *   [Unit]
 *   Description = 我的应用程序
 *   [Service]
 *   ExecStart = /usr/bin/java -jar myapp.jar
 *   [Install]
 *   WantedBy = multi-user.target
 *
 * 而 UnitFile 这个类（准确说是 Java 的 record）就是用来"装"这样一个
 * .service 配置文件解析出来的关键信息的容器。
 * 也就是说：解析器（parser）读配置文件 → 把读到的字段塞进 UnitFile →
 * 后面其它代码（比如生成服务、展示服务列表）直接拿着 UnitFile 用就行。
 * 它是一个纯数据的载体（Data Carrier），本身不包含任何业务逻辑，
 * 这符合"把数据和逻辑分离"的良好设计习惯。
 *
 * 为什么用 record 而不是传统的 class + getter/setter 呢？
 * 因为 Java 16 之后引入了 record 这个新语法：
 * 只要你声明了 record 的各个"组件"（component，也就是括号里那几项），
 * Java 编译器就会自动帮我们生成：
 *   1. 全参数的构造方法（把所有字段一次性传进来）；
 *   2. 每个组件的取值方法（比如 description() 就能拿到描述）；
 *   3. equals() / hashCode() / toString() 这些常用方法。
 * 这样一来代码就非常简洁，不用像老式写法那样写一大堆样板代码（boilerplate）。
 * 而且 record 天生就是"不可变"（immutable）的，
 * 一旦创建出来，里面的值就不能被修改，这能避免很多隐蔽的 bug。
 * ============================================================================
 */
/** A parsed systemd {@code .service} unit file. */
public record UnitFile(
        /*
         * ---- 下面逐个解释 record 的每一个"组件"（component）----
         * 可以把这个 record 想象成一个"表格"，括号里的每一项就是一"列"。
         * 每个组件都对应 .service 配置文件里的一个关键信息。
         * 注意：所有组件都没有 final 关键字，但因为 record 本身不可变，
         * 所以其实每个字段天生就是 final 的，编译器帮我们处理好了。
         */

        /*
         * sourcePath：这个 .service 配置文件在磁盘上的完整路径。
         * 例如 "/etc/systemd/system/myapp.service"。
         * 为什么要保存路径呢？因为后续可能需要重新读取、
         * 修改或删除这个文件，光有内容没有位置信息是不行的，
         * 所以"从哪来的"这个信息必须跟着数据一起走。
         * 这就是所谓的"溯源"（provenance），在做运维类工具时非常重要。
         */
        String sourcePath,

        /*
         * description：对应配置文件 [Unit] 小节里的 Description= 字段。
         * 它的作用就是给服务起一句"人话"描述，
         * 比如"我的 Web 服务"、"数据库备份定时任务"等等。
         * 这样用户在界面上看到的不再是一长串奇怪的路径或命令，
         * 而是一句能看懂的话。
         */
        String description,

        /*
         * execStart：对应 [Service] 小节里的 ExecStart= 字段。
         * 这是整个配置文件里"最核心"的一行，
         * 它告诉 systemd：启动这个服务时，到底要执行哪条命令？
         * 例如 ExecStart = /usr/bin/java -jar myapp.jar。
         * 注意这里存的是"完整的命令行字符串"，
         * 而不是把"程序"和"参数"拆开存——拆开的工作由
         * 下面那个 ExecStart 内部 record 去完成，各司其职。
         */
        String execStart,

        /*
         * workingDirectory：对应 [Service] 小节里的 WorkingDirectory= 字段。
         * 它表示服务进程启动时的工作目录（就是进程的"当前文件夹"）。
         * 很多程序会读写相对路径的文件，
         * 比如 "./logs/app.log"，那它到底相对哪里呢？
         * 就相对这个工作目录。所以如果程序找不到配置文件、
         * 或者日志写错地方，常常就是 WorkingDirectory 没配对。
         */
        String workingDirectory,

        /*
         * restart：对应 [Service] 小节里的 Restart= 字段。
         * 它决定了：当服务进程意外退出（比如崩溃、被杀死）时，
         * systemd 要不要自动把它重新拉起来？
         * 常见的值有 no（不重启）、always（总是重启）、
         * on-failure（只有失败才重启）等等。
         * 对于"必须 7x24 小时在线"的服务来说，
         * 这个字段就是服务高可用的第一道保障。
         */
        String restart,

        /*
         * wantedBy：对应 [Install] 小节里的 WantedBy= 字段。
         * 这个字段要稍微绕一点，它其实回答的问题是：
         * "这个服务在哪个'运行级别'（target）下应该被启动？"
         * 最常见的就是 multi-user.target（多用户模式，也就是正常开机后）。
         * 可以简单理解成：把服务的"开关"挂到某个开关组里，
         * 只要那个开关组被打开（系统进入该 target），
         * 这个服务就会跟着被启动。
         */
        String wantedBy,

        /*
         * environment：对应 [Service] 小节里的 Environment= 字段（可能有多行）。
         * 因为 Environment= 可以写很多次，每次一条 "KEY=VALUE"，
         * 所以这里用 List<String>（字符串列表）来存放，
         * 比如 ["JAVA_HOME=/usr/lib/jvm/java-17", "PORT=8080"]。
         * 这些环境变量会被注入到服务进程里，程序运行时就能通过
         * System.getenv() 之类的方式读到它们。
         */
        List<String> environment) {

    /*
     * ============================================================================
     * 下面是一个"嵌套 record"（nested record），名字叫 ExecStart。
     * 它专门用来表示"一条执行命令"的解析结果。
     *
     * 为什么还要单独再搞一个 record 呢？
     * 因为 ExecStart= 这一行的内容其实可以拆成两部分：
     *   1. exe  —— 要执行的"可执行程序"本身，比如 /usr/bin/java；
     *   2. args —— 传给这个程序的"参数列表"，比如 ["-jar", "myapp.jar"]。
     *
     * 把"程序"和"参数"分开存放非常有价值：
     * 界面展示时可以高亮程序名、单独显示参数；
     * 做校验时可以直接检查 exe 是否存在、是否可执行；
     * 以后想"以指定的用户/环境重新拼一条命令"也方便。
     * 如果一直用一整条字符串，每次都得自己用空格去切分，
     * 而命令里可能带引号、转义字符，自己切很容易切错，
     * 所以干脆在解析阶段就把结构定好，后续所有代码都省心。
     *
     * 嵌套（把 ExecStart 放在 UnitFile 里面）的原因也很简单：
     * ExecStart 这个概念是"从属于"UnitFile 的，
     * 单独放外面会显得很突兀，放在里面表示"它是 UnitFile 的组成部分"，
     * 语义上更清晰，命名空间也更干净。
     * 我们注意到：UnitFile 里存 execStart 时用的是 String（整条命令），
     * 而 ExecStart 里是拆分好的 exe + args ——
     * 两者是"原始串"和"结构化结果"的关系，
     * 具体用哪个，取决于调用方需要什么粒度的信息。
     * ============================================================================
     */
    /** The executable and arguments parsed from ExecStart=. */
    public record ExecStart(String exe, List<String> args) {
        /*
         * 这个内部 record 只有两个组件：
         * exe  —— 可执行程序的路径（例如 /usr/bin/java）；
         * args —— 程序参数列表（例如 ["-jar", "myapp.jar"]）。
         * 和外面一样，编译器会自动生成构造方法、取值方法、
         * equals/hashCode/toString，我们不用手写任何方法体，
         * 所以这里的花括号几乎是空的。
         */
    }
}
