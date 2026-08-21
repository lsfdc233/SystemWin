package com.systemwin.commands;

// ============================================================
// 包说明（废话时间）：
// 我们所在的包叫 com.systemwin.commands，翻译过来就是"SystemWin 的命令包"。
// 这个包里放的都是"用户敲一条命令，程序就去干活"的入口类，比如这个类就是
// 负责处理 is-active 这条命令的。你可以把这里想象成公司的前台接待处：
// 用户在前台说出想干什么（敲命令），前台（命令类）再把活儿转交给后台
// 的各个部门（service、util 之类的包）去真正执行。
// ============================================================

import com.systemwin.cli.Args;
import com.systemwin.service.ServiceInfo;
import com.systemwin.util.Units;

// 上面这三个 import 是"引入依赖"的意思，就好比做饭之前要先从柜子里把
// 油盐酱醋都拿出来摆好，下面代码里才能直接用：
//  - com.systemwin.cli.Args：命令行参数解析结果，用户敲命令时带的那些
//    参数都被装进这个对象里。比如用户敲 "is-active nginx sshd"，
//    后面的 "nginx"、"sshd" 就都在 Args 的 positional（位置参数）列表里。
//  - com.systemwin.service.ServiceInfo：服务的"信息档案"，里面记录了
//    这个服务叫什么名字、当前是不是在运行（running）等等状态。
//  - com.systemwin.util.Units：一个工具类，专门负责把用户输入的单位名
//    规范化（normalize），比如用户写 "nginx.service" 和写 "nginx"，
//    经过它处理后都能变成统一的内部名字，避免因为写法不同就认不出来。

/**
 * {@code systemwin is-active <unit>...} prints active/inactive and exits 0
 * when active, 3 when inactive, 4 when the unit does not exist.
 */
// ---- 上面的 javadoc 翻译成大白话（原文保留在上面，这里只是解释）----
// 这段注释是在给这个类"立规矩"：它实现的是 systemwin 的 is-active 命令。
// 用户敲 "systemwin is-active 单位名..."（unit 就是服务的单位，比如
// nginx.service）之后，程序会往屏幕上打印 active（活跃/正在运行）或者
// inactive（不活跃/没在运行），然后根据结果返回不同的"退出码"：
//   - 返回 0：服务是 active（活跃状态），0 在编程世界里通常代表"一切正常"；
//   - 返回 3：服务是 inactive（不活跃状态），3 代表"有情况但不算致命"；
//   - 返回 4：这个单位根本不存在，4 代表"你查的东西压根没有"。
// 为啥要搞退出码这一套呢？因为脚本程序（比如 .bat 或 .sh）没法"看懂"
// 屏幕上的文字，但能看懂退出码——脚本只要判断 $? 或者 %ERRORLEVEL%
// 是几，就知道服务到底啥状态了。这其实是模仿 Linux 上 systemctl
// is-active 的行为习惯，让老用户用起来特别亲切。
public final class IsActiveCommand implements Command {

    // 注意这个类是 final 的，意思是"不许别人继承我"。为什么？因为这个类
    // 逻辑简单、职责单一，不需要被扩展成子类；标成 final 还能让编译器
    // 放心大胆地做优化，也防止以后有人不小心写个子类把它改坏。
    // 它实现了 Command 接口，也就是"我是命令家族的一员"，外面统一
    // 通过 Command 接口的 run 方法来调用，不用关心具体是哪个命令。

    private final CommandContext ctx;

    // ---- 上面这个字段的解释 ----
    // ctx 是 CommandContext（命令上下文）的缩写。你可以把它想象成
    // 一个"随身小背包"，里面装着这个命令干活时需要的一切"公共资源"：
    // 比如国际化消息（i18n）、服务查询接口（services）等等。
    // 它被声明成 private final，意思是"只有本类能用，而且一旦赋值就
    // 不能再换"，这样能保证命令在运行期间用的始终是同一套资源，
    // 不会偷偷被换掉，代码也更安全、更好理解。

    public IsActiveCommand(CommandContext ctx) {
        this.ctx = ctx;
    }

    // ---- 上面这个构造方法的解释 ----
    // 这是类的构造方法，名字和类名一模一样，专门负责"把对象组装好"。
    // 外面创建 IsActiveCommand 的时候，必须把一个 CommandContext 递进来，
    // 也就是"把背包塞给这个命令"，然后我们把它存到 this.ctx 里备用。
    // 这种写法叫"依赖注入"（Dependency Injection），听起来很高大上，
    // 其实说白了就是：我不自己去 new 一个背包，而是别人给我啥我用啥，
    // 好处是以后想换背包（比如换成测试用的假背包）都不用改这个类的代码。

    @Override
    public int run(Args args) {
        // ---- run 方法是 Command 接口规定的"执行入口" ----
        // 不管哪个命令，外面都调用 run(args) 让它干活，args 就是用户
        // 敲命令时带的全部参数。返回值是退出码，0 表示成功，非 0 表示
        // 各种失败情况，最后会被 SystemWin 主程序拿去设置进程的退出状态。

        if (args.positional.isEmpty()) {
            // 如果用户一个参数都没给，比如只敲了 "systemwin is-active"，
            // 那程序就不知道要查哪个服务了，这属于"用法错误"。
            // 这时候我们调用 ctx.i18n.msg(...) 去拿一条"翻译好的错误消息"：
            // 第一个参数 "err.unit.required" 是消息的"钥匙"（key），
            // 第二个参数 "is-active" 是往消息里填的内容，这样同一句提示
            // 就能根据用户的语言环境自动变成中文、英文或者其他语言，
            // 这就是国际化（i18n，internationalization 的缩写）的妙处。
            System.out.println(ctx.i18n.msg("err.unit.required", "is-active"));
            return 1; // 退出码 1 表示"用法不对"，提醒用户去看帮助信息。
        }
        int code = 0;
        // ---- 下面这个循环是"逐个检查"的逻辑 ----
        // 用户可能一次查好几个服务，比如 "systemwin is-active a b c"，
        // 所以我们要用 for 循环把 args.positional（位置参数列表）里的
        // 每个服务名都过一遍，挨个调用 checkOne 去判断它的状态。
        // code 变量用来记录最终的退出码，一开始先假设一切正常（0）。
        for (String unit : args.positional) {
            int c = checkOne(unit);   // 检查这一个服务，得到它的退出码
            if (c != 0) {
                // 注意这里的奥妙：只要检查结果不是 0（也就是有异常），
                // 我们就用这个非 0 的值覆盖 code。也就是说"最后一个出问题的
                // 服务"说了算——如果前面查的都没问题、后面有一个不活跃，
                // 最终退出码就是那个不活跃的 3。反过来，如果后面又有个
                // 服务正常（返回 0），我们并不会把 code 改回 0，
                // 因为 code != 0 这个条件只在 c != 0 时才成立。
                // 这样设计是为了让调用方知道"至少有一个服务没处于
                // active 状态"，报告问题时不至于被正常项"洗白"。
                code = c;
            }
        }
        return code; // 把所有服务都查完，把最终退出码交出去。
    }

    private int checkOne(String rawUnit) {
        // ---- checkOne 是"检查单个服务"的私有辅助方法 ----
        // 它是 private 的，说明只给本类内部用，外面的人不关心、也不该
        // 直接调用它。参数名叫 rawUnit，raw 是"原始的、未经加工的"意思，
        // 就是说这个服务名还是用户原样敲进来的，可能带后缀也可能不带，
        // 可能大小写也不统一，所以第一步要先"洗一洗"。

        // 先用 Units.normalize 把原始名字规范化（比如去掉多余的
        // ".service" 后缀、统一大小写），再用 Units.serviceName 取出
        // 真正的服务名。两步配合就像"先清洗再贴标签"：
        // 不管用户敲的是 nginx、nginx.service 还是 Nginx.Service，
        // 最后都能得到同一个标准名字去数据库里查，不怕认错人。
        String name = Units.serviceName(Units.normalize(rawUnit));

        // 拿着标准名字去 ctx.services 这个"服务登记处"查询，
        // 查到的 ServiceInfo 就是该服务的信息档案（正在运行吗？等等）。
        ServiceInfo info = ctx.services.query(name);

        if (info == null) {
            // 查询结果是 null，说明登记处里根本没有这个服务——
            // 就像你在通讯录里找一个不认识的人，翻遍了也没有。
            // 这种情况既不是 active 也不是 inactive，而是"查无此人"，
            // 所以我们打印 unknown（未知），并返回 4。
            // 4 是专门为"单位不存在"准备的退出码，和 javadoc 里说的一致，
            // 调用方一看退出码是 4，就知道是自己把服务名写错了，
            // 而不是服务真的挂了。
            System.out.println("unknown");
            return 4;
        }
        if (info.running()) {
            // info.running() 会告诉我们这个服务现在是不是正在运行。
            // 如果是，那就是 active（活跃）状态，打印 "active"，
            // 退出码返回 0，表示"一切正常，服务好着呢"。
            System.out.println("active");
            return 0;
        }
        // 走到这里说明：服务存在，但它没有在运行，那就是 inactive。
        // 打印 "inactive" 并返回 3。注意区分 3 和 4：
        // 3 是"服务在册但没跑"，4 是"服务根本不在册"，含义完全不同，
        // 写脚本的人可以根据退出码精确判断到底发生了什么情况。
        System.out.println("inactive");
        return 3;
    }
}
