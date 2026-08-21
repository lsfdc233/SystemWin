package com.systemwin.util;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/*
 * ============================================================================
 * 下面是一大段"废话"性质的说明，给第一次看到这个文件的新同学看。
 * ============================================================================
 *
 * 这个文件在干什么？一句话版本：它把"输出"复制成两份。
 *
 * 详细版（请耐心看完）：
 * 在命令行程序里，System.out 是标准的"标准输出"流，程序往它上面写什么，
 * 用户的控制台（终端窗口）就会显示什么。但是呢，有时候我们不光想让用户
 * 在屏幕上看到输出，还想把同样的内容悄悄记到一份日志文件里，方便以后排查
 * 问题、回看历史记录。如果你直接在原代码里既 print 到屏幕又写文件，那每
 * 一个 print 调用都要写两遍，太麻烦，也容易漏。
 *
 * TeePrintStream 就是来解决这个问题的：它继承了 java.io.PrintStream，
 * 所以它"看起来"和 System.out 一模一样，谁都可以把它当成普通输出流来用；
 * 但在它内部，它偷偷把每一份内容都"劈"成两份——一份照常送到原来的输出流
 * （original，也就是真正的屏幕/控制台），另一份送到日志文件（log）。这就是
 * "Tee"（三通管）这个名字的由来：想象一根水管，进水口进来一股水，然后分岔
 * 成两个出水口，两边都有水流出。Linux 上有个非常有名的命令 tee 也是这个
 * 意思，这里不过是把那个思路搬到了 Java 的流世界里。
 *
 * 谁在用这个类？按照类上面的英文注释，是 __elevated 这个"内部模式"在用：
 * 程序会先启动一个"提权"（elevated，即以管理员权限运行）的子进程，子进程
 * 干活的时候，它所有的输出都会被这个 TeePrintStream 同时写进日志文件；
 * 等子进程结束以后，没有提权的父进程再把日志文件里的内容"回放"到自己屏幕
 * 上。这样一来，子进程看不到自己屏幕（因为它是后台跑的），但它的输出还是
 * 通过日志被原原本本保留下来了，用户在主窗口里也能看到。整个流程是不是很
 * 巧妙？——子进程只管输出，父进程只管显示，中间用一份日志文件当"传话筒"。
 *
 * 为什么这个类被声明成 final（最终类）？因为设计者希望它就是一个固定的
 * 工具类，不允许别人再继承它去改它的行为。如果允许子类覆盖方法，那"复制
 * 两份输出"这个核心保证就可能被破坏，所以干脆把门关死，谁都别想再扩展它。
 * 这也算是一种"防御性设计"：不给你犯错的机会，你自然就不会犯错。
 *
 * 编码问题也值得说两句：屏幕那边我们保持 original 流原来的编码（也就是
 * 控制台自己的编码，Windows 上通常可能是 GBK 或者 UTF-8，总之是"控制台
 * 说了算"），而日志文件这边我们强制统一用 UTF-8 写。为什么要统一成 UTF-8？
 * 因为日志文件可能要拿到别的机器上、别的软件里去看，如果每台机器的默认
 * 编码不一样，日志里就会出现乱码。UTF-8 是现在全世界的"通用语"，不管在
 * 哪个环境打开都是安全的。这种"显示用本地编码、存储用 UTF-8"的做法，在
 * 处理中文输出的程序里非常常见，值得记住。
 */
/**
 * A PrintStream that mirrors every write to the original stdout (preserving
 * its console encoding) and appends the same text to a UTF-8 log file.
 *
 * <p>Used by the {@code __elevated} internal mode: the elevated child process
 * tees its whole output to a log, and the non-elevated parent relays the log
 * back to its own console after the child exits.
 *
 * <p>【中文翻译】这是一个"会分身"的 PrintStream：每一次写入，它都会原封
 * 不动地同时交给两处——一处是原来的标准输出（保留控制台自己的编码），另一
 * 处是追加进一个 UTF-8 编码的日志文件。它被 __elevated 这个内部模式使用：
 * 提权后的子进程把自己全部的输出"分流"（tee）进日志，等子进程退出后，没
 * 有提权的父进程再把这份日志原样"转播"回自己的控制台，让用户能看到子进程
 * 到底输出了什么。
 */
public final class TeePrintStream extends PrintStream {

    /*
     * 下面这两个字段就是这个类全部的家当，就俩：
     *
     * original —— 真正的、原始的输出流。通常就是程序启动时那个真正的
     * System.out（连到控制台的那根"屏幕水管"）。我们所有要显示给用户的
     * 内容，最终都要经过它。
     *
     * log —— 日志文件对应的写入器。所有内容除了上屏幕，还会被它写进
     * 磁盘上的日志文件，方便事后翻旧账。
     *
     * 注意这两个字段都是 private final：private 表示外部代码碰不到它们，
     * 只能通过本类的方法间接使用；final 表示一旦在构造函数里赋值，就永远
     * 不能再换。也就是说，这个 TeePrintStream 从出生到死亡，都只对着同一
     * 个屏幕和同一份日志文件，很专一。
     */
    private final PrintStream original;
    private final PrintWriter log;

    /*
     * 构造函数：创建一个"双写"的输出流。
     *
     * 参数说明：
     *  - original：要"镜像"的那个原始输出流，一般是真正的 System.out。
     *  - logFile：日志文件的路径。如果文件不存在会创建，如果已存在则会
     *    被清空重写（TRUNCATE_EXISTING 就是"截断已存在的内容"的意思，
     *    也就是"我不要旧内容，从头开始写"）。
     *
     * 注意第一行那个看起来有点奇怪的调用：super(new ByteArrayOutputStream(),
     * true, UTF_8)。这是父类 PrintStream 的构造函数，它必须接收一个"底层
     * 输出流"。但我们其实根本不想往这个底层流里写任何东西——真正的输出都
     * 由我们自己的 tee 方法手动分发给 original 和 log。那为什么还要传一个
     * ByteArrayOutputStream 呢？纯粹是因为 Java 语法规定：子类构造函数第一
     * 行必须调用父类构造函数，而 PrintStream 没有无参构造，所以我们只好随便
     * 塞一个"内存里的字节缓冲流"应付一下。它就像一个摆设，永远用不上。后面
     * 的 true 表示自动刷新（autoFlush），UTF_8 是父类自己内部用的编码，反正
     * 我们用不到它，传个标准值就行。
     *
     * 另外注意：这个方法声明了 throws IOException。为什么？因为打开文件、
     * 创建文件这些磁盘操作是"可能失败"的（比如磁盘满了、路径不存在、权限
     * 不够），Java 要求这种操作必须声明可能抛出的异常，让调用者决定怎么处
     * 理，而不是程序悄悄崩溃。所以谁要 new 一个 TeePrintStream，谁就得准备
     * 好处理这个 IOException。
     */
    public TeePrintStream(PrintStream original, Path logFile) throws IOException {
        super(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        this.original = original;
        this.log = new PrintWriter(new OutputStreamWriter(
                Files.newOutputStream(logFile,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                StandardCharsets.UTF_8), true);
    }

    /*
     * tee 是本类的心脏，也是名字里"Tee"的真正含义所在。
     *
     * 它的工作流程极其简单，就三步：
     *   1. original.print(s)：把内容写给原始输出流 → 用户屏幕上能看到。
     *   2. log.print(s)：把同样的内容写给日志文件 → 硬盘上留下了记录。
     *   3. log.flush()：把缓冲区里的内容"冲"到磁盘上，确保真正落盘。
     *
     * 为什么要有第 3 步 flush？这里要解释一下缓冲（buffering）的概念：为了
     * 效率，PrintWriter 通常不会每写一个字符就去碰一次磁盘，而是先把内容攒
     * 在内存缓冲区里，攒够一批再一次写盘。这样磁盘 IO 次数少了，速度快了，
     * 但副作用是"如果不主动刷新，内容可能还赖在内存里没落到文件"——万一这
     * 个时候程序崩溃了，日志就会丢失。所以我们每写完一次就立刻 flush 一次，
     * 宁可慢一点，也要保证日志即时、可靠。这在调试场景里尤其重要：如果日志
     * 没落盘，程序一崩，你什么都查不到，那才是真抓瞎。
     *
     * 注意这里的顺序：先写屏幕，再写日志。这个顺序不是随便定的——如果先写
     * 日志后写屏幕，而屏幕那边万一抛异常，日志可能已经写了一半，导致日志里
     * 的内容和屏幕上看到的不一致。先屏幕后日志，能保证"屏幕上看到什么，日志
     * 里就有什么"，两边永远同步。
     */
    private void tee(String s) {
        original.print(s);
        log.print(s);
        log.flush();
    }

    /*
     * 重写父类的 print(String) 方法。
     *
     * 为什么这里要判断 s == null？因为 Java 的语法规定：如果直接往输出流里
     * 写一个 null，结果会是字面量 "null" 这四个字母（这是 PrintStream 自己
     * 的行为），但我们的 tee 方法用的是 original.print(s)，这个底层调用对
     * null 的处理和父类公开方法的行为可能不一致。为了避免"屏幕显示 null、
     * 日志却什么都不写"或者反过来之类的混乱，我们在这里统一处理：如果是
     * null 就转换成字符串 "null"，保证两个输出目标拿到的内容完全一致。细节
     * 决定成败，这种边界情况（边界值）恰恰是最容易出 bug 的地方。
     */
    @Override
    public void print(String s) {
        tee(s == null ? "null" : s);
    }

    /*
     * 打印一个任意对象（Object）。
     *
     * 思路：对象本身没法直接"写"到流里，只能先把它变成字符串。String.valueOf(o)
     * 会把对象变成字符串：如果 o 是 null，它会变成 "null"；否则调用对象的
     * toString() 方法。变成字符串之后，再交给上面的 print(String)，让"字符串
     * 版本"的统一逻辑去处理。这就是"把所有复杂类型都收敛到 String 一种类型
     * 再处理"的设计思路——类型虽然千千万，处理方式只有一个，代码就不容易乱。
     */
    @Override
    public void print(Object o) {
        print(String.valueOf(o));
    }

    /*
     * 打印一个字符数组 char[]。
     *
     * 为什么要把字符数组转成 String？因为我们的 tee 方法只认 String，把所有
     * 输入都转成 String 就是"殊途同归"。new String(c) 会把数组里的每一个字符
     * 依次拼接成一个字符串。当然也可以直接用 log.write(c) 之类的专门方法，但
     * 那样就要为每一种类型单独写一份分发逻辑，代码会膨胀。统一转 String 虽然
     * 多了一次转换开销（其实开销很小，几乎可以忽略），但换来的是逻辑极简，
     * 非常划算。
     */
    @Override
    public void print(char[] c) {
        print(new String(c));
    }

    /*
     * 打印布尔值 true/false。
     *
     * String.valueOf(b) 会把 true 变成 "true"，把 false 变成 "false"。
     * 你可能觉得"这不是理所当然的吗"，但对新手来说，这里要记住的要点是：
     * 输出流里永远没有"真正的布尔值"，一切最终都是字符串。布尔、整数、浮点
     * 数……全都要先"翻译"成文本，屏幕和日志才能显示出来。计算机的屏幕只会
     * 显示字符，别的什么都显示不了。
     */
    @Override
    public void print(boolean b) {
        print(String.valueOf(b));
    }

    /*
     * 打印单个字符 char。
     *
     * 单个字符也是先转成字符串再交给统一处理。这里其实可以直接调用
     * tee(String.valueOf(c))，但我们选择再套一层 print(String)，好处是：以后
     * 如果要在 print(String) 里加统一的前缀、后缀或者过滤逻辑，所有类型都会
     * 自动享受到，不需要挨个改。这种"所有重载方法都收敛到同一个方法"的模式叫
     * 重载链（overload chain），是 Java 里非常经典、非常值得模仿的写法。
     */
    @Override
    public void print(char c) {
        print(String.valueOf(c));
    }

    /*
     * 打印整数 int。
     *
     * 同样地，String.valueOf(i) 把数字 123 变成字符串 "123"，然后交给统一的
     * print(String)。注意 String.valueOf 处理 int 时是"十进制"表示，不会带
     * 奇怪的 0x 前缀，也不会补零，就是最自然的人读格式。为什么不用
     * Integer.toString(i)？其实两个效果一样，String.valueOf 内部就是调用了它，
     * 只是写起来更短、更通用（对任何类型都能用），所以这里统一用 valueOf。
     */
    @Override
    public void print(int i) {
        print(String.valueOf(i));
    }

    /*
     * 打印长整数 long。
     *
     * 和 int 几乎一样，只是数据范围更大。为什么父类要分别为 int 和 long 都
     * 准备一个重载方法，而不是只留一个？因为 Java 的重载机制是看参数类型精确
     * 匹配的，调用 print(5) 和 print(5L) 会走进不同的方法；如果不提供对应的
     * 重载，编译器就得做类型转换，可能出现精度问题（long 转 int 会溢出！）。
     * 所以父类把每种基本类型都配齐了，我们子类也跟着一个一个覆盖，保证任何
     * 类型的 print 都不会漏掉"双写"这一步。
     */
    @Override
    public void print(long l) {
        print(String.valueOf(l));
    }

    /*
     * 打印单精度浮点数 float。
     *
     * 浮点数转字符串时，String.valueOf 会给出一个"够用就行"的表示，比如 3.14
     * 就是 "3.14"。这里不需要关心格式化精度问题——那是 printf 系方法的职责，
     * 我们这种简单的 print 只要"别丢信息"就够了。浮点数是个容易踩坑的话题
     * （0.1 + 0.2 可能不等于 0.3），但那是运算精度问题，跟这里"转成字符串"
     * 无关，别混淆了。
     */
    @Override
    public void print(float f) {
        print(String.valueOf(f));
    }

    /*
     * 打印双精度浮点数 double。
     *
     * 跟 float 的区别就是精度更高、范围更大，除此之外流程一模一样。可以看到
     * 从 boolean、char、int、long 到 float、double，这些方法长得几乎是一个
     * 模子刻出来的——这就是"重载方法群"的典型样貌：模式相同、参数不同。读这
     * 种代码的时候，看第一个就懂了全部，剩下的都是复制粘贴换类型。
     */
    @Override
    public void print(double d) {
        print(String.valueOf(d));
    }

    /*
     * 打印一个"空行"（也就是只有换行符、没有其他内容）。
     *
     * 注意这里我们没有走 print 系列，而是直接调用 tee，并且传入的是
     * System.lineSeparator()。什么是行分隔符？就是"换行"那个看不见的字符。
     * 关键知识点：Windows 上换行是 \r\n（回车+换行两个字符），而 Linux/macOS
     * 上是 \n（只有一个换行符）。System.lineSeparator() 会自动根据当前操作系统
     * 返回正确的换行符，所以我们不用自己判断"我在哪个系统上"，让 Java 替我们
     * 操心。如果硬编码写死 "\n"，在 Windows 上记事本打开日志就可能出现"所有
     * 内容挤成一行"的惨剧。这里体现了"平台无关性"——Java 的口号"一次编写，
     * 到处运行"在换行这种小细节上也成立。
     */
    @Override
    public void println() {
        tee(System.lineSeparator());
    }

    /*
     * 打印一行字符串并换行，这是日常开发里用得最多的方法之一（System.out.println
     * 就是它）。
     *
     * 实现分两步：先把内容转成字符串（null 同样转成 "null"，和 print(String)
     * 保持一致），然后拼接上系统对应的换行符，最后整体交给 tee 双写。为什么
     * 要在字符串后面"追加"换行符而不是分别调用两次 tee？因为 tee 一次调用就
     * 是一次"完整的分发"，把"内容+换行"打包成一条再分发，能保证屏幕和日志看
     * 到的内容边界完全一致——日志里一行就是屏幕上的一行，将来按行解析日志也
     * 不会错位。
     */
    @Override
    public void println(String x) {
        tee((x == null ? "null" : x) + System.lineSeparator());
    }

    /*
     * 打印一行对象并换行。
     *
     * 和 print(Object) 的思路一模一样：先把对象 String.valueOf 成字符串，
     * 再交给 println(String)。这里就体现出了"重载链"的层层递进：println(Object)
     * → println(String) → tee。每一层只做自己那一件小事，责任分明，读起来像
     * 剥洋葱，一层套一层。
     */
    @Override
    public void println(Object o) {
        println(String.valueOf(o));
    }

    /*
     * 打印一行字符数组并换行。
     *
     * 同样是把 char[] 转成 String 再走统一通道。这里顺便复习一下：字符数组
     * 和字符串在概念上是近亲——String 内部其实就是一个 char 数组，只不过
     * String 是不可变的（内容不能改），而 char[] 是可变的。这里转成 String
     * 之后，后面的流程就完全不用关心它原来是什么了。
     */
    @Override
    public void println(char[] c) {
        println(new String(c));
    }

    /*
     * 打印一行布尔值并换行。
     *
     * true → "true"，false → "false"，后面跟换行。没有更复杂的东西了。
     * 注意像这样大量"看起来一模一样"的重载方法，恰恰说明父类 PrintStream
     * 的 API 设计得非常规整：所有类型一视同仁。作为子类，我们的工作就是
     * "无脑"地把每个入口都接住，然后统一导向 tee 这个唯一的出口。
     */
    @Override
    public void println(boolean b) {
        println(String.valueOf(b));
    }

    /*
     * 打印一行单个字符并换行。
     *
     * 和 println(char[]) 的区别只是一个字符还是多个字符，处理方式完全相同。
     * 这里可以再啰嗦一句：Java 的 char 是 16 位的，可以表示 UTF-16 里的一个
     * 编码单元。对于中文这种 BMP 平面内的字符，一个 char 就够；但某些生僻字
     * 或 emoji 需要两个 char（代理对）才能表示。不过这些都是后话，对于我们
     * 这个"转成字符串再输出"的流程来说，根本不用关心这些细节，String 会帮我
     * 们处理妥当。这就是"封装"带给我们的好处：底层再复杂，上面用起来都简单。
     */
    @Override
    public void println(char c) {
        println(String.valueOf(c));
    }

    /*
     * 打印一行整数并换行。
     *
     * 流水线：int → String → 拼上换行符 → tee 双写。从这一个方法，你就可以
     * 推出下面 long、float、double 三个方法长什么样了——不信你可以先自己猜
     * 一下再往下看，保证猜中。学习代码的最高境界，就是"看一知十"。
     */
    @Override
    public void println(int i) {
        println(String.valueOf(i));
    }

    /*
     * 打印一行长整数并换行。
     *
     * 果然和 println(int) 一模一样，只是类型换成了 long。看到这里你应该已经
     * 完全掌握这个类的套路了：所有 print/println 的重载，本质都是"把各种类型
     * 先变成 String，再统一交给 tee 去双写"。整个类其实就一个核心思想——
     * 殊途同归，万流归宗。
     */
    @Override
    public void println(long l) {
        println(String.valueOf(l));
    }

    /*
     * 打印一行单精度浮点数并换行。
     *
     * 同前面的套路，float → String → 加换行 → tee。虽然重复，但这是 Java
     * 重载机制的必然结果：既然父类提供了这么多重载，子类想"拦截"所有输出，
     * 就必须一个一个地覆盖，一个都不能少。少覆盖一个会怎样？少的那一个就会
     * 使用父类的原始实现，直接写到那个没用的 ByteArrayOutputStream 里，输出
     * 就悄悄"消失"了——屏幕和日志都看不到。所以这种覆盖必须齐全，漏一个都
     * 是 bug。这也解释了为什么这个类看起来"啰嗦"却不得不啰嗦。
     */
    @Override
    public void println(float f) {
        println(String.valueOf(f));
    }

    /*
     * 打印一行双精度浮点数并换行。
     *
     * 最后一个重载方法，至此，父类 PrintStream 提供的所有 print/println 入口
     * 我们全部接管完毕。整个类回顾一下：一个构造函数负责接线（连屏幕、开日
     * 志），一个私有 tee 方法负责双写（这是唯一真正干活的地方），其余十几个
     * 方法全是"翻译官"，把各种类型翻译成 String 后送到 tee 门口。结构清晰，
     * 职责单一，这就是好的小工具类的模样。
     */
    @Override
    public void println(double d) {
        println(String.valueOf(d));
    }
}
