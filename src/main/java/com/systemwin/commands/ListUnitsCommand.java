package com.systemwin.commands;

/*
 * 先说一下这个包（package）是干嘛的。
 * 我们这个项目叫 SystemWin，本质上就是想在 Windows 上模仿 Linux 的 systemctl 命令。
 * 而 commands 这个包，就是专门放“各种命令”的地方，比如 list-units、start、stop 之类的。
 * 每个命令都是一个类，并且都实现了 Command 这个接口，
 * 这样命令行解析器就可以用统一的方式去调用它们，不用管具体是哪个命令。
 * 可以把这个包理解成一个“命令仓库”，里面堆满了各种命令实现类。
 */

import com.systemwin.cli.Args;
import com.systemwin.service.ServiceInfo;
import com.systemwin.service.WindowsServiceManager;

/*
 * 下面这些 import 语句，就是告诉编译器：我这个类要用到别人写的类。
 * 就好比你写作文之前，先要把参考书从书架上拿下来摆在桌上一样。
 *
 * - Args：命令行参数。用户敲了 `list-units --xxx` 之类的东西，
 *   这些参数就会被解析成 Args 对象传进来。虽然本命令目前用不到参数，
 *   但接口规定了每个命令都必须接收 Args，所以我们也要写上。
 *
 * - ServiceInfo：这是“服务信息”的数据类（record），
 *   里面装着服务的名字、显示名称、是否在运行等等信息。
 *   list-units 命令的输出内容，全靠从它身上拿数据。
 *
 * - WindowsServiceManager：这是真正去跟 Windows 服务打交道的管理器，
 *   负责枚举（列出）系统里所有服务。我们这个命令只是“展示层”，
 *   真正干活的是它。这叫“职责分离”：一个负责收集数据，一个负责打印数据。
 */

import java.util.List;

/*
 * java.util.List 是 Java 自带的集合接口，表示“一组有序的元素”。
 * 因为我们要把系统里所有服务都列出来，数量可能有好几百个，
 * 所以必须用 List 这种容器把它们装起来，然后一个一个地遍历打印。
 * 如果不引入这个类，我们就没法声明 List<ServiceInfo> 这样的类型了。
 */

/** Lists Windows services in a systemctl-like table. */
/*
 * 上面这行英文注释（其实是个 javadoc），翻译成中文就是：
 * “以类似 systemctl 的表格形式，列出 Windows 服务。”
 *
 * 下面我们把它翻译得更详细一点，并且多唠叨几句：
 * systemctl 是 Linux 上管理 systemd 服务的命令，它的 list-units 子命令
 * 会输出一张表格，列有 UNIT、LOAD、ACTIVE、SUB、DESCRIPTION 这些表头。
 * 我们这个命令就是为了让 Windows 用户也能看到同样风格的输出，
 * 做到“换个系统，习惯不变”，降低大家的学习成本。
 *
 * 另外注意，这个类是 final 的，意思是它不能被继承。
 * 为什么？因为这个命令的实现已经足够简单和完整了，
 * 没必要让别人再派生子类去改动它。把类设计成 final 也是一种
 * “防御性编程”的思路——防止别人无意中破坏已有的行为。
 * 而且它实现了 Command 接口，也就是说它承诺了自己具备“运行命令”的能力。
 */
public final class ListUnitsCommand implements Command {

    /*
     * 这是一个成员变量（字段），名字叫 ctx，类型是 CommandContext。
     * CommandContext 顾名思义就是“命令上下文”，它是一个小盒子，
     * 里面装着执行命令时可能需要用到的东西——其中最重要的就是 services，
     * 也就是 WindowsServiceManager 的实例。
     *
     * 为什么不直接在 run() 方法里 new 一个 WindowsServiceManager 出来呢？
     * 因为那样的话，测试的时候就很难替换成假的（mock）管理器了，
     * 而且每个命令都自己 new，代码就重复了。
     * 现在这种写法叫“依赖注入”：把依赖通过构造方法传进来，
     * 谁创建命令，谁就负责把上下文准备好。
     * 这也让命令对象变得很轻——它自己不做复杂初始化，拿来就能用。
     *
     * 另外注意，这个字段是 private final 的：
     * - private：只有本类自己能访问，外界碰不到，保护数据安全；
     * - final：一旦在构造方法里赋值，以后就再也不能改了，保证不变性，
     *   避免有人在运行过程中偷偷换掉上下文导致奇怪的问题。
     */
    private final CommandContext ctx;

    /*
     * 这是构造方法（constructor），名字和类名一模一样。
     * 它做的事情极其简单：把外界传进来的 ctx 保存到自己的字段里。
     *
     * 为什么需要构造方法？因为 Java 规定，创建对象时必然要经过构造方法。
     * 我们在这个构造方法里要求“必须提供一个 CommandContext 才能创建命令”，
     * 这就保证了：只要 ListUnitsCommand 对象存在，它的 ctx 就一定有值，
     * 绝对不会出现 ctx 为空、一调用就空指针崩溃的尴尬局面。
     * 这种设计思路叫“失败要尽早”：与其等到运行时才发现缺东西，
     * 不如在创建对象的那一刻就强制检查好。
     */
    public ListUnitsCommand(CommandContext ctx) {
        this.ctx = ctx;
    }

    /*
     * 下面这个 @Override 注解，是告诉编译器：
     * “我这个方法是对接口（或父类）中某个方法的重写/实现。”
     *
     * 它有两个好处：
     * 1. 编译器会帮你检查：如果接口里根本没有 run 这个方法，
     *    或者签名写得不对（比如参数类型写错了），编译就直接报错，
     *    这样就能尽早发现拼写错误，而不是等到运行期才一脸懵。
     * 2. 读代码的人一眼就能看出：哦，这是 Command 接口规定的入口方法。
     *
     * 所以写实现类的时候，凡是覆盖接口方法的，都建议加上 @Override，
     * 这是一个非常好的编码习惯。
     */
    @Override
    /*
     * 这是命令真正执行的地方，返回值是 int（整数），表示退出码。
     * 为什么要有退出码？因为命令行程序在被别的脚本调用时，
     * 脚本需要知道这次执行到底成功了没有。
     * 习惯上 0 表示“成功”，非 0 表示“出错”。
     * 我们在方法最后 return 0，就是告诉调用方：一切正常，圆满完成任务。
     */
    public int run(Args args) {
        /*
         * 第一步：向“服务管理器”要一份完整的服务清单。
         * ctx.services 就是 CommandContext 里保存的 WindowsServiceManager，
         * 它的 list() 方法会去 Windows 系统里枚举所有已注册的服务，
         * 然后把每个服务的名称、运行状态、显示名称等包装成 ServiceInfo 对象，
         * 最后以 List 的形式全部返回给我们。
         *
         * 注意这里我们没有自己 new 任何东西，全靠 ctx 提供，
         * 这正是前面“依赖注入”的体现——命令只负责用，不负责造。
         * 如果以后想把数据源换成假数据（比如做单元测试），
         * 只需要换一个 ctx 进来就行，run 方法一行都不用改。
         */
        List<ServiceInfo> all = ctx.services.list();

        /*
         * 第二步：准备输出格式模板。
         * 这里的 fmt 是一个格式化字符串，它决定了表格每一列的宽度和对齐方式。
         * 我们拆开看每个占位符：
         *
         *   "%-26s" —— 左对齐（减号表示左对齐），占 26 个字符宽度，放字符串。
         *              这一列放的是服务单元名，比如 "WindowsUpdate.service"，
         *              名字比较长，所以要给它最宽的 26。
         *   "%-7s"  —— 左对齐，占 7 个字符，放 LOAD 列（加载状态）。
         *              我们固定打印 "loaded"，表示服务已加载。
         *   "%-8s"  —— 左对齐，占 8 个字符，放 ACTIVE 列（激活状态）。
         *              值是 "active" 或 "inactive"。
         *   "%-8s"  —— 左对齐，占 8 个字符，放 SUB 列（子状态）。
         *              值是 "running" 或 "dead"。
         *   "%s"    —— 最后一个占位符不限定宽度，放 DESCRIPTION（描述），
         *              因为描述文字长短不一，给足空间让它自然伸展。
         *
         * 为什么要这么认真地做格式化？
         * 因为如果每一行长度不齐，输出就会歪七扭八，人眼根本没法看。
         * 而统一宽度之后，每一列就对齐得像画过线一样，非常工整。
         * 这就是 systemctl 输出的那种专业感。
         */
        String fmt = "%-26s %-7s %-8s %-8s %s";

        /*
         * 第三步：打印表头（第一行）。
         * 我们把每一列的名字通过 String.format 填进模板里，
         * 再交给 System.out.println 打印到控制台。
         * 表头分别是：UNIT（单元名）、LOAD（加载）、ACTIVE（激活）、
         * SUB（子状态）、DESCRIPTION（描述）。
         * 这五个列和 Linux systemctl 的 list-units 输出完全一致，
         * 目的就是让熟悉 Linux 的用户无缝上手。
         */
        System.out.println(String.format(fmt,
                "UNIT", "LOAD", "ACTIVE", "SUB", "DESCRIPTION"));

        /*
         * 第四步：逐行遍历服务清单，为每个服务打印一行。
         * 这里用的是增强 for 循环（for-each）：
         * 每次循环从 all 里取出一个 ServiceInfo 对象赋值给变量 s，
         * 然后依次处理完再取下一个，直到全部取完为止。
         * 这种写法比传统的下标 for (int i = 0; i < all.size(); i++) 简洁得多，
         * 而且不用担心数组越界的问题，非常适合只读遍历的场景。
         */
        for (ServiceInfo s : all) {

            /*
             * 服务单元名：Windows 服务本身没有 .service 后缀，
             * 但为了在视觉上和 Linux 的 unit 名保持一致，
             * 我们手动给它加上 ".service"。
             * 比如 Windows 服务叫 "WSearch"，这里就显示成 "WSearch.service"，
             * 让熟悉 systemctl 的人一看就懂。这是典型的“用外观换熟悉感”。
             */
            String unit = s.name() + ".service";

            /*
             * ACTIVE 列：这个服务现在“活没活”？
             * 直接根据 s.running() 的布尔值来决定：
             * 运行中 -> "active"（活跃），没运行 -> "inactive"（不活跃）。
             * 这里用三元运算符 ?: 写成一个表达式，比写 if/else 更紧凑。
             * 可以把它读作：“running 是真的吗？是真的就给 active，否则给 inactive”。
             */
            String active = s.running() ? "active" : "inactive";

            /*
             * SUB 列：这是比 ACTIVE 更细一层的子状态。
             * systemctl 的惯例是：服务在跑就叫 "running"，没跑就叫 "dead"。
             * 我们这里简化处理，直接复用 running() 的结果来判断。
             * 也就是说 ACTIVE 和 SUB 其实是同一份信息的两套说法，
             * 一个回答“活没活”，一个回答“活成什么样”。
             */
            String sub = s.running() ? "running" : "dead";

            /*
             * DESCRIPTION 列：显示服务的显示名称（比如“Windows Update”）。
             * 注意这里做了一个空值保护：如果 displayName() 返回 null
             * （有些服务可能没有设置显示名称），我们就用空字符串 "" 顶上，
             * 而不是直接把 null 塞进格式化字符串。
             * 为什么这么谨慎？因为 String.format 遇到 null 会打印出 "null"
             * 这四个字母，表格里就会莫名其妙出现一堆 “null”，
             * 既难看又让用户困惑。用空字符串就干干净净。
             * 这里其实也算一个小小的“防御性编程”示范。
             */
            String desc = s.displayName() == null ? "" : s.displayName();

            /*
             * 终于到了打印本行数据的时候了！
             * 把刚才准备好的 unit、固定值 "loaded"、active、sub、desc
             * 依次填进 fmt 模板的五个占位符，然后打印。
             * 注意 LOAD 列我们始终填的是固定的 "loaded"，
             * 因为我们这个简化版实现里没有“加载失败”的概念，
             * 所有的服务只要能列出来，就当它是已加载的。
             * 一行代码，一行的输出，循环往复，直到所有服务都打完。
             */
            System.out.println(String.format(fmt, unit, "loaded", active, sub, desc));
        }

        /*
         * 最后：返回退出码 0，表示命令执行成功。
         * 整个方法的执行流程回顾一下：
         * 取数据 -> 定格式 -> 打表头 -> 逐行打印 -> 返回成功。
         * 就这么简单，没有网络请求，没有文件读写，纯粹是
         * “把系统里已有的信息，整理成一张好看的表格给用户看”。
         * 这也是 list-units 这类“查询类”命令的典型长相。
         */
        return 0;
    }
}
