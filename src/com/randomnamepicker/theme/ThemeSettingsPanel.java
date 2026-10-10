package com.randomnamepicker.theme;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;

/**
 * 设置窗内的“背景主题”**入口分节**（宿主内建主题，本轮 D2 瘦身）：
 * 只保留总开关 + 状态摘要 + 打开独立页按钮；图库列表管理、按窗口类别指定、活体预览
 * 全部移到独立页 {@link ThemeWindow}（避免两套完整 UI 分叉维护）。
 * <p>口径不变：未导入/未启用时窗口零变化；改动即时持久化（开关）并即时重排。</p>
 */
public class ThemeSettingsPanel extends JPanel {

    private final JCheckBox enabledCheck = new JCheckBox("启用照片背景");
    private final JLabel statusLabel = new JLabel();
    private final JButton openButton = new JButton("打开背景主题设置…");
    private final Timer statusTimer = new Timer(1500, e -> {
        if (isShowing()) {
            refreshStatus();
        }
    });

    private boolean syncing;

    public ThemeSettingsPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new TitledBorder(
                BorderFactory.createLineBorder(new java.awt.Color(0x99, 0x99, 0x99), 1),
                "背景主题",
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("微软雅黑", Font.BOLD, 13)));

        enabledCheck.setFont(font(13));
        statusLabel.setFont(font(11));
        openButton.setFont(font(12));

        // 第一行：开关 + 打开独立页；第二行：状态摘要（独立成行，避免窄窗下被横向裁掉）
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row1.setOpaque(false);
        row1.add(enabledCheck);
        row1.add(openButton);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row2.setOpaque(false);
        row2.add(statusLabel);

        add(row1);
        add(row2);

        enabledCheck.addActionListener(e -> {
            if (syncing) {
                return;
            }
            ThemeStore.setEnabled(enabledCheck.isSelected());
            BackdropManager.getInstance().reapplyAll();
            refreshStatus();
        });
        openButton.addActionListener(e -> {
            Window owner = SwingUtilities.getWindowAncestor(this);
            ThemeWindow.openWindow(owner);
            // 独立页是无模态的：打开后本分节可能仍可见，用定时器保持状态摘要最新
            refreshStatus();
        });

        refreshStatus();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        refreshStatus();
        statusTimer.start();
    }

    @Override
    public void removeNotify() {
        statusTimer.stop();
        super.removeNotify();
    }

    /** 状态摘要：启用位 + 图库张数 + 全局图 + 遮罩/表格档 + 单独指定类别数（保持一行可读）。 */
    public void refreshStatus() {
        syncing = true;
        try {
            enabledCheck.setSelected(ThemeStore.isEnabled());
        } finally {
            syncing = false;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ThemeStore.isEnabled() ? "已启用" : "未启用（窗口零变化）");
        sb.append(" · 图库 ").append(ThemeStore.listImages().size()).append(" 张");
        sb.append(" · 全局 ").append(shortLabel(ThemeStore.getGlobalImage()));
        if (!ThemeStore.getGlobalImage().isEmpty()) {
            sb.append("（").append(ThemeStore.globalModeNow().label()).append("）");
        }
        sb.append(" · 遮罩 ").append(ThemeStore.getMask());
        sb.append(" · 表格 ").append(tierText());
        int overridden = 0;
        for (String cid : ThemeStore.CATEGORY_IDS) {
            if (ThemeStore.categoryHasOwnImage(cid)) {
                overridden++;
            }
        }
        sb.append(" · 单独指定 ").append(overridden).append("/").append(ThemeStore.CATEGORY_IDS.length);
        statusLabel.setText(sb.toString());
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));
    }

    /** 摘要用短名：优先别名，无别名用短 id（不显示括号短标识，节省横向空间）。 */
    private String shortLabel(String imageId) {
        if (imageId == null || imageId.isEmpty()) {
            return "无图";
        }
        String alias = ThemeStore.getImageName(imageId);
        return alias.isEmpty() ? ThemeStore.shortId(imageId) : alias;
    }

    private String tierText() {
        switch (ThemeStore.getTableTier()) {
            case 0:
                return "原生";
            case 2:
                return "中";
            case 1:
            default:
                return "浅";
        }
    }

    private Font font(int size) {
        return new Font("微软雅黑", Font.PLAIN, size);
    }
}
