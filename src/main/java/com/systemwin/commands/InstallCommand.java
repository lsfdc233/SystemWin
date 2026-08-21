package com.systemwin.commands;

import com.systemwin.cli.Args;
import com.systemwin.cli.CliException;
import com.systemwin.service.UnitFile;
import com.systemwin.service.UnitFileParser;
import com.systemwin.service.WindowsServiceManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * {@code systemwin install -n <name> [-p <workdir>] -e <executable> [-c <args...>]}
 *
 * <p>By default the service is hosted by SystemWin's own service host (the
 * single systemwin.exe running in {@code --host} mode, service logic modeled
 * on nssm): the host is the service binary, it spawns the program as a child
 * with the working directory, environment and stdout/stderr log redirection,
 * restarts it on crash and terminates the whole process tree on stop. This
 * works for ANY program (a Node.js app, a JVM with -jar, a script, ...).
 *
 * <p>With {@code --direct} the program itself is registered as the service
 * binary — it must then be a real Windows service program.
 *
 * <p>{@code systemwin install -s <file.service>} imports a systemd unit file.
 */
/*
 * ===================== 这个类是干什么的？（废话版） =====================
 *
 * 简单来说，InstallCommand 就是“安装命令”的入口。用户在命令行里敲下
 * “systemwin install ...” 之后，真正干活的代码就在这个类里面。
 *
 * 你可以把 Windows 服务想象成一个“开机就能自动跑、崩溃了还能自动重启”
 * 的后台程序。但是 Windows 本身要求：要注册成一个服务，那个程序必须是一
 * 个“懂服务协议”的程序（专业术语叫 Service Program）。可是我们平时写的小
 * 脚本、Node.js 应用、Java 的 -jar 包…… 它们根本不懂什么服务协议啊！
 *
 * 那怎么办呢？SystemWin 想了一个很聪明的办法（这个思路是从 nssm 这个老前
 * 辈那里学来的）：我们自己做一个“中间人”（service host，也就是那个以
 * --host 模式运行起来的 systemwin.exe），把它注册成 Windows 服务，然后由
 * 这个中间人去“代养”你的程序——启动它、监视它、挂了就重启它、停止的时
 * 候把整棵进程树一起干掉。这样不管你原来的程序是什么鬼样子，只要它能被
 * 命令行启动，就能享受“Windows 服务”的待遇。这就是所谓的“托管模式”
 * （Hosted Mode），也是本类里 createHosted() 方法的由来。
 *
 * 另外还有两种补充玩法：
 *   1. --direct 直接模式：如果程序本身就是一个正经的 Windows 服务程序，
 *      那就不用中间人了，直接把它注册成服务二进制。对应 createDirect()。
 *   2. -s 导入模式：如果你手里有一个 Linux 上 systemd 的 .service 单位文
 *      件（就是那种写满了 [Unit]、[Service] 小节的文本文件），SystemWin
 *      可以帮你“翻译”成 Windows 服务，对应 importUnit()。
 *
 * 三个方法之间的关系：run() 是总入口，它先看用户有没有给 -s 文件；给了就
 * 走 importUnit()（导入），没给就看有没有 --direct，再决定走 createDirect()
 * 还是 createHosted()。像一个岔路口的路牌，先分叉、再分叉。
 */
public final class InstallCommand implements Command {

    /**
     * 程序崩溃之后，重启之前要等待的毫秒数。
     * 5000 毫秒就是 5 秒。为什么要等？因为如果程序一启动就崩溃、崩溃就重
     * 启、重启又崩溃…… 会形成一种可怕的“崩溃循环”（crash loop），把 CPU
     * 和磁盘 IO 全部打满。稍微歇 5 秒，给系统一点喘息的时间，也方便运维同
     * 学在日志里看清到底出了什么事。这个值被传给了服务参数设置
     * （setServiceParameters），由 Windows 服务管理器在重启时使用。
     */
    private static final int DEFAULT_RESTART_DELAY_MS = 5000;

    // ctx 就是“命令上下文”（CommandContext）的缩写。它里面装着这个命令
    // 干活时需要的所有“家伙什儿”：i18n 是国际化消息对象（所有要打印给用
    // 户看的文字都从它那里取，方便以后做多语言），services 是 Windows 服务
    // 管理器（增、删、改、查服务全靠它）。为什么要把这些塞进一个上下文对
    // 象而不是直接用静态方法？因为这样方便测试——测试的时候可以塞一个假的
    // 上下文进去，就不用真的去操作系统上捣鼓服务了。这是依赖注入的基本思
    // 想，属于“面向接口编程”的入门课。
    private final CommandContext ctx;

    public InstallCommand(CommandContext ctx) {
        // 构造函数：把外面传进来的上下文存起来，供后面所有方法使用。
        // 注意这里用 this.ctx = ctx，左边的 this.ctx 是成员变量，右边的
        // ctx 是参数。参数名和成员变量名一样的时候，必须用 this 来区分，
        // 否则 Java 会以为你只是把参数赋给了它自己，白忙活一场。
        this.ctx = ctx;
    }

    @Override
    public int run(Args args) throws CliException {
        // ==================== run()：命令的“总调度中心” ====================
        // 所有命令（Command 接口的实现类）都要实现 run() 方法。命令行解析
        // 框架（Args 对象）已经把用户敲的参数整理好了：options 就是一个
        // Map，键是参数名（比如 "name"），值是参数值。下面这几行就是把这
        // 个 Map 里的东西一个个“掏”出来，装进局部变量。为什么要先掏出来？
        // 一是后面代码用起来清爽，二是可以顺便判断“用户到底给没给这个参数”。

        // 服务的名字，对应 -n 参数。这是服务的唯一标识，就像人的身份证号。
        String name = args.options.get("name");
        // 工作目录，对应 -p 参数。程序启动时会以这个目录作为当前目录运行。
        String workDir = args.options.get("path");
        // 可执行文件路径，对应 -e 参数。也就是“要跑起来的那个程序”。
        String exe = args.options.get("executable");
        // 命令行参数，对应 -c 参数。注意这里拿到的是一整条字符串，比如
        // "-p 8080 --debug"，后面还要用 splitArgs() 把它拆成一个个独立的
        // 参数（就像命令行程序自己会做的那样）。
        String command = args.options.get("command");
        // 服务单位文件路径，对应 -s 参数。如果用户给了这个，说明要走“导入
        // systemd 单位文件”这条路。
        String file = args.options.get("service-file");
        // force 是一个“开关型”参数（没有值，只有“有没有出现”的区别），
        // 对应 --force。containsKey 就是检查这个键在不在 Map 里。如果在，
        // 说明用户要求“强行覆盖”，同名服务已存在也不怕，先删了再建。
        boolean force = args.options.containsKey("force");
        // direct 对应 --direct，表示“直接模式”：不经过中间人，程序自己当
        // 服务二进制。同样是用 containsKey 判断“用户有没有敲这个开关”。
        boolean direct = args.options.containsKey("direct");

        if (file != null) {
            // ============ 分支一：用户给了 -s 单位文件，走导入流程 ============
            // 注意：导入模式下，-e（可执行文件）和 -c（参数）都不能再给，
            // 因为那些信息在 .service 文件里已经有了，再给就是自相矛盾。
            // 就好比你既给了菜谱又非要口头再报一遍菜名，谁知道听哪个？
            // 所以这里要做“互斥检查”，发现冲突就直接报错，不干活。
            if (exe != null || command != null) {
                // 抛异常：install.mixed 这条消息专门描述“参数混用”的错误。
                // CliException 是 SystemWin 自己定义的命令行异常，抛出去之
                // 后上层会捕获它，把 i18n 消息翻译成友好文字展示给用户，
                // 而不是打印一堆吓人的堆栈信息。
                throw new CliException(ctx.i18n.msg("install.mixed"));
            }
            // 检查通过，正式进入导入。注意把 name、workDir、force、direct
            // 这些开关都传下去，因为导入的时候也可能需要覆盖名字、指定工
            // 作目录、强制覆盖、甚至直接模式导入，一个都不能少传。
            return importUnit(file, name, workDir, force, direct);
        }
        if (name == null || name.isEmpty()) {
            // ============ 分支二：没给 -s，走“普通安装” ============
            // 先检查必填项：服务名字不能没有。name == null 是“压根没传”，
            // name.isEmpty() 是“传了但传了个空字符串”，两种情况都要拦。
            // 这就好比填报名表，姓名栏空着，系统当然要提醒你“请填写姓名”。
            throw new CliException(ctx.i18n.msg("install.missing.name"));
        }
        if (exe == null || exe.isEmpty()) {
            // 可执行文件路径也不能没有：没有程序，拿什么当服务去跑？
            throw new CliException(ctx.i18n.msg("install.missing.executable"));
        }
        // 把 -c 给的那一整条参数字符串拆成真正的参数列表。比如用户敲了
        // -c "-p 8080 \"hello world\""，这里就会得到 ["-p", "8080",
        // "hello world"]。注意带引号的部分会被当成一个整体，不会从中间
        // 拆开——这就是 splitQuoted 里的“引号感知”逻辑。
        List<String> params = splitArgs(command);
        if (direct) {
            // ============ 分支三：--direct 直接模式 ============
            // 直接模式不需要中间人，程序自己当服务二进制。buildBinPath 是
            // WindowsServiceManager 提供的工具方法：把可执行文件路径、参数、
            // 工作目录、环境变量拼成 Windows 服务注册时需要的完整二进制
            // 路径字符串（比如 "C:\app\myapp.exe" -p 8080）。这里环境变量
            // 传的是空列表 List.of()，因为直接模式下暂时不支持自定义环境。
            String binPath = WindowsServiceManager.buildBinPath(exe, params, workDir, List.of());
            // 注意 createDirect 的参数：名字、拼好的二进制路径、显示名（传
            // 了 name）、描述（传了 null，表示用默认描述）、启动类型 "auto"
            // （开机自启）、工作目录、是否强制覆盖。
            return createDirect(name, binPath, name, null, "auto", workDir, force);
        }
        // ============ 分支四：默认的托管模式 ============
        // 走到这里说明：给了名字、给了程序、没给 -s、也没要 --direct，那
        // 就是最常规的“让 SystemWin 中间人代养我的程序”的用法。
        // createHosted 的参数依次是：服务名、可执行文件（注意这里 trim()
        // 了一下，把首尾多余的空格去掉，防止用户手滑多敲了空格导致路径找
        // 不到）、拆分好的参数、显示名、描述（null 用默认）、启动类型、
        // 工作目录、环境变量（空列表）、重启策略（null 用默认）、是否覆盖。
        return createHosted(name, exe.trim(), params, name, null, "auto",
                workDir, List.of(), null, force);
    }

    /**
     * 导入 systemd 单位文件（.service）并据此创建 Windows 服务。
     *
     * 为什么要有这一步？因为很多开源项目（尤其是 Linux 世界的）只提供
     * systemd 的 .service 文件来告诉你“这个服务该怎么跑”。SystemWin 想帮
     * 用户省事：直接把这份 Linux 配置“翻译”成 Windows 服务，用户就不用
     * 自己对着文档一条条敲 systemwin install 命令了。
     *
     * 大致的翻译对照关系：
     *   systemd 的 [Service] ExecStart        -> Windows 服务的二进制路径
     *   [Service] WorkingDirectory            -> 工作目录（-p）
     *   [Service] Environment                 -> 环境变量列表
     *   [Unit]    Description                 -> 服务的显示名和描述
     *   [Install] WantedBy                    -> 启动类型（有就是 auto 自启，
     *                                              没有就是 demand 手动）
     *   文件名去掉 .service 后缀               -> 服务名字（除非用户用 -n 覆盖）
     */
    private int importUnit(String filePath, String nameOverride, String workDirOverride,
                           boolean force, boolean direct) throws CliException {
        // 先把用户给的字符串路径转成 Java 的 Path 对象。Path 是 NIO 里表示
        // “文件系统路径”的类，比直接用字符串拼接路径要安全、跨平台得多。
        Path p = Paths.get(filePath);
        // 检查这个文件是不是真的存在、而且是个普通文件（而不是目录）。
        // isRegularFile 就是干这个的。文件都不存在，后面解析个寂寞？
        if (!Files.isRegularFile(p)) {
            // 文件不存在，报错。install.file.notfound 消息会把文件路径填进去。
            throw new CliException(ctx.i18n.msg("install.file.notfound", filePath));
        }
        UnitFile uf;
        try {
            // 调用 UnitFileParser 去解析这个单位文件。UnitFile 是一个数据
            // 类（record），专门用来装解析出来的结果：ExecStart、Description、
            // Environment、WorkingDirectory 等等，全都从文件里提取出来。
            // 注意：解析过程要读文件、做字符串处理，是有可能抛 IOException
            // 的（比如文件被占用、编码不对、磁盘出错），所以必须 try-catch。
            uf = UnitFileParser.parse(p);
        } catch (IOException e) {
            // 解析失败：把文件路径和底层错误信息都塞进消息里，方便用户排查。
            // 注意 e.getMessage() 是 Java 异常自带的“一句话描述”，非常有用。
            throw new CliException(ctx.i18n.msg("install.file.readerror",
                    filePath, e.getMessage()));
        }
        // 单位文件里最重要的信息就是 ExecStart——“启动这个服务时要执行什么
        // 命令”。要是连这个都没有，那这份配置就是废纸一张，没法翻译。
        if (uf.execStart() == null || uf.execStart().isEmpty()) {
            throw new CliException(ctx.i18n.msg("install.no.execstart", filePath));
        }
        // ExecStart 在 systemd 里是“命令 + 参数”写在一行的，比如
        // ExecStart=/usr/bin/python3 /opt/app/main.py --port 8080。
        // parseExecStart 就是把这行拆成“可执行文件”和“参数列表”两部分，
        // 返回一个 UnitFile.ExecStart 记录对象。这样 Windows 那边才能分别
        // 注册二进制路径和命令行参数。
        UnitFile.ExecStart es = UnitFileParser.parseExecStart(uf.execStart());
        // 拆完之后再检查一遍：万一 ExecStart 里只有参数没有命令（比如写成了
        // 空字符串开头），那也是不合格的，直接拒绝。
        if (es == null || es.exe().isEmpty()) {
            throw new CliException(ctx.i18n.msg("install.no.execstart", filePath));
        }
        // ============ 下面开始做“翻译映射”，把 systemd 字段变成 Windows 概念 ============
        // 服务名字：优先用用户 -n 参数给的名字（nameOverride），没给的话就
        // 从文件名推导——serviceNameFromFile() 会把 "myapp.service" 变成
        // "myapp"。这是一种“合理默认值”的设计思路：能推断就推断，用户
        // 明确指定了就以用户为准。
        String name = nameOverride != null && !nameOverride.isEmpty()
                ? nameOverride
                : serviceNameFromFile(p.getFileName().toString());
        // 显示名：优先用单位文件里的 Description（[Unit] 小节里那行人类可读
        // 的描述文字），没有 Description 就退而求其次用服务名字。
        String display = uf.description() != null && !uf.description().isEmpty()
                ? uf.description()
                : name;
        // 启动类型：systemd 的 WantedBy 表示“这个服务想被谁拉起”——一般写
        // 的是 multi-user.target，意思就是“系统进多用户模式时就该启动我”。
        // 所以只要写了 WantedBy，我们就翻译成 Windows 的 "auto"（自动启动）；
        // 没写，就翻译成 "demand"（按需手动启动）。这个映射逻辑简单粗暴但
        // 很实用，够用就行。
        String startType = (uf.wantedBy() != null && !uf.wantedBy().isEmpty())
                ? "auto"
                : "demand";
        // 工作目录：同样先看用户有没有用 -p 覆盖，没有就用单位文件里的
        // WorkingDirectory 字段，再没有就让它保持 null，后面 createHosted
        // 里还有兜底逻辑（会用可执行文件所在的目录）。
        String workDir = (workDirOverride != null && !workDirOverride.isEmpty())
                ? workDirOverride
                : uf.workingDirectory();
        int code;
        if (direct) {
            // 导入时也支持 --direct：直接模式同样要先把 ExecStart 里的命令
            // 和参数拼成 Windows 服务的二进制路径。注意这里把单位文件里的
            // 环境变量（uf.environment()）也传进去了，直接模式也是支持环境
            // 变量的，跟 -e 手动安装时不一样。
            String binPath = WindowsServiceManager.buildBinPath(es.exe(), es.args(),
                    workDir, uf.environment());
            code = createDirect(name, binPath, display, display, startType, workDir, force);
        } else {
            // 默认走托管模式：把 ExecStart 的命令、参数、环境变量、以及
            // systemd 的 Restart 策略（uf.restart()，比如 "always"、"no"）
            // 全部交给 createHosted，由它去注册服务并设置各项服务参数。
            code = createHosted(name, es.exe(), es.args(), display, display, startType,
                    workDir, uf.environment(), uf.restart(), force);
        }
        // 最后，如果上面创建服务的返回码是 0（0 表示成功，这是命令行程序的
        // 老传统了），就给用户打印一句“导入成功”的提示，告诉他文件路径和
        // 最终的服务名字。打印到 stdout 而不是用日志框架，是因为命令行工具
        // 的用户就等着看终端输出呢。
        if (code == 0) {
            System.out.println(ctx.i18n.msg("install.imported", filePath, name));
        }
        return code;
    }

    /** Hosted mode: the SystemWin service host wraps the program (works for ANY program). */
    /*
     * 托管模式（Hosted Mode）——本类的“主角”方法。
     *
     * 什么叫“托管”？就是 SystemWin 的 service host（systemwin.exe --host）
     * 去当 Windows 服务的“正式员工”，而你真正的程序只是它“带过来的家属”。
     * 家属不用懂服务协议，只要会跑就行；一切服务相关的脏活累活（注册、启
     * 停、日志、崩溃重启、进程树清理）都由 host 这个正式员工代办。
     *
     * 这也是为什么本方法叫 createHosted 而不是 createService——它创建的不
     * 只是“一个服务”，而是一整套“宿主 + 被托管程序”的组合拳。
     */
    private int createHosted(String name, String exe, List<String> params,
                             String display, String description, String startType,
                             String workingDirectory, List<String> environment,
                             String restartPolicy, boolean force) throws CliException {
        // 第一步：检查这个名字是不是已经被别的服务占用了。
        // exists() 就是去 Windows 服务管理器里查一下。为什么先查？因为服务
        // 名字必须是唯一的，重复了 Windows 会拒绝创建，到时候报错更难看。
        if (ctx.services.exists(name)) {
            // 已存在的情况下，就要看用户有没有给 --force。给了：狠心把旧的
            // 删掉（delete），再重新创建，实现“覆盖更新”；没给：直接报错
            // “服务已存在”，把决定权交还给用户，绝不擅自覆盖别人的服务。
            if (!force) {
                throw new CliException(ctx.i18n.msg("install.exists", name));
            }
            ctx.services.delete(name);
        }
        // 第二步：拿到 service host 的路径。这个路径是怎么来的呢？是 JVM
        // 系统属性 syswin.launcher.path，在 SystemWin 启动时由外层把
        // systemwin.exe 的实际位置塞进来的。没有这个属性，说明运行环境不
        // 对劲（比如被人直接从 IDE 里跑了），那就没法注册，只能报错。
        String launcher = System.getProperty("syswin.launcher.path");
        if (launcher == null || launcher.isEmpty()) {
            throw new CliException(ctx.i18n.msg("install.require.exe"));
        }
        // 给用户一句“正在创建服务 XXX”的进度提示，纯属用户体验。
        System.out.println(ctx.i18n.msg("install.creating", name));

        // 第三步：拼接 host 的命令行。binPath 是“这个服务启动时要执行的命
        // 令”。这里拼成 "\"path\to\systemwin.exe\" --host 服务名"：
        //   1. 前后加双引号是因为 Windows 路径可能带空格，不加引号会被拆
        //      成两个参数，服务就起不来了——这是 Windows 世界永恒的坑；
        //   2. --host 告诉 systemwin.exe：你不是普通命令行工具，你是服务
        //      宿主，去把名字为 XXX 的被托管程序拉起来吧。
        String binPath = "\"" + launcher + "\" --host " + name;
        // 调用 Windows 服务管理器真正去创建服务。ActionResult 是操作结果
        // 的封装：ok() 表示成没成功，detail() 是失败时的详细原因，
        // code() 是 Windows 返回的错误码。这一行是“干活”的核心。
        WindowsServiceManager.ActionResult res =
                ctx.services.create(name, binPath, display, description, startType);
        if (!res.ok()) {
            // 创建失败了。失败原因优先看 detail()（更具体），没有的话就退
            // 而求其次，用 errorMessage() 把 Windows 错误码翻译成文字。
            // 这个“优先具体、退而求其次”的模式在本文件里反复出现。
            String detail = res.detail() != null && !res.detail().isEmpty()
                    ? res.detail()
                    : ctx.services.errorMessage(res.code());
            System.out.println(ctx.i18n.msg("install.failed", detail));
            // 返回 1 表示失败。注意：这里只是“打印失败”然后返回 1，并没有
            // 把服务删掉——万一创建到一半失败了，Windows 那边一般也不会留
            // 下半成品，这里就不过度处理了。
            return 1;
        }

        // 第四步：确定工作目录。如果调用方没有指定（null 或空字符串），就
        // 自动用“可执行文件所在的目录”作为工作目录——这是最合理的默认值，
        // 因为程序通常要相对自己所在的位置找配置文件、数据文件等。这个推
        // 断逻辑用 toAbsolutePath() 保证拿到的是绝对路径，再用 getParent()
        // 取它的父目录。万一连父目录都没有（极端情况），就退化成空字符串。
        String workDir = workingDirectory;
        if (workDir == null || workDir.isEmpty()) {
            Path parent = Paths.get(exe).toAbsolutePath().getParent();
            workDir = parent == null ? "" : parent.toString();
        }
        // 第五步：确定日志目录。Windows 服务是后台跑的，没有终端可以打印，
        // 所以它的 stdout/stderr 必须重定向到日志文件里，否则出了事连个痕
        // 迹都没有。logsDir() 返回的是 ProgramData\SystemWin\logs 这个约定
        // 俗成的目录。下面用 createDirectories 把目录先建出来（注意是
        // Directories 复数版本：父目录不存在也会一层层全建好）。如果建目录
        // 失败（比如权限不够），就把 logDir 置空——宁可没日志也不能让整个
        // 安装流程崩溃，这是“容错优先”的思路。
        String logDir = logsDir();
        try {
            Files.createDirectories(Paths.get(logDir));
        } catch (IOException e) {
            logDir = "";
        }
        // 把参数列表用空格拼回一条字符串，方便存进服务的参数配置里。
        // 注意这里只是“展示用”的拼接，真正运行时参数已经通过
        // setServiceParameters 单独传递了，不会真的用这条字符串去解析。
        String argsLine = String.join(" ", params);
        // 重启策略：从 systemd 的 Restart 字段翻译过来。如果用户明确写了
        // "no"（不重启），就翻译成 "Default Ignore"（崩溃了不管）；其他情
        // 况（always、on-failure、或者没写）都按“崩溃了就重启”处理，对应
        // "Default Restart"。为什么要这样设计？因为对服务来说，自动重启是
        // 最常用的诉求，默认值取“重启”比取“不重启”更符合用户预期。
        String appExit = (restartPolicy != null && restartPolicy.equalsIgnoreCase("no"))
                ? "Default Ignore"
                : "Default Restart";
        // 第六步：给服务设置一堆“运行时参数”。这是托管模式的精髓所在：
        //   - 被托管的程序是谁（exe）？参数是什么（argsLine）？
        //   - 工作目录在哪（workDir）？
        //   - 环境变量有哪些（environment）？
        //   - stdout、stderr、host 自己的日志分别写到哪里？
        //     （就是上一步算出来的 logDir 下的三个 .log 文件）
        //   - 程序崩溃时怎么办（appExit）？重启前歇多久（DEFAULT_RESTART_DELAY_MS）？
        // 这些参数会被 WindowsServiceManager 存到服务的注册表配置里，等服
        // 务真正启动时，host 会读出来照着执行。
        WindowsServiceManager.ActionResult pr = ctx.services.setServiceParameters(name,
                exe, argsLine, workDir, environment,
                logDir.isEmpty() ? "" : logDir + "\\" + name + ".out.log",
                logDir.isEmpty() ? "" : logDir + "\\" + name + ".err.log",
                logDir.isEmpty() ? "" : logDir + "\\" + name + ".host.log",
                appExit, DEFAULT_RESTART_DELAY_MS);
        if (!pr.ok()) {
            // 设置参数失败的处理方式和上面创建失败一模一样：优先 detail，
            // 否则翻译错误码，打印给用户，然后返回 1。
            String detail = pr.detail() != null && !pr.detail().isEmpty()
                    ? pr.detail()
                    : ctx.services.errorMessage(pr.code());
            System.out.println(ctx.i18n.msg("install.failed", detail));
            return 1;
        }

        // 第七步：安装成功，给用户打印一份“成绩单”，把所有关键信息都列
        // 出来：服务已创建、用的是托管模式、二进制路径（binLine 是把可执
        // 行文件和参数拼起来给用户看的）、工作目录、日志目录、以及下一步
        // 该怎么做（install.next 一般是“你可以用 systemwin start XXX 启动
        // 它了”之类的提示）。命令行工具就是这样，事无巨细地汇报给用户。
        System.out.println(ctx.i18n.msg("install.created", name));
        System.out.println(ctx.i18n.msg("install.hosted"));
        String binLine = exe + (params.isEmpty() ? "" : " " + argsLine);
        System.out.println(ctx.i18n.msg("install.binpath", binLine));
        if (!workDir.isEmpty()) {
            System.out.println(ctx.i18n.msg("install.workdir", workDir));
        }
        if (!logDir.isEmpty()) {
            System.out.println(ctx.i18n.msg("install.logs.dir", logDir));
        }
        System.out.println(ctx.i18n.msg("install.next", name));
        return 0;
    }

    /** Direct mode: the program itself is the service binary (must be a service program). */
    /*
     * 直接模式（Direct Mode）——和托管模式正好相反。
     *
     * 托管模式是“中间人代养”，直接模式则是“本人出马”：被注册成 Windows
     * 服务的二进制就是你的程序自己，中间没有任何包装。代价是：你的程序必
     * 须是一个真正懂得 Windows 服务协议的“服务程序”（能响应 SCM 的启动、
     * 停止、暂停等控制请求）。如果你的程序是普通脚本或者没做服务适配的普
     * 通进程，千万别用直接模式，否则服务根本起不来，还不好排查。
     *
     * 什么时候用直接模式？比如你自己用 C++/C# 写了一个正经的服务程序，或
     * 者你要注册的本来就是 Windows 自带的某个服务。其余情况，优先考虑托管
     * 模式——这就是 createHosted 和 createDirect 这对“双胞胎”的分工。
     */
    private int createDirect(String name, String binPath, String display,
                             String description, String startType,
                             String workingDirectory, boolean force) throws CliException {
        // 和 createHosted 第一步一样：先查重。已存在且没给 --force 就报错，
        // 给了 --force 就先删旧建新。这段逻辑两个方法里各写了一遍，虽然略
        // 有重复，但胜在直白好读——两处各自独立演进，互不影响。
        if (ctx.services.exists(name)) {
            if (!force) {
                throw new CliException(ctx.i18n.msg("install.exists", name));
            }
            ctx.services.delete(name);
        }
        // 进度提示：正在创建服务。
        System.out.println(ctx.i18n.msg("install.creating", name));
        // 直接创建：注意这里不需要拼 --host 了，binPath 直接就是用户程序的
        // 完整命令行（在 run() 里已经用 buildBinPath 拼好了）。
        WindowsServiceManager.ActionResult res =
                ctx.services.create(name, binPath, display, description, startType);
        if (!res.ok()) {
            // 失败处理：还是老一套——优先 detail，否则翻译错误码。
            String detail = res.detail() != null && !res.detail().isEmpty()
                    ? res.detail()
                    : ctx.services.errorMessage(res.code());
            System.out.println(ctx.i18n.msg("install.failed", detail));
            return 1;
        }
        // 成功后的“成绩单”打印。install.direct 会提示用户这是直接模式，
        // install.caveat 会特别提醒“你的程序必须能正确响应 Windows 服务控
        // 制请求”，install.next 给出下一步操作指引。注意工作目录只有在非空
        // 时才打印，避免输出一行没意义的“工作目录：”。
        System.out.println(ctx.i18n.msg("install.created", name));
        System.out.println(ctx.i18n.msg("install.direct"));
        System.out.println(ctx.i18n.msg("install.binpath", binPath));
        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            System.out.println(ctx.i18n.msg("install.workdir", workingDirectory));
        }
        System.out.println(ctx.i18n.msg("install.caveat"));
        System.out.println(ctx.i18n.msg("install.next", name));
        return 0;
    }

    /** The directory used for service stdout/stderr logs (ProgramData\SystemWin\logs). */
    /*
     * 计算日志目录的小工具方法（静态的，因为不依赖任何实例状态）。
     *
     * 为什么日志要放在 ProgramData 下面而不是别的什么地方？因为 ProgramData
     * 是所有用户共享的、专门存放“程序数据”的目录（通常就是
     * C:\ProgramData），不需要管理员权限就能读写，而且服务是以系统身份运
     * 行的，放到这里最不容易出权限问题。\SystemWin\logs 则体现了“一个软件
     * 一个文件夹”的整洁原则。
     *
     * 特殊处理：如果环境变量 ProgramData 都没设置（极少见的老系统上），就
     * 返回空字符串。调用方（createHosted）看到空字符串就知道“日志目录不可
     * 用”，会放弃日志重定向而不是崩溃——容错设计无处不在。
     */
    private static String logsDir() {
        // System.getenv 是读取系统环境变量。ProgramData 是 Windows 约定俗成
        // 的环境变量，指向共享程序数据目录。
        String programData = System.getenv("ProgramData");
        if (programData == null || programData.isEmpty()) {
            return "";
        }
        // 字符串拼接生成完整路径。注意这里用的是反斜杠 \\（在 Java 字符串
        // 里要写两个反斜杠才能表示一个），因为这是 Windows 平台的路径分隔
        // 符。虽然 Windows 也能认正斜杠，但这里入乡随俗用反斜杠。
        return programData + "\\SystemWin\\logs";
    }

    /** Splits the -c command line into separate arguments, honoring quotes. */
    /*
     * 把 -c 参数里那一条完整的命令行字符串，拆成一个个独立的参数。
     *
     * 为什么要拆？因为用户在命令行敲的 -c "arg1 arg2 arg3" 传进来的时候是
     * 一整条字符串，但程序执行的时候需要的是参数数组——Windows 创建进程时
     * 就是按“一个个参数”来理解的。拆不好，带空格的路径或者参数就会被拦腰
     * 截断，程序收到的参数就错乱了。
     *
     * 拆的时候还要“尊重引号”：比如 -c "\"C:\My App\run.bat\" --debug"，
     * 引号里是一个整体，绝不能拆开。这个逻辑被封装在
     * UnitFileParser.splitQuoted() 里——一个方法两处用（这里和导入单位文
     * 件时），体现了“复用优先”的好习惯。
     */
    private static List<String> splitArgs(String command) {
        // 防御性检查：参数是 null（没给 -c）或者全是空白字符（给了但等于没
        // 给）的时候，直接返回空列表，而不是傻乎乎地去解析。空列表意味着
        // “这个服务启动时不需要额外参数”，完全合法。
        if (command == null || command.trim().isEmpty()) {
            return List.of();
        }
        // trim() 先把首尾空白去掉（防止用户敲了 -c "  xxx  " 这样的输入），
        // 再交给 splitQuoted 做真正的引号感知拆分。List.of() 和这里返回的
        // 都是不可变列表，安全且高效。
        return UnitFileParser.splitQuoted(command.trim());
    }

    /**
     * 从单位文件名推导服务名的小工具。
     *
     * 规则非常简单：如果文件名以 ".service" 结尾，就把这个后缀去掉。
     * 比如 "myapp.service" -> "myapp"。为什么只处理这一个后缀？因为
     * systemd 的单位文件约定俗成就是这个扩展名，处理它就够了，别的
     * 扩展名（比如 .socket、.timer）SystemWin 目前不支持导入，也就不
     * 需要推导。用 substring 而不是 replaceAll，是因为 substring 简单
     * 明确，只剪掉固定长度的尾巴，不会误伤文件名中间的同名字符串。
     */
    private static String serviceNameFromFile(String fileName) {
        String n = fileName;
        if (n.endsWith(".service")) {
            // endsWith 判断后缀，substring(0, 长度减 8) 就是“去掉最后 8 个
            // 字符”（".service" 正好 8 个字符，数一数：. s e r v i c e）。
            // 注意这个裁剪是针对整个名字的，包括前面的路径已经在传入前被
            // getFileName() 去掉了，所以这里拿到的就是纯文件名。
            n = n.substring(0, n.length() - ".service".length());
        }
        // 没有 .service 后缀的文件名就直接原样返回。返回的是新字符串，原
        // 来的 fileName 没被改动——字符串在 Java 里是不可变的，这是好事。
        return n;
    }
}
