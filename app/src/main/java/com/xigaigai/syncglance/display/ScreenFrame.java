package com.xigaigai.syncglance.display;

/**
 * 一「帧」车机屏幕内容 —— 预览器唯一认识的输入。
 *
 * <h3>设计意图</h3>
 * 预览器（ScreenPreviewView）只负责「把这一帧画出来」，
 * <b>不应该</b>知道歌名从哪来、歌词怎么切、导航数据怎么解析。
 * 所以这一帧里全是**已经算好的字符串**，加上一条计时器**指令**
 * （{@link ClockInstruction}，因为计时器是车机自己走表，不能传死读数）。
 *
 * <h3>抽库时的边界</h3>
 * 本类 + {@link ClockInstruction} + {@link ScreenPreviewView} + 对应布局
 * 构成一个零依赖的预览器包：
 * 不引 SDL、不引 NowPlaying / Navigation / 歌词模型。
 * 任何 AppLink 应用只要构造一帧就能复用这套预览。
 */
public final class ScreenFrame {

    /** 标题栏文字，例如 "SyncPlay" / "SyncPlay (未连接)" */
    public String title = "";

    /** 状态标签，只用于预览面板上那行「模式：…」的诊断显示 */
    public String stateLabel = "";

    /** 第 1 屏第 1、2 行 */
    public String f1 = "";
    public String f2 = "";

    /** 第 2 屏第 1、2 行 */
    public String f3 = "";
    public String f4 = "";

    /** 第 3 行左侧：mediaTrack（两屏共用一个值） */
    public String mediaTrack = "";

    /** 第 3 行右侧：计时器指令（车机自己走表） */
    public ClockInstruction clock = ClockInstruction.none();

    /** 底栏我们下发的软键文字（第 4 格「更多…」由车机自带，不在这里） */
    public String[] softKeys = new String[0];

    /** HMI 是否 FULL（决定标题栏是否标注「未连接」） */
    public boolean connected;

    /** 内容指纹，用于「内容没变就不重发 Show」 */
    public String stamp() {
        return stateLabel + "|" + f1 + "|" + f2 + "|" + f3 + "|" + f4 + "|" + mediaTrack
                + "|sk=" + java.util.Arrays.toString(softKeys);
    }
}
