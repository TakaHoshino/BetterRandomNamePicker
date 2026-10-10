import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;

/**
 * "计时子球控制面板"（计时子球插件主入口窗口）。
 * <p>
 * 右键菜单/主窗菜单/托盘菜单里的「计时子球 ▸ 控制面板…」打开本窗口：一屏内同时管理
 * <b>正计时子球</b>与<b>倒计时子球</b>（显示/收起、开始暂停、归零、快捷时长、全屏专注），
 * 并实时显示两颗子球的当前读数与可见状态——避免把十几个命令全部平铺进悬浮球右键菜单。
 * </p>
 * <p>
 * 面板通过注入的回调操作控制器（不在本类里持有插件内部状态），只在 EDT 使用；
 * 打开期间用 400ms 的 Swing Timer 刷新读数，关闭即停止。
 * </p>
 */
public class TimerControlPanel extends JDialog {

    /** 控制面板需要的宿主侧操作（由插件入口实现，内部转调两个控制器）。 */
    public interface Host {
        boolean isUpVisible();

        String upStateText();

        void toggleUpShowHide();

        void toggleUpRun();

        void resetUp();

        boolean isDownVisible();

        String downReadout();

        String downStateText();

        void toggleDownShowHide();

        void toggleDownRun();

        void resetDown();

        void showDownFocus();

        void setDownDuration(int minutes);

        void openUpSettings();

        void openDownSettings();
    }

    private static final int REFRESH_MS = 400;

    private final Host host;
    private final JLabel upStateLabel = new JLabel(" ");
    private final JLabel downReadoutLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel downStateLabel = new JLabel(" ");
    private final javax.swing.JCheckBox showUpCheck = new javax.swing.JCheckBox("显示正计时子球");
    private final javax.swing.JCheckBox showDownCheck = new javax.swing.JCheckBox("显示倒计时子球（自动避让，不重叠）");
    private final Timer refreshTimer = new Timer(REFRESH_MS, e -> refresh());
    private boolean syncing;

    public TimerControlPanel(Window owner, Host host) {
        super(owner, "计时子球 · 控制面板", ModalityType.MODELESS);
        this.host = host;
        initialize();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopRefresh();
            }
        });
        refresh();
        refreshTimer.start();
    }

    private void initialize() {
        setLayout(new BorderLayout(10, 10));
        setSize(430, 520);
        setMinimumSize(new Dimension(400, 460));
        setLocationRelativeTo(getOwner());

        JPanel main = new JPanel();
        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
        main.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        main.add(createUpPanel());
        main.add(Box.createVerticalStrut(8));
        main.add(createDownPanel());
        main.add(Box.createVerticalStrut(8));
        main.add(createFooterPanel());

        add(main, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        JButton settingsUp = new JButton("正计时设置…");
        settingsUp.addActionListener(e -> host.openUpSettings());
        JButton settingsDown = new JButton("倒计时设置…");
        settingsDown.addActionListener(e -> host.openDownSettings());
        JButton close = new JButton("关闭");
        close.addActionListener(e -> {
            stopRefresh();
            dispose();
        });
        bottom.add(settingsUp);
        bottom.add(settingsDown);
        bottom.add(close);
        add(bottom, BorderLayout.SOUTH);
    }

    private JPanel titledPanel(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new TitledBorder(BorderFactory.createLineBorder(Color.GRAY, 1), title,
                TitledBorder.LEFT, TitledBorder.TOP, new Font("微软雅黑", Font.BOLD, 13)));
        return p;
    }

    private JPanel createUpPanel() {
        JPanel panel = titledPanel("正计时子球（左键单击宿主悬浮球 = 冒出/收回）");
        showUpCheck.setFont(font13());
        showUpCheck.addActionListener(e -> {
            if (!syncing) {
                host.toggleUpShowHide();
                refresh();
            }
        });
        panel.add(showUpCheck);

        upStateLabel.setFont(font13());
        upStateLabel.setForeground(new Color(0x33, 0x55, 0x88));
        panel.add(upStateLabel);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.add(button("开始/暂停", e -> {
            host.toggleUpRun();
            refresh();
        }));
        row.add(button("归零", e -> {
            host.resetUp();
            refresh();
        }));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        panel.add(row);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        return panel;
    }

    private JPanel createDownPanel() {
        JPanel panel = titledPanel("倒计时子球（番茄钟；仅右键菜单操作）");
        showDownCheck.setFont(font13());
        showDownCheck.addActionListener(e -> {
            if (!syncing) {
                host.toggleDownShowHide();
                refresh();
            }
        });
        panel.add(showDownCheck);

        downReadoutLabel.setFont(new Font("微软雅黑", Font.BOLD, 30));
        downReadoutLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(downReadoutLabel);
        downStateLabel.setFont(font12());
        downStateLabel.setForeground(new Color(0x33, 0x55, 0x88));
        downStateLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(downStateLabel);

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row1.add(button("开始/暂停", e -> {
            host.toggleDownRun();
            refresh();
        }));
        row1.add(button("归零", e -> {
            host.resetDown();
            refresh();
        }));
        row1.add(button("全屏专注", e -> {
            host.showDownFocus();
            refresh();
        }));
        row1.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        panel.add(row1);

        JPanel presetRow = new JPanel(new GridLayout(0, 4, 6, 4));
        for (int m : TimerBallConfig.PRESET_MINUTES) {
            JButton b = button(m + " 分", e -> {
                host.setDownDuration(m);
                refresh();
            });
            presetRow.add(b);
        }
        presetRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        panel.add(presetRow);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row2.add(button("恢复默认时长 5 分", e -> {
            host.setDownDuration(5);
            refresh();
        }));
        row2.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        panel.add(row2);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 280));
        return panel;
    }

    private JPanel createFooterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel tip1 = new JLabel("提示：倒计时不使用左键单击（左键仅给正计时子球），全部命令走右键菜单。");
        tip1.setFont(font11());
        tip1.setForeground(Color.GRAY);
        JLabel tip2 = new JLabel("提示：两颗子球同时显示时自动分列；用鼠标可拖动任一子球改变位置。");
        tip2.setFont(font11());
        tip2.setForeground(Color.GRAY);
        JLabel tip3 = new JLabel("警告色/提醒音频/尺寸等常规设置：见下方两个“设置…”按钮。");
        tip3.setFont(font11());
        tip3.setForeground(Color.GRAY);
        panel.add(tip1);
        panel.add(tip2);
        panel.add(tip3);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        return panel;
    }

    private JButton button(String text, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.setFont(font12());
        b.setMargin(new java.awt.Insets(2, 8, 2, 8));
        b.addActionListener(l);
        return b;
    }

    private Font font13() {
        return new Font("微软雅黑", Font.PLAIN, 13);
    }

    private Font font12() {
        return new Font("微软雅黑", Font.PLAIN, 12);
    }

    private Font font11() {
        return new Font("微软雅黑", Font.PLAIN, 11);
    }

    /** 刷新面板读数与勾选状态（不触发动作回调）。 */
    private void refresh() {
        syncing = true;
        try {
            showUpCheck.setSelected(host.isUpVisible());
            upStateLabel.setText("　当前：" + host.upStateText());

            showDownCheck.setSelected(host.isDownVisible());
            downReadoutLabel.setText(host.downReadout());
            downStateLabel.setText(host.downStateText());
        } finally {
            syncing = false;
        }
    }

    private void stopRefresh() {
        if (refreshTimer.isRunning()) {
            refreshTimer.stop();
        }
    }

    @Override
    public void dispose() {
        stopRefresh();
        super.dispose();
    }
}
