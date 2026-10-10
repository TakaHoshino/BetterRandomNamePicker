package com.randomnamepicker.plugin;

import com.randomnamepicker.core.DataManager;
import com.randomnamepicker.core.LogManager;
import com.randomnamepicker.core.NameManager;
import com.randomnamepicker.core.PasswordManager;
import com.randomnamepicker.core.SchemeManager;
import com.randomnamepicker.mode.ModeHandler;
import com.randomnamepicker.mode.ModeHost;
import java.awt.event.ActionListener;

/**
 * 插件上下文（Stage3；插件 UI 槽一期/二期扩展；宿主插件生态一期新增事件订阅与只读查询）。
 * <p>
 * 注册类方法在插件 onLoad 期间只写入该插件的暂存区（pending），不触碰 UI / 注册表；
 * 所在 jar 全部候选 onLoad 成功后由 PluginManager 统一提交（含冲突预检），
 * 提交成功才对外可见；失败则整 jar 原子拒载并无任何残留。
 * </p>
 * <p>
 * 动作标题一律不判重：重复调用（含同名）各自独立成项。实现须线程安全
 * （onLoad 在后台线程调用）。
 * </p>
 * <p>
 * 服务访问与信任模型（如实口径）：本接口只提供 NameManager/SchemeManager/DataManager/LogManager 句柄
 * 与 ModeHost 形态的宿主；PasswordManager/ConfigManager/FloatingBall/NamePickerApp 等宿主类型属
 * “宿主内部、不承诺兼容”——它们在编译期可达、运行期可反射，只是本接口不给句柄，插件使用即脱离公开 API
 * （宿主对其升级不承担兼容义务）。getDataManager() 返回宿主主窗自持的 DataManager 实例
 * （各实例均为文件级操作、指向同一 data/ 根，插件不得自行 new DataManager）。
 * </p>
 */
public interface PluginContext {

    /** 注册一个插件模式（插件先经 getModeHost() 构造 ModeHandler 实例再注册）。 */
    void registerModeHandler(ModeHandler handler);

    /**
     * 在指定 UI 区域注册一个动作（暂存语义，提交后可见；标题不判重）。
     * <ul>
     * <li>zone 为 null：视为插件缺陷 → 抛 {@link IllegalArgumentException}，
     *     宿主将整 jar 原子拒载并记 PLUGIN_LOAD_ERROR（原因码 NULL_ZONE）；</li>
     * <li>title 为 null 或 trim 后为空、或 action 为 null：忽略；</li>
     * </ul>
     */
    void addUiAction(UiZone zone, String title, ActionListener action);

    /**
     * 在指定 UI 区域注册一个<b>带分组路径</b>的动作（宿主插件 API：菜单分组/级联子菜单的正式接口）。
     * <p>
     * {@code groupPath} 为空/null 时等价于 {@link #addUiAction(UiZone, String, ActionListener)}（平铺）；
     * 非空时宿主渲染端按路径逐级创建（或复用）级联子菜单，例如
     * {@code new String[]{"分组A", "子组B"}} → 分组A → 子组B → 命令。
     * 路径段为 null/空白会被忽略；宿主只按该字段渲染，不需要（也不应）约定任何标题分隔符。
     * </p>
     * <p>
     * 失败语义与 {@link #addUiAction} 一致：zone 为 null 抛 {@link IllegalArgumentException}
     * （宿主整 jar 拒载，原因码 NULL_ZONE）；title 空白或 action 为 null 时忽略。
     * </p>
     */
    void addGroupedUiAction(UiZone zone, String[] groupPath, String title, ActionListener action);

    /**
     * 订阅宿主事件（宿主插件生态一期）。回调在 <b>EDT</b> 逐监听器防御派发；订阅随插件卸载 /
     * 整 jar 拒载回滚 / onLoad 抛异常自动清理（归属键 = jar 文件名 + 实现类全名），
     * 插件仍应在 onUnload 显式退订（双保险）。事件类型与语义见 {@link HostEvent}。
     */
    void addHostEventListener(HostEventListener listener);

    /** 退订宿主事件（幂等）。 */
    void removeHostEventListener(HostEventListener listener);

    /** 宿主产品发布号（展示用，不参与插件兼容门控）。 */
    default String getHostVersion() {
        return HostApi.HOST_VERSION;
    }

    /** 宿主插件 API 级别（唯一兼容门控；插件可在运行期自检当前宿主级别）。 */
    default int getApiLevel() {
        return HostApi.PLUGIN_API_LEVEL;
    }

    /** 宿主当前是否处于锁定态（只读查询，宿主内部读取，插件无需依赖任何内部类）。 */
    default boolean isHostLocked() {
        return PasswordManager.isLocked();
    }

    /** 在主窗口“插件”菜单添加一项（暂存，提交后生效；标题不判重）。等价 addUiAction(UiZone.MAIN_MENU, …)。 */
    default void addMainMenuAction(String title, ActionListener action) {
        addUiAction(UiZone.MAIN_MENU, title, action);
    }

    /** 主窗“插件”菜单：带分组路径添加一项（宿主建级联子菜单；groupPath 空则平铺）。 */
    default void addMainMenuAction(String[] groupPath, String title, ActionListener action) {
        addGroupedUiAction(UiZone.MAIN_MENU, groupPath, title, action);
    }

    /** 在系统托盘菜单添加一项（暂存，提交后生效；标题不判重）。等价 addUiAction(UiZone.TRAY_MENU, …)。 */
    default void addTrayMenuAction(String title, ActionListener action) {
        addUiAction(UiZone.TRAY_MENU, title, action);
    }

    /** 托盘菜单：带分组路径添加一项（宿主建级联子菜单；groupPath 空则平铺）。 */
    default void addTrayMenuAction(String[] groupPath, String title, ActionListener action) {
        addGroupedUiAction(UiZone.TRAY_MENU, groupPath, title, action);
    }

    /** 在悬浮球右键菜单添加一项（暂存，提交后生效；标题不判重）。等价 addUiAction(UiZone.FLOATING_BALL_MENU, …)。 */
    default void addFloatingBallMenuAction(String title, ActionListener action) {
        addUiAction(UiZone.FLOATING_BALL_MENU, title, action);
    }

    /** 悬浮球右键菜单：带分组路径添加一项（宿主建级联子菜单；groupPath 空则平铺）。 */
    default void addFloatingBallMenuAction(String[] groupPath, String title, ActionListener action) {
        addGroupedUiAction(UiZone.FLOATING_BALL_MENU, groupPath, title, action);
    }

    /** 设置窗“插件”区：带分组路径添加一个按钮（一般不需要分组，保留一致性）。 */
    default void addSettingsWindowAction(String[] groupPath, String title, ActionListener action) {
        addGroupedUiAction(UiZone.SETTINGS_WINDOW, groupPath, title, action);
    }

    NameManager getNameManager();

    SchemeManager getSchemeManager();

    DataManager getDataManager();

    LogManager getLogManager();

    /** 宿主（ModeHost 接口形态；NamePickerApp 实现）。 */
    ModeHost getModeHost();

    /** 记录操作日志（等价 LogManager.log）。 */
    void log(String detail, String opCode);
}
