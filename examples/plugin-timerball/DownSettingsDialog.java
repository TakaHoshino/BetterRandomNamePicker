import com.randomnamepicker.core.LogManager;
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
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * "倒计时设置"对话框（由设置窗"插件"区"倒计时插件"按钮打开；模态）。
 * <p>
 * 覆盖常规设置：时长与预设、显示形态、子球外观（尺寸/间距/进度环/时刻格式/配色含<b>警戒色</b>）、
 * 全屏专注外观（全屏方式/置顶/背景/字号/结果行）、<b>提醒音频</b>（自定义音频文件/试听/校验/音量/重复/
 * 到时不响铃/最后读秒/系统提示音兜底）、交互（显示即开始/跟随悬浮球），以及恢复默认。
 * </p>
 * <p>
 * 任一项改动 → 写回 {@link TimerBallConfig} → 立即持久化（{@code data/countdown_plugin.properties}）
 * → 通过注入的 {@code applyChanges} 即时应用到已显示的子球/全屏窗。全部操作在 EDT。
 * </p>
 */
public class DownSettingsDialog extends JDialog {

    private final TimerBallConfig cfg;
    private final DownController controller;
    private final Runnable applyChanges;

    // 时长
    private final JLabel currentDurationLabel = new JLabel();
    private final JTextField durationField = new JTextField(8);

    // 形态
    private final JRadioButton ballRadio = new JRadioButton("倒计时子球（贴着宿主悬浮球）");
    private final JRadioButton focusRadio = new JRadioButton("全屏专注（番茄钟）");
    private final JRadioButton bothRadio = new JRadioButton("两者同时（读数同步）");

    // 子球外观
    private final JRadioButton autoSizeRadio = new JRadioButton("随悬浮球自动（直径×0.55，夹 36–60px）");
    private final JRadioButton fixedSizeRadio = new JRadioButton("固定直径：");
    private final JSlider fixedSlider = new JSlider(SwingConstants.HORIZONTAL,
            TimerBallConfig.FIXED_DIAMETER_MIN, TimerBallConfig.FIXED_DIAMETER_MAX,
            TimerBallConfig.DEFAULT_FIXED_DIAMETER);
    private final JLabel fixedValueLabel = new JLabel();
    private final JSlider gapSlider = new JSlider(SwingConstants.HORIZONTAL,
            TimerBallConfig.GAP_PX_MIN, TimerBallConfig.GAP_PX_MAX, TimerBallConfig.DEFAULT_GAP_PX);
    private final JLabel gapValueLabel = new JLabel();
    /** 间距行的标签与说明（跟随关闭时置灰/隐藏，避免出现无意义的可调项）。 */
    private JLabel gapLabel;
    private JLabel gapHintLabel;
    private final JCheckBox ringCheck = new JCheckBox("显示剩余比例进度环");
    private final JCheckBox hoursCheck = new JCheckBox("始终显示小时（h:mm:ss）");
    private final JButton colorIdleBtn = new JButton();
    private final JButton colorRunningBtn = new JButton();
    private final JButton colorPausedBtn = new JButton();
    private final JButton colorWarningBtn = new JButton();
    private final JButton colorFinishedBtn = new JButton();
    private final JSpinner warnSpinner = new JSpinner(new SpinnerNumberModel(
            TimerBallConfig.DEFAULT_WARN_SECONDS, TimerBallConfig.WARN_SECONDS_MIN,
            TimerBallConfig.WARN_SECONDS_MAX, 5));

    // 全屏专注
    private final JCheckBox fullscreenCheck = new JCheckBox("独占全屏（真实全屏，隐藏任务栏；失败自动回退）");
    private final JCheckBox onTopCheck = new JCheckBox("窗口置顶");
    private final JButton focusBgBtn = new JButton();
    private final JSlider focusTextSlider = new JSlider(SwingConstants.HORIZONTAL,
            TimerBallConfig.FOCUS_TEXT_PERCENT_MIN, TimerBallConfig.FOCUS_TEXT_PERCENT_MAX,
            TimerBallConfig.DEFAULT_FOCUS_TEXT_PERCENT);
    private final JLabel focusTextValueLabel = new JLabel();
    private final JCheckBox focusResultCheck = new JCheckBox("全屏底部显示最近一次抽取结果");

    // 提醒音频
    private final JTextField alertFileField = new JTextField(22);
    private final JCheckBox alertFinishCheck = new JCheckBox("倒计时到点时播放提醒音频");
    private final JCheckBox alertEachSecondCheck = new JCheckBox("最后 10 秒每秒提醒（读秒）");
    private final JCheckBox beepFallbackCheck = new JCheckBox("自定义音频不可用时回退系统提示音");
    private final JSpinner repeatSpinner = new JSpinner(new SpinnerNumberModel(
            TimerBallConfig.DEFAULT_ALERT_REPEAT, TimerBallConfig.ALERT_REPEAT_MIN,
            TimerBallConfig.ALERT_REPEAT_MAX, 1));
    private final JSlider volumeSlider = new JSlider(SwingConstants.HORIZONTAL, 0, 100,
            TimerBallConfig.DEFAULT_ALERT_VOLUME);
    private final JLabel volumeValueLabel = new JLabel();
    private final JLabel alertStateLabel = new JLabel(" ");

    // 交互
    private final JCheckBox autoStartCheck = new JCheckBox("显示子球/全屏时自动开始或继续计时");
    private final JCheckBox followBallCheck = new JCheckBox("子球跟随宿主悬浮球（取消则用拖动位置）");

    private boolean dirty = false;

    public DownSettingsDialog(Window owner, TimerBallConfig cfg,
                                   DownController controller, Runnable applyChanges) {
        super(owner, "计时子球 · 倒计时设置", ModalityType.APPLICATION_MODAL);
        this.cfg = cfg;
        this.controller = controller;
        this.applyChanges = applyChanges;
        initialize();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onClose();
            }
        });
    }

    private void initialize() {
        setLayout(new BorderLayout(10, 10));
        setSize(560, 640);
        setMinimumSize(new Dimension(520, 480));
        setLocationRelativeTo(getOwner());

        JPanel main = new JPanel();
        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
        main.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        main.add(createDurationPanel());
        main.add(Box.createVerticalStrut(8));
        main.add(createModePanel());
        main.add(Box.createVerticalStrut(8));
        main.add(createBallPanel());
        main.add(Box.createVerticalStrut(8));
        main.add(createColorPanel());
        main.add(Box.createVerticalStrut(8));
        main.add(createFocusPanel());
        main.add(Box.createVerticalStrut(8));
        main.add(createAlertPanel());
        main.add(Box.createVerticalStrut(8));
        main.add(createInteractionPanel());

        JScrollPane scroll = new JScrollPane(main);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        JButton resetButton = new JButton("恢复默认");
        resetButton.addActionListener(e -> onResetDefaults());
        JButton doneButton = new JButton("完成");
        doneButton.addActionListener(e -> {
            onClose();
            dispose();
        });
        bottom.add(resetButton);
        bottom.add(doneButton);
        add(bottom, BorderLayout.SOUTH);

        refreshControlsFromConfig();
    }

    // ===================== 面板 =====================

    private JPanel titledPanel(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new TitledBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1), title,
                TitledBorder.LEFT, TitledBorder.TOP, new Font("微软雅黑", Font.BOLD, 13)));
        return p;
    }

    private JPanel createDurationPanel() {
        JPanel panel = titledPanel("倒计时时长");
        JPanel presetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JLabel lab = new JLabel("预设：");
        lab.setFont(font13());
        presetRow.add(lab);
        for (int m : TimerBallConfig.PRESET_MINUTES) {
            JButton b = new JButton(m + " 分");
            b.setFont(font12());
            b.setMargin(new java.awt.Insets(1, 6, 1, 6));
            b.addActionListener(e -> {
                cfg.setDurationSeconds(m * 60);
                cfg.save();
                dirty = true;
                currentDurationLabel.setText("当前设定：" + TimerBallConfig.formatDuration(cfg.getDurationSeconds()));
                durationField.setText(TimerBallConfig.formatDuration(cfg.getDurationSeconds()));
                if (applyChanges != null) {
                    applyChanges.run();
                }
            });
            presetRow.add(b);
        }
        panel.add(presetRow);
        panel.add(Box.createVerticalStrut(4));

        JPanel customRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JLabel lab2 = new JLabel("自定义（mm:ss 或 mm:ss 以上、也可填秒数）：");
        lab2.setFont(font13());
        durationField.setFont(font12());
        durationField.setToolTipText("例如 25:00、1:30:00、90（秒）");
        JButton applyDuration = new JButton("应用");
        applyDuration.setFont(font12());
        applyDuration.addActionListener(e -> onApplyDurationField());
        durationField.addActionListener(e -> onApplyDurationField());
        customRow.add(lab2);
        customRow.add(durationField);
        customRow.add(applyDuration);
        panel.add(customRow);

        currentDurationLabel.setFont(font12());
        panel.add(currentDurationLabel);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        return panel;
    }

    private JPanel createModePanel() {
        JPanel panel = titledPanel("显示形态");
        ButtonGroup group = new ButtonGroup();
        group.add(ballRadio);
        group.add(focusRadio);
        group.add(bothRadio);
        for (JRadioButton r : new JRadioButton[]{ballRadio, focusRadio, bothRadio}) {
            r.setFont(font13());
            r.addActionListener(e -> commit());
            panel.add(r);
        }
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        return panel;
    }

    private JPanel createBallPanel() {
        JPanel panel = titledPanel("倒计时子球外观");
        ButtonGroup group = new ButtonGroup();
        group.add(autoSizeRadio);
        group.add(fixedSizeRadio);
        autoSizeRadio.setFont(font13());
        autoSizeRadio.addActionListener(e -> commit());
        fixedSizeRadio.setFont(font13());
        fixedSizeRadio.addActionListener(e -> commit());
        panel.add(autoSizeRadio);

        JPanel fixedRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        fixedRow.add(fixedSizeRadio);
        styleSlider(fixedSlider, 50);
        fixedSlider.addChangeListener(e -> {
            fixedValueLabel.setText(fixedSlider.getValue() + " px");
            commit();
        });
        fixedValueLabel.setFont(font12());
        fixedValueLabel.setPreferredSize(new Dimension(70, 20));
        fixedRow.add(fixedSlider);
        fixedRow.add(fixedValueLabel);
        fixedRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        panel.add(fixedRow);

        JPanel gapRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        gapLabel = new JLabel("与悬浮球间距：");
        gapLabel.setFont(font13());
        gapLabel.setPreferredSize(new Dimension(110, 24));
        styleSlider(gapSlider, 20);
        gapSlider.addChangeListener(e -> {
            gapValueLabel.setText(gapSlider.getValue() + " px");
            commit();
        });
        gapValueLabel.setFont(font12());
        gapValueLabel.setPreferredSize(new Dimension(70, 20));
        gapRow.add(gapLabel);
        gapRow.add(gapSlider);
        gapRow.add(gapValueLabel);
        gapRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        panel.add(gapRow);
        // 间距只在"跟随悬浮球"时生效：未跟随时禁用并说明，避免出现无意义选项
        gapHintLabel = new JLabel("　（仅「跟随宿主悬浮球」开启时生效）");
        gapHintLabel.setFont(font11());
        gapHintLabel.setForeground(Color.GRAY);
        panel.add(gapHintLabel);

        ringCheck.setFont(font13());
        ringCheck.addActionListener(e -> commit());
        hoursCheck.setFont(font13());
        hoursCheck.addActionListener(e -> commit());
        panel.add(ringCheck);
        panel.add(hoursCheck);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 250));
        return panel;
    }

    private JPanel createColorPanel() {
        JPanel panel = titledPanel("配色（就绪 / 运行 / 暂停 / 警戒 / 到时）");
        panel.add(colorRow("就绪色：", colorIdleBtn, "就绪"));
        panel.add(colorRow("运行色：", colorRunningBtn, "运行"));
        panel.add(colorRow("暂停色：", colorPausedBtn, "暂停"));
        panel.add(colorRow("警戒色：", colorWarningBtn, "警戒"));
        panel.add(colorRow("到时色：", colorFinishedBtn, "到时"));

        JPanel warnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JLabel warnLab = new JLabel("剩余时间 ≤");
        warnLab.setFont(font13());
        warnSpinner.setFont(font12());
        warnSpinner.setPreferredSize(new Dimension(70, 26));
        warnSpinner.addChangeListener(e -> commit());
        JLabel warnLab2 = new JLabel("秒时切换为警戒色（并参与全屏状态提示）");
        warnLab2.setFont(font13());
        warnRow.add(warnLab);
        warnRow.add(warnSpinner);
        warnRow.add(warnLab2);
        panel.add(warnRow);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 300));
        return panel;
    }

    private JPanel colorRow(String label, JButton swatch, String stateName) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel lab = new JLabel(label);
        lab.setFont(font13());
        lab.setPreferredSize(new Dimension(90, 24));
        swatch.setFont(font12());
        swatch.setFocusPainted(false);
        swatch.setOpaque(true);
        swatch.setBorderPainted(true);
        swatch.setPreferredSize(new Dimension(150, 26));
        swatch.addActionListener(e -> chooseColor(stateName, swatch));
        row.add(lab);
        row.add(swatch);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return row;
    }

    private JPanel createFocusPanel() {
        JPanel panel = titledPanel("全屏专注（番茄钟）");
        fullscreenCheck.setFont(font13());
        fullscreenCheck.addActionListener(e -> commit());
        onTopCheck.setFont(font13());
        onTopCheck.addActionListener(e -> commit());
        focusResultCheck.setFont(font13());
        focusResultCheck.addActionListener(e -> commit());
        panel.add(fullscreenCheck);
        panel.add(onTopCheck);

        JPanel bgRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel bgLab = new JLabel("背景色：");
        bgLab.setFont(font13());
        bgLab.setPreferredSize(new Dimension(90, 24));
        focusBgBtn.setFont(font12());
        focusBgBtn.setFocusPainted(false);
        focusBgBtn.setOpaque(true);
        focusBgBtn.setBorderPainted(true);
        focusBgBtn.setPreferredSize(new Dimension(150, 26));
        focusBgBtn.addActionListener(e -> {
            Color picked = JColorChooser.showDialog(this, "选择全屏背景色", cfg.getFocusBackground());
            if (picked != null) {
                cfg.setFocusBackground(picked);
                cfg.save();
                dirty = true;
                updateSwatch(focusBgBtn, picked);
                if (applyChanges != null) {
                    applyChanges.run();
                }
            }
        });
        bgRow.add(bgLab);
        bgRow.add(focusBgBtn);
        bgRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        panel.add(bgRow);

        JPanel textRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel textLab = new JLabel("读数大小：");
        textLab.setFont(font13());
        textLab.setPreferredSize(new Dimension(90, 24));
        styleSlider(focusTextSlider, 0);
        focusTextSlider.setPreferredSize(new Dimension(200, 50));
        focusTextSlider.addChangeListener(e -> {
            focusTextValueLabel.setText(focusTextSlider.getValue() + "%");
            commit();
        });
        focusTextValueLabel.setFont(font12());
        focusTextValueLabel.setPreferredSize(new Dimension(60, 20));
        textRow.add(textLab);
        textRow.add(focusTextSlider);
        textRow.add(focusTextValueLabel);
        textRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        panel.add(textRow);
        panel.add(focusResultCheck);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 230));
        return panel;
    }

    private JPanel createAlertPanel() {
        JPanel panel = titledPanel("提醒音频");

        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JLabel fileLab = new JLabel("音频文件：");
        fileLab.setFont(font13());
        alertFileField.setFont(font12());
        alertFileField.setEditable(false);
        alertFileField.setToolTipText("支持 javax.sound.sampled 可解码的格式（推荐 16bit PCM WAV）");
        JButton browse = new JButton("选择…");
        browse.setFont(font12());
        browse.addActionListener(e -> onChooseAlertFile());
        JButton test = new JButton("试听");
        test.setFont(font12());
        test.addActionListener(e -> onTestAlert());
        JButton clear = new JButton("清除");
        clear.setFont(font12());
        clear.addActionListener(e -> {
            cfg.setAlertFile("");
            cfg.save();
            dirty = true;
            refreshAlertControls();
            updateAlertState("已清除自定义音频；到时将使用系统提示音（若开启兜底）");
        });
        fileRow.add(fileLab);
        fileRow.add(alertFileField);
        fileRow.add(browse);
        fileRow.add(test);
        fileRow.add(clear);
        fileRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        panel.add(fileRow);

        JPanel stateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        alertStateLabel.setFont(font12());
        alertStateLabel.setForeground(new Color(0x33, 0x55, 0x88));
        stateRow.add(alertStateLabel);
        stateRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        panel.add(stateRow);

        alertFinishCheck.setFont(font13());
        alertFinishCheck.addActionListener(e -> commit());
        alertEachSecondCheck.setFont(font13());
        alertEachSecondCheck.addActionListener(e -> commit());
        beepFallbackCheck.setFont(font13());
        beepFallbackCheck.addActionListener(e -> commit());
        panel.add(alertFinishCheck);
        panel.add(alertEachSecondCheck);
        panel.add(beepFallbackCheck);

        JPanel repeatRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JLabel repeatLab = new JLabel("到时重复播放：");
        repeatLab.setFont(font13());
        repeatSpinner.setFont(font12());
        repeatSpinner.setPreferredSize(new Dimension(70, 26));
        repeatSpinner.addChangeListener(e -> commit());
        JLabel repeatLab2 = new JLabel("次（1–99；系统提示音最多 5 次）");
        repeatLab2.setFont(font13());
        repeatRow.add(repeatLab);
        repeatRow.add(repeatSpinner);
        repeatRow.add(repeatLab2);
        repeatRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        panel.add(repeatRow);

        JPanel volumeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JLabel volLab = new JLabel("音量：");
        volLab.setFont(font13());
        volLab.setPreferredSize(new Dimension(90, 24));
        styleSlider(volumeSlider, 0);
        volumeSlider.setPreferredSize(new Dimension(200, 46));
        volumeSlider.addChangeListener(e -> {
            volumeValueLabel.setText(volumeSlider.getValue() + "%");
            commit();
        });
        volumeValueLabel.setFont(font12());
        volumeValueLabel.setPreferredSize(new Dimension(60, 20));
        volumeRow.add(volLab);
        volumeRow.add(volumeSlider);
        volumeRow.add(volumeValueLabel);
        volumeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        panel.add(volumeRow);

        JLabel tip = new JLabel("提示：把音频放到 " + TimerBallConfig.DATA_DIR
                + " 目录便于随插件一起管理（删除插件 jar 不会自动清理数据）。");
        tip.setFont(font11());
        tip.setForeground(Color.GRAY);
        panel.add(tip);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 330));
        return panel;
    }

    private JPanel createInteractionPanel() {
        JPanel panel = titledPanel("交互");
        autoStartCheck.setFont(font13());
        autoStartCheck.addActionListener(e -> commit());
        followBallCheck.setFont(font13());
        followBallCheck.addActionListener(e -> {
            commit();
            syncGapState(); // 跟随开关变化会决定"与悬浮球间距"是否可用
        });
        panel.add(autoStartCheck);
        panel.add(followBallCheck);
        JLabel tip = new JLabel("提示：倒计时子球的显示/收起与开始都从右键菜单发起，双击悬浮球仍为宿主随机抽取。");
        tip.setFont(font11());
        tip.setForeground(Color.GRAY);
        panel.add(tip);
        JLabel entry = new JLabel("入口：主窗 设置 → 插件区「计时子球（倒计时）」按钮；主窗/托盘/悬浮球右键菜单也有「倒计时设置 ▸ 常规设置…」。");
        entry.setFont(font11());
        entry.setForeground(Color.GRAY);
        panel.add(entry);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
        return panel;
    }

    /**
     * 同步"与悬浮球间距"的可用状态。
     * <p>
     * 间距只在子球跟随宿主悬浮球时才参与落位计算；未跟随时它是无意义选项，
     * 因此这里把滑杆禁用并给出说明（而不是让用户改一个不生效的值）。
     * </p>
     */
    private void syncGapState() {
        boolean follow = followBallCheck.isSelected();
        gapSlider.setEnabled(follow);
        if (gapLabel != null) {
            gapLabel.setEnabled(follow);
        }
        gapValueLabel.setEnabled(follow);
        if (gapHintLabel != null) {
            gapHintLabel.setText(follow ? "　（子球与宿主悬浮球之间的留白）"
                    : "　（仅「跟随宿主悬浮球」开启时生效，当前已关闭）");
        }
    }

    // ===================== 交互 =====================

    private void onApplyDurationField() {
        int sec = TimerBallConfig.parseDuration(durationField.getText());
        if (sec < 0) {
            JOptionPane.showMessageDialog(this, "请输入 mm:ss（如 25:00）或秒数（如 90）。",
                    "计时子球 · 倒计时设置", JOptionPane.WARNING_MESSAGE);
            return;
        }
        cfg.setDurationSeconds(sec);
        cfg.save();
        dirty = true;
        durationField.setText(TimerBallConfig.formatDuration(cfg.getDurationSeconds()));
        currentDurationLabel.setText("当前设定：" + TimerBallConfig.formatDuration(cfg.getDurationSeconds()));
        updateAlertState("时长已设为 " + TimerBallConfig.formatDuration(cfg.getDurationSeconds())
                + "（正在计时的一轮不受影响，归零或重新开始后生效）");
        if (applyChanges != null) {
            applyChanges.run();
        }
    }

    private void onChooseAlertFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择提醒音频文件（推荐 16bit PCM WAV）");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "音频文件 (*.wav, *.aif, *.aiff, *.au)", "wav", "aif", "aiff", "au"));
        File dir = new File(TimerBallConfig.DATA_DIR);
        chooser.setCurrentDirectory(dir.exists() ? dir : new File("."));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = chooser.getSelectedFile();
        if (f == null || !f.isFile()) {
            return;
        }
        cfg.setAlertFile(f.getAbsolutePath());
        cfg.save();
        dirty = true;
        refreshAlertControls();
        updateAlertState("已选择：" + f.getName() + "（" + TimerAlert.checkFile(f.getAbsolutePath()) + "）");
        if (applyChanges != null) {
            applyChanges.run();
        }
    }

    private void onTestAlert() {
        String path = cfg.getAlertFile();
        updateAlertState("正在试听… " + (path.isEmpty() ? "（未设置自定义音频，播放系统提示音）" : path));
        boolean custom = controller != null && controller.alert() != null
                && controller.alert().testPlay(path, cfg.getAlertVolume());
        updateAlertState(custom ? "已试听自定义音频（音量 " + cfg.getAlertVolume() + "%）"
                : "已试听系统提示音" + (path.isEmpty() ? "" : "（自定义音频不可用，请检查格式：推荐 16bit PCM WAV）"));
    }

    /** 任意变更：写回 Config → 持久化 → 实时应用。 */
    private void commit() {
        applyControlsToConfig();
        cfg.save();
        dirty = true;
        if (applyChanges != null) {
            applyChanges.run();
        }
    }

    private void applyControlsToConfig() {
        // 时长输入框故意不在 commit() 中解析：它由"应用"按钮/回车显式提交（见 onApplyDurationField），
        // 避免每次按键都弹校验提示。
        cfg.setDisplayMode(ballRadio.isSelected() ? TimerBallConfig.DisplayMode.BALL
                : (focusRadio.isSelected() ? TimerBallConfig.DisplayMode.FOCUS
                : TimerBallConfig.DisplayMode.BOTH));
        cfg.setSizeMode(autoSizeRadio.isSelected() ? TimerBallConfig.SizeMode.AUTO
                : TimerBallConfig.SizeMode.FIXED);
        cfg.setFixedDiameter(fixedSlider.getValue());
        cfg.setGapPx(gapSlider.getValue());
        cfg.setShowProgressRing(ringCheck.isSelected());
        cfg.setShowHours(hoursCheck.isSelected());
        cfg.setWarnSeconds((Integer) warnSpinner.getValue());
        cfg.setFocusFullscreen(fullscreenCheck.isSelected());
        cfg.setFocusAlwaysOnTop(onTopCheck.isSelected());
        cfg.setFocusTextPercent(focusTextSlider.getValue());
        cfg.setFocusShowResult(focusResultCheck.isSelected());
        cfg.setAlertOnFinish(alertFinishCheck.isSelected());
        cfg.setAlertEachLastSecond(alertEachSecondCheck.isSelected());
        cfg.setSystemBeepFallback(beepFallbackCheck.isSelected());
        cfg.setAlertRepeat((Integer) repeatSpinner.getValue());
        cfg.setAlertVolume(volumeSlider.getValue());
        cfg.setAutoStartOnShow(autoStartCheck.isSelected());
        cfg.setFollowBall(followBallCheck.isSelected());
    }

    private void chooseColor(String stateName, JButton swatch) {
        Color current = colorOf(stateName);
        Color picked = JColorChooser.showDialog(this, "选择" + stateName + "色", current);
        if (picked == null) {
            return;
        }
        switch (stateName) {
            case "就绪":
                cfg.setColorIdle(picked);
                break;
            case "运行":
                cfg.setColorRunning(picked);
                break;
            case "暂停":
                cfg.setColorPaused(picked);
                break;
            case "警戒":
                cfg.setColorWarning(picked);
                break;
            default:
                cfg.setColorFinished(picked);
                break;
        }
        cfg.save();
        dirty = true;
        updateSwatch(swatch, picked);
        if (applyChanges != null) {
            applyChanges.run();
        }
    }

    private Color colorOf(String stateName) {
        switch (stateName) {
            case "就绪":
                return cfg.getColorIdle();
            case "运行":
                return cfg.getColorRunning();
            case "暂停":
                return cfg.getColorPaused();
            case "警戒":
                return cfg.getColorWarning();
            default:
                return cfg.getColorFinished();
        }
    }

    private void onResetDefaults() {
        cfg.resetToDefaults();
        cfg.save();
        dirty = true;
        if (applyChanges != null) {
            applyChanges.run();
        }
        refreshControlsFromConfig();
        updateAlertState("已恢复默认设置");
    }

    private void onClose() {
        if (dirty) {
            LogManager.log("倒计时插件-设置已保存 " + cfg.summary(), "CD_CONFIG");
            dirty = false;
        }
    }

    // ===================== 控件同步 =====================

    private void refreshControlsFromConfig() {
        currentDurationLabel.setText("当前设定：" + TimerBallConfig.formatDuration(cfg.getDurationSeconds())
                + (controller != null && controller.session() != null
                ? "　本轮读数：" + controller.session().displayText(cfg.isShowHours(), false) : ""));
        durationField.setText(TimerBallConfig.formatDuration(cfg.getDurationSeconds()));

        ballRadio.setSelected(cfg.getDisplayMode() == TimerBallConfig.DisplayMode.BALL);
        focusRadio.setSelected(cfg.getDisplayMode() == TimerBallConfig.DisplayMode.FOCUS);
        bothRadio.setSelected(cfg.getDisplayMode() == TimerBallConfig.DisplayMode.BOTH);

        autoSizeRadio.setSelected(!cfg.isFixedSize());
        fixedSizeRadio.setSelected(cfg.isFixedSize());
        fixedSlider.setValue(cfg.getFixedDiameter());
        fixedValueLabel.setText(cfg.getFixedDiameter() + " px");
        gapSlider.setValue(cfg.getGapPx());
        gapValueLabel.setText(cfg.getGapPx() + " px");
        ringCheck.setSelected(cfg.isShowProgressRing());
        hoursCheck.setSelected(cfg.isShowHours());
        warnSpinner.setValue(cfg.getWarnSeconds());

        updateSwatch(colorIdleBtn, cfg.getColorIdle());
        updateSwatch(colorRunningBtn, cfg.getColorRunning());
        updateSwatch(colorPausedBtn, cfg.getColorPaused());
        updateSwatch(colorWarningBtn, cfg.getColorWarning());
        updateSwatch(colorFinishedBtn, cfg.getColorFinished());

        fullscreenCheck.setSelected(cfg.isFocusFullscreen());
        onTopCheck.setSelected(cfg.isFocusAlwaysOnTop());
        updateSwatch(focusBgBtn, cfg.getFocusBackground());
        focusTextSlider.setValue(cfg.getFocusTextPercent());
        focusTextValueLabel.setText(cfg.getFocusTextPercent() + "%");
        focusResultCheck.setSelected(cfg.isFocusShowResult());

        refreshAlertControls();
        volumeSlider.setValue(cfg.getAlertVolume());
        volumeValueLabel.setText(cfg.getAlertVolume() + "%");
        repeatSpinner.setValue(cfg.getAlertRepeat());
        alertFinishCheck.setSelected(cfg.isAlertOnFinish());
        alertEachSecondCheck.setSelected(cfg.isAlertEachLastSecond());
        beepFallbackCheck.setSelected(cfg.isSystemBeepFallback());

        autoStartCheck.setSelected(cfg.isAutoStartOnShow());
        followBallCheck.setSelected(cfg.isFollowBall());
        syncGapState(); // 间距项按"跟随悬浮球"开关启用/禁用
    }

    private void refreshAlertControls() {
        String path = cfg.getAlertFile();
        alertFileField.setText(path.isEmpty() ? "(未设置，使用系统提示音)" : path);
        updateAlertState(path.isEmpty() ? "未设置自定义音频"
                : TimerAlert.checkFile(path));
    }

    private void updateAlertState(String text) {
        alertStateLabel.setText(text);
    }

    private void updateSwatch(JButton btn, Color c) {
        btn.setBackground(c);
        btn.setForeground(contrast(c));
        btn.setText(TimerBallConfig.toHex(c));
        btn.setToolTipText("点击选择颜色（当前 " + TimerBallConfig.toHex(c) + "）");
    }

    /** 根据亮度选择前景黑/白，保证色块文字可读。 */
    private Color contrast(Color c) {
        double lum = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
        return lum > 150 ? Color.BLACK : Color.WHITE;
    }

    private void styleSlider(JSlider slider, int majorTick) {
        if (majorTick > 0) {
            slider.setMajorTickSpacing(majorTick);
            slider.setMinorTickSpacing(Math.max(1, majorTick / 5));
            slider.setPaintTicks(true);
            slider.setPaintLabels(true);
        }
        slider.setFont(font11());
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

    /** 供自检/调试：对话框标题与已注册分区数量。 */
    public String debugSummary() {
        return "倒计时设置 分区=8 音频=" + (cfg.getAlertFile().isEmpty() ? "未设置" : cfg.getAlertFile());
    }

    @Override
    public void dispose() {
        onClose();
        super.dispose();
    }
}
