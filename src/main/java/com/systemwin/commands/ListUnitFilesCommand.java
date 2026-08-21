package com.systemwin.commands;

/*
 * 下面这一堆 import 是干嘛的呢？其实很简单：我们这个类要用到别人写好的工具类，
 * 但是 Java 编译器自己又不认识这些类在哪里，所以我们必须通过 import 语句告诉它：
 * “喂，我要用 com.systemwin.cli 包下面的 Args 类啦！” 如果你不写 import，
 * 编译器就会一脸懵，报一个“找不到符号”的错误，初学者经常在这里卡住，
 * 所以记住：用了哪个类，就要 import 哪个类，一个都不能少。
 */
import com.systemwin.cli.Args;
import com.systemwin.service.ServiceInfo;
import com.systemwin.service.WindowsServiceManager;

/*
 * 这里 import 了 java.util.List，List 是 Java 自带的“列表”接口，
 * 你可以把它想象成一个可以装很多个东西的盒子，而且是有顺序的，
 * 就像排队买奶茶的队伍一样，先来的排前面，后来的排后面。
 * 我们下面要用它来装一大堆服务（ServiceInfo）的信息，所以必须先引入它。
 */
import java.util.List;

/**
 * Lists services and their enable state (systemctl list-unit-files).
 *
 * 上面的英文注释是这个类原本就有的，翻译成中文就是：
 * “这个类用来列出所有的服务，并且告诉我们每个服务是启用（enable）还是禁用（disable）的状态，
 * 功能上就相当于 Linux 系统上 systemctl list-unit-files 这条命令。”
 *
 * 为什么要实现 Command 接口呢？因为这个项目里所有的命令（比如启动、停止、查看状态等等）
 * 都遵循同一个规矩：都要有一个 run 方法。这样上层代码就可以“一视同仁”地调用它们，
 * 不用管你到底是哪条命令，只管调用 run 就行了，这就是传说中的“面向接口编程”，
 * 好处是以后想加新命令，只要实现这个接口，别的代码一行都不用改。
 *
 * 还有，注意这个类被 final 修饰了，意思是它不允许被别人继承。
 * 作者可能觉得这个类已经写得够完美了，不需要别人再来扩展它，
 * 又或者只是单纯想防止有人不小心继承它然后搞出奇怪的行为，
 * 反正记住：final 类不能被继承，final 方法不能被重写，final 变量不能被重新赋值。
 */
public final class ListUnitFilesCommand implements Command {

    /*
     * 这个私有字段 ctx 是什么呢？它的类型是 CommandContext，
     * 直译过来就是“命令上下文”。什么是上下文呢？你可以把它理解成一个“大背包”，
     * 里面装着这条命令运行所需要的各种依赖和工具，比如这里我们用到的 ctx.services
     * 就是那个负责和 Windows 服务打交道的管理器。
     * 为什么要用字段存起来而不是每次现用现造呢？因为构造的时候传进来一次，
     * 后面 run 方法里就能反复使用，省得每次都要重新创建对象，既省内存又省时间，
     * 这也是一种常见的“依赖注入”的朴素写法。
     */
    private final CommandContext ctx;

    /*
     * 这是构造方法，名字跟类名一模一样（Java 的规定，不能乱起）。
     * 它的作用就是“初始化”这个命令对象：把外面传进来的 CommandContext
     * 塞进我们上面那个私有的 ctx 字段里，存起来备用。
     * 注意这个参数是 final 的，意思是构造方法内部不能把它重新指向别的对象，
     * 这是 Java 的一个小规矩，也保证了我们存进去的引用不会被意外替换掉。
     * 顺便说一句：调用方在创建命令对象的时候，就得把上下文准备好传进来，
     * 这叫做“构造时注入”，比那种先用 setter 再调用的方式更安全，
     * 因为对象一出生就“装备齐全”，不会出现用着用着发现字段还是 null 的尴尬。
     */
    public ListUnitFilesCommand(CommandContext ctx) {
        // 这里就是简单的赋值：把参数 ctx 的值（其实是一个引用）交给字段 this.ctx。
        // 为什么要写 this 呢？因为参数名和字段名都叫 ctx，编译器会分不清，
        // 写 this.ctx 就明确告诉编译器：我说的是“我这个对象的 ctx 字段”，
        // 而不是“方法参数里那个 ctx”。这是 Java 里非常经典的一个写法。
        this.ctx = ctx;
    }

    /*
     * @Override 注解是什么意思？意思是：下面这个 run 方法是对父接口 Command 里
     * 那个 run 方法的“重写（override）”。有了这个注解，编译器就会帮我们检查：
     * 如果你写的签名跟接口里的对不上（比如参数类型写错了），编译器立刻报错，
     * 等于给代码上了一道保险。所以以后重写方法的时候，记得加上 @Override，
     * 这是一个好习惯，很多初学者忘了加，结果方法名拼错，程序根本不调用你的方法，
     * 还查了半天 bug，惨痛的教训啊！
     */
    @Override
    public int run(Args args) {
        /*
         * 这里是整个命令的核心逻辑，我们一行一行来拆解。
         * 先看第一行：调用 ctx.services.list() 把系统里所有的服务都查出来。
         * 返回的是一个 List<ServiceInfo>，也就是一个装着很多 ServiceInfo 对象的列表，
         * 每一个 ServiceInfo 就代表系统里的一个服务，里面存着服务的名字、启动模式等信息。
         * 我们给它起的变量名叫 all，意思是“全部的服务”，非常直白好懂。
         */
        List<ServiceInfo> all = ctx.services.list();

        /*
         * 这一行是在准备打印表格的格式。fmt 是一个格式字符串：
         * "%-26s %s" 是什么意思呢？拆开来看：
         *   %s 表示这里会放一个字符串；
         *   %-26s 表示这个字符串要占 26 个字符的宽度，并且靠左对齐（负号就是左对齐的意思）；
         *   中间的 %s 就是第二个字符串，不限定宽度，有几个字符就占几个字符。
         * 为什么要搞得这么讲究？因为我们要打印一个对齐美观的表格，
         * 如果服务名长短不一，不固定宽度的话，输出就会歪歪扭扭的，
         * 就像没对齐的 Excel 表格一样难看。这个 -26 是作者试出来的经验值，
         * 刚好能让列宽看起来舒服。
         */
        String fmt = "%-26s %s";

        /*
         * 打印表头。表头就是表格第一行，告诉读者下面每一列分别是什么内容：
         * 第一列叫 UNIT FILE（单元文件，也就是服务文件的文件名），
         * 第二列叫 STATE（状态，也就是这个服务是启用还是禁用）。
         * 我们用刚才准备好的 fmt 格式来打印，这样表头也会按照同样的宽度对齐，
         * 看起来整整齐齐的。printf 家族的方法都是这样：第一个参数是格式，
         * 后面的参数是往格式里填的“数据”，一一对应。
         */
        System.out.println(String.format(fmt, "UNIT FILE", "STATE"));

        /*
         * 下面是一个 for-each 循环，也叫“增强 for 循环”，
         * 意思是：把 all 列表里的每一个 ServiceInfo 对象依次取出来，
         * 每取一个就赋值给循环变量 s，然后执行一次循环体，直到列表里的东西全部取完为止。
         * 你可以把它想象成排队逐个点名：s 就是当前被点到名的那个服务。
         * 相比传统的 for (int i = 0; ...) 写法，这种写法不用关心下标，
         * 不会出现“数组越界”这种低级错误，所以能用 for-each 就用 for-each。
         */
        for (ServiceInfo s : all) {
            /*
             * 这一行调用了 WindowsServiceManager.enableState(s.startMode())，
             * 我们一层一层往里看：
             *   最里面 s.startMode() 是取出这个服务的“启动模式”，
             *     比如是“自动启动”“手动启动”还是“禁用”等等，返回的是一个枚举或者字符串；
             *   然后把这个启动模式交给 WindowsServiceManager.enableState 这个静态方法，
             *     它负责把底层的启动模式翻译成我们人类能看懂的“启用/禁用”状态文字。
             * 为什么要经过这么一层转换呢？因为 Windows 底层的启动模式有很多种，
             * 直接打印出来又长又晦涩，而 Linux 的 systemctl list-unit-files 只显示
             * enabled / disabled 这种简洁的状态，为了保持输出风格一致，就必须转换一下。
             * 注意这个方法是用类名直接调用的（WindowsServiceManager.enableState），
             * 说明它是一个静态方法，不需要先 new 一个对象就能用，非常方便。
             */
            String state = WindowsServiceManager.enableState(s.startMode());

            /*
             * 最后一行就是把这一条服务的信息打印出来：
             * s.name() 是服务的名字，后面拼上 ".service" 后缀。
             * 为什么要拼后缀呢？因为在 systemd 的世界里，每个服务文件都叫 xxx.service，
             * 比如 nginx.service、sshd.service，这样拼出来才跟 Linux 的习惯一致，
             * 用户看着也亲切。整个输出就是：“服务名.service” 加上 固定的宽度，
             * 再加上转换好的状态文字，一行一个服务，整整齐齐。
             */
            System.out.println(String.format(fmt, s.name() + ".service", state));
        }

        /*
         * 最后返回 0。在命令行程序的世界里，返回 0 表示“一切正常，圆满成功”，
         * 返回非 0 的数字则表示出错了。调用我们这条命令的上一层代码
         * 会根据这个返回值来判断命令有没有执行成功，
         * 所以我们这里老老实实返回 0，代表列表打印完毕，任务圆满完成。
         * 如果中途真的出了什么差错（比如服务查询失败），那就应该返回别的值，
         * 不过这个简单的命令里没有处理那些复杂情况，也就直接返回 0 了。
         */
        return 0;
    }
}
