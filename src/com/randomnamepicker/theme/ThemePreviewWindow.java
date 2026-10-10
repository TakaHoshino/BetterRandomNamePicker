package com.randomnamepicker.theme;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * **独立预览窗口**（独立页 D5）：把 {@link ThemePreviewPanel} 放进一个可自由拖拽缩放的
 * 无模态窗口，用来验证“窗口变化时图片跟着调”——居中/适应/填充/拉伸/自由裁切在 resize 时的重排行为
 * 与真实窗口完全一致（同一 {@link WindowBackdrop} 绘制路径 + 同一解析口径）。
 * <p>本窗口自身不参与贴图（类名不在 {@code BackdropManager} 映射表内，D8），保证预览背景干净可判读。</p>
 */
public class ThemePreviewWindow extends JDialog {

    private static final String PREVIEW_SELECTED = "图库选中图（试贴）";

    private final ThemePreviewPanel preview = new ThemePreviewPanel();
    private final JComboBox<String> targetCombo = new JComboBox<>();
    private final JCheckBox mockCheck = new JCheckBox("显示模拟内容", true);
    private boolean syncing;

    public ThemePreviewWindow(Window owner) {
        super(owner, "背景主题预览（模拟窗口，可拖拽缩放）", ModalityType.MODELESS);
        setLayout(new BorderLayout(6, 6));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        Font font = new Font("微软雅黑", Font.PLAIN, 11);
        targetCombo.setFont(font);
        targetCombo.addItem(PREVIEW_SELECTED);
        for (String label : ThemeStore.categoryLabels()) {
            targetCombo.addItem(label);
        }
        targetCombo.setSelectedIndex(1);
        mockCheck.setFont(font);
        mockCheck.setSelected(true);
        JButton refresh = new JButton("刷新");
        refresh.setFont(font);
        top.add(new JLabel("预览类别："));
        top.add(targetCombo);
        top.add(mockCheck);
        top.add(refresh);
        add(top, BorderLayout.NORTH);

        add(preview, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JLabel hint = new JLabel("提示：拖动本窗口边缘缩放 → 背景按当前适配模式即时重排（与真实窗口一致）；"
                + "本窗口不参与贴图，仅作预览。");
        hint.setFont(font);
        JButton close = new JButton("关闭");
        close.setFont(font);
        bottom.add(hint);
        bottom.add(close);
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        add(bottom, BorderLayout.SOUTH);

        targetCombo.addActionListener(e -> {
            if (syncing) {
                return;
            }
            preview.setCategory(selectedCategory());
        });
        mockCheck.addActionListener(e -> preview.setMockContentVisible(mockCheck.isSelected()));
        refresh.addActionListener(e -> preview.refresh());
        close.addActionListener(e -> dispose());

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(680, 660);
        setMinimumSize(new Dimension(460, 420));
        setResizable(true);
        setLocationRelativeTo(owner);
        preview.setCategory(selectedCategory());
    }

    /** 由独立页调用：把当前预览目标（类别 + 试贴图 + 模拟内容开关）带过来。 */
    public void setPreviewTarget(String category, String selectedImageId, boolean mockVisible) {
        syncing = true;
        try {
            int idx = indexOfCategory(category);
            targetCombo.setSelectedIndex(idx);
            mockCheck.setSelected(mockVisible);
        } finally {
            syncing = false;
        }
        preview.setSelectedImageId(selectedImageId);
        preview.setCategory(category != null ? category : ThemeStore.CATEGORY_MAIN);
        preview.setMockContentVisible(mockVisible);
        preview.refresh();
    }

    public ThemePreviewPanel getPreviewPanel() {
        return preview;
    }

    private String selectedCategory() {
        int idx = targetCombo.getSelectedIndex();
        return idx <= 0 ? ThemePreviewPanel.TARGET_SELECTED_IMAGE : ThemeStore.CATEGORY_IDS[idx - 1];
    }

    private int indexOfCategory(String category) {
        if (category == null || ThemePreviewPanel.TARGET_SELECTED_IMAGE.equals(category)) {
            return 0;
        }
        for (int i = 0; i < ThemeStore.CATEGORY_IDS.length; i++) {
            if (ThemeStore.CATEGORY_IDS[i].equals(category)) {
                return i + 1;
            }
        }
        return 1;
    }
}
