package com.systemwin.commands;

import com.systemwin.cli.Args;
import com.systemwin.service.WindowsServiceManager;
import com.systemwin.util.Units;

/**
 * Enables auto-start for services: {@code systemwin enable [--now] <unit>...}.
 *
 * （中文翻译：这个类的作用是“启用服务的开机自启动”。也就是说，当用户在命令行里
 *  输入 systemwin enable 后面跟上一串服务单元的名字时，这个命令就会去把那些服务
 *  设置成“开机自动启动”的模式——就像我们在 Windows 的“服务”管理窗口里，把某个
 *  服务的“启动类型”从“手动”改成“自动”一样。而且它还支持一个 --now 选项，
 *  意思是：设置完自启动之后，顺便立刻把服务启动起来，不用傻傻地等下次开机。
 *  完整的用法是：systemwin enable [--now] <unit>...  ，其中 <unit>... 表示可以
 *  一次写好几个服务单元名字，程序会挨个处理，不会漏掉任何一个。）
 */
public final class EnableCommand implements Command {

    // ===================== 类级别的说明（新手必读） =====================
    // 这个类实现了 Command 接口，属于经典的“命令模式”（Command Pattern）。
    // systemwin 这个程序里有很多子命令（比如 enable、disable、start、stop……），
    // 每一个子命令都会写一个专门的类去实现 Command 接口，然后由框架统一调度。
    // 这样设计的好处是什么呢？让我们掰开揉碎讲一讲：
    //   1. 每个命令的逻辑都装在自己独立的类里面，互不干扰，代码非常清晰，
    //      以后想找“enable 命令的代码”就直奔这个文件，不用满世界翻；
    //   2. 以后要增加一个新命令，只需要再写一个新类去实现 Command 接口，
    //      完全不用改动已经写好的老命令，这符合软件设计的“开闭原则”
    //      （对扩展开放、对修改关闭），改一个地方不会牵连别的地方；
    //   3. 每个命令都可以单独写单元测试，测试起来很方便。
    // 另外请注意：这个类被声明成了 final，也就是说它不允许被别人继承。
    // 为什么不让继承呢？因为命令类没有什么“抽象变化”的空间，禁止继承可以
    // 防止别人乱改行为，让代码更安全、更可控。

    private final CommandContext ctx;

    // 上面这个字段 ctx 保存的是“命令上下文”（CommandContext）。
    // 我们可以把它想象成一个“百宝箱”或者说“万能工具箱”：
    // 里面装着这个命令运行时所需要的一切依赖，比如：
    //   - i18n：国际化消息对象，负责根据消息键（比如 "enable.enabling"）
    //     查出对应的文字，方便程序支持多语言；
    //   - services：Windows 服务管理器，真正去操作系统服务的对象，
    //     设置启动类型、启动服务这些“脏活累活”都是它来干。
    // 为什么要把这些依赖通过构造器“注入”进来，而不是在类里面自己 new 一个呢？
    // 主要是为了方便测试——测试的时候我们可以传入假的（mock）对象，
    // 这样就能在不真正碰 Windows 系统服务的情况下，验证命令的逻辑对不对。
    // 字段用了 private final 修饰：private 表示只有本类能访问，
    // final 表示一旦在构造函数里赋值之后就再也不能修改，
    // 两者结合保证了命令在整个生命周期里使用的都是同一个上下文，
    // 不会出现“用着用着上下文被换掉了”这种诡异的问题。

    public EnableCommand(CommandContext ctx) {
        // 构造函数：创建 EnableCommand 对象的时候，调用方必须把 CommandContext
        // 传进来，这是强制的，参数列表里写了就躲不掉。
        // 注意这里没有做任何空值检查（比如 ctx 是不是 null），
        // 这是有意为之：框架层（调用这个构造函数的地方）保证了传进来的
        // ctx 一定不为空，所以这里可以放心大胆地直接赋值。
        // 如果将来某一天框架的保证被打破了，这里会抛 NullPointerException，
        // 到时候一眼就能看出是哪个环节出了问题，反而好排查。
        this.ctx = ctx;
    }

    @Override
    public int run(Args args) {
        // run 方法是 Command 接口要求每个命令都必须实现的核心方法，
        // 前面加的 @Override 注解是告诉编译器（和读代码的人）：
        // “这个方法是我覆写（override）接口里的方法”，如果接口签名改了
        // 而这里没跟上，编译器会立刻报错，帮我们提前发现不一致。
        // 框架解析完命令行参数之后，就会把参数对象（Args）交给这个方法执行。
        // 返回值是一个整数，表示命令执行的结果代码：
        //   0 表示成功；非 0 表示失败（不同的数字代表不同的失败原因）。
        // 这个返回值最终会被 systemwin 的主程序用作进程的退出码（exit code），
        // 这样脚本或者批处理文件就能根据退出码判断命令到底成没成功。
        if (args.positional.isEmpty()) {
            // 第一步：检查用户有没有在 enable 后面跟上服务单元的名字。
            // args.positional 是一个列表，装着命令后面那些“不带选项”的参数，
            // 比如 "systemwin enable nginx mysql" 里面的 nginx 和 mysql
            // 就会出现在这个列表里。
            // 如果这个列表是空的，说明用户只写了 enable、没写任何服务名，
            // 那当然就无从下手了，直接给用户打印一条错误提示
            //（提示的具体文字由 i18n 根据 "err.unit.required" 这个键查出来，
            //  后面传的 "enable" 是占位符，用来拼进提示里），
            // 然后返回 1 表示“参数不对，命令失败”。
            System.out.println(ctx.i18n.msg("err.unit.required", "enable"));
            return 1;
        }
        int code = 0;
        // 初始化一个“总的结果代码”，先乐观地假设全部成功，所以是 0。
        // 注意：这里不能图省事直接 return 每个服务的处理结果，
        // 因为用户可能一次启用了好几个服务，只要其中有一个失败，
        // 整个命令就应该报告失败——不能因为最后一个成功了就假装全成功。
        // 所以我们用一个变量把“最严重”的结果攒起来，最后统一返回。
        for (String unit : args.positional) {
            // 第二步：遍历用户输入的每一个服务单元名字，挨个处理。
            // 这里的循环逻辑有一个小细节值得注意：只要任何一个服务处理出错，
            // 就把总的 code 更新成那个出错的代码；但后面即使有服务成功了，
            // 也绝对不要把 code 改回 0，因为前面已经失败过了，
            // 整个命令不能算是完全成功。这种“失败优先”的记账方式
            // 在批量处理场景里非常常见，值得记住。
            int c = enableOne(unit, args.now);
            // 调用私有的 enableOne 方法去处理“单个”服务，
            // 把单元名字和 --now 选项（是否设置完立刻启动）都传进去，
            // 返回值 c 就是这个服务单独处理的结果代码。
            if (c != 0) {
                // 如果 c 不是 0，说明这个服务处理失败了，
                // 就把总结果 code 更新成这个失败代码（覆盖之前的值）。
                code = c;
            }
        }
        return code;
        // 循环结束，返回累积下来的结果代码：0 表示所有服务都成功，
        // 非 0 表示至少有一个服务失败了，调用方可以根据数字进一步判断。
    }

    private int enableOne(String rawUnit, boolean now) {
        // 这个私有方法负责处理“一个”服务单元：把它的启动类型设置为“自动”。
        // 为什么要把这一步单独抽成一个方法呢？因为 run 方法里要循环处理
        // 很多个单元，把“单次处理”的逻辑单独拎出来，代码结构更清晰，
        // 读起来不用在一大坨循环里找重点，也方便单独理解、单独测试。
        // 两个参数说明一下：
        //   - rawUnit：用户原始输入的服务单元名字，可能带后缀（比如
        //     "nginx.service"），也可能大小写不规范；
        //   - now：布尔值，表示设置完自启动之后，要不要立刻把服务启动起来。
        String unit = Units.normalize(rawUnit);
        // 第一步：把用户输入的原始名字“规范化”（normalize）。
        // 为什么要规范化呢？因为用户的输入格式往往五花八门：
        //   可能写全名 "nginx.service"；
        //   也可能偷懒只写 "nginx"；
        //   还可能大小写混写 "Nginx"。
        // Units.normalize 会把各种输入统一成一种标准格式，
        // 这样后面查找服务、打印提示信息的时候，才不会因为格式不一致
        // 而对不上号。这就好比我们平时填表单之前，先统一去掉空格、
        // 统一大小写，是一种非常常见的“输入预处理”手段。
        String name = Units.serviceName(unit);
        // 第二步：从规范化之后的单元名里取出“服务名”。
        // 在 systemd 风格的概念里，一个“单元”（unit）可能带有 .service
        // 后缀或者其他修饰；而真正对应 Windows 服务的名字，是去掉这些
        // 修饰之后的纯名字部分。Units.serviceName 就是专门干这件事的，
        // 它返回的 name 才是后面拿去操作系统服务的“真名”。
        if (!ctx.services.exists(name)) {
            // 第三步：检查这个服务在 Windows 上到底存不存在。
            // 如果用户拼错了名字，或者这个服务确实没有安装，
            // 我们就不能傻乎乎地继续去设置自启动了——对一个不存在的服务
            // 做操作，Windows 会报错，而且报错信息还很让人费解。
            // 所以先拦一道：打印“找不到这个服务”的提示，然后返回 4。
            // 返回 4 而不是 1，是为了让错误码更有区分度：
            // “服务不存在”和“操作失败”是两种不同的问题，
            // 脚本可以根据不同的错误码做不同的处理。
            System.out.println(ctx.i18n.msg("err.unit.not.found", unit));
            return 4;
        }
        System.out.println(ctx.i18n.msg("enable.enabling", unit));
        // 给用户打印一行“正在启用……”的提示，让用户知道程序没有卡住，
        // 正在干活。这种“先告诉用户我要干什么，然后再动手”的做法，
        // 能让命令行程序用起来更有反馈感，不会让人觉得程序像死机了一样，
        // 是写命令行工具时很值得养成的习惯。
        WindowsServiceManager.ActionResult res = ctx.services.setStartType(name, "auto");
        // 第四步：真正去调用 Windows 服务管理器，把这个服务的启动类型
        // 设置成 "auto"（自动启动）。这一步在背后会通过 Windows 的系统 API
        //（比如 ChangeServiceConfig 之类的接口，或者等价于 sc config 命令）
        // 去修改系统里关于这个服务的启动配置。
        // 调用结果封装在一个 ActionResult 对象里，里面既带着“成没成功”
        // 的标志（ok()），也带着出错时的错误代码（code()），
        // 这样我们就不用靠猜来判断结果了。
        if (!res.ok()) {
            // 如果设置失败了（ok() 返回 false），原因可能有很多：
            // 权限不够、服务被系统锁定、服务名写错等等。
            // 这时候我们打印一条失败信息，并且把错误代码翻译成人话
            //（errorMessage 方法会把数字代码转换成对应的文字说明，
            //  比如 5 通常代表“拒绝访问”），让用户一看就懂。
            // 最后返回 1 表示这个服务处理失败。
            System.out.println(ctx.i18n.msg("enable.failed", unit,
                    ctx.services.errorMessage(res.code())));
            return 1;
        }
        System.out.println(ctx.i18n.msg("enable.enabled", unit));
        // 能走到这里，说明启动类型已经成功改成“自动”了，
        // 给用户打印一行成功的提示，算是给这次设置画上一个圆满的句号。
        if (now) {
            // 如果用户带了 --now 选项（此时 now 为 true），
            // 说明用户希望“改了自启动之后马上就把服务启动起来”，
            // 那就调用 startNow 去立即启动服务，
            // 并且把启动的结果代码直接返回给调用方。
            // 如果没带 --now（now 为 false），那就什么都不用做，
            // 直接返回 0，表示“设置成功即可，启动的事以后再说”。
            return startNow(name, unit);
        }
        return 0;
    }

    int startNow(String name, String unit) {
        // 这个方法负责“立刻启动”一个服务。
        // 请注意它的访问修饰符：既不是 public 也不是 private，而是默认的
        // “包级可见”（package-private）。意思是：同一个包
        //（com.systemwin.commands）里的其他类也能调用它，
        // 方便将来其他命令（比如独立的 start 命令）复用
        // 这段“启动服务并处理结果”的逻辑，避免重复写代码。
        // 另外提醒一句：启动服务通常需要管理员权限，
        // 如果当前进程没有以管理员身份运行，这里往往会失败，
        // 返回一个表示“拒绝访问”之类的错误代码。
        WindowsServiceManager.ActionResult res = ctx.services.start(name);
        // 调用 Windows 服务管理器去启动这个服务。
        // 返回的结果对象 res 里带着操作是否成功的信息。
        if (res.ok()) {
            // 启动“成功”了？那也不一定是“刚刚才启动的”哦，
            // 这里藏着一种特殊情况：如果服务本来就已经在运行了
            //（比如用户之前手动启动过，或者别的程序把它拉起来了），
            // Windows 会返回错误码 1056（ERROR_SERVICE_ALREADY_RUNNING）。
            // 这种情况下我们并不把它当成“失败”，
            // 而是给用户打印“服务已经在运行了”的提示，然后照样返回 0，
            // 因为用户想要的结果——服务正在运行——已经达到了。
            System.out.println(res.code() == 1056
                    ? ctx.i18n.msg("start.already", unit)
                    : ctx.i18n.msg("start.started", unit));
            // 上面是一个三元表达式（? :），相当于 if/else 的简写：
            //   如果错误码恰好是 1056，就打印“已经启动”的消息；
            //   否则（真的是刚刚启动成功的）就打印“启动成功”的消息。
            // 这样处理能让用户看到的信息更准确，不会产生误导，
            // 也省得写一大段 if/else 把代码撑得又长又啰嗦。
            return 0;
        }
        // 能走到这里，说明启动真的失败了（不是 1056 那种“伪失败”）。
        // 我们把错误代码翻译成文字说明，打印出来告诉用户为什么失败，
        // 然后返回 1 表示失败，让调用方和脚本都能知道出了问题。
        System.out.println(ctx.i18n.msg("start.failed", unit,
                ctx.services.errorMessage(res.code())));
        return 1;
    }
}
