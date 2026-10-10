package com.randomnamepicker.theme;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.Vector;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

/**
 * 背景主题**预览画布**（独立页 D5）：用一个“模拟宿主窗口”实时呈现当前主题效果。
 * <p>
 * 保真口径（关键）：底层放的是**真实 {@link WindowBackdrop}**（与各真实窗口同一绘制路径、同一解析结果），
 * 上层的模拟内容按 {@code BackdropManager} 的透明化白名单手工做成同构——普通面板非不透明（透出照片）、
 * 按钮/下拉/列表/表格/表头保持原生；表格单元格使用 {@code BackdropManager} 内**同一个 T2 渲染器**。
 * 因此预览所见与真实窗口一致，且随本组件缩放即时重排（等价于拖动真实窗口大小）。
 * </p>
 */
public class ThemePreviewPanel extends JPanel {

    /** 预览目标：图库当前选中图“试贴”（只读预览，不写入配置）。 */
    public static final String TARGET_SELECTED_IMAGE = "*selected";

    private final Canvas canvas;
    private final WindowBackdrop backdrop = new WindowBackdrop();
    private final JPanel mockContent = new JPanel(new BorderLayout());
    private final JLabel bannerLabel = new JLabel();
    private final JLabel captionLabel = new JLabel();

    private final JTable table;
    private final JScrollPane tableScroll;
    private final TableCellRenderer nativeObjectRenderer;
    private final TableCellRenderer nativeStringRenderer;
    private final boolean nativeTableOpaque;

    private String category = ThemeStore.CATEGORY_MAIN;
    private String selectedImageId = "";
    private boolean mockContentVisible = true;

    public ThemePreviewPanel() {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // ---- 模拟表格（先建，便于记录原生渲染器用于档 0 回退） ----
        DefaultTableModel model = new DefaultTableModel(
                new Object[][]{
                        {"张伟", "1", "已抽取"},
                        {"李娜", "2", "未抽取"},
                        {"王强", "3", "未抽取"},
                        {"刘洋", "4", "已抽取"}},
                new Object[]{"姓名", "序号", "状态"}) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(model);
        table.setFont(font(12));
        table.setRowHeight(24);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nativeObjectRenderer = table.getDefaultRenderer(Object.class);
        nativeStringRenderer = table.getDefaultRenderer(String.class);
        nativeTableOpaque = table.isOpaque();

        tableScroll = new JScrollPane(table);
        tableScroll.setOpaque(false);
        tableScroll.getViewport().setOpaque(false);
        tableScroll.setPreferredSize(new Dimension(360, 150));

        mockContent.setOpaque(false);
        rebuildMockContent();

        // ---- 画布：真实背景垫层在下、模拟内容在上（与真实窗口 layeredPane 结构一致） ----
        canvas = new Canvas(backdrop, mockContent);
        canvas.setPreferredSize(new Dimension(430, 300));
        canvas.setMinimumSize(new Dimension(200, 140));

        bannerLabel.setOpaque(true);
        bannerLabel.setBackground(new Color(0xFF, 0xF3, 0xCD));
        bannerLabel.setForeground(new Color(0x8A, 0x5A, 0x00));
        bannerLabel.setFont(font(11));
        bannerLabel.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        bannerLabel.setVisible(false);

        captionLabel.setFont(font(11));
        captionLabel.setForeground(new Color(0x44, 0x44, 0x44));

        add(bannerLabel, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(captionLabel, BorderLayout.SOUTH);

        refresh();
    }

    // ===================== 对外 API =====================

    /** 设置预览类别（{@link ThemeStore#CATEGORY_IDS} 之一，或 {@link #TARGET_SELECTED_IMAGE}）。 */
    public void setCategory(String category) {
        this.category = category;
        rebuildMockContent();
        refresh();
    }

    public String getCategory() {
        return category;
    }

    /** 图库选中图（{@link #TARGET_SELECTED_IMAGE} 的试贴目标）。 */
    public void setSelectedImageId(String imageId) {
        this.selectedImageId = imageId != null ? imageId : "";
        if (TARGET_SELECTED_IMAGE.equals(category)) {
            refresh();
        }
    }

    public String getSelectedImageId() {
        return selectedImageId;
    }

    /** 是否绘制模拟内容（关掉 = 只看纯背景图效果）。 */
    public void setMockContentVisible(boolean visible) {
        this.mockContentVisible = visible;
        mockContent.setVisible(visible);
        revalidate();
        repaint();
    }

    public boolean isMockContentVisible() {
        return mockContentVisible;
    }

    /** 按当前配置重新解析样式并重绘（任何主题改动后调用）。 */
    public void refresh() {
        ThemeStyle style = resolveStyle();
        backdrop.setStyle(style);
        if (style.isAnimated()) {
            MediaClock.getInstance().addTarget(backdrop);
        } else {
            MediaClock.getInstance().removeTarget(backdrop);
        }
        applyTableTier();
        updateTexts(style);
        canvas.revalidate();
        canvas.repaint();
    }

    // ===================== 解析与渲染 =====================

    private ThemeStyle resolveStyle() {
        if (TARGET_SELECTED_IMAGE.equals(category)) {
            if (selectedImageId == null || selectedImageId.isEmpty()) {
                return ThemeStyle.NONE;
            }
            Rectangle2D.Double crop = ThemeStore.parseCrop(ThemeStore.configCropString(null));
            return ThemeStore.styleFor(selectedImageId, ThemeStore.globalModeNow(), crop, ThemeStore.getMask());
        }
        return ThemeStore.resolvePreview(category);
    }

    /** T2 档位：与真实窗口同一渲染器（档 0 = 恢复表格原生渲染器与不透明度）。 */
    private void applyTableTier() {
        TableCellRenderer renderer = BackdropManager.previewTableCellRenderer();
        if (renderer == null) {
            table.setOpaque(nativeTableOpaque);
            table.setDefaultRenderer(Object.class, nativeObjectRenderer);
            table.setDefaultRenderer(String.class, nativeStringRenderer);
        } else {
            table.setOpaque(false);
            table.setDefaultRenderer(Object.class, renderer);
            table.setDefaultRenderer(String.class, renderer);
        }
        table.repaint();
    }

    private void updateTexts(ThemeStyle style) {
        boolean enabled = ThemeStore.isEnabled();
        if (!enabled) {
            bannerLabel.setText("主题未启用：以下仅为效果预览，不会应用到窗口。");
            bannerLabel.setVisible(true);
        } else if (!style.isActive()) {
            bannerLabel.setText("该类别当前无可用图片（未指定 / 图片丢失或无法解码）——真实窗口此时零变化。");
            bannerLabel.setVisible(true);
        } else {
            bannerLabel.setVisible(false);
        }

        StringBuilder sb = new StringBuilder("预览：");
        if (TARGET_SELECTED_IMAGE.equals(category)) {
            sb.append("试贴图库选中图");
            sb.append(" · ").append(selectedImageId.isEmpty() || !style.isActive()
                    ? "未选中可用图片" : ThemeStore.imageDisplayName(selectedImageId));
            if (style.isActive()) {
                sb.append(" · 适配 ").append(style.getMode().label());
            }
        } else {
            sb.append(ThemeStore.categoryLabel(category));
            if (style.isAnimated()) {
                sb.append(" · 动态背景 ").append(MediaLibrary.mediaDisplayName(style.getMediaId()));
                sb.append(" · ").append(MediaLibrary.mediaInfoText(style.getMediaId()));
            } else {
                sb.append(" · ").append(style.isActive()
                        ? ThemeStore.imageDisplayName(style.getImageFile().getName()) : "无图");
                if (style.isActive() && !ThemeStore.categoryHasOwnImage(category)) {
                    sb.append("（继承全局）");
                }
            }
            if (style.isActive()) {
                sb.append(" · ").append(style.getMode().label());
            }
        }
        sb.append(" · 遮罩 ").append(ThemeStore.getMask());
        sb.append(" · 表格浅透 ").append(tableTierText());
        captionLabel.setText(sb.toString());
    }

    private String tableTierText() {
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

    // ===================== 模拟内容（按类别同构） =====================

    /** 按预览类别重建模拟内容：配置名单=表格、方案管理=列表、其余=主窗样式（大字+按钮阵）。 */
    private void rebuildMockContent() {
        if (mockContent == null) {
            return;
        }
        mockContent.removeAll();
        if (ThemeStore.CATEGORY_CONFIG.equals(category)) {
            mockContent.add(mockToolbarRow("预设方案:", "抽取模式:"), BorderLayout.NORTH);
            mockContent.add(tableScroll, BorderLayout.CENTER);
            mockContent.add(mockButtonRow("导入名单", "保存并关闭"), BorderLayout.SOUTH);
        } else if (ThemeStore.CATEGORY_SCHEME.equals(category)) {
            mockContent.add(mockToolbarRow("方案列表", null), BorderLayout.NORTH);
            mockContent.add(mockListScroll(), BorderLayout.CENTER);
            mockContent.add(mockButtonRow("新建方案", "删除方案"), BorderLayout.SOUTH);
        } else {
            mockContent.add(mockToolbarRow("预设方案:", "抽取模式:"), BorderLayout.NORTH);
            JLabel center = new JLabel("请选择方案和模式后开始抽取", SwingConstants.CENTER);
            center.setFont(font(18).deriveFont(Font.BOLD));
            center.setOpaque(false);
            mockContent.add(center, BorderLayout.CENTER);
            mockContent.add(mockButtonRow("开始抽取", "配置名单"), BorderLayout.SOUTH);
        }
        mockContent.setVisible(mockContentVisible);
        mockContent.revalidate();
        mockContent.repaint();
    }

    /** 模拟工具条：标签/下拉/按钮保持原生（真实窗口中它们本就不透明）。 */
    private JPanel mockToolbarRow(String label1, String label2) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        row.setOpaque(false);
        row.add(mockLabel(label1));
        if (label2 == null) {
            JLabel other = new JLabel("（列表区保持原生，不透出照片）");
            other.setFont(font(11));
            other.setOpaque(false);
            row.add(other);
        } else {
            JComboBox<String> combo1 = new JComboBox<>(new String[]{"默认方案"});
            combo1.setFont(font(11));
            row.add(combo1);
            row.add(mockLabel(label2));
            JComboBox<String> combo2 = new JComboBox<>(new String[]{"名单抽取"});
            combo2.setFont(font(11));
            row.add(combo2);
        }
        JButton settings = new JButton("设置");
        settings.setFont(font(11));
        row.add(settings);
        return row;
    }

    private JLabel mockLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(font(11));
        label.setOpaque(false);
        return label;
    }

    private JScrollPane mockListScroll() {
        JList<String> list = new JList<>(new Vector<>(Arrays.asList(
                "默认方案（名单抽取）", "小组名单（名单抽取）", "1-50 数字（数字抽取）")));
        list.setFont(font(12));
        list.setVisibleRowCount(4);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setPreferredSize(new Dimension(340, 140));
        return scroll;
    }

    private JPanel mockButtonRow(String left, String right) {
        JPanel row = new JPanel(new GridLayout(1, 2, 8, 8));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        JButton b1 = new JButton(left);
        b1.setFont(font(12));
        JButton b2 = new JButton(right);
        b2.setFont(font(12));
        row.add(b1);
        row.add(b2);
        return row;
    }

    private Font font(int size) {
        return new Font("微软雅黑", Font.PLAIN, size);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(440, 340);
    }

    /**
     * 画布：背景垫层铺满 + 模拟内容同尺寸覆盖（等价真实窗口 layeredPane 中
     * FRAME_CONTENT_LAYER-1 的背景垫层 + 内容层）。
     */
    private static final class Canvas extends JLayeredPane {

        private final WindowBackdrop backdrop;
        private final JComponent content;

        Canvas(WindowBackdrop backdrop, JComponent content) {
            this.backdrop = backdrop;
            this.content = content;
            Color panelBg = UIManager.getColor("Panel.background");
            setBackground(panelBg != null ? panelBg : Color.LIGHT_GRAY);
            setOpaque(true);
            setBorder(BorderFactory.createLineBorder(new Color(0x99, 0x99, 0x99), 1));
            add(backdrop, JLayeredPane.DEFAULT_LAYER);
            add(content, JLayeredPane.PALETTE_LAYER);
        }

        @Override
        public void doLayout() {
            java.awt.Insets in = getInsets();
            int x = in.left;
            int y = in.top;
            int w = Math.max(1, getWidth() - in.left - in.right);
            int h = Math.max(1, getHeight() - in.top - in.bottom);
            backdrop.setBounds(x, y, w, h);
            content.setBounds(x, y, w, h);
        }
    }
}
