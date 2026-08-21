package com.systemwin;

import com.systemwin.util.Figlet;

/**
 * Prints the fixed startup banner: the ASCII-art "SystemWin" rendered with the
 * figlet Standard font (same style as the winSAB project). The version and
 * author lines are NOT printed here — that information already lives in the
 * OUTPUT-<lang>.txt help text (e.g. "SystemWin 1.0.0 ... Author: ..."), so
 * printing it again in the banner would be a duplicate.
 */
/**
 * 这个类的职责非常单一：在程序启动的时候，往控制台（标准输出）打印一张固定的“欢迎横幅”。
 * 什么是横幅（banner）呢？其实就是程序一运行，你第一眼看到的那几行文字。
 * 很多命令行工具都有这种东西，比如 MySQL、Redis 启动的时候都会打印自己的 logo，
 * 用来告诉用户“嘿，我启动了，而且我是谁”。
 *
 * 现在这张横幅的内容很简单，只有一样东西：
 *   用 figlet 的 Standard 字体渲染出来的 ASCII 艺术字 “SystemWin”。
 *
 * 为什么不再打印版本号和作者了呢？因为版本号和作者信息已经写在帮助文本
 * （OUTPUT-en_US.txt / OUTPUT-zh_CN.txt）的开头了——用户执行 systemwin 或
 * systemwin help 时本来就会看到。如果横幅里再打印一遍，同一个信息出现两次，
 * 显得啰嗦。所以横幅里就专心打艺术字，其他信息交给帮助文本去负责，
 * 大家各司其职，互不重复。
 *
 * 为什么要做成一个独立的类，而不是随便写在 main 方法里呢？因为“打印横幅”这件事
 * 是一个独立的、可复用的动作，把它单独抽出来，main 方法看起来就更干净，
 * 以后想改横幅的样式或者内容，也只需要改这一个文件，不用去别的地方翻代码。
 * 这就是“单一职责原则”在实践中的一个小例子——每个类只管自己那一摊子事。
 */
public final class Banner {

    // 私有的无参构造方法：这是“工具类”的经典写法。
    // 因为 Banner 里只有静态方法，我们根本不需要（也不希望）有人 new 出一个 Banner 对象来，
    // 所以把构造方法设为 private，这样外面就 new 不了了。
    // 如果忘了写这个私有构造方法，Java 会偷偷给我们生成一个 public 的默认构造方法，
    // 那样别人就能随便实例化这个类了，虽然不一定会出 bug，但语义上就不够严谨。
    // 加了 private 构造方法之后，如果有人试图 new Banner()，编译器直接报错，从源头杜绝。
    private Banner() {
        // 构造方法体是空的，什么都不用做，因为我们只是不想让别人调用它而已。
    }

    /**
     * 这是整个类唯一对外暴露的“门面”方法：调用它，横幅就会被打印到控制台上。
     * 之所以是 static，是因为打印横幅不需要依赖任何对象状态——我们不保存什么数据，
     * 也不需要记住上次打印了什么，所以用静态方法最合适，直接 Banner.print() 就能调用。
     *
     * 注意这个方法现在不再需要 i18n 参数了：以前它要拿“版本号”“作者”这两条本地化
     * 消息来打印，如今这两行已经去掉，横幅里只剩 ASCII 艺术字，艺术字本身是不分语言
     * 的，所以也就不用再传 i18n 进来。调用处（Main 里）也因此简化成了 Banner.print()。
     */
    public static void print() {
        // 第一步：用 Figlet 工具类把字符串 "SystemWin" 渲染成 ASCII 艺术字。
        // Figlet 是一个把普通文本变成“大字”的经典工具，Standard 字体是它的默认风格。
        // 这里特意提到和 winSAB 项目保持一致的风格，说明这个横幅的样式是有历史传承的，
        // 老项目长什么样，新项目就长什么样，保持品牌一致性。
        // 注意：Figlet.render(...) 返回的字符串末尾不带换行，所以我们随后手动换行，
        // 这样艺术字的最后一行下面会留出一个空行，视觉上跟后面的命令输出分隔开。
        System.out.print(Figlet.render("SystemWin"));

        // 第二步：补一个换行收尾。
        // 为什么需要这个空行？因为如果不加，艺术字的最后一行会紧贴着下面的命令输出，
        // 看起来挤成一团，非常不美观。加一个空行之后，视觉上就清爽多了。
        // 这里我们就是想单纯输出一个换行，所以直接 println() 不传任何参数。
        // （版本号、作者信息已由 OUTPUT-<lang>.txt 帮助文本承载，这里不再重复打印。）
        System.out.println();
    }
}
