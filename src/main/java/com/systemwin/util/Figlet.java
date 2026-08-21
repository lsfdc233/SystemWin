package com.systemwin.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal figlet renderer (figlet "Standard" font, smushing rules).
 *
 * <p>Adapted from the winSAB project
 * (https://github.com/... winSAB, MIT License, Copyright (c) 2026 lsfdc):
 * parses the bundled {@code standard.flf} (the classic figlet Standard font,
 * the same one used by pyfiglet/figlet) and renders text as ASCII art. Used to
 * draw the fixed "SystemWin" startup banner.
 */
/**
 * 中文翻译：这是一个极简的 figlet 渲染器（使用 figlet 官方的 "Standard" 字体，并实现了 smushing 挤压规则）。
 *
 * <p>中文翻译：本类改编自 winSAB 项目（https://github.com/... winSAB，MIT 许可证，版权归 lsfdc 所有，2026 年）：
 * 它会解析随程序一起打包的 {@code standard.flf} 资源文件（经典的 figlet Standard 字体，
 * 与 pyfiglet / figlet 这两个知名工具用的是同一个字体），然后把普通文本渲染成 ASCII 艺术字。
 * 这个类唯一的用途就是绘制程序启动时固定显示的那一行 "SystemWin" 大字横幅。
 */

/*
 * 补充说明（写给第一次看这份代码的初学者，废话很多，但看完会很有帮助）：
 *
 * 什么是 figlet？figlet 是一个非常古老的命令行小工具，它能把一行普通文字变成用字符拼出来的
 * 巨型字母。比如 "Hello" 会被渲染成好几行由 #、/、\、_、| 等字符组成的大字，非常有年代感。
 *
 * 什么是 "smushing"（挤压）？当两个字母并排放在一起的时候，如果直接硬拼，中间会留下很宽的
 * 空白缝隙，看起来稀稀拉拉的，一点都不像连笔字。所以 figlet 定义了一整套 "挤压" 规则，
 * 允许相邻字母的边缘互相重叠、甚至合并成一个新字符，这样拼出来的效果更紧凑、更好看。
 * 这份代码里反复出现的 smushAmount、smushChars 这两个方法，就是在实现这套挤压规则。
 *
 * 为什么要有这个类？因为 SystemWin 程序启动的时候想显示一个漂亮的大字横幅，
 * 但又不想为这么一个小功能引入第三方依赖，所以作者就用纯 Java 把 figlet 的核心逻辑
 * 重新实现了一遍，并且只保留了渲染必需的部分，所以类的名字里有个 "Minimal"（极简）。
 *
 * 阅读建议：先看最下面那个 Font 类（字体怎么解析进来的），再看中间的 render 主方法
 * （字符是怎么一个个贴上去的），最后再看 smushAmount / smushChars（挤压规则是怎么算的），
 * 从上到下正好是"数据准备 -> 主流程 -> 细节规则"的顺序，这样理解起来最顺。
 */
public final class Figlet {

    // 中文：字体资源文件的路径。standard.flf 放在 classpath 的根目录下，
    // 所以前面有个斜杠 "/"，表示从类路径根部开始找。
    // 这个文件是 figlet 官方提供的 Standard 字体，也是最经典、最常用的一种。
    private static final String FONT_RESOURCE = "/standard.flf";

    // figlet smush mode bits
    // 中文翻译：下面这些常量是 figlet 的 "smush 模式" 位标志（bit flag）。
    /*
     * 这一组常量是什么意思？（废话版讲解）
     * 在 figlet 的世界里，两个相邻字符的边缘能不能互相重叠、重叠之后变成什么字符，
     * 是由一套叫做 "smush mode" 的配置决定的。这个配置其实就是一个整数，
     * 它的每一个二进制位分别代表一条规则的开关：
     *   - SM_EQUAL（位 0）：两个相同的字符相遇时可以合并，比如两个 "=" 叠在一起还是 "="；
     *   - SM_LOWLINE（位 1）：下划线 "_" 可以和括号、斜杠这类字符合并；
     *   - SM_HIERARCHY（位 2）：按字符的"层级"决定谁覆盖谁，层级高的吃掉层级低的；
     *   - SM_PAIR（位 3）：成对的括号（[]、{}、()）可以合并成一个竖线 "|"；
     *   - SM_BIGX（位 4）：斜杠和反斜杠交叉可以合成 "X"、"Y" 之类的特殊字符；
     *   - SM_HARDBLANK（位 5）：字体里的硬空格（hard blank）有特殊的处理规则；
     *   - SM_KERN（位 6）：只做"字距调整"，即允许重叠，但重叠的部分直接丢掉，不做合并；
     *   - SM_SMUSH（位 7）：真正的挤压合并，重叠的部分按照规则合并成新的字符。
     * 数字 1、2、4、8、16、32、64、128 其实就是 2 的 0 次方到 7 次方，
     * 用按位与（&）运算就能检查某个位是否被打开，用按位或（|）就能组合多个规则。
     * 这套位标志的定义是从 figlet 官方格式规范里抄过来的，和标准 figlet 程序完全一致，
     * 所以千万不要随便改这些数字，改了就和官方字体不兼容了。
     */
    private static final int SM_EQUAL = 1;
    private static final int SM_LOWLINE = 2;
    private static final int SM_HIERARCHY = 4;
    private static final int SM_PAIR = 8;
    private static final int SM_BIGX = 16;
    private static final int SM_HARDBLANK = 32;
    private static final int SM_KERN = 64;
    private static final int SM_SMUSH = 128;

    // 中文：私有构造函数。为什么是私有的？因为这个类只提供静态方法（render 等），
    // 根本不需要创建对象。把构造函数私有化，可以防止别人（包括未来的自己）不小心 new 一个
    // 没意义的 Figlet 实例出来，这是工具类（utility class）的标准写法。
    private Figlet() {
    }

    /** Renders one line of text as figlet ASCII art (default width 80). */
    /*
     * 中文翻译：把一行文本渲染成 figlet 风格的 ASCII 艺术字，默认最大宽度是 80 列。
     *
     * 这个重载方法纯粹是给调用方偷懒用的：大多数时候我们只关心"给我渲染出来"，
     * 根本不在乎宽度是多少，所以这里就硬编码了一个 80（老式终端默认的列宽），
     * 然后转手调用下面那个真正干活的 render(text, width) 方法。
     * 这种"少参数的便捷版本 -> 多参数的完整版本"的写法在 Java 里非常常见，
     * 叫做便捷重载（convenience overload），好处是调用方少写一个参数，
     * 坏处是初学者可能会疑惑"为什么同一个方法有两个"——现在你知道了。
     */
    public static String render(String text) {
        return render(text, 80);
    }

    /** Renders one line of text; wraps at the nearest space when wider than {@code width}. */
    /*
     * 中文翻译：渲染一行文本；当整行宽度超过 {@code width} 时，会在最近的一个空格处换行。
     *
     * （废话版详细讲解）这是整个类的核心方法，也是最难懂的一个方法，
     * 建议初学者先耐心读完下面的注释，再回头看代码，会轻松很多。
     *
     * 整体思路是这样的：
     * 1. 先把字体文件解析出来（Font.load()），拿到每个字符的"字形"（glyph）。
     *    一个字形就是这个字符在字体里对应的那几行字符串，比如字母 "A" 可能是 8 行字符拼出来的。
     * 2. 从左到右扫描输入文本的每一个字符，把它的字形一格一格地"贴"到当前行的缓冲 buffer 上。
     * 3. 每贴一个字符之前，先算一下它能和左边已经贴好的内容重叠多少列（smushAmount），
     *    重叠的列数越多，拼出来的字越紧凑、越像连笔字。
     * 4. 贴的时候如果发现当前行已经超过指定宽度 width 了，就说明这一行装不下了，需要换行。
     *    换行时优先回退到最近一次遇到空格的位置（靠 blankMarks 这个栈实现），
     *    因为在空格处换行不会把单词劈成两半，排版更美观。
     * 5. 所有字符处理完之后，把缓冲区里剩下的内容也作为最后一行输出，
     *    再把每一行右端的空格裁掉（右边留白没有意义），最后拼成一个完整的字符串返回。
     *
     * 注意：这个方法虽然名字叫 render"一行"，但如果输入文本里含有换行符 \n，
     *    它也会正确处理，把 \n 当成分隔符，分别渲染成多行大字。
     */
    public static String render(String text, int width) {
        Font font = Font.load(); // 中文：加载字体。注意这里每次调用都会重新读一次 .flf 文件并解析，
                                 // 稍微有点浪费，但对启动横幅这种低频调用来说完全无所谓，
                                 // 代码简单、不易出错才是最重要的。
        int height = font.height; // 中文：这个字体里每个字符占多少行（Standard 字体通常是 8 行）。

        // 中文：buffer 是"当前正在拼的这一行大字"的缓冲。
        // 它是一个 List<String>，每个元素对应大字的一行（row），一共有 height 行。
        // 一开始所有行都是空字符串，随着字符不断贴进来，每一行会越来越长，
        // 直到这一行拼完被存入 product，或者遇到换行、超宽等情况被重置。
        List<String> buffer = emptyRows(height);

        // 中文：product 用来收集所有已经拼好、可以输出的完整"大字行"。
        // 每完成一行，就把那一行的副本塞进 product，最后统一遍历输出。
        // 注意它的元素类型是 List<String>（一整行大字由 height 行字符串组成），
        // 所以 product 本身是"列表的列表"。
        List<List<String>> product = new ArrayList<>();

        // 中文：blankMarks 是一个栈（用 ArrayDeque 当栈用，栈顶是最近一次 push 的元素）。
        // 它记录"最近一次在空格处停下来的位置"。为什么需要它？
        // 因为万一某一行拼到一半超宽了，我们想回退到最近的空格处再换行，
        // 这样单词就不会被拦腰截断。每遇到一个空格，就把当时的缓冲快照和字符下标压栈，
        // 等超宽需要换行时，再从栈顶弹出使用（后进先出，所以永远拿到"最近"的那个空格）。
        ArrayDeque<BlankMark> blankMarks = new ArrayDeque<>();

        // 中文：prevCharWidth 记录"上一个字符的宽度"。
        // 在 smush 规则里，宽度小于 2 的字符（比如空格、单个标点）是禁止参与挤压的，
        // 所以这里必须记住上一个字符有多宽，等会儿算挤压量（smushChars）的时候要用。
        int prevCharWidth = 0;

        int i = 0; // 中文：i 是当前正在处理的字符在 text 字符串里的下标，从 0 开始。
        while (i < text.length()) { // 中文：主循环，一个字符一个字符地处理，直到把整行文本处理完。
            char c = text.charAt(i); // 中文：取出当前要处理的字符。

            // ---- 情况一：遇到换行符 \n ----
            // 中文：说明用户想在文本中间强制换行。做法是：把当前 buffer 里已经拼好的内容
            // 作为一个完整的大字行存进 product，然后清空 buffer，重新开始拼下一行。
            // blankMarks 和 prevCharWidth 也要一并重置，因为新的一行是从零开始的。
            if (c == '\n') {
                product.add(copyRows(buffer)); // 中文：注意这里存的是 buffer 的"副本"！
                                               // 因为下面马上要把 buffer 清空重用，
                                               // 如果不复制一份，存进去的内容就会被清空操作破坏。
                buffer = emptyRows(height);
                blankMarks.clear();
                prevCharWidth = 0;
                i++;
                continue; // 中文：continue 跳过当前字符，继续处理下一个字符。
            }

            // ---- 情况二：字体里没有这个字符，或者字符太宽 ----
            // 中文：如果字体表里查不到这个字符（比如中文、emoji 等，figlet 的 Standard 字体
            // 只覆盖了 ASCII 码 32 到 126），那就直接跳过它，什么都不画。
            // 反正画不了，硬画只会出错，跳过去是最稳妥的做法。
            List<String> glyph = font.chars.get((int) c);
            if (glyph == null) {
                i++;
                continue;
            }
            // 中文：如果这个字符本身的宽度就比允许的最大宽度还宽，那也没法画，同样跳过。
            // 这种情况在正常文本里几乎不会出现，属于防御性检查，防止后面计算出负数或越界。
            int curCharWidth = font.widths.get((int) c);
            if (curCharWidth > width) {
                i++;
                continue;
            }

            // 中文：算一下当前字符最多可以和左边已经贴好的内容重叠（smush）多少列。
            // maxSmush 越大，两个字符贴得越紧；这个值是所有行里最小的那个（具体见 smushAmount）。
            int maxSmush = smushAmount(font, buffer, glyph, curCharWidth, prevCharWidth);
            // 中文：如果把当前字符贴上去，这一行的总宽度 = 已有宽度 + 新字符宽度 - 重叠列数。
            // 要减去 maxSmush，是因为重叠的那几列不占新的宽度，已经被旧内容占着了。
            int totalWidth = buffer.get(0).length() + curCharWidth - maxSmush;

            // 中文：如果当前字符是空格，就把"当前拼到一半的缓冲快照"和"空格的下标"压进栈。
            // 这样万一之后超宽需要换行，我们就能回到这个空格的位置，保证单词的完整性。
            // 注意压栈的同样是副本（copyRows），原因和上面一样：缓冲之后还会被修改。
            if (c == ' ') {
                blankMarks.push(new BlankMark(copyRows(buffer), i));
            }

            // ---- 情况三：把当前字符贴上去就超宽了，需要换行 ----
            if (totalWidth >= width) {
                // 中文：优先从栈里取最近一次记录的空格位置回退。
                if (!blankMarks.isEmpty()) {
                    BlankMark mark = blankMarks.pop(); // 中文：弹出最近的那个空格标记。
                    product.add(mark.rows);            // 中文：把空格之前的缓冲作为一整行输出。
                    i = mark.iterator;                 // 中文：把主循环的下标回退到空格处。
                                                       // 注意：回退之后，下一次循环还会再处理一次这个空格，
                                                       // 但空格是空白字符，重新拼进 buffer 不会改变视觉效果，
                                                       // 所以这种"多处理一次"的做法是安全的。
                    buffer = emptyRows(height);        // 中文：清空缓冲，开始拼新的一行。
                    blankMarks.clear();                // 中文：旧一行的空格标记全部作废。
                    prevCharWidth = 0;                 // 中文：新的一行从零开始，没有"上一个字符"。
                } else {
                    // 中文：栈是空的，说明这一行从头到尾都没遇到过空格（比如一个超长的单词）。
                    // 那就没办法了，只能硬生生在当前位置断开，单词会被劈开，但总比无限变宽好。
                    product.add(copyRows(buffer));
                    i--; // 中文：下标先减一，等会儿循环末尾还会 i++，正好抵消，
                         // 等于这个字符下一轮重新处理一遍（因为这一轮它还没被贴进 buffer）。
                    buffer = emptyRows(height);
                    blankMarks.clear();
                    prevCharWidth = 0;
                }
            } else {
                // ---- 情况四：宽度够，正常把字符贴进缓冲 ----
                // 中文：这是最常见的情况：把这一个字符的字形，按照算好的重叠量，
                // 一行一行地合并到 buffer 里。具体的合并细节见 addGlyphToBuffer。
                addGlyphToBuffer(font, buffer, glyph, maxSmush, prevCharWidth, curCharWidth);
            }
            // 中文：循环末尾的统一收尾：把"当前字符宽度"记成"上一个字符宽度"，供下一个字符使用；
            // 然后下标加一，继续处理下一个字符。
            prevCharWidth = curCharWidth;
            i++;
        }

        // 中文：主循环结束后，buffer 里可能还残留着最后一行没拼完的内容，
        // 只要它不是全空的，就把它也当作最后一行输出。
        // 为什么要判空？如果输入恰好以空格结尾，buffer 里可能只剩全空的几行，
        // 不判空的话输出末尾就会多出一个多余的空行，不好看。
        if (!buffer.get(0).isEmpty()) {
            product.add(buffer);
        }

        // ---- 把收集到的所有大字行拼成一个字符串输出 ----
        StringBuilder out = new StringBuilder();
        boolean firstLine = true; // 中文：标记当前是不是第一行，用来决定行与行之间要不要加换行符。
        for (List<String> line : product) { // 中文：遍历所有已经拼好的大字行。
            if (!firstLine) {
                out.append('\n'); // 中文：不是第一行的话，先补一个换行符，让两段大字上下分开。
            }
            firstLine = false;
            // 中文：一个大字行里有 height 行字符（因为每个字符都是 height 行高），逐行输出。
            for (int row = 0; row < height; row++) {
                // 中文：figlet 字体里有一个特殊字符叫 hardBlank（硬空格），
                // 它在字模文件里看起来像空格，但实际是一个普通字符，专门用来表示
                // "这里渲染时应该显示为空格"。真正输出之前必须把它替换成真正的空格字符，
                // 否则显示出来会是一个奇怪的符号。
                String rowText = line.get(row).replace(font.hardBlank, ' ');
                // 中文：把这一行右端的空格全部裁掉。ASCII 艺术字右边留白没有意义，
                // 只会让整体看起来参差不齐，所以先把尾部空格的下标找出来。
                int end = rowText.length();
                while (end > 0 && rowText.charAt(end - 1) == ' ') {
                    end--;
                }
                out.append(rowText, 0, end); // 中文：只追加从开头到最后一个非空格字符的部分。
                // 中文：除了最后一行，每一行后面都要加换行符，这样多行大字才能正确分行显示。
                if (row < height - 1) {
                    out.append('\n');
                }
            }
        }
        return out.toString(); // 中文：返回最终拼好的 ASCII 艺术字字符串。
    }

    /**
     * 中文翻译：创建一个高度为 height、每一行都是空字符串的"空缓冲"。
     *
     * （废话版讲解）因为 figlet 的每个字符都占 height 行，所以"一整行大字"也是由 height 行
     * 字符串组成的。开始拼一行新的大字之前，我们需要先把这 height 行都初始化为空字符串，
     * 后面 addGlyphToBuffer 会逐行把字符内容拼接上去。注意这里用的是 ArrayList，
     * 构造时直接指定初始容量为 height，可以避免后面扩容带来的额外开销
     * （虽然对这个小场景来说，这点开销可以忽略不计，但养成好习惯总没错）。
     */
    private static List<String> emptyRows(int height) {
        List<String> rows = new ArrayList<>(height);
        for (int r = 0; r < height; r++) {
            rows.add("");
        }
        return rows;
    }

    /**
     * 中文翻译：复制一份 rows 列表。
     *
     * （废话版讲解）为什么到处都要"复制一份"？因为 Java 里 List 是引用类型，
     * 你把一个 List 变量传来传去，大家操作的都是同一个对象。而我们经常需要
     * "把当前拼到一半的缓冲存档"，存档之后缓冲还会继续被修改，如果不复制一份，
     * 存档的内容也会跟着被改掉，那就等于没存档。
     * 所以每次存档（比如遇到空格时压栈、换行时把整行放入 product）都必须先 copyRows 一份副本。
     * 这里直接用 new ArrayList<>(rows) 就够了，因为里面的每个元素是 String，
     * 而 String 是不可变对象，不需要再做深拷贝——这就是所谓的"浅拷贝足够用"。
     */
    private static List<String> copyRows(List<String> rows) {
        return new ArrayList<>(rows);
    }

    /** Adds a glyph to the current line (smushing + appending). */
    /*
     * 中文翻译：把一个字符的字形（glyph）加到当前行缓冲上，包含 smush（挤压合并）和追加两个步骤。
     *
     * （废话版讲解）这是"贴字符"的核心操作。想象一下我们在拼图：
     * buffer 是已经拼好的部分，glyph 是手里这块新拼图，maxSmush 告诉我们
     * 新拼图最多可以往左伸进已拼区域多少列。具体做法是一行一行处理（因为字形有 height 行）：
     *   1. 对每一行，先把新字形的前 maxSmush 列和旧内容的最后 maxSmush 列逐个对齐，
     *      逐个字符调用 smushChars 判断能不能合并、合并成什么字符；
     *   2. 能合并就把合并结果写回 buffer 的对应位置，不能合并就保持原样；
     *   3. 新字形剩下的部分（超出 maxSmush 的"尾巴"）直接追加到这一行的末尾。
     * 所有行都处理完后，这个字符就算"贴"到缓冲上了，可以继续处理下一个字符。
     */
    private static void addGlyphToBuffer(Font font, List<String> buffer, List<String> glyph,
                                         int maxSmush, int prevCharWidth, int curCharWidth) {
        int height = buffer.size(); // 中文：行数就是字体的高度，和 buffer 的行数一致。
        for (int row = 0; row < height; row++) { // 中文：逐行处理。
            String addRight = glyph.get(row);    // 中文：新字符在这一行的内容（要贴进来的部分）。
            String addLeft = buffer.get(row);    // 中文：缓冲里这一行已经拼好的内容。

            // 中文：合并结果先放在 StringBuilder 里（它可变，方便原地修改），初始内容为旧内容。
            StringBuilder merged = new StringBuilder(addLeft);
            // 中文：只需要处理重叠的那 maxSmush 列，一一对应地比较合并。
            for (int k = 0; k < maxSmush; k++) {
                // 中文：idx 是旧内容里参与重叠的字符下标。
                // 公式是"旧长度 - 重叠列数 + 当前列"，也就是从重叠区间的起点往右数第 k 列。
                int idx = addLeft.length() - maxSmush + k;
                if (idx >= 0 && idx < addLeft.length()) { // 中文：防御性边界检查，防止下标越界。
                    char left = addLeft.charAt(idx);      // 中文：旧内容里这一列的字符。
                    // 中文：新内容里对应的字符；如果新内容不够长，就用空格补位。
                    char right = k < addRight.length() ? addRight.charAt(k) : ' ';
                    // 中文：调用 smushChars 判断这两个字符能否合并、合并成什么。
                    // 返回 null 表示不能合并（保持原样），返回字符表示合并后的结果。
                    Character smushed = smushChars(font, left, right, prevCharWidth, curCharWidth);
                    if (smushed != null) {
                        merged.setCharAt(idx, smushed); // 中文：能合并就覆盖写回合并结果。
                    }
                }
            }
            // 中文：重叠部分处理完了，把新字形超出重叠范围的那段"尾巴"直接接在整行后面。
            String tail = addRight.length() > maxSmush ? addRight.substring(maxSmush) : "";
            buffer.set(row, merged.append(tail).toString()); // 中文：整行替换成合并后的结果。
        }
    }

    /** Blank-marker for line breaking at the nearest space. */
    /*
     * 中文翻译：这是一个"空格标记"内部类，用于在最近的空格处换行。
     *
     * （废话版讲解）它只有两个字段，非常单纯：
     *   rows     —— 遇到这个空格时，当前大字缓冲的完整快照（副本）；
     *   iterator —— 这个空格在原始文本里的下标（也就是主循环的 i 当时的取值）。
     * 当后面某一行拼到超宽需要换行时，就从栈里弹出最近的 BlankMark，
     * 把 rows 作为一行输出，并把主循环下标跳回 iterator，从而实现"从空格处重新开始"。
     * 为什么两个字段都要存？因为换行时需要同时恢复"当时的拼图状态"和"当时的扫描位置"，
     * 光有下标没有缓冲，或者光有缓冲没有下标，都没法完成回退。
     * 另外注意：这个类没有显式写构造函数之外的任何方法，也没有 setter，
     * 字段都是 final 的，说明它就是个"只读的数据袋子"，创建之后就不允许改了。
     */
    private static final class BlankMark {
        final List<String> rows;
        final int iterator;

        BlankMark(List<String> rows, int iterator) {
            this.rows = rows;
            this.iterator = iterator;
        }
    }

    /** Number of columns the current char can smush with the left buffer. */
    /*
     * 中文翻译：计算当前字符最多可以和左边缓冲重叠（smush）多少列。
     *
     * （废话版讲解）返回值越大，两个字符贴得越紧。计算方法很朴素：对字形的每一行，
     * 分别找出"旧内容最右边的非空格字符"和"新内容最左边的非空格字符"，
     * 它们之间的水平距离就是这一行允许的重叠列数；如果这两个边缘字符本身还能合并成新字符，
     * 还可以再多重叠一列。最后取所有行里最小的那个值作为最终结果。
     * 为什么取最小值而不是最大值？因为重叠量必须让每一行都放得下，
     * 只要有一行放不下，整体就会错位、字符就会叠穿，所以只能照顾最"挤"的那一行，
     * 取最小值才是最安全的。
     *
     * 另外注意开头那个判断：如果字体的 smushMode 里既没有 SM_SMUSH 也没有 SM_KERN，
     * 说明这种字体根本不允许任何重叠，直接返回 0，也就是老老实实硬拼、中间留空隙。
     */
    private static int smushAmount(Font font, List<String> buffer, List<String> curChar,
                                   int curCharWidth, int prevCharWidth) {
        if ((font.smushMode & (SM_SMUSH | SM_KERN)) == 0) {
            return 0;
        }
        int maxSmush = curCharWidth; // 中文：先假设最多能重叠"整个新字符那么宽"，
                                     // 后面每一行都会尝试把这个值往小了压（取最小值）。
        for (int row = 0; row < font.height; row++) { // 中文：逐行扫描，每一行都算一遍。
            String lineLeft = buffer.get(row);   // 中文：这一行里旧内容的字符串。
            String lineRight = curChar.get(row); // 中文：这一行里新字符的字符串。

            // 中文：从右往左找旧内容里最后一个非空格字符。trimmedLeft 是"去掉尾部空格后的长度"。
            int trimmedLeft = lineLeft.length();
            while (trimmedLeft > 0 && lineLeft.charAt(trimmedLeft - 1) == ' ') {
                trimmedLeft--;
            }
            // 中文：linebd 就是那个"最右边非空格字符"的下标。减一是因为下标从 0 开始，
            // 长度是 5 的话，最后一个字符的下标是 4。
            int linebd = trimmedLeft - 1;
            if (linebd < 0) { // 中文：如果这一行全是空格（没有任何非空格字符），就把它当成 0。
                linebd = 0;
            }
            char ch1; // 中文：旧内容边缘的字符。
            if (linebd < lineLeft.length()) {
                ch1 = lineLeft.charAt(linebd);
            } else {
                // 中文：边界情况：行内容为空时，把下标归零、字符设为空字符 '\0' 作为占位，
                // 表示"这一行其实没有字符"。
                linebd = 0;
                ch1 = '\0';
            }

            // 中文：从左往右找新内容里第一个非空格字符的下标（charbd）。
            int charbd = 0;
            while (charbd < lineRight.length() && lineRight.charAt(charbd) == ' ') {
                charbd++;
            }
            char ch2; // 中文：新内容边缘的字符。
            if (charbd < lineRight.length()) {
                ch2 = lineRight.charAt(charbd);
            } else {
                // 中文：同样处理空内容的边界情况，charbd 直接放到行尾，ch2 用 '\0' 占位。
                charbd = lineRight.length();
                ch2 = '\0';
            }

            // 中文：这一行允许的重叠列数 = 新内容第一个非空格字符的位置 + 旧内容总长度 - 1 - 旧内容最后非空格字符的位置。
            // 说白了就是"两个边缘字符之间的水平距离"，距离越大，能重叠的列数就越多。
            int amt = charbd + lineLeft.length() - 1 - linebd;
            // 中文：特殊修正：如果旧内容边缘是空的（\0 或空格），说明旧内容这一行是空的或全空格，
            // 左侧没有任何字符挡路，重叠量还能再加一列。
            if (ch1 == '\0' || ch1 == ' ') {
                amt += 1;
            } else if (ch2 != '\0' && smushChars(font, ch1, ch2, prevCharWidth, curCharWidth) != null) {
                // 中文：否则，如果两个边缘字符能合并成一个字符，重叠量也可以再加一列
                // （因为合并后它们共占一列，相当于多挤进去一列）。
                amt += 1;
            }
            if (amt < maxSmush) { // 中文：取所有行里的最小值，保证每一行都放得下，不会错位。
                maxSmush = amt;
            }
        }
        return maxSmush; // 中文：返回最终算出的最大重叠列数，供调用方贴字符时使用。
    }

    /** Whether two edge characters can smush, and the resulting character. */
    /*
     * 中文翻译：判断两个相邻的边缘字符能否挤压合并（smush），如果能，返回合并后的字符。
     *
     * （废话版讲解）这个方法就是 figlet smushing 规则的"总裁判"。它接收两个字符
     * （left 是左边已有的字符，right 是右边新来的字符），按照标准 figlet 的规则依次判断：
     *   返回 null       —— 不允许合并，两个字符保持原样；
     *   返回 left/right —— 其中一个被另一个覆盖（通常是空白被非空白覆盖）；
     *   返回新字符      —— 两个字符按规则合并成一个全新的字符（比如 "/" 和 "\" 变成 "|"）。
     * 判断顺序非常重要，必须从上往下严格执行，后面的规则只在前面的规则没命中时才会被检查，
     * 这个优先级顺序和 figlet 官方规范里的定义是一致的，不能随意调换。
     * 下面把每一条规则都用大白话解释一遍：
     *
     * 1. 有一边是普通空格：空格没有"内容"，自然被另一边覆盖，直接返回另一边。
     * 2. 上一个字符或当前字符宽度小于 2：太窄的字符（比如单个标点）不参与挤压，返回 null。
     *    这是 figlet 的硬性规定，防止窄字符被挤压得面目全非。
     * 3. 字体根本没开 SM_SMUSH 模式：不允许真正合并，返回 null。
     * 4. 六个"低位"规则（smushMode & 63 为 0，即前六位全关）一个都没开：
     *    这时只剩最朴素的规则——硬空格优先保留，其余情况一律用右边的字符覆盖左边。
     * 5. SM_HARDBLANK：两个硬空格碰在一起，保留硬空格（硬空格在字模里是特殊字符，后面会转成空格）。
     * 6. 只有一边是硬空格：硬空格不能和其他字符合并，返回 null（保持原样，稍后统一转成空格）。
     * 7. SM_EQUAL：左右字符相同，合并结果就是它自己（比如 "==" 叠出 "="，两个 "O" 叠出 "O"）。
     * 8. SM_LOWLINE：下划线 "_" 和括号、斜杠类字符相遇，下划线被对方覆盖。
     * 9. SM_HIERARCHY：按字符层级覆盖，层级顺序是 "|" < "/\" < "[]" < "{}" < "()" < "<>"，
     *    层级高的覆盖层级低的（比如 "|" 会被 "/" 覆盖，"/" 会被 "[" 覆盖）。
     * 10. SM_PAIR：成对的括号（[]、{}、()）合并成一个竖线 "|"。
     * 11. SM_BIGX："/" 和 "\" 交叉产生 "|" 或 "Y"；">" 和 "<" 交叉产生 "X"。
     * 12. 以上规则全都没命中，返回 null，表示这两个字符不兼容，保持原样。
     */
    private static Character smushChars(Font font, char left, char right,
                                        int prevCharWidth, int curCharWidth) {
        if (left == ' ') { // 中文：左边是空格？直接让右边的字符覆盖它，空格没有内容，理应让位。
            return right;
        }
        if (right == ' ') { // 中文：右边是空格？直接保留左边的字符，新来的空格没有意义。
            return left;
        }
        if (prevCharWidth < 2 || curCharWidth < 2) { // 中文：任一字符宽度小于 2，禁止挤压，返回 null。
            return null;
        }
        if ((font.smushMode & SM_SMUSH) == 0) { // 中文：字体没开真正的合并模式，禁止挤压。
            return null;
        }
        if ((font.smushMode & 63) == 0) {
            // 中文：63 的二进制是 111111，正好是 SM_EQUAL(1) | SM_LOWLINE(2) | SM_HIERARCHY(4) |
            // SM_PAIR(8) | SM_BIGX(16) | SM_HARDBLANK(32) 这六个低位规则的总和。
            // 如果这六个规则一个都没开，就走这里的最朴素处理：
            if (left == font.hardBlank) { // 中文：左边是硬空格，保留它（硬空格要留到输出时转成空格）。
                return right;
            }
            if (right == font.hardBlank) { // 中文：右边是硬空格，保留它。
                return left;
            }
            return right; // 中文：否则一律用右边覆盖左边——这是最粗犷、最简单的合并方式。
        }
        if ((font.smushMode & SM_HARDBLANK) != 0) {
            if (left == font.hardBlank && right == font.hardBlank) { // 中文：两个硬空格相遇，保留硬空格。
                return left;
            }
        }
        if (left == font.hardBlank || right == font.hardBlank) { // 中文：只要有一边是硬空格，就不合并。
            return null;
        }
        if ((font.smushMode & SM_EQUAL) != 0 && left == right) { // 中文：两个相同的字符合并成它自己。
            return left;
        }

        if ((font.smushMode & SM_LOWLINE) != 0) {
            // 中文：SM_LOWLINE 规则：下划线 "_" 和这些成对符号（| / \ [ ] { } ( ) < >）相遇时，
            // 下划线被对方覆盖。这样连写的下划线在视觉上就不会被括号顶开，更连贯。
            if (left == '_' && "|/\\[]{}()<>".indexOf(right) >= 0) {
                return right;
            }
            if (right == '_' && "|/\\[]{}()<>".indexOf(left) >= 0) {
                return left;
            }
        }

        if ((font.smushMode & SM_HIERARCHY) != 0) {
            // 中文：SM_HIERARCHY 规则：字符分五个层级，从低到高依次是 |、/\ 、[] 、{} 、() 、<>。
            // 后面层级的字符可以覆盖前面层级的字符，看起来就像"高级的字符把低级的字符顶掉了"。
            // 下面这 10 个 if 就是低层和高层两两配对的所有情况，
            // 每一对都判断"左边是低层、右边是高层就取右边，反之取左边"，逻辑完全对称。
            if ("|".indexOf(left) >= 0 && "/\\[]{}()<>".indexOf(right) >= 0) {
                return right;
            }
            if ("|".indexOf(right) >= 0 && "/\\[]{}()<>".indexOf(left) >= 0) {
                return left;
            }
            if ("/\\".indexOf(left) >= 0 && "[]{}()<>".indexOf(right) >= 0) {
                return right;
            }
            if ("/\\".indexOf(right) >= 0 && "[]{}()<>".indexOf(left) >= 0) {
                return left;
            }
            if ("[]".indexOf(left) >= 0 && "{}()<>".indexOf(right) >= 0) {
                return right;
            }
            if ("[]".indexOf(right) >= 0 && "{}()<>".indexOf(left) >= 0) {
                return left;
            }
            if ("{}".indexOf(left) >= 0 && "()<>".indexOf(right) >= 0) {
                return right;
            }
            if ("{}".indexOf(right) >= 0 && "()<>".indexOf(left) >= 0) {
                return left;
            }
            if ("()".indexOf(left) >= 0 && "<>".indexOf(right) >= 0) {
                return right;
            }
            if ("()".indexOf(right) >= 0 && "<>".indexOf(left) >= 0) {
                return left;
            }
        }

        if ((font.smushMode & SM_PAIR) != 0) {
            // 中文：SM_PAIR 规则：左右两边刚好是一对成对的括号时，合并成一个竖线 "|"。
            // 比如 "[" 挨着 "]" 会变成 "|"，这样写 "([])" 这种括号串时，
            // 相邻的括号会被合并成一条竖线，看起来更整齐。
            String pair = "" + left + right;
            if (pair.equals("[]") || pair.equals("{}") || pair.equals("()")) {
                return '|';
            }
            pair = "" + right + left; // 中文：还要检查反过来的顺序，比如 "]" 挨着 "[" 也是成对的。
            if (pair.equals("[]") || pair.equals("{}") || pair.equals("()")) {
                return '|';
            }
        }

        if ((font.smushMode & SM_BIGX) != 0) {
            // 中文：SM_BIGX 规则：斜杠和反斜杠交叉时会产生特殊字符。
            // 左斜杠 "/" 在左、右斜杠 "\" 在右时合并成 "|"（像两条线交叉成一根竖线）；
            // 右斜杠 "\" 在左、左斜杠 "/" 在右时合并成 "Y"；
            // ">" 在左、"<" 在右时合并成 "X"。
            if (left == '/' && right == '\\') {
                return '|';
            }
            if (right == '/' && left == '\\') {
                return 'Y';
            }
            if (left == '>' && right == '<') {
                return 'X';
            }
        }
        return null; // 中文：所有规则都没命中，这两个字符不兼容，保持原样，不合并。
    }

    /** A parsed figlet font. */
    /*
     * 中文翻译：一个解析好的 figlet 字体。
     *
     * （废话版讲解）figlet 的字体文件（.flf 格式）本质上是一个文本文件：
     * 第一行是头信息（包含字体高度、硬空格字符、布局模式等），
     * 后面跟着很多"字符块"，每个字符块由 height 行字符串组成，描绘一个 ASCII 字符长什么样。
     * 这个 Font 类就是把这样一个字体文件解析成 Java 对象之后的内存表示，各字段含义如下：
     *   height     —— 每个字符占多少行（Standard 字体通常是 8 行）；
     *   hardBlank  —— 字体里用来表示"空格"的特殊字符（看起来像空格，但实际是个特殊符号，
     *                  渲染输出时要替换成真正的空格）；
     *   smushMode  —— 该字体支持的挤压模式（就是上面那组 SM_ 位标志的组合）；
     *   chars      —— 从字符编码（int）到字形（List<String>）的映射；
     *   widths     —— 从字符编码（int）到字形宽度（int）的映射。
     * 为什么宽度要单独存一份而不每次现算？因为解析的时候顺手就记下来了，
     * 而渲染时 smushAmount 等地方要频繁查询宽度，直接查 Map 比反复遍历字形数长度快得多，
     * 属于典型的"以空间换时间"。
     */
    static final class Font {
        int height;                                  // 中文：字体高度，即每个字符占几行。
        char hardBlank;                              // 中文：硬空格字符，输出时要替换成真空格。
        int smushMode;                               // 中文：挤压模式，SM_ 位标志的组合值。
        final Map<Integer, List<String>> chars = new HashMap<>(); // 中文：字符编码 -> 字形。
        final Map<Integer, Integer> widths = new HashMap<>();     // 中文：字符编码 -> 宽度。

        static Font load() {
            // 中文：从 classpath 资源里读取字体文件并解析。这里用了 try-with-resources 写法，
            // 保证 InputStream 用完之后自动关闭，不会泄漏文件句柄资源。
            try (InputStream in = Figlet.class.getResourceAsStream(FONT_RESOURCE)) {
                if (in == null) {
                    // 中文：资源找不到（比如打包时漏掉了 standard.flf），直接抛异常，
                    // 宁可让程序启动时报错，也不能假装没事继续跑，否则后面渲染全是乱的。
                    throw new IllegalStateException("Missing figlet font resource " + FONT_RESOURCE);
                }
                // 中文：用 UTF-8 编码读取，因为 .flf 字体文件里可能含有扩展字符，
                // 用平台默认编码读的话在不同系统上结果可能不一样，所以这里显式指定 UTF-8。
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                return parse(reader); // 中文：真正的解析逻辑都在 parse 方法里，这里只负责开流。
            } catch (IOException e) {
                // 中文：读文件出错时，把受检异常 IOException 包成一个运行时异常抛出去，
                // 这样调用方就不用被迫写 try-catch 处理受检异常了，调用代码更干净。
                throw new IllegalStateException("Failed to read figlet font resource: " + e.getMessage(), e);
            }
        }

        static Font parse(BufferedReader reader) throws IOException {
            // 中文：解析 .flf 字体文件的主方法。文件格式是 figlet 官方定义的，
            // 想深入了解可以去看 figlet 的格式规范文档（figlet 2.2 font format）。
            Font font = new Font();
            String header = reader.readLine(); // 中文：第一行是头信息，包含各种元数据。
            if (header == null || !(header.startsWith("flf2") || header.startsWith("tlf2"))) {
                // 中文：头信息必须以 "flf2"（figlet 格式）或 "tlf2"（tolfee 格式）开头，
                // 否则说明这个文件根本不是合法的 figlet 字体，直接报错。
                throw new IOException("Invalid figlet font header: " + header);
            }
            // 中文：头信息去掉前 5 个字符（"flf2a" 之类的版本标记），再用空白分隔出各个字段。
            String[] parts = header.substring(5).trim().split("\\s+");
            if (parts.length < 6) {
                // 中文：标准头至少要有 6 个字段，不够就是坏文件，直接报错。
                throw new IOException("figlet font header has too few fields: " + header);
            }
            font.hardBlank = parts[0].charAt(0);           // 中文：第 1 个字段是硬空格字符。
            font.height = Integer.parseInt(parts[1]);      // 中文：第 2 个字段是字体高度（每个字符几行）。
            int oldLayout = Integer.parseInt(parts[4]);    // 中文：第 5 个字段是"旧版布局"参数。
            int commentLines = Integer.parseInt(parts[5]); // 中文：第 6 个字段是注释行数，下面要跳过这些行。
            // 中文：第 8 个字段（下标 7）是"完整布局"参数，这是新格式才有的字段，
            // 旧格式文件里没有，所以这里用 parts.length > 7 判断一下。
            Integer fullLayout = parts.length > 7 ? Integer.parseInt(parts[7]) : null;
            if (fullLayout == null) {
                // 中文：旧格式没有完整布局字段，需要根据旧版布局参数推算：
                //  oldLayout == 0  → 完全禁止挤压，只允许字距调整（64 = SM_KERN）；
                //  oldLayout < 0   → 什么都不允许（0），字符之间留满空隙；
                //  其他值          → 把低 5 位当作旧式挤压规则，并强制加上 SM_SMUSH（128）。
                // 这套换算规则同样来自 figlet 官方规范，是历史兼容性的产物。
                if (oldLayout == 0) {
                    fullLayout = 64;
                } else if (oldLayout < 0) {
                    fullLayout = 0;
                } else {
                    fullLayout = (oldLayout & 31) | 128;
                }
            }
            font.smushMode = fullLayout; // 中文：最终确定的挤压模式存进字体对象，供渲染时查询。

            // 中文：跳过文件头部那一大段注释文字（作者信息、版权声明之类的），
            // 它们不是字符数据，读掉就好，不需要保存。
            for (int i = 0; i < commentLines; i++) {
                reader.readLine();
            }

            // standard ASCII 32..126
            // 中文翻译：下面开始解析标准 ASCII 字符 32 到 126（也就是空格到波浪号 ~）。
            // 这是 figlet Standard 字体覆盖的完整字符范围，超出这个范围的字符查不到字形。
            for (int code = 32; code <= 126; code++) {
                List<String> glyph = new ArrayList<>(); // 中文：收集这个字符的所有行。
                int width = 0;                          // 中文：记录这个字符的最大宽度。
                Character endMark = null;               // 中文：行尾标记字符，具体用途见下面。
                for (int row = 0; row < font.height; row++) {
                    String line = reader.readLine(); // 中文：读取这个字符的第 row 行内容。
                    if (line == null) { // 中文：文件提前结束（比如字体文件被截断），安全起见直接跳出。
                        break;
                    }
                    if (endMark == null) {
                        // 中文：figlet 文件里，每一行的末尾都会用同一个特殊字符（通常是 '@'）
                        // 作为"行结束标记"，把字符内容和其他部分分隔开。
                        // 第一个字符块的每一行会告诉我们这个标记是什么：
                        // 取这一行最后一个非空格字符，它就是结束标记。
                        char last = ' ';
                        for (int j = line.length() - 1; j >= 0; j--) {
                            if (line.charAt(j) != ' ') {
                                last = line.charAt(j);
                                break;
                            }
                        }
                        endMark = last;
                    }
                    // 中文：把行尾最多 2 个结束标记字符裁掉（有的字符块用 1 个标记，有的用 2 个，
                    // 所以这里数到 2 就停，再多就可能是字符内容本身了）。
                    int len = line.length();
                    int count = 0;
                    while (len > 0 && line.charAt(len - 1) == endMark && count < 2) {
                        len--;
                        count++;
                    }
                    line = line.substring(0, len); // 中文：真正裁剪，去掉结束标记，得到干净的一行。
                    if (line.length() > width) {   // 中文：更新这个字符的最大宽度（取最长那一行）。
                        width = line.length();
                    }
                    glyph.add(line); // 中文：把这一行存进字形列表。
                }
                // 中文：空格（编码 32）必须存进映射，否则渲染到空格时会查不到字形直接跳过，
                // 空格就"消失"了，单词之间的间距就没了；
                // 其他字符如果整个字形都是空行（比如某些字体根本没画这个字符），就不存了，
                // 省一点内存，反正查不到就跳过，行为一致。
                if (code == 32 || !isEmptyGlyph(glyph)) {
                    font.chars.put(code, glyph);
                    font.widths.put(code, width);
                }
            }
            return font; // 中文：解析完成，返回装好所有数据的字体对象。
        }

        private static boolean isEmptyGlyph(List<String> glyph) {
            // 中文：判断一个字形是否"全空"，也就是每一行都是空字符串。
            // 全空的字形说明字体根本没画这个字符，存了也没有渲染价值，直接丢弃。
            for (String row : glyph) {
                if (!row.isEmpty()) {
                    return false; // 中文：只要有一行非空，就说明不是空字形。
                }
            }
            return true; // 中文：所有行都是空的，确实是空字形。
        }
    }
}
