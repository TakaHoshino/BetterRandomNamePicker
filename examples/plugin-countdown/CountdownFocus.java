import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.Graphics2D;
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
public class CountdownFocus extends JWindow {

    /** 全屏专注形态交互回调（由控制器实现）。 */
    public interface Actions {
        void onExit();

        void onToggle();
    }

    private final CountdownSession session;
    private final CountdownConfig config;
    private final Actions actions;
    private final RingPanel ring = new RingPanel();
    private final JLabel statusLabel = new JLabel("就绪", SwingConstants.CENTER);
    private final JLabel hintLabel = new JLabel(
            "空格：暂停/继续    R：归零    双击或 ESC：收起", SwingConstants.CENTER);
    private final JLabel resultLabel = new JLabel(" ", SwingConstants.CENTER);

    private boolean fullscreenActive;
    private String resultText = "";

    public CountdownFocus(CountdownSession session, CountdownConfig config, Actions actions) {
        this.session = session;
        this.config = config;
        this.actions = actions;

        setFocusableWindowState(true); // 专注计时需要接收键盘（焦点由控制器在显示时请求）
        setAlwaysOnTop(config.isFocusAlwaysOnTop());
        setLayout(new GridBagLayout());
        getContentPane().setBackground(config.getFocusBackground());

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1.0;
        c.weighty = 1.0;
        c.fill = GridBagConstraints.BOTH;
        add(ring, c);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new javax.swing.BoxLayout(bottom, javax.swing.BoxLayout.Y_AXIS));
        statusLabel.setForeground(new Color(0xE8, 0xEC, 0xF1));
        statusLabel.setFont(new Font("微软雅黑", Font.BOLD, 20));
        statusLabel.setAlignmentX(CENTER_ALIGNMENT);
        resultLabel.setForeground(new Color(0xB8, 0xC2, 0xCC));
        resultLabel.setFont(new Font("微软雅黑", Font.PLAIN, 18));
        resultLabel.setAlignmentX(CENTER_ALIGNMENT);
        hintLabel.setForeground(new Color(0x7E, 0x8A, 0x96));
        hintLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        hintLabel.setAlignmentX(CENTER_ALIGNMENT);
        bottom.add(statusLabel);
        bottom.add(javax.swing.Box.createVerticalStrut(6));
        bottom.add(resultLabel);
        bottom.add(javax.swing.Box.createVerticalStrut(10));
        bottom.add(hintLabel);

        GridBagConstraints cb = new GridBagConstraints();
        cb.gridx = 0;
        cb.gridy = 1;
        cb.weightx = 1.0;
        cb.fill = GridBagConstraints.HORIZONTAL;
        cb.insets = new java.awt.Insets(0, 20, 28, 20);
        add(bottom, cb);

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

    private void installKeyAndMouse() {
        // 键绑定注册在根窗格的 WHEN_IN_FOCUSED_WINDOW 输入映射上：窗口内任意组件有焦点都生效
        // （独占全屏下不会漏掉空格/R/ESC）。
        JComponent root = getRootPane();
        bind(root, KeyEvent.VK_ESCAPE, "cd-exit", this::hideFocus);
        bind(root, KeyEvent.VK_SPACE, "cd-toggle", () -> {
            if (actions != null) {
                actions.onToggle();
            }
            refresh();
        });
        bind(root, KeyEvent.VK_R, "cd-reset", () -> {
            if (!session.isFinished()) {
                session.reset();
                refresh();
            }
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
        try {
            requestFocus();
        } catch (Throwable ignore) {
            // 抢焦点失败不致命：鼠标点击后仍可接收键盘
        }
        refresh();
    }

    /** 隐藏全屏专注窗（退出全屏，保留读数）。 */
    public void hideFocus() {
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
        leaveFullscreen();
        setVisible(false);
    }

    /** 外观变更即时应用（配色/字号/置顶/背景/结果行开关）。 */
    public void applyAppearance() {
        setAlwaysOnTop(config.isFocusAlwaysOnTop());
        getContentPane().setBackground(config.getFocusBackground());
        setResultVisible(config.isFocusShowResult());
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

            // 副标（百分比）
            String pct = Math.round(session.progress() * 100) + "%";
            g2.setFont(new Font("微软雅黑", Font.PLAIN, Math.max(12, fs / 6)));
            FontMetrics fm2 = g2.getFontMetrics();
            g2.setColor(new Color(0x9A, 0xA6, 0xB2));
            g2.drawString(pct, cx - fm2.stringWidth(pct) / 2,
                    ty + fm.getDescent() + fm2.getAscent() + 6);

            g2.dispose();
        }
    }
}
