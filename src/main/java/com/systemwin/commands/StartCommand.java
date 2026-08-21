package com.systemwin.commands;

import com.systemwin.cli.Args;
import com.systemwin.service.WindowsServiceManager;
import com.systemwin.util.Units;

/*
 * 中文解释（废话版）：
 * 这个文件是 SystemWin 这个命令行工具里负责“启动服务”的那个命令类。
 * 也就是说，当用户在命令行里敲 `systemwin start xxx` 的时候，
 * 程序最终会跑到这个类里面来干活。
 *
 * 简单来说，一个命令类就是一个“干活的小工”：
 * 用户说什么，它就做什么。这个 StartCommand 专门负责“启动一个或多个 Windows 服务”。
 * 就好比你去饭馆点菜，StartCommand 就是那个负责“把菜端上来”的服务员，
 * 只不过它端上来的不是菜，而是“让 Windows 服务跑起来”这件事。
 *
 * 它实现了 Command 这个接口（interface），
 * 接口就像是一份“劳动合同”，规定了所有命令类都必须有一个 run 方法，
 * 这样上层代码（比如主程序）就可以用统一的方式调用任何命令，
 * 不用管这个命令到底是“启动”还是“停止”还是别的什么。
 */
/** Starts one or more services: {@code systemwin start <unit>...}. */
/* 上面这句英文的意思是：启动一个或多个服务，用法是 `systemwin start <unit>...`。
 * 换句话说，这个类的职责就是处理 start 这个子命令。
 * unit 在这里可以理解为“服务的名字”或者“服务的单位”，
 * 你可以一次给好几个服务名字，它就会挨个儿把他们都启动起来。 */
public final class StartCommand implements Command {

    /*
     * 这里保存了一个 CommandContext（命令上下文）对象。
     * 什么是“上下文”？你可以把它想象成一个“工具包”或者“百宝箱”，
     * 里面装着这个命令干活时需要用到的一切东西：
     * 比如 i18n（国际化/翻译文案的模块）、services（管理 Windows 服务的模块）等等。
     *
     * 为什么要用 final 修饰这个字段呢？
     * final 的意思是“一旦赋值就不能再改”，
     * 这样能保证这个命令在创建之后，它手里的“工具包”不会被人偷偷换掉，
     * 用起来更安全、更不容易出 bug。这是一种很常见的写法。
     */
    private final CommandContext ctx;

    /*
     * 这是构造函数（constructor），名字和类名一模一样，没有返回值。
     * 当外面要创建 StartCommand 对象的时候，就必须把 ctx 传进来，
     * 就像你去餐厅坐下，服务员必须先给你拿菜单一样——
     * 没有 ctx，这个命令就啥也干不了，所以必须在“出生”的时候就拿到它。
     * 这种写法叫“依赖注入”（dependency injection），
     * 听起来很高级，其实说白了就是：你需要什么，就让别人在创建你的时候给你，
     * 而不是你自己满世界去找。
     */
    public StartCommand(CommandContext ctx) {
        this.ctx = ctx;
    }

    /*
     * 下面这个 run 方法是 Command 接口要求我们实现的“主入口”。
     * 当用户在命令行敲了 start 命令之后，主程序就会调用这个 run 方法，
     * 并把命令行解析出来的参数（Args）传进来。
     *
     * 返回值是一个 int（整数），用来表示“这次命令执行的结果如何”：
     * 返回 0 表示成功，返回非 0 表示失败（不同的数字代表不同的失败原因）。
     * 这就像考试打分，0 分就是及格，其他分数代表不同的错误类型。
     */
    @Override
    public int run(Args args) {
        // 第一步：检查用户有没有给我们要启动的服务名字。
        // 如果 args.positional（位置参数列表）是空的，说明用户只敲了
        // `systemwin start`，后面啥也没跟，那我们就不知道要启动谁了。
        if (args.positional.isEmpty()) {
            // 既然没给服务名字，我们就打印一条提示信息，告诉用户“必须指定要启动的服务”。
            // 注意：这里没有直接写死中文或英文的提示文字，
            // 而是通过 ctx.i18n.msg(...) 去查翻译表（国际化），
            // 这样同一个程序可以根据用户的语言环境显示不同的文字。
            // 第一个参数 "err.unit.required" 是这条消息的“编号/键名”，
            // 第二个参数 "start" 会被填进消息模板里，告诉用户是在 start 命令这里出错了。
            System.out.println(ctx.i18n.msg("err.unit.required", "start"));
            return 1; // 返回 1 表示“用法错误”，跟 Windows 命令行工具的惯例保持一致。
        }
        int code = 0;
        // 第二步：用户可能一次指定了好几个服务（positional 列表里有好几个名字），
        // 所以我们用 for-each 循环挨个儿处理，一个都不能落下。
        // 这就像洗衣服，你不可能把所有衣服一次性塞进去就算了，
        // 得一件一件地检查、一件一件地处理。
        for (String unit : args.positional) {
            // 对每一个服务名字，都调用下面的 startOne 方法来真正执行启动动作。
            int c = startOne(unit);
            // 只要有一个服务启动失败（c != 0），我们就要把这个失败状态记下来。
            // 注意这里的小细节：我们是“保留”失败码，而不是直接 return，
            // 因为后面可能还有别的服务要启动，我们不能因为第一个失败就放弃后面所有的。
            // 但如果后面又失败了，新的失败码会覆盖旧的（因为 code = c）。
            // 这是一种“尽量把活干完，最后汇报总体结果”的思路。
            if (c != 0) {
                code = c;
            }
        }
        // 第三步：全部处理完了，把最终的结果码返回给上层调用者。
        // 只要有任何一次失败，code 就不是 0，上层就知道这次 start 没有完全成功。
        return code;
    }

    /*
     * 下面这个 startOne 方法负责“启动单个服务”。
     * 注意方法名里的 One：一次只处理一个服务，跟上面 run 里的循环是配合使用的。
     * 把“启动单个服务”的逻辑单独抽出来成一个方法，
     * 是为了让代码更清晰、更容易测试——每个方法只干一件事，这就是“单一职责”原则。
     *
     * 参数 rawUnit 是用户从命令行敲进来的“原始”服务名字，
     * 之所以叫 raw（原始），是因为它可能没经过任何处理，
     * 比如大小写不统一、或者带着空格什么的，所以下面要先“归一化”。
     */
    private int startOne(String rawUnit) {
        // 第一步：把用户输入的原始名字“归一化”（normalize）。
        // 什么是归一化？就是把五花八门的写法统一成标准写法。
        // 比如用户敲了 "nginx"，或者 "NGINX"，或者 " nginx "（带空格），
        // Units.normalize 都会把它们整理成同一个标准形式。
        // 这就像查快递单号之前，先把前后的空格去掉、字母统一成小写，
        // 不然同一个东西可能被当成两个不同的东西，那就乱套了。
        String unit = Units.normalize(rawUnit);
        // 第二步：根据归一化后的名字，算出对应的 Windows 服务名。
        // 注意：用户输入的名字和 Windows 系统里真正的服务名可能不一样，
        // 比如用户可能输入的是一个“友好名”/别名，
        // 而 Windows 服务管理器里注册的是另一个名字。
        // Units.serviceName 就是负责做这个“翻译”的。
        // 这就像你去餐厅点“宫保鸡丁”，后厨真正做菜的编号是“菜谱第 12 号”，
        // 服务员得把你说的话翻译成后厨能懂的编号。
        String name = Units.serviceName(unit);
        // 第三步：检查这个服务到底存不存在。
        // 万一用户拼错了名字，或者这个服务根本没安装，
        // 我们可不能硬着头皮去启动一个不存在的服务，那肯定会报错。
        // ctx.services.exists(name) 就是在问“这个服务存在吗？”，返回 true/false。
        if (!ctx.services.exists(name)) {
            // 不存在！那就打印一条“找不到这个服务”的错误信息，并把 unit（用户输入的原名）
            // 也一起打印出来，方便用户对照自己到底敲了啥。
            System.out.println(ctx.i18n.msg("err.unit.not.found", unit));
            return 4; // 返回 4 表示“服务不存在”这类错误。
        }
        // 第四步：好，服务存在，那就开始启动吧。
        // 先打印一句“正在启动 xxx”给用户看，让用户知道程序没卡死，正在干活。
        // 这种“给用户反馈”的输出很重要，不然用户看着黑乎乎的命令行，
        // 还以为程序死机了呢。
        System.out.println(ctx.i18n.msg("start.starting", unit));
        // 第五步：真正调用 Windows 服务管理器的 start 方法来启动服务。
        // 注意：我们不会自己去跟 Windows 的系统 API 打交道，
        // 而是把这件事委托给 ctx.services（WindowsServiceManager）去做。
        // 这样代码的分工就很清楚：StartCommand 只负责“指挥”，
        // WindowsServiceManager 才负责“真正动手操作 Windows”。
        // 返回的结果 res 是一个 ActionResult（操作结果）对象，
        // 里面既告诉我们成功还是失败，也告诉我们 Windows 返回的错误码。
        WindowsServiceManager.ActionResult res = ctx.services.start(name);
        // 第六步：看看启动的结果到底如何。
        if (res.ok()) {
            // 启动成功了！但成功里面还分两种情况：
            if (res.code() == 1056) {
                // 情况一：错误码是 1056。这个 1056 是 Windows 的一个特殊错误码，
                // 意思是“这个服务本来就已经在运行了”。
                // 也就是说，用户想启动的服务其实早就启动过了，
                // 我们没必要再启动一次（也启动不了第二次），
                // 所以就友好地提示用户“服务已经在运行了”，而不是报错。
                // 这就像你去开门，发现门本来就开着，你总不能说“开锁失败”吧。
                System.out.println(ctx.i18n.msg("start.already", unit));
            } else {
                // 情况二：错误码不是 1056，那就是正常的“首次启动成功”，
                // 打印“服务已启动”的提示。
                System.out.println(ctx.i18n.msg("start.started", unit));
            }
            return 0; // 成功就返回 0，让上层知道这个服务启动成功了。
        }
        // 第七步：走到这里说明启动失败了（res.ok() 是 false）。
        // 我们打印一条失败信息，并且把 Windows 返回的错误码翻译成人话
        // （通过 ctx.services.errorMessage(res.code()) 把错误码变成可读的文本），
        // 这样用户一眼就能看出到底哪里出了问题。
        System.out.println(ctx.i18n.msg("start.failed", unit,
                ctx.services.errorMessage(res.code())));
        return 1; // 启动失败，返回 1 表示失败。
    }
}
