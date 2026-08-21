package com.systemwin.commands;

import com.systemwin.cli.Args;
import com.systemwin.service.WindowsServiceManager;
import com.systemwin.util.Units;

/*
 * 这个类是整个 SystemWin 程序里负责"卸载"（也就是删除）Windows 服务的那个命令类。
 * 你可以把 SystemWin 想象成一个简化版的 sc.exe 或者 net stop 工具，
 * 用户敲一行命令，比如 systemwin uninstall myservice，程序就会把对应的服务从系统里删掉。
 *
 * 为什么要有这样一个单独的类？因为项目里的命令不止一个（可能有 install、uninstall、status 等等），
 * 每个命令自己管自己的事情，互不干扰，这就是"单一职责原则"（Single Responsibility Principle）。
 * 以后要加新命令，就再写一个类实现 Command 接口，主分发器根本不用改，非常方便。
 *
 * 注意这个类名字叫 UninstallCommand，里面的注释也写着 Removes (deletes) a service，
 * 意思就是"把服务从系统里移除/删除"，注意不是"停止"，停止是暂停，删除是彻底移除，
 * 一个是临时状态，一个是永久操作，差别很大，初学者一定要分清楚。
 */

/** Removes (deletes) a service: {@code systemwin uninstall <unit>}. */
/*
 * 上面这段英文 javadoc 翻译过来就是："移除（删除）一个服务，用法是 systemwin uninstall <unit>"。
 * <unit> 指的是服务的"单元名"，也就是用户想卸载哪个服务，就把那个服务的名字写在这个位置。
 * 这种花括号 {@code ...} 的写法是 javadoc 的专用语法，意思是里面的内容按"代码字体"显示，
 * 这样文档生成出来以后，命令行示例看起来就像代码一样，一目了然。
 */
public final class UninstallCommand implements Command {

    /*
     * 下面这个 ctx 字段（context 的缩写，意思是"上下文"）是这个命令类的"工具箱"。
     * 它里面装着这个命令干活需要的各种"公共资源"，比如：
     *   - i18n：国际化的消息文本，程序里所有要打印给用户看的话都是从这儿取的，
     *           好处是以后要翻译成英文、日文，只要改资源文件，不用改代码；
     *   - services：真正操作 Windows 服务的底层管理器，删除服务这种"重活"都是它干的。
     * 为什么不直接在类里 new 一个？因为那样这个类就"自己造轮子"了，测试和复用都麻烦。
     * 用构造器把依赖传进来，这种设计叫"依赖注入"（Dependency Injection），
     * 说白了就是：你需要什么工具，别人从外面递给你，而不是你自己去商店买。
     */
    private final CommandContext ctx;

    /*
     * 这是 UninstallCommand 的构造方法（constructor），Java 里每个类都可以有构造方法，
     * 它负责在创建对象的时候做一些初始化工作。这里做的事很简单：
     * 把外面传进来的 CommandContext 保存到自己的 ctx 字段里，以后这个命令的所有方法都能用了。
     * 字段是 final 的，意思是这个引用一旦赋值就不能再改，保证命令从创建到用完，用的都是同一个上下文，
     * 不会出现"干到一半工具被人换走了"这种诡异的情况。
     *
     * 为什么需要这个构造方法？因为 main 程序在解析完命令行参数之后，
     * 会 new 一个 UninstallCommand，把全局上下文塞给它，然后调用它的 run() 方法去执行。
     */
    public UninstallCommand(CommandContext ctx) {
        this.ctx = ctx;
    }

    /*
     * run() 方法是 Command 接口规定好的"入口方法"，所有命令都必须实现它。
     * 也就是说，不管是什么命令，程序框架只会调用 run(args) 这一个方法，
     * 至于命令内部怎么折腾，框架不管，这是典型的"面向接口编程"。
     *
     * 这里的逻辑可以用大白话讲一遍：
     *   1. 先看看用户到底有没有给要卸载的服务名（args.positional 是命令后面的位置参数列表）；
     *   2. 如果一个都没给，那没法干，打印一条错误消息（从 i18n 里取，key 是 err.unit.required），
     *      然后返回退出码 1，表示"出错了"，主程序看到非 0 就知道失败了；
     *   3. 如果给了，就逐个服务去卸载（因为一次可以卸载好几个服务，比如 systemwin uninstall a b c）；
     *   4. 任何一个失败都会把退出码记下来，最后返回。注意这里"失败码会覆盖成功"，但不会覆盖别的失败码，
     *      后面还有更详细的解释。
     *
     * 退出码（exit code）是什么？就是程序结束时给操作系统的一个整数，0 表示成功，
     * 非 0 表示各种失败，脚本和 CI 工具全靠它判断命令有没有跑成功。
     */
    @Override
    public int run(Args args) {
        // positional 是"位置参数"，也就是不带 --xxx 选项、直接跟在命令后面的普通参数。
        // 比如 systemwin uninstall webapp cache，那 positional 就是 ["webapp", "cache"]。
        // 如果这个列表是空的，说明用户只敲了 uninstall，没说要卸载谁，这显然是不行的。
        if (args.positional.isEmpty()) {
            // 打印错误消息。i18n.msg("err.unit.required", "uninstall") 的意思是：
            // 去消息资源里找 key 为 err.unit.required 的那条模板，把 "uninstall" 填进去，
            // 最后得到类似 "uninstall 需要一个服务单元名" 这样的提示。
            // 返回 1 表示"用法错误/失败"，命令执行到此结束，后面的代码都不会跑了。
            System.out.println(ctx.i18n.msg("err.unit.required", "uninstall"));
            return 1;
        }
        // code 用来记录整个命令最终的退出码，初始是 0，也就是"目前一切都好"。
        // 为什么要在循环外面定义它？因为循环里可能要卸载好几个服务，
        // 我们需要记住"所有这些卸载里面，最严重的失败是哪个"，所以用一个变量攒着。
        int code = 0;
        // 遍历用户给的每一个服务单元名，一个一个处理。for-each 循环，
        // 每次循环 unit 变量就是当前这个服务的名字，处理完自动跳到下一个。
        for (String unit : args.positional) {
            // 调用私有方法 removeOne 去真正执行"删除单个服务"这件事，
            // 它会返回一个退出码 c：0 表示成功，非 0 表示失败。
            int c = removeOne(unit);
            // 只要有一个服务卸载失败，就把整个命令的最终退出码改成失败码。
            // 注意这里的写法是"非 0 才覆盖"，也就是说：
            //   - 前面失败过（code=1），后面成功了（c=0），code 保持 1，不错，因为整体算失败；
            //   - 前面成功，后面失败，code 变成失败码，也对；
            //   - 全都成功，code 一直是 0，完美。
            // 这比"直接 code = c"更严谨，避免把之前的失败"冲掉"。
            if (c != 0) {
                code = c;
            }
        }
        // 循环结束，把攒了一路的退出码返回给调用者（主程序），主程序再用它决定退出状态。
        return code;
    }

    /*
     * removeOne 是"私有方法"，只能在这个类内部调用，外面的世界看不到它。
     * 为什么要把"卸载单个服务"单独抽成一个方法？
     * 因为 run() 里可能有多个服务要卸载，如果全写在一个方法里，代码会又长又乱；
     * 抽出来之后，run() 的循环就变成"对每个服务调用一下 removeOne"，
     * 读起来就像在读一句话：对每个 unit，卸载它。这就是把大问题拆成小问题的好处。
     *
     * 这个方法做的事，一步一步说：
     *   1. 把用户输入的原始字符串"规范化"（normalize），因为用户可能写错大小写、
     *      或者多打了空格，甚至写的是别名，Units.normalize 负责把这些情况统一处理；
     *   2. 从规范化后的单元名推导出真正的 Windows 服务名（Units.serviceName），
     *      因为"单元名"和"服务名"可能不是一回事，中间有一层映射；
     *   3. 检查服务是否存在，不存在就直接报错退出，返回退出码 4（"找不到"专用的错误码）；
     *   4. 打印"正在卸载"的提示，然后调用 WindowsServiceManager 真正删服务；
     *   5. 根据删除结果判断成败：失败打印失败原因并返回 1，成功打印成功消息并返回 0。
     */
    private int removeOne(String rawUnit) {
        // normalize 就是"规范化"：把用户乱七八糟的输入整理成标准形式。
        // 比如用户可能敲了 SystemWin 的大小写问题、多余的空白、或者是缩写别名，
        // 这里统一处理，后面的代码就不用再担心格式问题了。
        // 变量名叫 rawUnit，raw 就是"原始"的意思，暗示这是用户原封不动敲进来的字符串。
        String unit = Units.normalize(rawUnit);
        // 拿到规范化后的单元名，再问 Units 要对应的真正的 Windows 服务名。
        // 为什么单元名和服务名不一样？因为 SystemWin 可能支持用户友好的短名字/别名，
        // 而 Windows 服务注册表里存的是系统级的正式名字，这两者需要翻译一下。
        String name = Units.serviceName(unit);
        // 删除之前一定要先确认服务确实存在，不然等于"删一个不存在的东西"，
        // 没意义还容易误导用户（用户可能以为删成功了，其实根本没有）。
        // exists(name) 就是去系统里查一查：有没有这个服务？返回 true/false。
        if (!ctx.services.exists(name)) {
            // 服务不存在，打印错误消息：err.unit.not.found 这个 key 对应的模板，
            // 把单元名 unit 填进去，告诉用户"你要卸的服务根本不存在"。
            // 返回 4：这是专门给"服务不存在"这种情况准备的退出码，
            // 和"用法错误(1)"、"删除失败(1)"区分开，方便脚本判断具体原因。
            System.out.println(ctx.i18n.msg("err.unit.not.found", unit));
            return 4;
        }
        // 到这里服务确实存在，可以动手删了。先打印一句"正在移除 xxx"的提示，
        // 让用户看到程序有反应，不至于以为卡死了。用户体验的小细节。
        System.out.println(ctx.i18n.msg("uninstall.removing", unit));
        // 真正干活的一行！调用 WindowsServiceManager 的 delete 方法删除服务，
        // 返回一个 ActionResult（操作结果）对象，里面封装了成功/失败和错误码等信息。
        // 注意我们用的是 ctx.services，也就是构造器注入的那个管理器实例。
        WindowsServiceManager.ActionResult res = ctx.services.delete(name);
        // ActionResult.ok() 返回是否成功。!res.ok() 就是"如果没成功"。
        // 删除服务是危险操作，可能因为权限不足、服务正在运行等原因失败，所以必须检查结果。
        if (!res.ok()) {
            // 删除失败：打印 uninstall.failed 模板的消息，里面带上错误的具体原因。
            // res.code() 是 Windows 返回的错误码（比如"拒绝访问"这类），
            // ctx.services.errorMessage(code) 负责把冷冰冰的数字错误码翻译成人能看懂的文字。
            System.out.println(ctx.i18n.msg("uninstall.failed",
                    ctx.services.errorMessage(res.code())));
            // 返回 1 表示失败，run() 里的循环会把这个失败码记到最终结果里。
            return 1;
        }
        // 能走到这一行，说明删除成功了，打印成功消息 uninstall.removed（比如"xxx 已卸载"）。
        System.out.println(ctx.i18n.msg("uninstall.removed", unit));
        // 返回 0 表示这个服务卸载成功。
        return 0;
    }
}
