package com.systemwin.cli;

/**
 * 文件说明（中文翻译 + 废话式讲解）：
 * 这个文件是 SystemWin 项目里"命令行（CLI，也就是 Command Line Interface，命令行界面）"
 * 专用的异常类。什么是异常呢？简单来说，异常就是程序在运行过程中遇到"意外情况"时抛出的
 * 一个特殊对象，就好像你走在路上突然踩到一个坑，程序就会"哎哟"一声，把这个坑的信息
 * 告诉调用它的上层代码。
 *
 * 为什么我们要单独做一个 CliException，而不是直接用 Java 自带的 Exception 或者
 * RuntimeException 呢？原因有几点：
 *   1. 语义清晰：看到 CliException 这个名字，读代码的人立刻就知道"哦，这是命令行
 *      相关的错误"，不用去猜这个异常是从哪里冒出来的。
 *   2. 便于区分：程序里可能有很多种异常，比如文件读写的 IOException、网络连接的
 *      SocketException 等等。把命令行错误单独拎出来，上层代码就可以专门针对它做
 *      处理（比如格式化输出到终端），而不用去 catch 一大堆乱七八糟的类型。
 *   3. 扩展方便：以后如果命令行还想加别的信息（比如错误码、退出码），直接在
 *      这个类里加字段和方法就行了，不影响别的代码。
 *
 * 另外请注意，这个类继承的是 Exception（受检异常，checked exception），不是
 * RuntimeException（非受检异常）。受检异常的意思是说：编译器会强制要求调用方
 * 要么用 try-catch 把它接住，要么用 throws 把它继续往上抛，总之不能假装它不存在。
 * 这样做的好处是，命令行出错是一个"可预期"的事情（用户输错参数很常见嘛），
 * 所以我们应该强迫调用方认真对待它，而不是让它悄悄溜走。
 *
 * 还有一个很重要的设计点：这个异常的信息（message）已经是"本地化"（localized）
 * 之后的文本了，也就是说，创建这个异常的地方传入的 message 就是最终要展示给
 * 用户看的中文（或其他语言）提示，不需要在这里再做任何翻译处理。
 */

/** A user-facing command line error; its message is already localized. */
/*
 * 英文原文的意思是："一个面向用户的命令行错误；它的消息已经是本地化（翻译好）的文本。"
 *
 * 这里的"面向用户"（user-facing）指的是：这个异常最终是要显示给终端里的使用者看的，
 * 而不是给程序员调试用的内部错误。所以它的 message 必须写得"人话"，比如
 * "找不到文件：xxx"，而不能是那种充满堆栈信息的内部错误。
 */
public class CliException extends Exception {
    /*
     * hint 字段（提示信息字段）：
     * 这是一个私有（private）字段，只能在本类内部访问，外面的人不能直接改它。
     * 它的作用是什么呢？就是保存一条"可选的、只有一行"的提示文字。
     *
     * 举个例子：用户敲错了命令参数，我们抛出一个 CliException，message 可以写
     * "未知的参数：--froce"，而 hint 就可以写"正确用法：systemwin --force"，
     * 这样程序打印错误信息的时候，先打印 message，再换行打印 hint，用户一看
     * 就知道自己哪里错了、该怎么改，体验是不是好多了？
     *
     * 为什么用 final 修饰呢？final 表示这个字段一旦在构造函数里被赋值，
     * 之后就不允许再被修改了。这是异常类的常见做法，因为异常对象通常是一次性
     * 创建的，创建完之后它的信息就应该固定下来，防止有人半路偷偷改掉。
     */
    private final String hint;

    /**
     * 构造函数一（只有 message 的版本）：
     * 这个构造函数只接收一个 message 参数，适合那些"只有错误信息、没有额外提示"
     * 的场景。比如用户输入了一个根本不存在的命令，我们只需要告诉他"没有这个命令"
     * 就够了，不需要再教他用法。
     *
     * 它的实现非常偷懒：直接调用下面的两参构造函数，并把 hint 传成 null。
     * 这种写法在 Java 里叫"构造函数链"（constructor chaining），好处是：
     *   1. 不用重复写 this.hint = ... 这种赋值代码；
     *   2. 以后如果想在初始化时统一做点别的事情（比如记日志），只需要改
     *      两参的那个构造函数，所有入口都会自动带上这个逻辑。
     */
    public CliException(String message) {
        this(message, null);
    }

    /**
     * 构造函数二（完整版本）：
     * 这是真正的"干活"的构造函数，它接收两个参数：
     *   - message：错误的文字描述，必须已经是本地化（翻译好）的文本；
     *   - hint：可选的单行提示，比如用法说明；如果不想要提示，传 null 即可。
     *
     * 里面做了两件事：
     *   1. 调用 super(message)，也就是父类 Exception 的构造函数，让父类帮我们
     *      把 message 保存起来。以后调用 getMessage() 就能拿到这段文字了。
     *      为什么要把 message 交给父类保存而不是自己存一份呢？因为 Exception
     *      里已经有一套成熟的消息机制（包括堆栈信息、cause 链等），我们没必要
     *      自己再实现一遍，直接复用父类的能力是最省事也最不容易出错的。
     *   2. 把 hint 存到自己的私有字段 this.hint 里，供后面的 hint() 方法读取。
     */
    public CliException(String message, String hint) {
        super(message);
        this.hint = hint;
    }

    /** Optional one-line hint printed after the message (e.g. usage hint). */
    /*
     * 英文原文的意思是："可选的单行提示，打印在消息后面（例如用法提示）。"
     *
     * hint() 是一个 getter（取值方法）：外部代码调用 cliException.hint() 就能
     * 拿到我们之前存进去的提示文字。如果当初传的是 null，这里返回的也就是 null，
     * 调用方拿到 null 之后就知道"没有提示"，那打印的时候就可以选择不打印这一行。
     *
     * 为什么不直接让外部访问 hint 字段呢？因为 hint 是 private 的，Java 的封装
     * 原则就是"字段私有、方法公开"，这样以后如果想改变 hint 的存储方式
     * （比如从字符串改成列表），只需要改这一个方法，外部代码完全不用动。
     */
    public String hint() {
        return hint;
    }
}
