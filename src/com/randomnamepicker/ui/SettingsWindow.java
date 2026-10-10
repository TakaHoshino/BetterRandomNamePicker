package com.randomnamepicker.ui;

import com.randomnamepicker.core.ConfigManager;
import com.randomnamepicker.core.LogManager;
import com.randomnamepicker.core.PasswordManager;
import com.randomnamepicker.main.Main;
import com.randomnamepicker.plugin.PluginManager;
import com.randomnamepicker.plugin.UiZone;
import com.randomnamepicker.theme.ThemeSettingsPanel;
import java.awt.*;
import java.io.File;
import javax.swing.*;
import javax.swing.border.TitledBorder;

public class SettingsWindow extends JDialog {
    private JCheckBox autoStartCheckBox;
    private JCheckBox minimizeToTrayCheckBox;
    private JCheckBox autoShowBallCheckBox;
    private JButton exportLogButton;
    private JSlider radiusSlider;
    private JSlider opacitySlider;
    private JLabel radiusValueLabel;
    private JLabel opacityValueLabel;
    private JButton lockButton;
    private JButton changePasswordButton;
    /** 程序化载入配置期间为 true：避免"载入→滑块被夹取→监听器写回"把超范围值改小。 */
    private boolean loadingSettings = false;

    public SettingsWindow(JFrame parent) {
        super(parent, "系统设置", true);
        initializeComponents();
        setupLayout();
        loadSettings();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private void initializeComponents() {
        setSize(600, 550);
        setLocationRelativeTo(getParent());
        setMinimumSize(new Dimension(500, 450));

        autoStartCheckBox = new JCheckBox("启用后程序将在系统启动时自动运行");
        autoStartCheckBox.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        autoStartCheckBox.addActionListener(e -> {
            boolean enabled = autoStartCheckBox.isSelected();
            ConfigManager.setAutoStart(enabled);
            
            if (enabled) {
                Main.registerAutoStart();
            } else {
                Main.unregisterAutoStart();
            }
        });

        minimizeToTrayCheckBox = new JCheckBox("关闭窗口时最小化到系统托盘（悬浮球继续运行）");
        minimizeToTrayCheckBox.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        minimizeToTrayCheckBox.addActionListener(e -> {
            ConfigManager.setMinimizeToTray(minimizeToTrayCheckBox.isSelected());
        });

        // 新增：打开主程序后自动显示悬浮球（勾选后立即显示一次，便于确认效果）
        autoShowBallCheckBox = new JCheckBox("打开主程序后自动显示悬浮球");
        autoShowBallCheckBox.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        autoShowBallCheckBox.addActionListener(e -> {
            boolean auto = autoShowBallCheckBox.isSelected();
            ConfigManager.setAutoShowFloatingBall(auto);
            if (auto && getParent() instanceof NamePickerApp) {
                ((NamePickerApp) getParent()).showFloatingBallIfConfigured();
            }
        });

        exportLogButton = new JButton("导出日志文件");
        exportLogButton.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        exportLogButton.addActionListener(e -> exportLog());

        radiusSlider = new JSlider(SwingConstants.HORIZONTAL, 30, 100, 50);
        radiusSlider.setMajorTickSpacing(20);
        radiusSlider.setMinorTickSpacing(10);
        radiusSlider.setPaintTicks(true);
        radiusSlider.setPaintLabels(true);
        radiusSlider.setFont(new Font("微软雅黑", Font.PLAIN, 11));
        radiusValueLabel = new JLabel("50 像素");
        radiusValueLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));

        opacitySlider = new JSlider(SwingConstants.HORIZONTAL, 50, 255, 200);
        opacitySlider.setMajorTickSpacing(50);
        opacitySlider.setMinorTickSpacing(25);
        opacitySlider.setPaintTicks(true);
        opacitySlider.setPaintLabels(true);
        opacitySlider.setFont(new Font("微软雅黑", Font.PLAIN, 11));
        opacityValueLabel = new JLabel("200 (半透明)");
        opacityValueLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));

        lockButton = new JButton("锁定/解锁配置");
        lockButton.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        lockButton.addActionListener(e -> toggleLock());

        changePasswordButton = new JButton("修改密码");
        changePasswordButton.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        changePasswordButton.addActionListener(e -> changePassword());

    }

    private void setupLayout() {
        setLayout(new BorderLayout(10, 10));

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        JPanel startupPanel = createStartupPanel();
        // 三行复选框（自启动 / 最小化到托盘 / 自动显示悬浮球）+ 间距与边框
        startupPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        mainPanel.add(startupPanel);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        JPanel appearancePanel = createAppearancePanel();
        appearancePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        mainPanel.add(appearancePanel);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // 宿主内建主题（报告 2/3）：背景主题设置分节（内置于设置窗，非插件入口）
        ThemeSettingsPanel themePanel = new ThemeSettingsPanel();
        themePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        mainPanel.add(themePanel);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        JPanel logPanel = createLogPanel();
        logPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        mainPanel.add(logPanel);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        JPanel securityPanel = createSecurityPanel();
        securityPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        mainPanel.add(securityPanel);

        // 插件 UI 槽一期：设置窗插件按钮区（置于列表最末；无插件动作时返回 null、不渲染）
        JPanel pluginZonePanel = PluginUiSupport.createZonePanel(UiZone.SETTINGS_WINDOW);
        if (pluginZonePanel != null) {
            mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));
            mainPanel.add(pluginZonePanel);
        }

        JScrollPane scrollPane = new JScrollPane(mainPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setBorder(null);
        add(scrollPane, BorderLayout.CENTER);

        radiusSlider.addChangeListener(e -> {
            if (loadingSettings) {
                return;
            }
            int radius = radiusSlider.getValue();
            radiusValueLabel.setText(radius + " 像素");
            saveFloatingBallSettings();
        });

        opacitySlider.addChangeListener(e -> {
            if (loadingSettings) {
                return;
            }
            int opacity = opacitySlider.getValue();
            opacityValueLabel.setText(opacityText(opacity));
            saveFloatingBallSettings();
        });
    }

    private JPanel createStartupPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new TitledBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1),
                "启动与关闭设置",
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("微软雅黑", Font.BOLD, 13)
        ));

        JPanel autoStartPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        autoStartPanel.add(autoStartCheckBox);
        autoStartPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        JPanel minimizePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        minimizePanel.add(minimizeToTrayCheckBox);
        minimizePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        JPanel autoShowBallPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        autoShowBallPanel.add(autoShowBallCheckBox);
        autoShowBallPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        panel.add(autoStartPanel);
        panel.add(Box.createRigidArea(new Dimension(0, 5)));
        panel.add(minimizePanel);
        panel.add(Box.createRigidArea(new Dimension(0, 5)));
        panel.add(autoShowBallPanel);

        return panel;
    }

    private JPanel createAppearancePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1),
                "悬浮球外观",
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("微软雅黑", Font.BOLD, 13)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        panel.add(new JLabel("半径："), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(radiusSlider, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        panel.add(radiusValueLabel, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        panel.add(new JLabel("透明度："), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(opacitySlider, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        panel.add(opacityValueLabel, gbc);

        return panel;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        panel.setBorder(new TitledBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1),
                "日志管理",
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("微软雅黑", Font.BOLD, 13)
        ));
        panel.add(exportLogButton);
        return panel;
    }

    private JPanel createSecurityPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        panel.setBorder(new TitledBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1),
                "安全设置",
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("微软雅黑", Font.BOLD, 13)
        ));
        panel.add(lockButton);
        panel.add(changePasswordButton);
        // 仍在使用源码内置默认密码时给出醒目提示（该密码写在源码里，等于未设防）
        if (PasswordManager.isUsingBuiltinDefaultPassword()) {
            JLabel warn = new JLabel("⚠ 当前仍是内置默认密码（写在源码里，等于未设防），建议点“修改密码”更换");
            warn.setFont(new Font("微软雅黑", Font.PLAIN, 12));
            warn.setForeground(new Color(0xC0, 0x30, 0x30));
            panel.add(warn);
        }
        return panel;
    }

    private void loadSettings() {
        // 载入期间屏蔽滑块的写回：半径允许超出滑块范围（内部只判下界，无上界），
        // 若不屏蔽，"setValue 被夹到滑块上限 → 监听器保存" 会把用户/手工写的大值改小。
        loadingSettings = true;
        try {
            autoStartCheckBox.setSelected(ConfigManager.isAutoStartEnabled());
            minimizeToTrayCheckBox.setSelected(ConfigManager.isMinimizeToTray());
            autoShowBallCheckBox.setSelected(ConfigManager.isAutoShowFloatingBall());

            int radius = ConfigManager.getFloatingBallRadius();
            radiusSlider.setValue(radius); // 超出 30–100 时滑块只做显示夹取，不回写配置
            radiusValueLabel.setText(radius + " 像素" + radiusOutOfSliderHint(radius));

            int opacity = ConfigManager.getFloatingBallOpacity();
            opacitySlider.setValue(opacity);
            opacityValueLabel.setText(opacityText(opacity));
        } finally {
            loadingSettings = false;
        }

        updateLockButtonText();
    }

    /** 半径超出滑块可视范围时的提示（配置值本身不受滑块限制）。 */
    private String radiusOutOfSliderHint(int radius) {
        if (radius > radiusSlider.getMaximum()) {
            return "（大于滑块上限 " + radiusSlider.getMaximum() + "，滑块仅作显示）";
        }
        if (radius < radiusSlider.getMinimum()) {
            return "（小于滑块下限 " + radiusSlider.getMinimum() + "，滑块仅作显示）";
        }
        return "";
    }

    private String opacityText(int opacity) {
        if (opacity < 100) {
            return opacity + " (高透明)";
        }
        if (opacity < 180) {
            return opacity + " (半透明)";
        }
        return opacity + " (低透明)";
    }

    private void toggleLock() {
        if (PasswordManager.isLocked()) {
            PasswordDialog passwordDialog = new PasswordDialog((Frame) getParent());
            passwordDialog.setVisible(true);

            if (passwordDialog.isPasswordVerified()) {
                PasswordManager.unlock();
                // G5：锁状态翻转去重派发（PasswordManager 零改动；解锁多在对话框验证成功处已派发，此处去重不重复）
                PluginManager.getInstance().notifyLockStateIfChanged();
                JOptionPane.showMessageDialog(this,
                        "已解锁！现在可以修改配置了。",
                        "提示",
                        JOptionPane.INFORMATION_MESSAGE);
                updateLockButtonText();
            }
        } else {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "确定要锁定配置吗？\n锁定后需要密码才能修改配置。",
                    "确认锁定",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);

            if (confirm == JOptionPane.YES_OPTION) {
                PasswordManager.lock();
                // G5：锁定翻转 → 锁状态翻转去重派发
                PluginManager.getInstance().notifyLockStateIfChanged();
                JOptionPane.showMessageDialog(this,
                        "已锁定！修改配置需要密码验证。",
                        "提示",
                        JOptionPane.INFORMATION_MESSAGE);
                updateLockButtonText();
            }
        }
    }

    private void updateLockButtonText() {
        if (PasswordManager.isLocked()) {
            lockButton.setText("🔒 当前已锁定（点击解锁）");
        } else {
            lockButton.setText("🔓 当前已解锁（点击锁定）");
        }
    }

    private void changePassword() {
        if (PasswordManager.isLocked()) {
            JOptionPane.showMessageDialog(this,
                    "请先解锁后再修改密码！",
                    "提示",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        OldPasswordDialog oldDialog = new OldPasswordDialog((Frame) getParent());
        oldDialog.setVisible(true);

        if (!oldDialog.isPasswordVerified()) {
            return;
        }

        NewPasswordDialog newDialog = new NewPasswordDialog((Frame) getParent());
        newDialog.setVisible(true);
    }

    private void saveFloatingBallSettings() {
        int radius = radiusSlider.getValue();
        int opacity = opacitySlider.getValue();

        ConfigManager.setFloatingBallRadius(radius);
        ConfigManager.setFloatingBallOpacity(opacity);
    }

    private void exportLog() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("文本文件 (*.txt)", "txt"));
        fileChooser.setSelectedFile(new java.io.File("Modifylog_export.txt"));

        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                java.io.File destFile = fileChooser.getSelectedFile();
                if (!destFile.getName().toLowerCase().endsWith(".txt")) {
                    destFile = new java.io.File(destFile.getAbsolutePath() + ".txt");
                }

                java.nio.file.Files.copy(
                        new java.io.File(LogManager.LOG_FILE_PATH).toPath(),
                        destFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );

                JOptionPane.showMessageDialog(this, "日志已成功导出到：\n" + destFile.getAbsolutePath(),
                        "导出成功", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "导出失败：" + ex.getMessage(), "错误",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private boolean isAutoStartEnabled() {
        return Main.checkAutoStartStatus();
    }
}
