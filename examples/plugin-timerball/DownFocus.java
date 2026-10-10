import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingConstants;

/**
 * 全屏专注倒计时（番茄钟形态）。
 * <p>
 * 默认独占全屏（真实全屏、隐藏任务栏，失败自动回退为无边框最大窗），也可在设置中改为"全屏窗口"。
 * 深色背景 + 大号剩余时间 + 剩余比例进度环 + 状态行；可选在底部显示最近一次抽取结果
 * （来自宿主 PICK_FINISHED 事件，便于"抽取 → 讨论计时"场景）。
 * </p>
 * <p>
 * 交互：双击 / ESC / 右键 = 收起；空格 = 暂停·继续；R = 归零。全部动作委托给控制器，
 * 保证与子球形态状态同步。方法仅 EDT 调用。
 * </p>
 */
public class DownFocus extends JWindow {

    /** 全屏按钮的前景色（浅色按钮底 → 黑色图标与文字，避免"看不清"）。 */
    private static final Color BUTTON_FG = new Color(0x11, 0x14, 0x18);

    /** 全屏番茄钟标题：黑色加粗大字（底色由标题栏铺满配置背景色，不再用浅色底衬）。 */
    private static final Color TITLE_FG = new Color(0x0A, 0x0C, 0x0F);
    /** 标题字号占窗口宽度的比例（越大越醒目）。 */
    private static final double TITLE_WIDTH_RATIO = 0.052;

    /** 全屏界面所有文字统一黑色（按需求：不再使用白色字体）。 */
    private static final Color TEXT_FG = new Color(0x0A, 0x0C, 0x0F);

    /** 全屏专注形态交互回调（由控制器实现）。 */
    public interface Actions {
        void onExit();

        void onToggle();

        /** 复原：回到上次设定的倒计时起点并重新开始。 */
        void onRestore();
    }

    private final DownSession session;
    private final TimerBallConfig config;
    private final Actions actions;
    private final RingPanel ring = new RingPanel();
    private final JLabel statusLabel = new JLabel("就绪", SwingConstants.CENTER);
    private final JLabel hintLabel = new JLabel(
            "标题可直接点击编辑    空格：暂停/继续    复原：回到上次起点    双击或 ESC：收起", SwingConstants.CENTER);
    private final JLabel resultLabel = new JLabel(" ", SwingConstants.CENTER);
    /** 番茄钟顶部可即时编辑的标题。 */
    private final javax.swing.JTextField titleField = new javax.swing.JTextField();
    private final javax.swing.JButton toggleButton = new javax.swing.JButton();
    private final javax.swing.JButton restoreButton = new javax.swing.JButton();

    private boolean fullscreenActive;
    /** 全局按键兜底监听（独占全屏下窗口拿不到焦点时使用）。 */
    private java.awt.event.AWTEventListener awtKeyListener;
    private String resultText = "";

    public DownFocus(DownSession session, TimerBallConfig config, Actions actions) {
        this.session = session;
        this.config = config;
        this.actions = actions;

        setFocusableWindowState(true); // 专注计时需要接收键盘（焦点由控制器在显示时请求）
        setAlwaysOnTop(config.isFocusAlwaysOnTop());
        setLayout(new java.awt.BorderLayout());
        getContentPane().setBackground(config.getFocusBackground());

        // 顶部：标题（边框布局 NORTH 才是真正的"顶部"；此前用 GridBag 行序把标题挤到了底部）
        add(createTitleBar(), java.awt.BorderLayout.NORTH);

        // 中间：进度环 + 大字读数
        add(ring, java.awt.BorderLayout.CENTER);

        JPanel bottom = new JPanel() {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                // 与内容区完全同色：整屏统一底色，不加任何蒙版/分隔色块
                g.setColor(config.getFocusBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                super.paintComponent(g);
            }
        };
        bottom.setOpaque(false);
        bottom.setLayout(new javax.swing.BoxLayout(bottom, javax.swing.BoxLayout.Y_AXIS));
        // 全部文字统一黑色（按需求不再用白色字体）
        statusLabel.setForeground(TEXT_FG);
        statusLabel.setFont(new Font("微软雅黑", Font.BOLD, 20));
        statusLabel.setAlignmentX(CENTER_ALIGNMENT);
        resultLabel.setForeground(TEXT_FG);
        resultLabel.setFont(new Font("微软雅黑", Font.PLAIN, 18));
        resultLabel.setAlignmentX(CENTER_ALIGNMENT);
        hintLabel.setForeground(TEXT_FG);
        hintLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        hintLabel.setAlignmentX(CENTER_ALIGNMENT);
        bottom.add(javax.swing.Box.createVerticalStrut(8));
        bottom.add(statusLabel);
        bottom.add(javax.swing.Box.createVerticalStrut(6));
        bottom.add(resultLabel);
        bottom.add(javax.swing.Box.createVerticalStrut(12));
        bottom.add(createButtonRow());
        bottom.add(javax.swing.Box.createVerticalStrut(10));
        bottom.add(hintLabel);
        bottom.add(javax.swing.Box.createVerticalStrut(10));
        bottom.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 16, 0, 16));

        // 底部：状态行 + 两个图标按钮 + 操作提示（BorderLayout.SOUTH）
        add(bottom, java.awt.BorderLayout.SOUTH);

        installKeyAndMouse();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                hideFocus();
            }
        });
        setLocation(-20000, -20000);
        setSize(640, 480);
        setResultVisible(config.isFocusShowResult());
    }

    /**
     * 顶部标题栏：加粗黑色大字标题 + 右上角"✕ 退出"按钮。
     * <p>
     * 标题为无边框输入框：点击即编辑，随打随存；同时工具栏按钮/双击/右键都能退出全屏
     * （鼠标退出路径不依赖键盘焦点——独占全屏下键盘焦点并不可靠）。
     * </p>
     */
    private JPanel createTitleBar() {
        JPanel band = new JPanel(new java.awt.BorderLayout()) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                // 与内容区完全同色：整屏统一底色，不加任何蒙版/分隔色块
                g.setColor(config.getFocusBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                super.paintComponent(g);
            }

            @Override
            public java.awt.Dimension getPreferredSize() {
                // 高度随标题字号自适应：此前固定 74px 会把大号标题裁掉（TextF…）
                int textHeight = titleField.getFontMetrics(titleField.getFont()).getHeight();
                return new java.awt.Dimension(Math.max(320, getWidth()), textHeight + 24);
            }
        };
        band.setOpaque(false);

        titleField.setOpaque(false);
        titleField.setBorder(null);
        titleField.setHorizontalAlignment(SwingConstants.CENTER);
        titleField.setForeground(TITLE_FG);
        titleField.setCaretColor(TITLE_FG);
        titleField.setSelectionColor(new Color(0x9E, 0xC5, 0xFF));
        titleField.setFont(new Font("微软雅黑", Font.BOLD, 34));
        titleField.setToolTipText("点击即可编辑标题（即时生效）");
        titleField.setText(session.getTitle());
        titleField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                onTitleChanged();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                onTitleChanged();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                onTitleChanged();
            }
        });
        titleField.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // 独占全屏下键盘焦点不可靠：再给一条"点击弹窗改名"的确定路径
                if (e.getClickCount() >= 2) {
                    editTitleViaDialog();
                }
            }
        });

        JButton exitButton = new JButton("✕ 退出");
        exitButton.setFont(new Font("微软雅黑", Font.PLAIN, 16));
        exitButton.setForeground(TITLE_FG);
        exitButton.setFocusPainted(false);
        exitButton.setMargin(new java.awt.Insets(6, 14, 6, 14));
        exitButton.setToolTipText("退出全屏番茄钟（ESC / 双击 / 右键同样可退出）");
        exitButton.addActionListener(e -> hideFocus());

        JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(exitButton);

        JPanel leftSpacer = new JPanel();
        leftSpacer.setOpaque(false);
        leftSpacer.setPreferredSize(new java.awt.Dimension(170, 10));

        band.add(leftSpacer, java.awt.BorderLayout.WEST);
        band.add(titleField, java.awt.BorderLayout.CENTER);
        band.add(right, java.awt.BorderLayout.EAST);
        band.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 10, 6, 10));
        band.setPreferredSize(null); // 高度由 getPreferredSize() 随字号自适应
        titleBand = band;
        return band;
    }

    /** 标题栏（保存引用以便按字号重算高度）。 */
    private JPanel titleBand;

    /** 弹窗式改名（点击标题两次触发；也是键盘焦点不可靠时的兜底）。 */
    private void editTitleViaDialog() {
        try {
            String current = session.getTitle();
            Object result = javax.swing.JOptionPane.showInputDialog(this,
                    "番茄钟标题：", "编辑标题", javax.swing.JOptionPane.PLAIN_MESSAGE,
                    null, null, current);
            if (result != null) {
                String text = result.toString();
                titleField.setText(text); // DocumentListener 会写回会话与配置
                onTitleChanged();
            }
            try {
                requestFocus();
            } catch (Throwable ignore) {
                // 忽略
            }
        } catch (Throwable t) {
            LogQuiet("计时子球-编辑标题失败: " + t);
        }
    }

    private static void LogQuiet(String detail) {
        try {
            com.randomnamepicker.core.LogManager.log(detail, "PLUGIN_LOAD_ERROR");
        } catch (Throwable ignore) {
            // 忽略
        }
    }

    private boolean titleEditing() {
        return titleField != null && titleField.isFocusOwner();
    }

    /** 标题输入即时生效：写回会话与插件配置（下次打开仍是这个标题）。 */
    private void onTitleChanged() {
        String text = titleField.getText() == null ? "" : titleField.getText();
        session.setTitle(text);
        try {
            config.setFocusTitle(text);
            config.save();
        } catch (Throwable ignore) {
            // 保存失败不影响编辑
        }
    }

    /** 标题字号随窗口宽度自适应（独占全屏时更大更醒目；仍为加粗黑色），并同步标题栏高度。 */
    private void applyTitleFont() {
        int w = Math.max(320, getWidth() <= 1 ? 1280 : getWidth());
        int size = Math.max(24, Math.min(96, (int) Math.round(w * TITLE_WIDTH_RATIO)));
        titleField.setFont(new Font("微软雅黑", Font.BOLD, size));
        // 字号变化后标题栏高度必须跟着变，否则大号标题被裁切
        if (titleBand != null) {
            titleBand.setPreferredSize(null);
            titleBand.revalidate();
            titleBand.repaint();
            revalidate();
        }
    }

    /** 请求标题输入框获得焦点（由控制器在显示后调用，便于"打开即可改名"）。 */
    public void focusTitle() {
        try {
            titleField.requestFocusInWindow();
            titleField.selectAll();
        } catch (Throwable ignore) {
            // 抢焦点失败不致命
        }
    }

    /**
     * 全屏底部的两个操作按钮（自带 Java2D 自绘图标）：
     * 「暂停/继续」与「复原（回到上次设定的倒计时起点，并重新开始）」。
     * <p>按钮为浅色底，因此图标与文字统一用<b>黑色</b>，保证在深色全屏上清晰可读。</p>
     */
    private JPanel createButtonRow() {
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 24, 0));
        row.setOpaque(false);

        Color fg = BUTTON_FG;
        toggleButton.setIcon(TimerIcons.pause(fg, 20));
        toggleButton.setText("暂停");
        toggleButton.setFont(new Font("微软雅黑", Font.PLAIN, 15));
        toggleButton.setForeground(fg);
        toggleButton.setFocusPainted(false);
        toggleButton.setIconTextGap(8);
        toggleButton.setMargin(new java.awt.Insets(6, 18, 6, 18));
        toggleButton.setToolTipText("暂停 / 继续（空格）");
        toggleButton.addActionListener(e -> {
            if (actions != null) {
                actions.onToggle();
            }
            refresh();
        });

        restoreButton.setIcon(TimerIcons.restore(fg, 20));
        restoreButton.setText("复原");
        restoreButton.setFont(new Font("微软雅黑", Font.PLAIN, 15));
        restoreButton.setForeground(fg);
        restoreButton.setFocusPainted(false);
        restoreButton.setIconTextGap(8);
        restoreButton.setMargin(new java.awt.Insets(6, 18, 6, 18));
        restoreButton.setToolTipText("复原到上次设定的倒计时起点（"
                + TimerBallConfig.formatDuration(session.getLastStartSeconds()) + "）并重新开始");
        restoreButton.addActionListener(e -> {
            if (actions != null) {
                actions.onRestore();
            }
            refresh();
        });

        row.add(toggleButton);
        row.add(restoreButton);
        return row;
    }

    /** 按钮图标/文案随状态刷新（运行中显示"暂停+暂停图标"，否则显示"继续+播放图标"）。 */
    private void refreshButtons() {
        Color fg = BUTTON_FG;
        if (session.isRunning()) {
            toggleButton.setIcon(TimerIcons.pause(fg, 20));
            toggleButton.setText("暂停");
        } else {
            toggleButton.setIcon(TimerIcons.play(fg, 20));
            toggleButton.setText("继续");
        }
        restoreButton.setToolTipText("复原到上次设定的倒计时起点（"
                + TimerBallConfig.formatDuration(session.getLastStartSeconds()) + "）并重新开始");
    }

    private void installKeyAndMouse() {
        // 键绑定注册在根窗格的 WHEN_IN_FOCUSED_WINDOW 输入映射上：窗口内任意组件有焦点都生效
        // （独占全屏下不会漏掉空格/R/ESC）。
        JComponent root = getRootPane();
        bind(root, KeyEvent.VK_ESCAPE, "cd-exit", this::hideFocus);
        bind(root, KeyEvent.VK_SPACE, "cd-toggle", () -> {
            if (titleEditing()) {
                return; // 正在编辑标题：空格是打字，不当作暂停/继续
            }
            if (actions != null) {
                actions.onToggle();
            }
            refresh();
        });
        bind(root, KeyEvent.VK_R, "cd-restore", () -> {
            if (titleEditing()) {
                return; // 正在编辑标题：R 是打字
            }
            // R = 复原（回到上次设定的起点并重新开始），与底部"复原"按钮同一命令
            if (actions != null) {
                actions.onRestore();
            }
            refresh();
        });

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2 || javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    hideFocus();
                }
            }
        };
        ring.addMouseListener(mouse);
        getContentPane().addMouseListener(mouse);
    }

    private static void bind(JComponent target, int keyCode, String name, Runnable action) {
        target.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke(keyCode, 0), name);
        target.getActionMap().put(name, new javax.swing.AbstractAction(name) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
    }

    // ===================== 显示 / 隐藏 =====================

    /** 显示全屏专注窗（按配置进入独占全屏或全屏窗口）。 */
    public void showFocus() {
        applyAppearance();
        if (config.isFocusFullscreen()) {
            tryEnterFullscreen();
        } else {
            leaveFullscreen();
            applyFullscreenBounds();
        }
        setVisible(true);
        toFront();
        installGlobalKeyFallback();
        grabKeyboardFocus();
        applyTitleFont();
        refresh();
        // 全屏切换后窗口尺寸/可聚焦状态才最终确定：稍后再自适应字号并"重试抢键盘焦点"
        javax.swing.Timer settle = new javax.swing.Timer(300, e -> {
            ((javax.swing.Timer) e.getSource()).stop();
            applyTitleFont();
            revalidate();
            repaint();
            grabKeyboardFocus();
        });
        settle.setRepeats(false);
        settle.start();
    }

    /**
     * 抢键盘焦点（重要）。
     * <p>
     * 实测：{@link JWindow} 在进入<b>独占全屏</b>后 {@code isFocusableWindow()} 为 false、键盘焦点为 null
     * （表现为"标题打不了字、ESC/空格无反应"）。这里在窗口已显示后再显式设置可聚焦状态并请求焦点，
     * 且与 {@link #installGlobalKeyFallback()} 的全局按键监听形成双保险。
     * </p>
     */
    public void grabKeyboardFocus() {
        try {
            if (!isFocusableWindow()) {
                setFocusableWindowState(true);
            }
            setAutoRequestFocus(true);
            toFront();
            requestFocus();
            titleField.requestFocusInWindow();
        } catch (Throwable ignore) {
            // 抢不到焦点时仍可：① 双击标题弹窗改名；② 鼠标退出（✕/双击/右键）；③ 全局按键兜底
        }
    }

    /**
     * 全局按键兜底：独占全屏下 JWindow 可能永远拿不到焦点，此时根窗格键绑定收不到按键。
     * 这里安装一个全局 AWT 监听，只处理"番茄钟可见期间"的 ESC/空格/R，且在<b>编辑标题时让位</b>；
     * 窗口隐藏即卸载监听（不打扰宿主其它输入）。
     */
    private void installGlobalKeyFallback() {
        if (awtKeyListener != null) {
            return;
        }
        awtKeyListener = ev -> {
            if (!(ev instanceof java.awt.event.KeyEvent) || !isVisible()) {
                return;
            }
            java.awt.event.KeyEvent ke = (java.awt.event.KeyEvent) ev;
            if (ke.getID() != java.awt.event.KeyEvent.KEY_PRESSED) {
                return;
            }
            if (titleEditing() || ke.isControlDown() || ke.isAltDown() || ke.isMetaDown()) {
                return; // 正在打字或组合键：不拦截
            }
            switch (ke.getKeyCode()) {
                case java.awt.event.KeyEvent.VK_ESCAPE:
                    hideFocus();
                    break;
                case java.awt.event.KeyEvent.VK_SPACE:
                    if (actions != null) {
                        actions.onToggle();
                    }
                    refresh();
                    break;
                case java.awt.event.KeyEvent.VK_R:
                    if (actions != null) {
                        actions.onRestore();
                    }
                    refresh();
                    break;
                default:
                    break;
            }
        };
        try {
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(awtKeyListener,
                    java.awt.AWTEvent.KEY_EVENT_MASK);
        } catch (Throwable t) {
            awtKeyListener = null;
        }
    }

    private void uninstallGlobalKeyFallback() {
        if (awtKeyListener == null) {
            return;
        }
        try {
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(awtKeyListener);
        } catch (Throwable ignore) {
            // 忽略
        }
        awtKeyListener = null;
    }

    /** 隐藏全屏专注窗（退出全屏，保留读数）。 */
    public void hideFocus() {
        uninstallGlobalKeyFallback();
        leaveFullscreen();
        setVisible(false);
        if (actions != null) {
            actions.onExit();
        }
    }

    /**
     * 控制器在退出路径上调用：直接隐藏并退出全屏，不回抛 onExit，避免递归。
     */
    public void hideQuiet() {
        uninstallGlobalKeyFallback();
        leaveFullscreen();
        setVisible(false);
    }

    /** 外观变更即时应用（配色/字号/置顶/背景/结果行开关）。 */
    public void applyAppearance() {
        setAlwaysOnTop(config.isFocusAlwaysOnTop());
        getContentPane().setBackground(config.getFocusBackground());
        setResultVisible(config.isFocusShowResult());
        // 标题：以会话为唯一真值（用户可能已在设置里改过配置）
        if (!titleField.getText().equals(session.getTitle())) {
            titleField.setText(session.getTitle());
        }
        applyTitleFont();
        if (isVisible() && config.isFocusFullscreen() && !fullscreenActive) {
            tryEnterFullscreen();
        }
        if (isVisible() && !config.isFocusFullscreen() && fullscreenActive) {
            leaveFullscreen();
            applyFullscreenBounds();
        }
        ring.revalidate();
        ring.repaint();
    }

    private void setResultVisible(boolean visible) {
        resultLabel.setVisible(visible);
        if (!visible) {
            resultText = "";
        }
    }

    /** 显示最近一次抽取结果（宿主 PICK_FINISHED 事件）。 */
    public void setResultText(String text) {
        resultText = text == null ? "" : text;
        resultLabel.setText(resultText.isEmpty() ? " " : "最近抽取：" + resultText);
    }

    private boolean tryEnterFullscreen() {
        try {
            GraphicsDevice dev = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice();
            if (dev != null && dev.isFullScreenSupported()) {
                setVisible(true);
                dev.setFullScreenWindow(this);
                fullscreenActive = true;
                return true;
            }
        } catch (Throwable ignore) {
            // 回退到"全屏窗口"
        }
        fullscreenActive = false;
        applyFullscreenBounds();
        return false;
    }

    private void leaveFullscreen() {
        if (!fullscreenActive) {
            return;
        }
        try {
            GraphicsDevice dev = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice();
            if (dev != null && dev.getFullScreenWindow() == this) {
                dev.setFullScreenWindow(null);
            }
        } catch (Throwable ignore) {
            // 忽略
        }
        fullscreenActive = false;
    }

    /** 无独占全屏权限时的回退：覆盖主屏可视线并取消装饰。 */
    private void applyFullscreenBounds() {
        try {
            java.awt.Rectangle b = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
            setBounds(b);
        } catch (Throwable t) {
            Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
            setBounds(0, 0, d.width, d.height);
        }
    }

    /** 刷新读数与状态行。 */
    public void refresh() {
        if (session.isFinished()) {
            statusLabel.setText("时间到");
        } else if (session.isPaused()) {
            statusLabel.setText("已暂停");
        } else if (session.isRunning()) {
            statusLabel.setText(session.isWarning(config.getWarnSeconds()) ? "最后冲刺" : "专注中");
        } else {
            statusLabel.setText("就绪（空格开始）");
        }
        try {
            refreshButtons();
        } catch (Throwable ignore) {
            // 图标刷新失败不影响计时
        }
        ring.repaint();
    }

    /** 颜色按状态（运行/警戒/暂停/到时/就绪）。 */
    private Color stateColor() {
        if (session.isFinished()) {
            return config.getColorFinished();
        }
        if (session.isPaused()) {
            return config.getColorPaused();
        }
        if (session.isRunning()) {
            return session.isWarning(config.getWarnSeconds())
                    ? config.getColorWarning() : config.getColorRunning();
        }
        return config.getColorIdle();
    }

    /** 全屏内容面板：进度环 + 大号读数。 */
    private class RingPanel extends JPanel {

        RingPanel() {
            setOpaque(false);
            setBackground(new Color(0, 0, 0, 0));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int d = (int) (Math.min(w, h) * 0.70);
            if (d < 80) {
                d = Math.max(80, Math.min(w, h) - 20);
            }
            int cx = w / 2;
            int cy = h / 2;
            int x = cx - d / 2;
            int y = cy - d / 2;
            Color base = stateColor();

            // 轨道
            g2.setColor(new Color(255, 255, 255, 26));
            g2.setStroke(new BasicStroke(Math.max(6f, d / 34f), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g2.drawOval(x, y, d, d);

            // 已用进度（12 点顺时针增长）
            double used = session.progress();
            if (used > 0.0001) {
                g2.setColor(base);
                g2.setStroke(new BasicStroke(Math.max(6f, d / 34f), BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                g2.draw(new java.awt.geom.Arc2D.Double(x, y, d, d, 90, -360.0 * used,
                        java.awt.geom.Arc2D.OPEN));
            } else {
                g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 160));
                g2.setStroke(new BasicStroke(Math.max(6f, d / 34f), BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                g2.drawOval(x, y, d, d);
            }

            // 大号读数
            String txt = session.displayText(config.isShowHours(),
                    session.isRunning() || session.isPaused());
            int shortEdge = Math.min(w, h);
            int fs = Math.max(18, (int) (shortEdge * config.getFocusTextPercent() / 100.0));
            Font font = new Font("微软雅黑", Font.BOLD, fs);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics(font);
            int maxWidth = Math.max(40, (int) (d * 0.86));
            while (fs > 12 && fm.stringWidth(txt) > maxWidth) {
                fs--;
                font = new Font("微软雅黑", Font.BOLD, fs);
                g2.setFont(font);
                fm = g2.getFontMetrics(font);
            }
            g2.setColor(base.brighter());
            int tw = fm.stringWidth(txt);
            int tx = cx - tw / 2;
            int ty = cy - (fm.getAscent() + fm.getDescent()) / 2 + fm.getAscent();
            g2.drawString(txt, tx, ty);

            // 副标（百分比）——按需求统一黑色
            String pct = Math.round(session.progress() * 100) + "%";
            g2.setFont(new Font("微软雅黑", Font.PLAIN, Math.max(12, fs / 6)));
            FontMetrics fm2 = g2.getFontMetrics();
            g2.setColor(TEXT_FG);
            g2.drawString(pct, cx - fm2.stringWidth(pct) / 2,
                    ty + fm.getDescent() + fm2.getAscent() + 6);

            g2.dispose();
        }
    }
}
