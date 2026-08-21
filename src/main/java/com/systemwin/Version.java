package com.systemwin;

/**
 * Build-time constants for SystemWin.
 *
 * 这个类的名字叫 Version，翻译过来就是“版本”的意思。
 * 它存在的唯一目的，就是集中保存一些跟“构建（Build）”相关的常量，
 * 也就是说这些值是在编译的时候就已经写死（固定）在代码里了，
 * 并不是程序运行到一半才从某个配置文件或者数据库里读出来的。
 *
 * 为什么要专门搞一个类来放这些东西呢？废话也得讲清楚：
 * 因为在整个项目里，很多地方（比如主程序、命令行工具、界面、日志输出等）
 * 都需要知道“我们这个软件叫什么名字、当前是哪个版本、作者是谁、有什么用途”，
 * 如果每个地方都自己写死一份 "SystemWin"、"1.0.0" 这样的字符串，
 * 那么将来升级版本号的时候，就得一个文件一个文件地去改，非常容易漏改、改错。
 * 现在把这些常量集中放在这一个类里，大家统一引用 Version.VERSION 之类的字段，
 * 将来要发新版本的时候，只需要改这一个地方，其他所有地方自动就跟着变了，
 * 这就是“单一事实来源（Single Source of Truth）”的思想，非常省心。
 */
public final class Version {
    /** The product name, shown in UI and logs. */
    // NAME 是“名字”的意思，这里就是软件对外展示的名字，叫 SystemWin。
    // 注意它是 public static final 的：
    //   public   —— 表示任何其他类都能直接访问它，不需要绕弯子；
    //   static   —— 表示它是属于“类”的，而不是属于某个具体对象的，
    //               所以不需要 new 一个 Version 对象就能直接写 Version.NAME 来用；
    //   final    —— 表示这个值一旦定下来就再也不能被修改了，防止有人不小心改掉。
    // 这三点合在一起，就是 Java 里定义“全局常量”的标准写法，也是最安全的写法。
    public static final String NAME = "SystemWin";

    /** The current version, following semantic versioning. */
    // VERSION 是“版本号”的意思，当前是 1.0.0。
    // 这里采用语义化版本号（Semantic Versioning）的格式：主版本号.次版本号.修订号。
    // 简单粗暴地解释一下：
    //   第一位 1 是主版本号，一般来说 API 发生不兼容的重大变化时才加一；
    //   第二位 0 是次版本号，增加新功能但保持兼容时就加一；
    //   第三位 0 是修订号，只是修修 bug、打打补丁的时候就加一。
    // 之所以把版本号也做成常量，是因为程序在启动、打印帮助信息、
    // 或者向用户报告错误的时候，都需要带上版本号，方便大家知道
    // “你运行的是哪个版本”，排查问题的时候特别有用。
    public static final String VERSION = "1.0.0";

    /** The author or owning team of the software. */
    // AUTHOR 是“作者”的意思，这里写的是 SystemWin Team，也就是开发这个软件的团队。
    // 把它做成常量，通常是为了在“关于（About）”对话框、许可证信息、
    // 或者软件启动画面里展示，让用户知道这个软件是谁做的、出了问题该找谁。
    public static final String AUTHOR = "SystemWin Team";

    /** A one-line human-readable summary of the product. */
    // DESCRIPTION 是“描述”的意思，这里用一句话概括这个软件是干嘛的：
    // “A systemctl/systemd work-alike for Windows”，翻译过来就是
    // “一个在 Windows 上模仿 systemctl / systemd 用法的工具”。
    // 也就是说，Linux 上有 systemctl 和 systemd 这套管理系统服务的工具，
    // 而我们的 SystemWin 想在 Windows 上提供类似的功能和体验，
    // 让熟悉 Linux 的开发者到了 Windows 上也能用差不多的命令来管理服务。
    // 这句话通常会被打印到命令行工具的“帮助信息”里，或者作为软件的一句话简介。
    public static final String DESCRIPTION = "A systemctl/systemd work-alike for Windows";

    /**
     * Private constructor to prevent instantiation.
     *
     * 这个构造函数是 private（私有的），而且里面是空的，什么都不干。
     * 为什么要这样写呢？因为 Version 这个类里放的全是静态常量，
     * 我们从来不需要（也不希望）有人去 new 一个 Version 对象出来——
     * 这个类存在的意义就是当一个“常量仓库”，不是用来创建实例的。
     * 把构造函数设成 private，就等于告诉编译器和其他程序员：
     * “这个类不允许被实例化，谁想 new 谁就编译不过去。”
     * 这是一种很常见的“工具类（Utility Class）”写法，
     * 比如 java.lang.Math、java.util.Collections 这些类也都是这么干的，
     * 目的就是防止别人误用，保持类的语义清晰。
     */
    private Version() {
    }
}
