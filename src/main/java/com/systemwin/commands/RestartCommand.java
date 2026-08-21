package com.systemwin.commands;

import com.systemwin.cli.Args;
import com.systemwin.service.WindowsServiceManager;
import com.systemwin.util.Units;

/**
 * Restarts one or more services: {@code systemwin restart <unit>...}.
 *
 * <p>【中文翻译】这个类的职责是：把用户敲进来的“重启”命令真正执行掉。
 * 也就是说，当用户在命令行里输入类似 {@code systemwin restart nginx} 或者
 * {@code systemwin restart nginx mysql} 这样的一行命令时，程序最终会走到我们这个类里面来，
 * 由我们负责把列出来的每一个服务（unit，也就是一个服务单元）逐个停下来、再重新启动起来。
 * 这个过程在运维界有个专门的说法叫 “restart”（重启），其实就是“先停后启”两个动作的组合。</p>
 *
 * <p>【废话时间】为什么要单独做一个 RestartCommand 类，而不是把逻辑直接写在 main 方法里？
 * 因为程序把“命令”这个概念抽象成了一个接口（Command 接口），每个命令（比如启动、停止、重启、查看状态）
 * 都各自实现成一个独立的类。这样做的好处是：每个命令的代码互不干扰，改一个命令不会影响另一个命令，
 * 而且以后要新增一个命令，只要照着这个模式再写一个类就行了，非常符合“单一职责原则”
 * （Single Responsibility Principle，简单说就是一个类只专心做一件事）。</p>
 *
 * <p>这个类被声明为 {@code final}，意思是它不能再被继承（subclass）。
 * 这其实是一个刻意的设计决定：RestartCommand 的实现已经完全固定了，没必要让别人去继承它然后覆盖
 * （override）里面的方法，所以干脆用 final 锁死，防止别人乱来，也让代码的意图更清晰。</p>
 */
public final class RestartCommand implements Command {

    // ------------------------------------------------------------------
    // 成员变量区
    // ------------------------------------------------------------------
    // ctx 是 “CommandContext” 的缩写，翻译过来就是“命令上下文”。
    // 你可以把它理解成一个“工具包”或者“万能插座”：里面装着这个命令运行时可能用到的所有外部依赖，
    // 比如：i18n（国际化文本，用来输出不同语言的提示信息）、services（Windows 服务管理器，真正去操作系统
    // 启停服务的地方）等等。为什么要把这些东西打包传进来，而不是在类里面直接 new 一个？
    // 因为这样方便测试（测试时可以传入一个假的服务管理器，不用真的去动操作系统里的服务），
    // 也方便以后替换实现（依赖注入的思想，简单理解就是“要用什么，就由外面递进来，而不是自己造”）。
    private final CommandContext ctx;

    /**
     * 构造函数：把外面传进来的 CommandContext 存起来备用。
     * 【中文翻译】这是类的构造方法（constructor），名字跟类名一模一样，没有返回值。
     * 它做的事情很简单：把参数 ctx 赋值给成员变量 this.ctx。这样，以后类里面任何一个方法
     * 想用上下文里的东西（比如 i18n 消息、services 服务管理器），直接访问 this.ctx 就行了。
     * 注意这里用 final 修饰成员变量，意味着 ctx 一旦在构造时被赋值，就永远不能再被替换了，
     * 这保证了命令对象在整个生命周期里使用的上下文始终是同一个，不会出现“用到一半被换掉”的诡异 bug。
     *
     * @param ctx 命令上下文（命令运行所需的依赖集合）
     */
    public RestartCommand(CommandContext ctx) {
        // 把传进来的上下文保存到自己的成员变量里，供后面的方法使用
        this.ctx = ctx;
    }

    /**
     * 命令的入口方法：当用户执行 restart 命令时，框架就会调用这个方法。
     * 【中文翻译】因为我们实现了 Command 接口，所以必须实现接口里定义的 run 方法。
     * 参数 args 是用户输入的命令行参数（Arguments 的缩写），里面有一个 positional 字段，
     * 也就是“位置参数”列表，简单说就是用户敲命令时，不带任何选项符号（比如没有 -x、--y 这种）的那些单词。
     * 比如用户输入 {@code systemwin restart nginx mysql}，那 positional 就是 ["nginx", "mysql"]。
     *
     * 【执行流程废话版】这个方法一共分三步：
     * 1. 先检查用户有没有给参数。如果 positional 是空的（用户只敲了 restart 没敲服务名），
     *    那就提示“必须指定服务名”，并且返回退出码 1 表示出错（返回非 0 的退出码，命令行就知道出错了）。
     * 2. 如果用户给了参数，就准备一个初始为 0 的“退出码”变量 code。为什么要 0？
     *    因为在 Linux/Windows 命令行世界里，约定俗成：0 表示成功，非 0 表示失败。
     *    我们先把结果假设成成功（0），后面只要有一个服务重启失败，就把 code 改成那个失败码。
     * 3. 用一个 for 循环，把用户列出的每一个服务名挨个拿去执行“重启单个服务”的逻辑（restartOne 方法），
     *    把每次的结果收集起来。循环结束后，把最终的 code 返回给上层调用者，上层再把它作为进程的退出码。
     *
     * 【小细节】注意循环里这一句：{@code if (c != 0) { code = c; }}，它不是直接把 code 赋成 c，
     * 而是只有在 c 不为 0（即这次重启失败了）的时候才覆盖 code。这就造成一个效果：
     * 如果重启三个服务，第一个失败了（返回 4）、后面两个成功了（返回 0），那么最终 code 会保留第一个的 4，
     * 不会因为后面的成功而把错误码覆盖回 0。换句话说，只要有任何一次失败，最终退出码就是非 0，
     * 这是很常见的“聚合错误码”写法——宁可按最坏情况报错，也不要把失败悄悄吞掉。
     *
     * @param args 命令行参数对象
     * @return 退出码（0 表示全部成功，非 0 表示至少有一个失败）
     */
    @Override
    public int run(Args args) {
        // 第一步：检查用户有没有提供服务名。positional.isEmpty() 表示用户一个参数都没给。
        if (args.positional.isEmpty()) {
            // 通过 i18n 输出本地化提示：err.unit.required 是一条“必须提供服务单元”的错误消息。
            // 这里不直接写死字符串，而是用消息 key（键）去查，好处是以后想翻译成英文/其他语言不用改代码。
            System.out.println(ctx.i18n.msg("err.unit.required", "restart"));
            // 返回退出码 1：约定好的“一般性错误”码，命令行脚本拿到 1 就知道重启命令失败在参数校验上了。
            return 1;
        }
        // 第二步：初始化一个“最终退出码”，先默认 0（成功）。
        int code = 0;
        // 第三步：遍历用户给出的每一个服务名，逐个处理。增强 for 循环，逐个取出 positional 里的字符串。
        for (String unit : args.positional) {
            // 把单个服务的重启结果（退出码）存到局部变量 c 里
            int c = restartOne(unit);
            // 如果这次重启失败了（c != 0），就把最终退出码更新成它。
            // 注意：成功（c == 0）时不覆盖，这样前面失败的错误码就不会被后面的成功冲掉。
            if (c != 0) {
                code = c;
            }
        }
        // 把所有服务都处理完了，把聚合出来的最终退出码返回给调用方。
        return code;
    }

    /**
     * 重启单个服务的私有方法。
     * 【中文翻译】private 表示这个方法只能在 RestartCommand 类内部被调用，外部是看不见的，
     * 这是“封装”思想的体现：重启动一个服务的复杂细节不该暴露给外面，外面只需要调用 run 就够了。
     * 参数 rawUnit 是用户原始输入的服务名（raw 就是“原始的、未经处理的”意思），
     * 为什么叫 raw？因为用户输入的可能是个简称、别名或者带了奇怪格式的名字，需要先“规范化”一下才能用。
     *
     * 【完整流程废话版】这个方法就是整个重启命令的核心，做四件事：
     * 1. 规范化（normalize）：把用户输入的原始名字，用 Units.normalize 转成标准的单位名。
     *    就好比用户输入 “nginx.exe” 和输入 “nginx”，经过规范化之后可能变成同一个标准名字。
     * 2. 取服务名（serviceName）：再通过 Units.serviceName 从单位名里提取出真正的 Windows 服务名。
     *    注意：单位名（unit）和 Windows 服务名（service name）是两个概念——unit 是 systemwin 自己的叫法，
     *    而 Windows 服务名是操作系统注册表里那个真正用来控制服务的名字。
     * 3. 检查服务是否存在：调用 ctx.services.exists(name) 问服务管理器“这个服务在不在呀？”
     *    如果不存在，就提示“服务未找到”并返回退出码 4（4 一般表示“找不到东西”这类资源错误）。
     * 4. 先停止、再启动：stop 之后 start，两步都成功才算重启成功，最后打印“已重启”并返回 0。
     *
     * 【为什么先停再启？】重启（restart）的本质就是“停掉旧的，再拉起新的”。
     * 因为有些服务（比如改了配置）必须完全停止后重新启动，新配置才会生效；
     * 直接启动一个已经在运行的服务，Windows 通常会报“服务已启动”的错误（错误码 1056）。
     *
     * @param rawUnit 用户输入的原始服务单元名
     * @return 退出码：0 表示该服务重启成功，4 表示服务不存在，1 表示停止或启动过程中出错
     */
    private int restartOne(String rawUnit) {
        // 第 1 步：规范化服务名。用户输入的“乱七八糟”的名字，先统一成标准格式。
        String unit = Units.normalize(rawUnit);
        // 第 2 步：从规范化后的单位名，取出对应的 Windows 服务名（真正给操作系统用的名字）。
        String name = Units.serviceName(unit);
        // 第 3 步：先检查这个服务到底存不存在，避免对一个不存在的服务瞎折腾。
        // exists 是问服务管理器“这个服务在系统里注册了吗？”
        if (!ctx.services.exists(name)) {
            // 服务不存在：通过 i18n 输出“找不到该服务”的提示，把用户输入的单位名原样带进消息里，
            // 方便用户知道自己敲错的是哪个。
            System.out.println(ctx.i18n.msg("err.unit.not.found", unit));
            // 返回退出码 4：表示“资源/对象找不到”这类错误（每个退出码的含义都是约定好的）。
            return 4;
        }
        // 第 4 步（前半）：告诉用户“正在重启 xxx……”，先给个心理准备，毕竟启停服务可能要花一两秒。
        System.out.println(ctx.i18n.msg("restart.restarting", unit));
        // 调用 Windows 服务管理器去“停止”这个服务。ActionResult 是操作结果对象，
        // 里面封装了这次操作是否成功（ok()）以及 Windows 返回的错误码（code()）。
        WindowsServiceManager.ActionResult stop = ctx.services.stop(name);
        // 判断停止是否成功。注意这个条件很讲究：不是只要 !ok() 就报错，
        // 而是还要排除错误码 1062 —— 1062 在 Windows 里表示“服务尚未启动”（ERROR_SERVICE_NOT_ACTIVE）。
        // 也就是说：如果服务本来就没在运行，那么“停止它”失败是完全可以接受的，
        // 因为我们的目的只是让它处于停止状态，它本来就停着，等于目标已经达成了，不算错误。
        // 这就是编程里常说的“宽容处理”/“幂等性”思想：同样的操作做多少次，结果都一样，不较真。
        if (!stop.ok() && stop.code() != 1062) {
            // 停止真的失败了（而且不是因为服务本来就没启动）：打印失败原因。
            // errorMessage(code) 会把 Windows 的数字错误码翻译成人类能看懂的文字。
            System.out.println(ctx.i18n.msg("restart.failed", unit,
                    ctx.services.errorMessage(stop.code())));
            // 返回 1：停止阶段出错，本次重启失败。
            return 1;
        }
        // 第 4 步（后半）：停止搞定之后，紧接着调用服务管理器去“启动”这个服务。
        WindowsServiceManager.ActionResult start = ctx.services.start(name);
        // 同样的宽容处理逻辑：正常来说启动失败就是失败，但要排除错误码 1056 ——
        // 1056 在 Windows 里表示“服务已经在运行”（ERROR_SERVICE_ALREADY_RUNNING）。
        // 如果服务已经在跑了，那“启动它”这个动作虽然技术上报了错，但我们要的效果（服务在运行）已经达成了，
        // 所以也不算错误。这两处（1062 和 1056）正好是一对：一个容忍“本来就没跑”，一个容忍“本来就在跑”，
        // 合起来就是“无论服务之前是什么状态，重启之后它一定是运行状态”。
        if (!start.ok() && start.code() != 1056) {
            // 启动真的失败了：把错误码翻译成人话，打印给用户看。
            System.out.println(ctx.i18n.msg("restart.failed", unit,
                    ctx.services.errorMessage(start.code())));
            // 返回 1：启动阶段出错，本次重启失败。
            return 1;
        }
        // 走到这里说明：停止成功（或本来就停着），启动成功（或本来就在跑），
        // 这个服务的重启就算圆满完成了，打印“已重启”给用户看。
        System.out.println(ctx.i18n.msg("restart.restarted", unit));
        // 返回 0：这个服务重启成功。这个 0 会一路传回 run 方法，参与最终的退出码聚合。
        return 0;
    }
}
