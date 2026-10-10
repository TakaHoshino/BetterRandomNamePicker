import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * 倒计时插件配置（自持 {@code data/countdown_plugin.properties}）。
 * <p>
 * 约定（对照《插件开发文档.md》§5.7 与正计时子球 {@code TimerBallConfig}）：
 * 默认值 = 代码常量；坏值/缺键回退默认；合并式读写（只覆盖能解析的键）；
 * 写失败仅记日志、不影响运行；颜色以十六进制串（{@code RRGGBB}）持久化。
 * 仅供 EDT 使用（设置对话框与控制器同线程），无并发要求。
 * </p>
 */
public class TimerBallConfig {

    /** 插件私有配置文件（相对程序工作目录 user.dir）。 */
    public static final String FILE_PATH = "data" + File.separator + "countdown_plugin.properties";
    /** 插件私有数据目录（提醒音频建议放这里，删 jar 不自动清理）。 */
    public static final String DATA_DIR = "data" + File.separator + "countdown";

    /** 时长预设（分钟）：按钮一键设置。 */
    public static final int[] PRESET_MINUTES = {1, 3, 5, 10, 15, 25, 30, 45};

    /** 显示形态：子球（贴着宿主悬浮球）/ 全屏专注（番茄钟）/ 两者（双显示，状态同步）。 */
    public enum DisplayMode { BALL, FOCUS, BOTH }

    /** 子球尺寸模式：AUTO=随宿主悬浮球联动；FIXED=固定直径。 */
    public enum SizeMode { AUTO, FIXED }

    // ===================== 默认值常量 =====================

    public static final DisplayMode DEFAULT_DISPLAY_MODE = DisplayMode.BALL;
    public static final int DEFAULT_DURATION_SECONDS = 300;          // 5 分钟
    public static final int DURATION_MIN_SECONDS = 1;
    public static final int DURATION_MAX_SECONDS = 99 * 60 + 59;
    public static final boolean DEFAULT_AUTO_START = true;

    public static final SizeMode DEFAULT_SIZE_MODE = SizeMode.AUTO;
    public static final int DEFAULT_FIXED_DIAMETER = 60;
    public static final int FIXED_DIAMETER_MIN = 30;
    public static final int FIXED_DIAMETER_MAX = 300;
    public static final int DEFAULT_GAP_PX = 6;
    public static final int GAP_PX_MIN = 4;
    public static final int GAP_PX_MAX = 60;
    public static final boolean DEFAULT_FOLLOW_BALL = true;

    public static final boolean DEFAULT_SHOW_PROGRESS_RING = true;
    public static final boolean DEFAULT_SHOW_HOURS = false;

    public static final Color DEFAULT_COLOR_RUNNING = new Color(0x2E, 0x8B, 0x57);   // 绿
    public static final Color DEFAULT_COLOR_PAUSED = new Color(0xE0, 0x8A, 0x2E);    // 橙
    public static final Color DEFAULT_COLOR_WARNING = new Color(0xD9, 0xA4, 0x06);   // 琥珀（警戒色）
    public static final Color DEFAULT_COLOR_FINISHED = new Color(0xC0, 0x39, 0x2B);  // 红（到时）
    public static final Color DEFAULT_COLOR_IDLE = new Color(0x70, 0x86, 0xA6);      // 灰蓝（就绪）

    public static final int DEFAULT_WARN_SECONDS = 60;              // 进入警戒（≤60s）
    public static final int WARN_SECONDS_MIN = 5;
    public static final int WARN_SECONDS_MAX = 1800;

    public static final boolean DEFAULT_FOCUS_FULLSCREEN = true;
    public static final boolean DEFAULT_FOCUS_ALWAYS_ON_TOP = true;
    public static final Color DEFAULT_FOCUS_BACKGROUND = new Color(0x11, 0x14, 0x18);
    public static final int DEFAULT_FOCUS_TEXT_PERCENT = 20;        // 文本高度占窗口短边百分比
    public static final int FOCUS_TEXT_PERCENT_MIN = 6;
    public static final int FOCUS_TEXT_PERCENT_MAX = 45;
    public static final boolean DEFAULT_FOCUS_SHOW_RESULT = true;
    public static final String DEFAULT_FOCUS_TITLE = "专注时间";

    public static final String DEFAULT_ALERT_FILE = "";             // 空 = 未设置自定义音频
    public static final boolean DEFAULT_ALERT_ON_FINISH = true;
    public static final boolean DEFAULT_ALERT_EACH_LAST_SECOND = false;
    public static final int DEFAULT_ALERT_REPEAT = 1;
    public static final int ALERT_REPEAT_MIN = 1;
    public static final int ALERT_REPEAT_MAX = 99;
    public static final int DEFAULT_ALERT_VOLUME = 80;
    public static final boolean DEFAULT_SYSTEM_BEEP_FALLBACK = true;

    // ===================== 键名 =====================

    private static final String K_DISPLAY_MODE = "displayMode";
    private static final String K_DURATION = "defaultDurationSeconds";
    private static final String K_AUTO_START = "autoStartOnShow";
    private static final String K_SIZE_MODE = "sizeMode";
    private static final String K_FIXED_DIAMETER = "fixedDiameter";
    private static final String K_GAP = "gapPx";
    private static final String K_FOLLOW = "followBall";
    private static final String K_RING = "showProgressRing";
    private static final String K_SHOW_HOURS = "showHours";
    private static final String K_COLOR_RUNNING = "colorRunning";
    private static final String K_COLOR_PAUSED = "colorPaused";
    private static final String K_COLOR_WARNING = "colorWarning";
    private static final String K_COLOR_FINISHED = "colorFinished";
    private static final String K_COLOR_IDLE = "colorIdle";
    private static final String K_WARN_SECONDS = "warnSeconds";
    private static final String K_FOCUS_FULLSCREEN = "focusFullscreen";
    private static final String K_FOCUS_ON_TOP = "focusAlwaysOnTop";
    private static final String K_FOCUS_BG = "focusBackground";
    private static final String K_FOCUS_TEXT_PERCENT = "focusTextPercent";
    private static final String K_FOCUS_SHOW_RESULT = "focusShowResult";
    private static final String K_FOCUS_TITLE = "focusTitle";
    private static final String K_ALERT_FILE = "alertFile";
    private static final String K_ALERT_ON_FINISH = "alertOnFinish";
    private static final String K_ALERT_EACH_SECOND = "alertEachLastSecond";
    private static final String K_ALERT_REPEAT = "alertRepeat";
    private static final String K_ALERT_VOLUME = "alertVolume";
    private static final String K_BEEP_FALLBACK = "systemBeepFallback";
    private static final String K_BALL_POS = "ballPos";
    private static final String K_REMAIN_MS = "remainingMs";
    private static final String K_WAS_RUNNING = "wasRunning";
    private static final String K_WAS_STARTED = "wasStarted";

    // ===================== 字段 =====================

    private DisplayMode displayMode = DEFAULT_DISPLAY_MODE;
    private int durationSeconds = DEFAULT_DURATION_SECONDS;
    private boolean autoStartOnShow = DEFAULT_AUTO_START;

    private SizeMode sizeMode = DEFAULT_SIZE_MODE;
    private int fixedDiameter = DEFAULT_FIXED_DIAMETER;
    private int gapPx = DEFAULT_GAP_PX;
    private boolean followBall = DEFAULT_FOLLOW_BALL;
    private boolean showProgressRing = DEFAULT_SHOW_PROGRESS_RING;
    private boolean showHours = DEFAULT_SHOW_HOURS;

    private Color colorRunning = DEFAULT_COLOR_RUNNING;
    private Color colorPaused = DEFAULT_COLOR_PAUSED;
    private Color colorWarning = DEFAULT_COLOR_WARNING;
    private Color colorFinished = DEFAULT_COLOR_FINISHED;
    private Color colorIdle = DEFAULT_COLOR_IDLE;
    private int warnSeconds = DEFAULT_WARN_SECONDS;

    private boolean focusFullscreen = DEFAULT_FOCUS_FULLSCREEN;
    private boolean focusAlwaysOnTop = DEFAULT_FOCUS_ALWAYS_ON_TOP;
    private Color focusBackground = DEFAULT_FOCUS_BACKGROUND;
    private int focusTextPercent = DEFAULT_FOCUS_TEXT_PERCENT;
    private boolean focusShowResult = DEFAULT_FOCUS_SHOW_RESULT;
    /** 全屏番茄钟标题（可即时编辑；空 = 不显示标题文字）。 */
    private String focusTitle = DEFAULT_FOCUS_TITLE;

    private String alertFile = DEFAULT_ALERT_FILE;
    private boolean alertOnFinish = DEFAULT_ALERT_ON_FINISH;
    private boolean alertEachLastSecond = DEFAULT_ALERT_EACH_LAST_SECOND;
    private int alertRepeat = DEFAULT_ALERT_REPEAT;
    private int alertVolume = DEFAULT_ALERT_VOLUME;
    private boolean systemBeepFallback = DEFAULT_SYSTEM_BEEP_FALLBACK;

    /** 子球上次位置（未跟随悬浮球时用；-1 = 无记录）。 */
    private int ballPosX = -1;
    /** 跨会话记忆：上次退出时的剩余毫秒与状态（-1 / false = 未开始的一轮，无需恢复）。 */
    private long savedRemainingMs = -1L;
    private boolean savedWasRunning = false;
    private int ballPosY = -1;

    /** 配置文件路径（可覆盖，供自检脚本写到临时目录；默认 {@link #FILE_PATH}）。 */
    private String filePath = FILE_PATH;

    /** 覆盖配置文件路径（仅自检/测试用；生产走默认 data/countdown_plugin.properties）。 */
    public void setFilePath(String path) {
        if (path != null && !path.trim().isEmpty()) {
            filePath = path.trim();
        }
    }

    public String getFilePath() {
        return filePath;
    }

    // ===================== 访问器 =====================

    public DisplayMode getDisplayMode() {
        return displayMode;
    }

    public void setDisplayMode(DisplayMode m) {
        if (m != null) {
            displayMode = m;
        }
    }

    public boolean isBallEnabled() {
        return displayMode == DisplayMode.BALL || displayMode == DisplayMode.BOTH;
    }

    public boolean isFocusEnabled() {
        return displayMode == DisplayMode.FOCUS || displayMode == DisplayMode.BOTH;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(int s) {
        durationSeconds = clamp(s, DURATION_MIN_SECONDS, DURATION_MAX_SECONDS);
    }

    public boolean isAutoStartOnShow() {
        return autoStartOnShow;
    }

    public void setAutoStartOnShow(boolean b) {
        autoStartOnShow = b;
    }

    public SizeMode getSizeMode() {
        return sizeMode;
    }

    public boolean isFixedSize() {
        return sizeMode == SizeMode.FIXED;
    }

    public void setSizeMode(SizeMode m) {
        if (m != null) {
            sizeMode = m;
        }
    }

    public int getFixedDiameter() {
        return fixedDiameter;
    }

    public void setFixedDiameter(int d) {
        fixedDiameter = clamp(d, FIXED_DIAMETER_MIN, FIXED_DIAMETER_MAX);
    }

    public int getGapPx() {
        return gapPx;
    }

    public void setGapPx(int g) {
        gapPx = clamp(g, GAP_PX_MIN, GAP_PX_MAX);
    }

    public boolean isFollowBall() {
        return followBall;
    }

    public void setFollowBall(boolean b) {
        followBall = b;
    }

    public boolean isShowProgressRing() {
        return showProgressRing;
    }

    public void setShowProgressRing(boolean b) {
        showProgressRing = b;
    }

    public boolean isShowHours() {
        return showHours;
    }

    public void setShowHours(boolean b) {
        showHours = b;
    }

    public Color getColorRunning() {
        return colorRunning;
    }

    public void setColorRunning(Color c) {
        if (c != null) {
            colorRunning = c;
        }
    }

    public Color getColorPaused() {
        return colorPaused;
    }

    public void setColorPaused(Color c) {
        if (c != null) {
            colorPaused = c;
        }
    }

    public Color getColorWarning() {
        return colorWarning;
    }

    public void setColorWarning(Color c) {
        if (c != null) {
            colorWarning = c;
        }
    }

    public Color getColorFinished() {
        return colorFinished;
    }

    public void setColorFinished(Color c) {
        if (c != null) {
            colorFinished = c;
        }
    }

    public Color getColorIdle() {
        return colorIdle;
    }

    public void setColorIdle(Color c) {
        if (c != null) {
            colorIdle = c;
        }
    }

    public int getWarnSeconds() {
        return warnSeconds;
    }

    public void setWarnSeconds(int s) {
        warnSeconds = clamp(s, WARN_SECONDS_MIN, WARN_SECONDS_MAX);
    }

    public boolean isFocusFullscreen() {
        return focusFullscreen;
    }

    public void setFocusFullscreen(boolean b) {
        focusFullscreen = b;
    }

    public boolean isFocusAlwaysOnTop() {
        return focusAlwaysOnTop;
    }

    public void setFocusAlwaysOnTop(boolean b) {
        focusAlwaysOnTop = b;
    }

    public Color getFocusBackground() {
        return focusBackground;
    }

    public void setFocusBackground(Color c) {
        if (c != null) {
            focusBackground = c;
        }
    }

    public int getFocusTextPercent() {
        return focusTextPercent;
    }

    public void setFocusTextPercent(int p) {
        focusTextPercent = clamp(p, FOCUS_TEXT_PERCENT_MIN, FOCUS_TEXT_PERCENT_MAX);
    }

    /** 全屏番茄钟标题。 */
    public String getFocusTitle() {
        return focusTitle == null ? "" : focusTitle;
    }

    public void setFocusTitle(String text) {
        focusTitle = text == null ? "" : text;
    }

    public boolean isFocusShowResult() {
        return focusShowResult;
    }

    public void setFocusShowResult(boolean b) {
        focusShowResult = b;
    }

    public String getAlertFile() {
        return alertFile;
    }

    public void setAlertFile(String path) {
        alertFile = path == null ? "" : path.trim();
    }

    /** 自定义提醒音频文件（未设置返回 null）。 */
    public File getAlertFileObject() {
        if (alertFile.isEmpty()) {
            return null;
        }
        return new File(alertFile);
    }

    public boolean isAlertOnFinish() {
        return alertOnFinish;
    }

    public void setAlertOnFinish(boolean b) {
        alertOnFinish = b;
    }

    public boolean isAlertEachLastSecond() {
        return alertEachLastSecond;
    }

    public void setAlertEachLastSecond(boolean b) {
        alertEachLastSecond = b;
    }

    public int getAlertRepeat() {
        return alertRepeat;
    }

    public void setAlertRepeat(int n) {
        alertRepeat = clamp(n, ALERT_REPEAT_MIN, ALERT_REPEAT_MAX);
    }

    public int getAlertVolume() {
        return alertVolume;
    }

    public void setAlertVolume(int v) {
        alertVolume = clamp(v, 0, 100);
    }

    public boolean isSystemBeepFallback() {
        return systemBeepFallback;
    }

    public void setSystemBeepFallback(boolean b) {
        systemBeepFallback = b;
    }

    public int getBallPosX() {
        return ballPosX;
    }

    public int getBallPosY() {
        return ballPosY;
    }

    public void setBallPos(int x, int y) {
        ballPosX = x;
        ballPosY = y;
    }

    public boolean hasBallPos() {
        return ballPosX >= 0 && ballPosY >= 0;
    }

    // ===================== 跨会话记忆（剩余时间与状态） =====================

    /** 上次退出时记住的剩余毫秒；<0 表示"没有未完成的一轮"。 */
    public long getSavedRemainingMs() {
        return savedRemainingMs;
    }

    /** 上次退出时是否正在走（true=继续走，false=保持暂停）。 */
    public boolean isSavedWasRunning() {
        return savedWasRunning;
    }

    /** 记录当前这一轮（供退出时写入配置文件）。 */
    public void saveSession(long remainingMs, boolean wasRunning) {
        savedRemainingMs = Math.max(0L, remainingMs);
        savedWasRunning = wasRunning;
    }

    /** 清除记忆（归零/走完并已确认后调用）。 */
    public void clearSession() {
        savedRemainingMs = -1L;
        savedWasRunning = false;
    }

    /** 恢复全部默认（内存态；持久化由调用方决定）。 */
    public void resetToDefaults() {
        displayMode = DEFAULT_DISPLAY_MODE;
        durationSeconds = DEFAULT_DURATION_SECONDS;
        autoStartOnShow = DEFAULT_AUTO_START;
        sizeMode = DEFAULT_SIZE_MODE;
        fixedDiameter = DEFAULT_FIXED_DIAMETER;
        gapPx = DEFAULT_GAP_PX;
        followBall = DEFAULT_FOLLOW_BALL;
        showProgressRing = DEFAULT_SHOW_PROGRESS_RING;
        showHours = DEFAULT_SHOW_HOURS;
        colorRunning = DEFAULT_COLOR_RUNNING;
        colorPaused = DEFAULT_COLOR_PAUSED;
        colorWarning = DEFAULT_COLOR_WARNING;
        colorFinished = DEFAULT_COLOR_FINISHED;
        colorIdle = DEFAULT_COLOR_IDLE;
        warnSeconds = DEFAULT_WARN_SECONDS;
        focusFullscreen = DEFAULT_FOCUS_FULLSCREEN;
        focusAlwaysOnTop = DEFAULT_FOCUS_ALWAYS_ON_TOP;
        focusBackground = DEFAULT_FOCUS_BACKGROUND;
        focusTextPercent = DEFAULT_FOCUS_TEXT_PERCENT;
        focusShowResult = DEFAULT_FOCUS_SHOW_RESULT;
        focusTitle = DEFAULT_FOCUS_TITLE;
        alertFile = DEFAULT_ALERT_FILE;
        alertOnFinish = DEFAULT_ALERT_ON_FINISH;
        alertEachLastSecond = DEFAULT_ALERT_EACH_LAST_SECOND;
        alertRepeat = DEFAULT_ALERT_REPEAT;
        alertVolume = DEFAULT_ALERT_VOLUME;
        systemBeepFallback = DEFAULT_SYSTEM_BEEP_FALLBACK;
        ballPosX = -1;
        ballPosY = -1;
    }

    // ===================== 持久化 =====================

    /** 从文件加载（缺失/坏值回退默认；仅覆盖能解析的键）。 */
    public void load() {
        File f = new File(filePath);
        if (!f.exists()) {
            return;
        }
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (IOException e) {
            safeLog("倒计时插件-配置读取失败: " + e);
            return;
        }
        String dm = trim(p.getProperty(K_DISPLAY_MODE, ""));
        if ("BALL".equalsIgnoreCase(dm)) {
            displayMode = DisplayMode.BALL;
        } else if ("FOCUS".equalsIgnoreCase(dm)) {
            displayMode = DisplayMode.FOCUS;
        } else if ("BOTH".equalsIgnoreCase(dm)) {
            displayMode = DisplayMode.BOTH;
        }
        durationSeconds = readClamped(p, K_DURATION, DEFAULT_DURATION_SECONDS,
                DURATION_MIN_SECONDS, DURATION_MAX_SECONDS);
        autoStartOnShow = readBool(p, K_AUTO_START, DEFAULT_AUTO_START);
        String sm = trim(p.getProperty(K_SIZE_MODE, ""));
        if ("FIXED".equalsIgnoreCase(sm)) {
            sizeMode = SizeMode.FIXED;
        } else if ("AUTO".equalsIgnoreCase(sm)) {
            sizeMode = SizeMode.AUTO;
        }
        fixedDiameter = readClamped(p, K_FIXED_DIAMETER, DEFAULT_FIXED_DIAMETER,
                FIXED_DIAMETER_MIN, FIXED_DIAMETER_MAX);
        gapPx = readClamped(p, K_GAP, DEFAULT_GAP_PX, GAP_PX_MIN, GAP_PX_MAX);
        followBall = readBool(p, K_FOLLOW, DEFAULT_FOLLOW_BALL);
        showProgressRing = readBool(p, K_RING, DEFAULT_SHOW_PROGRESS_RING);
        showHours = readBool(p, K_SHOW_HOURS, DEFAULT_SHOW_HOURS);
        colorRunning = readColor(p, K_COLOR_RUNNING, DEFAULT_COLOR_RUNNING);
        colorPaused = readColor(p, K_COLOR_PAUSED, DEFAULT_COLOR_PAUSED);
        colorWarning = readColor(p, K_COLOR_WARNING, DEFAULT_COLOR_WARNING);
        colorFinished = readColor(p, K_COLOR_FINISHED, DEFAULT_COLOR_FINISHED);
        colorIdle = readColor(p, K_COLOR_IDLE, DEFAULT_COLOR_IDLE);
        warnSeconds = readClamped(p, K_WARN_SECONDS, DEFAULT_WARN_SECONDS,
                WARN_SECONDS_MIN, WARN_SECONDS_MAX);
        focusFullscreen = readBool(p, K_FOCUS_FULLSCREEN, DEFAULT_FOCUS_FULLSCREEN);
        focusAlwaysOnTop = readBool(p, K_FOCUS_ON_TOP, DEFAULT_FOCUS_ALWAYS_ON_TOP);
        focusBackground = readColor(p, K_FOCUS_BG, DEFAULT_FOCUS_BACKGROUND);
        focusTextPercent = readClamped(p, K_FOCUS_TEXT_PERCENT, DEFAULT_FOCUS_TEXT_PERCENT,
                FOCUS_TEXT_PERCENT_MIN, FOCUS_TEXT_PERCENT_MAX);
        focusShowResult = readBool(p, K_FOCUS_SHOW_RESULT, DEFAULT_FOCUS_SHOW_RESULT);
        focusTitle = trim(p.getProperty(K_FOCUS_TITLE, DEFAULT_FOCUS_TITLE));
        alertFile = trim(p.getProperty(K_ALERT_FILE, DEFAULT_ALERT_FILE));
        alertOnFinish = readBool(p, K_ALERT_ON_FINISH, DEFAULT_ALERT_ON_FINISH);
        alertEachLastSecond = readBool(p, K_ALERT_EACH_SECOND, DEFAULT_ALERT_EACH_LAST_SECOND);
        alertRepeat = readClamped(p, K_ALERT_REPEAT, DEFAULT_ALERT_REPEAT,
                ALERT_REPEAT_MIN, ALERT_REPEAT_MAX);
        alertVolume = readClamped(p, K_ALERT_VOLUME, DEFAULT_ALERT_VOLUME, 0, 100);
        systemBeepFallback = readBool(p, K_BEEP_FALLBACK, DEFAULT_SYSTEM_BEEP_FALLBACK);
        savedRemainingMs = readLong(p, K_REMAIN_MS, -1L);
        savedWasRunning = readBool(p, K_WAS_RUNNING, false);
        String pos = trim(p.getProperty(K_BALL_POS, ""));
        int comma = pos.indexOf(',');
        if (comma > 0) {
            try {
                int x = Integer.parseInt(pos.substring(0, comma).trim());
                int y = Integer.parseInt(pos.substring(comma + 1).trim());
                if (x >= -10000 && y >= -10000) {
                    ballPosX = x;
                    ballPosY = y;
                }
            } catch (NumberFormatException ignore) {
                // 坏值：保持无记录
            }
        }
    }

    /** 整存到插件自持配置文件（失败仅记日志，不影响运行）。 */
    public void save() {
        try {
            File f = new File(filePath);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            Properties p = new Properties();
            p.setProperty(K_DISPLAY_MODE, displayMode.name());
            p.setProperty(K_DURATION, String.valueOf(durationSeconds));
            p.setProperty(K_AUTO_START, String.valueOf(autoStartOnShow));
            p.setProperty(K_SIZE_MODE, sizeMode.name());
            p.setProperty(K_FIXED_DIAMETER, String.valueOf(fixedDiameter));
            p.setProperty(K_GAP, String.valueOf(gapPx));
            p.setProperty(K_FOLLOW, String.valueOf(followBall));
            p.setProperty(K_RING, String.valueOf(showProgressRing));
            p.setProperty(K_SHOW_HOURS, String.valueOf(showHours));
            p.setProperty(K_COLOR_RUNNING, toHex(colorRunning));
            p.setProperty(K_COLOR_PAUSED, toHex(colorPaused));
            p.setProperty(K_COLOR_WARNING, toHex(colorWarning));
            p.setProperty(K_COLOR_FINISHED, toHex(colorFinished));
            p.setProperty(K_COLOR_IDLE, toHex(colorIdle));
            p.setProperty(K_WARN_SECONDS, String.valueOf(warnSeconds));
            p.setProperty(K_FOCUS_FULLSCREEN, String.valueOf(focusFullscreen));
            p.setProperty(K_FOCUS_ON_TOP, String.valueOf(focusAlwaysOnTop));
            p.setProperty(K_FOCUS_BG, toHex(focusBackground));
            p.setProperty(K_FOCUS_TEXT_PERCENT, String.valueOf(focusTextPercent));
            p.setProperty(K_FOCUS_SHOW_RESULT, String.valueOf(focusShowResult));
            p.setProperty(K_FOCUS_TITLE, focusTitle == null ? "" : focusTitle);
            p.setProperty(K_ALERT_FILE, alertFile);
            p.setProperty(K_ALERT_ON_FINISH, String.valueOf(alertOnFinish));
            p.setProperty(K_ALERT_EACH_SECOND, String.valueOf(alertEachLastSecond));
            p.setProperty(K_ALERT_REPEAT, String.valueOf(alertRepeat));
            p.setProperty(K_ALERT_VOLUME, String.valueOf(alertVolume));
            p.setProperty(K_BEEP_FALLBACK, String.valueOf(systemBeepFallback));
            p.setProperty(K_REMAIN_MS, String.valueOf(savedRemainingMs));
            p.setProperty(K_WAS_RUNNING, String.valueOf(savedWasRunning));
            if (hasBallPos()) {
                p.setProperty(K_BALL_POS, ballPosX + "," + ballPosY);
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                p.store(out, "TimerBall plugin - countdown section");
            }
        } catch (IOException e) {
            safeLog("倒计时插件-配置保存失败: " + e);
        }
    }

    /** 配置摘要（写日志用）。 */    public String summary() {
        return "形态=" + displayMode + " 时长=" + formatDuration(durationSeconds)
                + " 尺寸=" + (isFixedSize() ? fixedDiameter + "px" : "自动")
                + " 跟随=" + followBall + " 警戒≤" + warnSeconds + "s"
                + " 色=" + toHex(colorRunning) + "/" + toHex(colorPaused) + "/"
                + toHex(colorWarning) + "/" + toHex(colorFinished) + "/" + toHex(colorIdle)
                + " 音频=" + (alertFile.isEmpty() ? "(未设置)" : alertFile)
                + " 音量=" + alertVolume + " 重复=" + alertRepeat
                + " 到时响铃=" + alertOnFinish + " 读秒=" + alertEachLastSecond
                + " 兜底蜂鸣=" + systemBeepFallback;
    }

    // ===================== 工具 =====================

    /** 秒 → mm:ss（或 h:mm:ss）；供对话框/日志/自检复用。 */
    public static String formatDuration(int totalSeconds) {
        int s = Math.max(0, totalSeconds);
        int h = s / 3600;
        int m = (s % 3600) / 60;
        int sec = s % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, sec);
        }
        return String.format("%02d:%02d", m, sec);
    }

    /** 解析 "分:秒"/"时:分:秒"/纯秒数；失败返回 -1。 */
    public static int parseDuration(String text) {
        if (text == null) {
            return -1;
        }
        String s = text.trim();
        if (s.isEmpty()) {
            return -1;
        }
        try {
            if (s.indexOf(':') < 0) {
                return Integer.parseInt(s);
            }
            String[] parts = s.split(":");
            if (parts.length == 2) {
                int m = Integer.parseInt(parts[0].trim());
                int sec = Integer.parseInt(parts[1].trim());
                return m * 60 + sec;
            }
            if (parts.length == 3) {
                int h = Integer.parseInt(parts[0].trim());
                int m = Integer.parseInt(parts[1].trim());
                int sec = Integer.parseInt(parts[2].trim());
                return h * 3600 + m * 60 + sec;
            }
        } catch (NumberFormatException ignore) {
            // 落到 -1
        }
        return -1;
    }

    private static long readLong(Properties p, String key, long def) {
        String v = trim(p.getProperty(key, ""));
        if (v.isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean readBool(Properties p, String key, boolean def) {
        String v = trim(p.getProperty(key, ""));
        if (v.isEmpty()) {
            return def;
        }
        if ("true".equalsIgnoreCase(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v)) {
            return false;
        }
        return def;
    }

    private static int readClamped(Properties p, String key, int def, int min, int max) {
        String v = trim(p.getProperty(key, ""));
        if (v.isEmpty()) {
            return def;
        }
        try {
            return clamp(Integer.parseInt(v), min, max);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Color readColor(Properties p, String key, Color def) {
        Color c = parseHexColor(p.getProperty(key, ""));
        return c == null ? def : c;
    }

    /** 十六进制解析（"2E8B57" 或 "#2E8B57"），失败返回 null。 */
    public static Color parseHexColor(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            return null;
        }
        try {
            return new Color(Integer.parseInt(s, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String toHex(Color c) {
        return String.format("%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 配置读写失败日志（不依赖宿主内部类）。 */
    private static void safeLog(String detail) {
        try {
            com.randomnamepicker.core.LogManager.log(detail, "CD_CONFIG_ERROR");
        } catch (Throwable ignore) {
            // 忽略：日志不可用不影响插件运行
        }
    }
}
