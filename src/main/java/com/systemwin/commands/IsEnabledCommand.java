package com.systemwin.commands;

/*
 * 【这个文件是干什么的？先花两分钟把背景讲清楚】
 * 这个文件实现了 systemwin 这个命令行工具里的一个子命令：
 *   systemwin is-enabled <unit>...
 * 它的作用就一句话：帮用户查一查"某个 Windows 服务到底有没有被启用
 * （开机自启）"。用过 Linux 的同学都知道，systemctl 里有个命令叫
 * is-enabled，专门用来查看某个服务的"启用状态"。SystemWin 这个工具
 * 说白了就是想在 Windows 上复刻 systemctl 的体验，所以它的子命令
 * 命名、行为、甚至退出码，都尽量向 systemctl 看齐。
 *
 * 为了让刚接触这段代码的新同学能看懂，这个文件里我加了大量啰里啰嗦
 * （废话一般）的中文注释，把每一个细节都掰开揉碎了讲。请记住：注释
 * 再多也不会影响程序运行，编译器会直接忽略它们，所以放心大胆地读。
 *
 * 顺便说一下这个文件的"位置"：它住在 commands 包（命令包）里，这个
 * 包专门放各种子命令的实现类。凡是实现 Command 接口的类，都可以被
 * 框架以统一的方式调用，也就是"你给我一份解析好的参数，我负责干活
 * 并返回退出码"。
 */

import com.systemwin.cli.Args;                                  // 命令行参数解析结果类：用户敲了什么，都在这个对象里装着
import com.systemwin.service.ServiceInfo;                       // 服务信息类：一次查询得到的一个服务的各种属性（如启动模式）
import com.systemwin.service.WindowsServiceManager;             // Windows 服务管理器：真正和操作系统打交道、查询服务的"中间人"
import com.systemwin.util.Units;                                // 单位名工具类：负责把用户输入的服务名规范化、去后缀等

/**
 * {@code systemwin is-enabled <unit>...} prints enabled/static/disabled and
 * exits 0 when enabled, 1 otherwise, 4 when the unit does not exist.
 */
// 【上面这段英文 javadoc 的中文翻译】
// “{@code systemwin is-enabled <unit>...} 这个命令会打印出
// enabled / static / disabled 三种状态之一，并且：
//   - 当服务处于 enabled（已启用）状态时，进程退出码为 0（成功）；
//   - 否则退出码为 1（失败）；
//   - 当这个服务根本不存在的时候，退出码为 4（特殊的"找不到"错误码）。
//
// 【再展开啰嗦几句】
// 为什么退出码这么讲究？因为 systemwin 经常被脚本调用——脚本没法"看懂"
// 屏幕上打印的文字，但它能看懂退出码这个小整数。所以退出码本质上就是
// 程序跟脚本之间约定的"暗号"：0 表示一切正常，非 0 表示出了问题，
// 而 4 这个数字是特意挑的，它对应 systemctl 里"单元不存在"的语义，
// 这样从 Linux 迁移过来的脚本就能无缝对接，不用改任何判断逻辑。
//
// 注意这里还提到三种状态：enabled（已启用/开机自启）、static（静态/
// 手动启动，不会自动随开机启动）、disabled（已禁用）。这三种说法不是
// SystemWin 发明的，而是模仿 systemctl 的术语，让用户在两套系统上
// 看到的输出保持一致。
public final class IsEnabledCommand implements Command {

    /*
     * 【字段：ctx（命令上下文）】
     * ctx 是 CommandContext（命令上下文）的缩写。什么叫"上下文"？
     * 你可以把它想象成一个随身携带的"工具箱"，里面放着命令干活时
     * 可能需要的各种公共依赖：
     *   - ctx.i18n     国际化对象，负责把提示信息翻译成用户设置的语言
     *                  （中文或英文）；
     *   - ctx.services Windows 服务管理器，负责真正去操作系统查询服务。
     *
     * 为什么命令自己不直接 new 一个出来，而非要别人传进来呢？这就是
     * 依赖注入（Dependency Injection）的思想：依赖由外部统一创建好，
     * 谁需要就递给谁。好处是：创建逻辑只写一遍、方便替换实现、各个
     * 命令用起来完全一致。你去看同包里的其他命令（比如 StartCommand、
     * StatusCommand），它们也都有这个字段，套路完全一样。
     *
     * 注意修饰符是 private final：
     *   - private 表示只有这个类自己能访问，防止外面乱动；
     *   - final 表示这个引用一旦赋值就永远不能指向别的对象，也就是说
     *     上下文在整个命令的生命周期里"焊死"不变，保证行为稳定。
     * 所以每个命令对象被创建的时候，框架会把同一个"工具箱"递进来，
     * 这个命令保存下来慢慢用。
     */
    private final CommandContext ctx;

    /*
     * 【构造函数：命令对象是怎么出生的？】
     * 构造函数的名字必须和类名一模一样（IsEnabledCommand），它负责在
     * 对象被 new 出来的那一刻，把对象初始化好。
     *
     * 这个构造函数特别简单，只干一件事：把外部递进来的 CommandContext
     * 存到自己的 ctx 字段里，供后面的 run 方法使用。你可能要问：那为什么
     * 不直接在字段声明处赋值呢？因为字段的值要由"外部调用者"决定——
     * 框架在创建命令的时候才会准备好上下文，构造函数就是接收这个参数的
     * 最佳入口。这就是最常见的"构造注入"（constructor injection）。
     *
     * 另外注意：这里没有检查 ctx 是否为 null。这是一种约定——框架保证
     * 永远会传一个非空上下文进来，如果传了 null，那属于框架的 bug，
     * 后面用的时候自然会抛异常，早点暴露问题反而比藏着掖着好。
     */
    public IsEnabledCommand(CommandContext ctx) {
        this.ctx = ctx;
    }

    /*
     * 【方法：run —— 命令的真正入口】
     * 这个方法来自 Command 接口（implements Command 带来的义务），
     * 框架解析完用户敲的命令行之后，就会调用它来执行这条命令。
     * 它接收一个 Args 对象（装着解析好的参数），返回一个 int 退出码。
     *
     * 整个方法的逻辑可以概括成三步：
     *   第一步：检查用户有没有给参数。is-enabled 后面必须跟着至少一个
     *           服务名，否则就是"空手而来"，我们直接报错退出；
     *   第二步：遍历用户给的每一个服务名，逐个调用私有的 checkOne 方法
     *           去查询并输出状态；
     *   第三步：把各个服务的退出码汇总成一个总退出码返回给调用方。
     * 下面我们一步步看代码，每一行都有注释。
     */
    @Override
    public int run(Args args) {
        // 第一步：检查位置参数是否为空。
        // args.positional 是"位置参数"列表，所谓位置参数就是不带任何
        // 开关（比如 -n、--all 之类）的裸参数。对 is-enabled 来说，
        // 这些裸参数就应该是用户想查询的服务名，例如：
        //   systemwin is-enabled spooler
        // 这里的 "spooler" 就会出现在 positional 列表里。
        //
        // 如果列表是空的，说明用户只敲了 "systemwin is-enabled" 却忘了
        // 告诉我们要查谁——这当然是用法错误，没必要继续往下执行了。
        if (args.positional.isEmpty()) {
            // 这里用 ctx.i18n.msg(...) 来取提示文本，而不是硬编码一串
            // 字符串。i18n.msg 的第一个参数是"消息键"，第二个参数是
            // 占位符。它会在用户当前设置的语言（中文或英文）下，找到
            // 对应的翻译文本填进去。这样同一句提示，中文用户看到中文、
            // 英文用户看到英文，代码里却只写一遍，非常优雅。
            System.out.println(ctx.i18n.msg("err.unit.required", "is-enabled"));
            // 用法错误，返回退出码 1（失败）。脚本拿到 1 就知道"这条
            // 命令没执行成功"，至于具体为什么失败，看屏幕上打印的那句
            // 提示就行。
            return 1;
        }
        // 第二步：准备一个"总退出码"。
        // 初始值设为 0（成功），因为默认情况下我们当然希望结果是好的。
        // 后面如果发现某个服务不满足条件，就把这个变量改成非 0。
        // 之所以要先设成 0 而不是直接使用某个服务的返回值，是因为
        // 用户可能一次性传入多个服务名，每个服务的结果可能不一样，
        // 我们需要把它们"汇总"成一个最终结果返回。
        int code = 0;
        // 第三步：遍历用户给的每一个服务名，逐个检查。
        // for 循环的写法：for (元素类型 变量名 : 集合)，意思是"依次
        // 从 positional 列表里取出一个服务名，赋值给 unit，执行一遍
        // 循环体，直到列表被取完为止"。这是 Java 里的"增强 for 循环"
        // （for-each），比老式的下标循环简洁得多，也不会出现下标越界。
        for (String unit : args.positional) {
            // 对当前这个服务名调用私有的 checkOne 方法，得到它的退出码。
            // checkOne 负责单个服务的全部工作：查状态、打印状态、返回
            // 退出码。run 方法本身不关心具体怎么查，只关心"这个服务
            // 检查得怎么样"——这叫做"职责分离"，run 管大局，checkOne
            // 管细节。
            int c = checkOne(unit);
            // 如果这个服务的退出码不是 0（也就是失败了），就把它记录到
            // 总退出码里。
            //
            // 【一个值得注意的小细节】
            // 这里用的是"覆盖"而不是"累积"：if (c != 0) { code = c; }。
            // 也就是说，如果有多个服务都失败了，最终返回的是"最后一个
            // 失败服务"的退出码，而不是第一个，更不是把它们相加。
            // 比如传了两个服务，第一个不存在（返回 4），第二个处于
            // disabled（返回 1），那么最终 code 会是 1。这是有意为之的
            // 简化设计——反正只要有一个失败，整体就算失败（非 0），
            // 至于具体是哪个失败码，取最后一个就够了。
            if (c != 0) {
                code = c;
            }
        }
        // 第四步：把汇总好的退出码返回给框架。框架再把这个值作为整个
        // 进程的退出码交还给操作系统/脚本，完成"汇报结果"的使命。
        return code;
    }

    /*
     * 【方法：checkOne —— 检查单个服务（私有辅助方法）】
     * 这个方法处理"一个服务名"的全部逻辑，是 run 方法的核心帮手。
     * 为什么要单独拆成一个方法，而不是直接写在 run 的循环体里？
     * 两个原因：
     *   1. 可读性：run 方法只需要"对每个名字调用 checkOne"，读起来
     *      一目了然，不会被一堆细节淹没；
     *   2. 可复用：以后如果别的地方也想"查单个服务的启用状态"，
     *      直接调用这个方法就行（当然它现在是私有的，同一个类的其他
     *      方法可以调用）。
     *
     * 它的完整流程是四步走：
     *   1. 把用户输入的服务名"规范化"并转成真正的服务名；
     *   2. 调用服务管理器查询这个服务的信息；
     *   3. 如果查不到（服务不存在），打印 unknown 并返回退出码 4；
     *   4. 如果查到了，把启动模式翻译成 enabled/static/disabled 三种
     *      状态之一，打印出来，然后按"是否 enabled"返回 0 或 1。
     *
     * 参数名叫 rawUnit，意思是"原始的服务名"——也就是用户怎么敲的，
     * 这里就收到什么，还没经过任何加工。
     */
    private int checkOne(String rawUnit) {
        // 第一步：规范化服务名。
        // 用户输入的服务名可能是五花八门的，比如 "spooler"、"Spooler"、
        // "spooler.service"、甚至 "spooler.exe" 之类。直接拿原始字符串
        // 去查询，很可能查不到，所以我们分两步处理：
        //   1. Units.normalize(rawUnit)：把用户输入统一成标准的
        //      "xxx.service" 完整形式（补全后缀、统一大小写等）；
        //   2. Units.serviceName(...)：再把带后缀的完整名字去掉
        //      ".service" 后缀，还原成纯服务名。
        // 这一正一反两个操作合起来的效果是：不管用户怎么敲，最终得到
        // 的 name 都是一个"干干净净、可以拿去查询"的标准服务名。
        // 这种"输入规范化"是命令行工具里非常常见的套路，能省去后面
        // 一大堆麻烦的判断。
        String name = Units.serviceName(Units.normalize(rawUnit));
        // 第二步：查询服务信息。
        // ctx.services 是 Windows 服务管理器，query(name) 方法会去
        // 操作系统里查找名为 name 的服务，并返回一个 ServiceInfo 对象，
        // 里面装着这个服务的各种属性（比如启动模式 startMode）。
        // 如果找到了，info 就是非 null；如果没找到，info 就是 null。
        // 注意：这里查询的是"服务是否存在"，跟"服务是否启用"是两码事，
        // 先确认存在，才有资格谈启用状态。
        ServiceInfo info = ctx.services.query(name);
        // 第三步：判断服务是否存在。
        // info == null 意味着操作系统里压根没有这个名字的服务。
        // 这种情况不能当成"禁用"处理——禁用至少说明服务是存在的，
        // 只是不允许启动；不存在则是另一回事，必须用专门的错误码区分。
        if (info == null) {
            // 打印 "unknown"（未知）。这是模仿 systemctl 的输出风格：
            // 单元不存在时，is-enabled 就显示 "unknown"。
            System.out.println("unknown");
            // 返回退出码 4。这个 4 不是随便定的，它对应 systemctl 里
            // "单元不存在"的约定，脚本可以根据 4 判断"服务压根不存在"，
            // 从而给出更准确的错误提示。
            return 4;
        }
        // 第四步：翻译启动模式并打印。
        // info.startMode() 返回的是 Windows 原生的启动模式，可能是
        // Auto（自动）、Manual（手动）、Disabled（禁用）等。这些英文
        // 词直接给用户看不太直观，而且跟 Linux 的 systemctl 对不上，
        // 所以要用 WindowsServiceManager.enableState(...) 这个静态方法
        // 把它们翻译成统一的 systemctl 风格状态：
        //   Auto/automatic  -> enabled （已启用，开机自启）
        //   Manual/demand   -> static  （静态，手动启动）
        //   Disabled        -> disabled（已禁用）
        // 这样用户在 Windows 上看到的输出，跟在 Linux 上用 systemctl
        // 看到的完全一致。
        String state = WindowsServiceManager.enableState(info.startMode());
        // 把翻译好的状态打印到屏幕上，比如 "enabled"、"static" 或
        // "disabled"。这就是用户最终看到的答案。
        System.out.println(state);
        // 最后一步：根据状态决定退出码。
        // "enabled".equals(state) 是一个布尔表达式：state 恰好是
        // "enabled" 时为 true，否则为 false。写成 "enabled".equals(state)
        // 而不是 state.equals("enabled")，是个小小的防御技巧——就算
        // state 是 null，也不会抛空指针异常（equals 是字符串常量在调用，
        // 常量永远不为 null）。严谨的程序员连这种边角料都会考虑。
        //
        // 返回 0 还是 1 的逻辑：
        //   - 是 enabled（已启用）→ 返回 0（成功，条件满足）；
        //   - 是 static 或 disabled → 返回 1（失败，没启用）。
        // 这样一来，脚本就能用一条简单的判断（退出码是不是 0）来知道
        // "这个服务到底启没启用"，跟 systemctl is-enabled 的行为完全
        // 一致。注意这里的退出码只关心"是否 enabled"，static 和
        // disabled 在脚本眼里都算"没启用"，都返回 1。
        return "enabled".equals(state) ? 0 : 1;
    }
}
