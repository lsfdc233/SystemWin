package com.systemwin.commands;

import com.systemwin.cli.Args;
import com.systemwin.service.WindowsServiceManager;
import com.systemwin.util.Units;

/*
 * 【中文翻译】本类的英文注释意思是：为服务禁用“自动启动”，
 * 命令用法是：systemwin disable [--now] <unit>...
 * 也就是说，用户可以在命令行里敲 “systemwin disable 服务名” 来禁用某个服务，
 * 后面还可以带上可选的 --now 开关，表示“禁用之后立刻把服务也停掉”。
 *
 * 【废话一般的详细讲解】这个 DisableCommand 类到底是干嘛的？
 * 1. 它是命令行工具 systemwin 众多命令中的“禁用”命令：
 *    把 Windows 服务设置成“禁用（disabled）”启动类型，
 *    这样一来，电脑开机的时候这个服务就不会自己自动启动了，
 *    就好比把某个软件的“开机自启动”开关给关掉了一样。
 * 2. 它实现了 Command 接口（interface），所以必须实现 run(Args) 方法。
 *    命令行框架在解析完用户敲的参数之后，就会调用 run 方法，
 *    run 方法就是这个命令的“总入口”，所有的逻辑都从那里开始。
 * 3. 它里面保存了一个 CommandContext 对象（就是下面的字段 ctx）。
 *    什么是 CommandContext？你可以把它想象成一个“万能工具箱”，
 *    里面装着这个命令干活时需要的各种依赖：
 *    比如国际化消息（i18n，用来打印提示语）、
 *    Windows 服务管理器（services，用来真正操作 Windows 服务）等等。
 *    为什么不在方法里现用现建，而是用构造器传进来呢？
 *    这叫“依赖注入”，好处很多：方便单元测试时替换假实现，
 *    也方便多个命令共享同一个上下文，避免重复初始化。
 * 4. 这个类被声明成 final，意思是“不允许别人继承它”，
 *    说明设计者认为这个类的行为已经定死了，不需要被子类扩展。
 *
 * 下面这一行是原始的英文 javadoc，原封不动地保留：
 */
/** Disables auto-start for services: {@code systemwin disable [--now] <unit>...}. */
public final class DisableCommand implements Command {

    // 这个字段 ctx 就是上面啰嗦了半天提到的“命令上下文/万能工具箱”。
    // 它是 private final 的：private 表示只有本类内部能访问，
    // final 表示一旦在构造函数里赋值之后就不能再改变了，
    // 这样能保证这个命令在运行期间使用的依赖是稳定不变的，
    // 不会出现用着用着被偷偷换掉的情况，让人放心。
    private final CommandContext ctx;

    // 构造函数：创建 DisableCommand 对象的时候，把“工具箱”ctx 传进来。
    // 这里没有做任何多余的操作，就是简简单单地把参数赋值给成员变量，
    // 这种写法叫“构造器注入”，是 Java 世界里非常非常常见的一种写法。
    // 好处是：谁需要这个命令，谁就自己负责提供上下文，
    // 命令本身不关心上下文是怎么造出来的，职责分明。
    public DisableCommand(CommandContext ctx) {
        this.ctx = ctx;
    }

    // ========== run 方法：命令真正的入口 ==========
    // 用户敲下“systemwin disable xxx”回车之后，命令行框架就会调用这个方法。
    // 它的返回值是退出码（int）：
    //   0 表示一切顺利，成功了；
    //   非 0 表示出了某种错误。
    // 这个退出码最后会被命令行工具返回给操作系统，
    // 这样写批处理脚本或者别的程序的人，就能根据退出码判断命令到底成没成功，
    // 就好比红绿灯：0 是绿灯（可以走），非 0 是红灯（出问题了）。
    @Override
    public int run(Args args) {
        // 第一步：先检查用户到底有没有给参数。
        // args.positional 是“位置参数”的列表，也就是命令后面不带横杠的那些单词。
        // 举个例子，“systemwin disable mysql”里的“mysql”就是一个位置参数，
        // 它表示用户想要禁用的那个“单位”（unit）。
        // 如果用户光敲了“systemwin disable”而没有写任何服务名，
        // 那我们根本不知道要禁用谁，巧妇难为无米之炊，
        // 所以必须立刻报错，并且返回退出码 1。
        if (args.positional.isEmpty()) {
            // 注意：这里用的是 ctx.i18n.msg(...) 来打印消息，
            // 也就是“国际化消息”。好处是：以后要支持中文、英文、
            // 日文等各种语言的时候，只需要改语言包（资源文件），
            // 一行代码都不用改，非常优雅。
            // 第一个参数 "err.unit.required" 是消息的“键”，
            // 第二个参数 "disable" 是往消息文本里填的占位符，
            // 最终打印出来的大概意思是“disable 命令需要一个单位名称”。
            System.out.println(ctx.i18n.msg("err.unit.required", "disable"));
            return 1;
        }
        // code 用来记录“到目前为止遇到的最严重的错误码”，初始为 0（一切正常）。
        // 注意：用户可能一次禁用好几个服务，比如
        //   systemwin disable mysql redis nginx
        // 这种情况下我们就要一个接一个地处理。全部成功当然最好，
        // 但只要其中有一个失败，最后返回的退出码就必须是非 0，
        // 否则脚本会误以为全部成功了，那可就误导人了。
        int code = 0;
        // 这个 for 循环就是在遍历用户给的所有“单位”（unit）。
        // 不管用户给了一个还是十个服务，我们都用同样的方式逐个处理，
        // 这就是“把一件事抽成一个方法，然后循环调用”的典型用法。
        for (String unit : args.positional) {
            // disableOne 是真正干活的方法（私有方法，类外部看不到），
            // 它的职责是处理“单个”服务的禁用。
            // 第二个参数 args.now 表示用户有没有加 --now 这个开关，
            // --now 的意思是“不但要禁用，而且立刻把正在运行的服务停掉”。
            int c = disableOne(unit, args.now);
            // 只要某一个单位失败了（返回值 c 不等于 0），
            // 就把 code 更新成这个失败码。
            // 这里故意用的是简单的“赋值覆盖”，而不是什么高级逻辑，
            // 意思很直白：只要出过一次错，最终结果就是错误，
            // 别想蒙混过关。后面的单位仍然会继续处理，
            // 不会因为前面一个失败就停下来（尽量多帮用户干点活）。
            if (c != 0) {
                code = c;
            }
        }
        // 循环结束，把最终的退出码返回给调用方（命令行框架）。
        return code;
    }

    // ========== disableOne 方法：处理“单个”服务的禁用 ==========
    // 这个方法负责把“一个”服务设置成“禁用（disabled）”状态。
    // 参数说明：
    //   rawUnit —— 用户在命令行里敲的原始名字（可能是简称、别名，
    //              也可能带了奇怪的格式，总之是“原始”的，还没整理过）；
    //   now     —— 布尔值，是否要同时立刻停止服务（对应 --now 开关）。
    // 返回值：退出码，0 表示成功，非 0 表示失败。
    private int disableOne(String rawUnit, boolean now) {
        // 第一步：把用户输入的名字“规范化”。
        // 用户敲的命令行字符串不一定规范，比如大小写不一致、
        // 多了个 .exe 后缀、或者写的是服务的“显示名”而不是真正的“服务名”，
        // Units.normalize 就是负责把这些乱七八糟的输入整理成统一格式，
        // 免得后面用不规范的名字去查服务导致找不到。
        String unit = Units.normalize(rawUnit);
        // 第二步：从规范化之后的单位名里，提取出真正的“服务名”。
        // 这里要科普一下：Windows 里每个服务有两个名字——
        // 一个是“服务名”（Service Name），是系统内部用来唯一标识服务的，
        // 比如 MySQL 的服务名可能是 “MySQL80”；
        // 另一个是“显示名”（Display Name），是给人在管理面板里看的，
        // 比如 “MySQL80” 的显示名可能是 “MySQL80 (服务)” 这种。
        // 我们调用 Windows API 的时候，只认“服务名”，所以必须提取出来。
        String name = Units.serviceName(unit);
        // 第三步：检查这个服务到底存不存在。
        // 万一用户拼错了名字，或者这个服务已经被删除了，
        // 直接去改它的启动类型是会出错的（Windows 会报“找不到服务”），
        // 所以动手之前先查一遍，防患于未然，这也是一种防御性编程。
        if (!ctx.services.exists(name)) {
            // 服务不存在：打印一条友好的错误提示（用国际化消息），
            // 然后返回退出码 4。注意这里返回的是 4 而不是 1，
            // 说明设计者给不同错误分配了不同的退出码，
            // 这样调用方（脚本）可以根据退出码区分错误类型：
            // 1 是通用错误，4 是“找不到这个单位/服务”。
            System.out.println(ctx.i18n.msg("err.unit.not.found", unit));
            return 4;
        }
        // 第四步：先跟用户打个招呼，告诉他“我开始禁用啦”。
        // 命令行工具跟 GUI 程序不一样，没有进度条、没有对话框，
        // 唯一的沟通方式就是在控制台打印文字，所以每一步都要打印提示，
        // 让用户知道程序正在干什么，不然用户会以为程序卡死了。
        System.out.println(ctx.i18n.msg("disable.disabling", unit));
        // 第五步：真正动手干活——调用 Windows 服务管理器，
        // 把这个服务的启动类型改成 "disabled"（禁用）。
        // 注意：修改服务的启动类型通常需要管理员权限，
        // 如果当前进程不是以管理员身份运行的，这一步就会失败，
        // 返回的结果对象里会带上错误码。
        // 另外这里返回的是一个 ActionResult 对象而不是单纯的 boolean，
        // 为什么要这么设计呢？因为失败的时候我们还想知道“错误码”是什么，
        // 这样才能把具体的错误原因（比如“拒绝访问”）翻译成人话告诉用户，
        // 只返回 true/false 的话信息量就太少了。
        WindowsServiceManager.ActionResult res = ctx.services.setStartType(name, "disabled");
        // 第六步：检查刚才的操作到底成没成功。
        // res.ok() 返回 true 表示成功，false 表示失败。
        if (!res.ok()) {
            // 失败分支：打印失败消息，错误码 res.code() 会被 errorMessage 方法
            // 翻译成人类能读懂的描述（比如“拒绝访问”），
            // 然后返回退出码 1（通用错误）。
            System.out.println(ctx.i18n.msg("disable.failed", unit,
                    ctx.services.errorMessage(res.code())));
            return 1;
        }
        // 成功分支：打印“已经禁用”的提示，告诉用户改好了。
        System.out.println(ctx.i18n.msg("disable.disabled", unit));
        // 第七步：处理 --now 开关。
        // 如果用户带了 --now，说明他不只是想“下次开机不启动”，
        // 而是希望“现在立刻就把服务停下来”，立刻生效；
        // 如果没带 --now，那就只改启动类型（下次开机生效），
        // 服务现在该怎么运行还怎么运行，不打扰正在跑的服务。
        if (now) {
            // 需要立刻停止：交给 stopNow 方法去执行“停止”这个动作，
            // 并把它的返回值直接作为本方法的最终返回值。
            // 也就是说，禁用成功但停止失败的话，整体仍然算失败。
            return stopNow(name, unit);
        }
        // 没有 --now 的情况：什么都不用再做了，直接返回 0（成功）。
        return 0;
    }

    // ========== stopNow 方法：立刻停止正在运行的服务 ==========
    // 这个方法只在用户加了 --now 开关的时候才会被调用，
    // 作用就是调用 Windows 服务管理器，把正在运行的服务立刻停下来。
    // 注意它的访问级别：既没有 private，也没有 public，
    // 这种叫“包内可见”（package-private），同包下的类都能访问。
    // 这么设计很可能是为了方便单元测试——测试代码跟它放在同一个包里，
    // 就可以直接调用这个方法做测试，而不用绕道走公开接口。
    int stopNow(String name, String unit) {
        // 调用服务管理器的 stop 方法，传入“真正的服务名”，
        // 返回一个 ActionResult 结果对象。
        // 注意：这里用的是第一步提取出来的服务名 name，
        // 而不是用户输入的原始字符串 unit，
        // 因为 Windows 的底层 API 只认标准服务名，用别名会出错。
        WindowsServiceManager.ActionResult res = ctx.services.stop(name);
        if (res.ok()) {
            // 停止操作“成功”了。但是！这里还有一个特殊情况要处理：
            // 错误码 1062 在 Windows 世界里表示“服务尚未启动”。
            // 这是什么意思呢？就是说这个服务本来就是停着的，
            // 我们执行“停止”操作相当于停了个寂寞，
            // 这种情况下不能算错误，但提示语也不能说“已停止”，
            // 否则就撒谎了，所以要用三目运算符判断一下：
            // 如果错误码是 1062，就打印“服务本来就没在运行”；
            // 否则才打印“已停止”。细节见真章，这就是贴心的小设计。
            System.out.println(res.code() == 1062
                    ? ctx.i18n.msg("stop.notstarted", unit)
                    : ctx.i18n.msg("stop.stopped", unit));
            // 不管是“真停了”还是“本来就没启动”，
            // 这两种情况都算成功，统一返回退出码 0。
            return 0;
        }
        // 停止失败：打印失败原因（把错误码翻译成人话），
        // 然后返回退出码 1（通用错误）。
        System.out.println(ctx.i18n.msg("stop.failed", unit,
                ctx.services.errorMessage(res.code())));
        return 1;
    }
}
