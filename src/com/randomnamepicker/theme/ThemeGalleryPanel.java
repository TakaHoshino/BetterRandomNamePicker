package com.randomnamepicker.theme;

import com.randomnamepicker.core.LogManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * 主题图库**图片列表管理**（独立页 D4）：缩略图列表 + 导入/重命名(别名)/替换/删除/设为全局，
 * 并在列表中直接显示每张图的尺寸、大小与**引用情况**（全局/具体类别/未使用）。
 * <p>
 * 口径：重命名只改**别名**（配置键 {@code theme.name.<id>}），不动磁盘文件名与任何引用；
 * 替换=新图入库+引用重定向+删旧图（{@link ThemeStore#replaceImage}）；删除会一并清理引用与解码缓存。
 * 本面板不做窗口贴图，任何变更通过 {@link Listener} 通知宿主页面执行即时生效。
 * </p>
 */
public class ThemeGalleryPanel extends JPanel {

    /** 图库事件回调（由独立页实现：刷新参数区/预览 + reapplyAll）。 */
    public interface Listener {
        /** 选中项变化（含清空）。 */
        void onGallerySelectionChanged(String imageId);

        /** 图库内容或引用关系变化（导入/删除/替换/改名/设为全局后）。 */
        void onGalleryContentChanged();
    }

    private static final int THUMB_SIZE = 56;
    private static final int CELL_HEIGHT = 66;

    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private final Map<String, ImageIcon> thumbs = new HashMap<>();

    private final JButton importButton = new JButton("导入照片…");
    private final JButton renameButton = new JButton("重命名…");
    private final JButton replaceButton = new JButton("替换…");
    private final JButton deleteButton = new JButton("删除");
    private final JButton setGlobalButton = new JButton("设为全局背景");
    private final JButton openDirButton = new JButton("打开图库目录");
    private final JButton formatHelpButton = new JButton("格式说明…");

    private final JLabel metaLabel = new JLabel();
    private final JLabel usageLabel = new JLabel();
    private final JLabel emptyHint = new JLabel();

    private Listener listener;
    private boolean syncing;

    public ThemeGalleryPanel() {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(0xBB, 0xBB, 0xBB), 1),
                "图片库（列表管理）",
                javax.swing.border.TitledBorder.LEFT,
                javax.swing.border.TitledBorder.TOP,
                new Font("微软雅黑", Font.BOLD, 12)));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        Font btnFont = new Font("微软雅黑", Font.PLAIN, 11);
        for (JButton b : new JButton[]{importButton, renameButton, replaceButton,
                deleteButton, setGlobalButton, openDirButton, formatHelpButton}) {
            b.setFont(btnFont);
        }
        toolbar.add(importButton);
        toolbar.add(renameButton);
        toolbar.add(replaceButton);
        toolbar.add(deleteButton);
        toolbar.add(setGlobalButton);
        toolbar.add(openDirButton);
        toolbar.add(formatHelpButton);
        add(toolbar, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new CellRenderer());
        list.setFixedCellHeight(CELL_HEIGHT);
        list.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        list.setVisibleRowCount(8);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(360, 320));
        add(scroll, BorderLayout.CENTER);

        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        Font infoFont = new Font("微软雅黑", Font.PLAIN, 11);
        metaLabel.setFont(infoFont);
        usageLabel.setFont(infoFont);
        emptyHint.setFont(infoFont);
        emptyHint.setForeground(new Color(0x8A, 0x5A, 0x00));
        emptyHint.setText("<html>尚未导入图片：点“导入照片…”添加；未导入/未启用时所有窗口零变化。<br>"
                + "当前可解码格式：" + ThemeStore.supportedExtensionsText()
                + "（WebP 等可看“格式说明…”：放 lib/ 插件或启用 ffmpeg 自动转换）</html>");
        emptyHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        metaLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        usageLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        info.add(emptyHint);
        info.add(Box.createVerticalStrut(2));
        info.add(metaLabel);
        info.add(Box.createVerticalStrut(2));
        info.add(usageLabel);
        add(info, BorderLayout.SOUTH);

        wireEvents();
        refresh();
    }

    // ===================== 对外 API =====================

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 重载列表（尽量保持选中项）。 */
    public void refresh() {
        String keep = getSelectedImageId();
        syncing = true;
        try {
            model.clear();
            for (String id : ThemeStore.listImageIdsSorted()) {
                model.addElement(id);
            }
            if (keep != null && !keep.isEmpty() && model.contains(keep)) {
                list.setSelectedValue(keep, true);
            } else if (!model.isEmpty()) {
                list.setSelectedIndex(0);
            }
            boolean empty = model.isEmpty();
            emptyHint.setVisible(empty);
            if (empty) {
                setGlobalButton.setEnabled(false);
            }
        } finally {
            syncing = false;
        }
        updateInfo();
        updateButtons();
    }

    public String getSelectedImageId() {
        Object sel = list.getSelectedValue();
        return sel == null ? "" : sel.toString();
    }

    public void selectImage(String imageId) {
        if (imageId == null || imageId.isEmpty()) {
            list.clearSelection();
            return;
        }
        if (!model.contains(imageId)) {
            refresh();
        }
        if (model.contains(imageId)) {
            list.setSelectedValue(imageId, true);
        }
    }

    // ===================== 事件 =====================

    private void wireEvents() {
        importButton.addActionListener(e -> importPhotos());
        renameButton.addActionListener(e -> renameSelected());
        replaceButton.addActionListener(e -> replaceSelected());
        deleteButton.addActionListener(e -> deleteSelected());
        setGlobalButton.addActionListener(e -> {
            String id = getSelectedImageId();
            if (!id.isEmpty()) {
                ThemeStore.setGlobalImage(id);
                LogManager.log("主题-设为全局背景: " + ThemeStore.imageDisplayName(id), "THEME_IMPORT");
                afterChange(id);
            }
        });
        openDirButton.addActionListener(e -> openImageDir());
        formatHelpButton.addActionListener(e -> showFormatHelp());

        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || syncing) {
                return;
            }
            updateInfo();
            updateButtons();
            if (listener != null) {
                listener.onGallerySelectionChanged(getSelectedImageId());
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && getSelectedImageId() != null
                        && !getSelectedImageId().isEmpty()) {
                    setGlobalButton.doClick();
                }
            }
        });
    }

    private void updateButtons() {
        boolean has = !getSelectedImageId().isEmpty();
        renameButton.setEnabled(has);
        replaceButton.setEnabled(has);
        deleteButton.setEnabled(has);
        setGlobalButton.setEnabled(has);
    }

    private void updateInfo() {
        String id = getSelectedImageId();
        if (id == null || id.isEmpty()) {
            metaLabel.setText("未选中图片");
            usageLabel.setText("引用：-");
            return;
        }
        File file = ThemeStore.imageFile(id);
        metaLabel.setText("选中：" + ThemeStore.imageDisplayName(id)
                + " · " + ThemeStore.imageFormatText(file)
                + " · " + ThemeStore.imageSizeText(file)
                + " · " + ThemeStore.fileSizeText(file));
        boolean decodable = ThemeStore.imageDimension(file) != null;
        if (!decodable) {
            usageLabel.setText("引用：" + ThemeStore.usageText(id)
                    + "　⚠ 该文件无法解码：贴图会被跳过。点“格式说明…”可查看两条修复路径"
                    + "（放 lib/ 插件，或启用 ffmpeg 自动转换后重新导入）");
        } else {
            usageLabel.setText("引用：" + ThemeStore.usageText(id));
        }
    }

    /** 变更后的统一收尾：重载列表 + 保持选中 + 通知宿主页面。 */
    private void afterChange(String keepSelection) {
        refresh();
        if (keepSelection != null && !keepSelection.isEmpty()) {
            selectImage(keepSelection);
        }
        updateInfo();
        updateButtons();
        if (listener != null) {
            listener.onGalleryContentChanged();
        }
    }

    // ===================== 各项操作 =====================

    private void importPhotos() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("导入照片到主题图库");
        chooser.setFileFilter(imageFileFilter());
        if (chooser.showOpenDialog(ownerWindow()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        ThemeStore.ensureDirs();
        List<String> added = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (File f : chooser.getSelectedFiles()) {
            if (!ThemeStore.canDecode(f)) {
                rejected.add(f.getName());
                continue;
            }
            String id = ThemeStore.importImage(f);
            if (id != null) {
                added.add(id);
            } else {
                rejected.add(f.getName());
            }
        }
        if (added.isEmpty()) {
            JOptionPane.showMessageDialog(ownerWindow(),
                    "没有导入任何图片。\n\n" + rejectedText(rejected)
                            + "\n当前环境可解码的格式：\n" + ThemeStore.supportedExtensionsText()
                            + "\n\n打不开的格式（如 WebP）怎么办？点左侧“格式说明…”：\n"
                            + "　· 把 ImageIO 插件 jar 放入 " + ThemeImageIo.libDirPath() + " 后重启；或\n"
                            + "　· 用本机 ffmpeg 自动转换（当前：" + ThemeImageIo.ffmpegStatusText() + "）；或\n"
                            + "　· 先用“照片/画图/浏览器”另存为 PNG/JPG 再导入。",
                    "导入失败", JOptionPane.WARNING_MESSAGE);
            return;
        }
        LogManager.log("主题-导入图片 " + added.size() + " 张"
                + (rejected.isEmpty() ? "" : "，拒收 " + rejected.size() + " 个不可解码文件"), "THEME_IMPORT");
        // 全局尚未指定时自动把首张设为全局背景（沿用既有行为，便于立刻看到效果）
        if (ThemeStore.getGlobalImage().isEmpty()) {
            ThemeStore.setGlobalImage(added.get(0));
        }
        afterChange(added.get(added.size() - 1));
        if (!rejected.isEmpty()) {
            JOptionPane.showMessageDialog(ownerWindow(),
                    "已导入 " + added.size() + " 张；以下文件不可解码、已跳过：\n" + rejectedText(rejected),
                    "部分文件被跳过", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** 文件选择器过滤器：后缀随运行环境实际可解码格式动态生成（装了 lib/ 插件即自动包含 WebP 等）。 */
    private FileNameExtensionFilter imageFileFilter() {
        String[] exts = ThemeStore.supportedExtensions();
        return new FileNameExtensionFilter(
                "图片文件（" + String.join("/", exts) + "）", exts);
    }

    private String rejectedText(List<String> rejected) {
        if (rejected.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String name : rejected) {
            sb.append("　· ").append(name).append("\n");
        }
        return sb.toString();
    }

    private void renameSelected() {
        String id = getSelectedImageId();
        if (id == null || id.isEmpty()) {
            return;
        }
        String current = ThemeStore.getImageName(id);
        String input = (String) JOptionPane.showInputDialog(ownerWindow(),
                "为该图片设置别名（只影响列表与下拉显示，不改磁盘文件名、不影响引用）：",
                "重命名图片",
                JOptionPane.PLAIN_MESSAGE, null, null,
                current.isEmpty() ? ThemeStore.shortId(id) : current);
        if (input == null) {
            return;
        }
        ThemeStore.setImageName(id, input);
        LogManager.log("主题-图片别名: " + id + " → [" + input.trim() + "]", "THEME_IMPORT");
        afterChange(id);
    }

    private void replaceSelected() {
        String id = getSelectedImageId();
        if (id == null || id.isEmpty()) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择替换用的图片（沿用原图别名与全部引用）");
        chooser.setFileFilter(imageFileFilter());
        if (chooser.showOpenDialog(ownerWindow()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String usage = ThemeStore.usageText(id);
        String newId = ThemeStore.replaceImage(id, chooser.getSelectedFile());
        if (newId == null) {
            JOptionPane.showMessageDialog(ownerWindow(),
                    "替换失败：所选文件不可解码。\n\n当前环境可解码的格式：\n"
                            + ThemeStore.supportedExtensionsText(),
                    "替换失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        LogManager.log("主题-替换图片: " + id + " → " + newId + "（原引用：" + usage + "）", "THEME_IMPORT");
        afterChange(newId);
    }

    private void deleteSelected() {
        String id = getSelectedImageId();
        if (id == null || id.isEmpty()) {
            return;
        }
        String usage = ThemeStore.usageText(id);
        int confirm = JOptionPane.showConfirmDialog(ownerWindow(),
                "确定删除图片 “" + ThemeStore.imageDisplayName(id) + "” 吗？\n"
                        + "当前引用：" + usage + "\n"
                        + "删除后这些位置会被清空（类别回继承全局、全局回无图），磁盘文件一并删除。",
                "删除图片", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        boolean ok = ThemeStore.deleteImageAndCleanup(id);
        LogManager.log("主题-删除图片: " + id + "（文件删除=" + ok + "，原引用：" + usage + "）", "THEME_IMPORT");
        thumbs.remove(id);
        afterChange("");
    }

    private void openImageDir() {
        ThemeStore.ensureDirs();
        File dir = new File(ThemeStore.IMAGES_DIR).getAbsoluteFile();
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir);
                return;
            }
        } catch (Exception e) {
            LogManager.log("主题-打开图库目录失败: " + e, "THEME_DEBUG");
        }
        JOptionPane.showMessageDialog(ownerWindow(),
                "图库目录：\n" + dir.getAbsolutePath(), "图库目录", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * “格式说明…”：把"能解码哪些格式、插件放哪、ffmpeg 兜底"讲清楚，并就地提供 ffmpeg 开关。
     * 用户遇到"某张图打不开"时，这里给出两条可执行路径（放插件 / 用 ffmpeg 自动转换）。
     */
    private void showFormatHelp() {
        JCheckBox ffmpegCheck = new JCheckBox("允许用本机 ffmpeg 把打不开的图片（WebP/AVIF/HEIC 等）自动转成 PNG 后导入",
                ThemeImageIo.ffmpegEnabled());
        ffmpegCheck.setFont(new Font("微软雅黑", Font.PLAIN, 12));

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel l1 = new JLabel("当前环境可解码的图片格式：" + ThemeStore.supportedExtensionsText());
        JLabel l2 = new JLabel("ImageIO 插件目录（放 .jar 后重启本程序即可扩展格式）：");
        JLabel l3 = new JLabel("    " + ThemeImageIo.libDirPath());
        JLabel l4 = new JLabel("    插件扫描：" + ThemeImageIo.scanNote());
        JLabel l5 = new JLabel("本机 ffmpeg：" + ThemeImageIo.ffmpegStatusText());
        JLabel l6 = new JLabel("说明：JDK 不带 WebP 解码器。可二选一——");
        JLabel l7 = new JLabel("    ① 把 WebP 的 ImageIO 读取器 jar 放进上面的插件目录（重启生效）；");
        JLabel l8 = new JLabel("    ② 勾选下面的 ffmpeg 兜底（导入时自动转 PNG，运行时不需要 ffmpeg）。");
        JLabel l9 = new JLabel("    也可直接用“照片/画图/浏览器”把图另存为 PNG 或 JPG 再导入。");
        for (JLabel l : new JLabel[]{l1, l2, l3, l4, l5, l6, l7, l8, l9}) {
            l.setFont(new Font("微软雅黑", Font.PLAIN, 12));
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(l);
        }
        panel.add(Box.createVerticalStrut(8));
        ffmpegCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(ffmpegCheck);

        int result = JOptionPane.showConfirmDialog(ownerWindow(), panel, "图片格式说明",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            ThemeImageIo.setFfmpegEnabled(ffmpegCheck.isSelected());
            LogManager.log("主题-ffmpeg 兜底开关设为 " + ffmpegCheck.isSelected(), "THEME_DEBUG");
            refresh();
            if (listener != null) {
                listener.onGalleryContentChanged();
            }
        }
    }

    private Window ownerWindow() {
        return SwingUtilities.getWindowAncestor(this);
    }

    // ===================== 缩略图与渲染 =====================

    private Icon thumbnail(String imageId) {
        if (thumbs.containsKey(imageId)) {
            return thumbs.get(imageId);
        }
        File file = ThemeStore.imageFile(imageId);
        ImageIcon icon = null;
        if (file != null) {
            try {
                BufferedImage src = ImageIO.read(file);
                if (src != null) {
                    icon = new ImageIcon(scaleToBox(src, THUMB_SIZE));
                }
            } catch (Exception ignore) {
                icon = null;
            }
        }
        if (icon == null) {
            icon = new ImageIcon(missingThumb());
        }
        thumbs.put(imageId, icon);
        return icon;
    }

    /** 等比缩放进 THUMB_SIZE×THUMB_SIZE 方框（居中，浅灰底 + 细边框）。 */
    private BufferedImage scaleToBox(BufferedImage src, int box) {
        BufferedImage out = new BufferedImage(box, box, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(0xF2, 0xF2, 0xF2));
        g.fillRect(0, 0, box, box);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double scale = Math.min((double) box / src.getWidth(), (double) box / src.getHeight());
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        g.drawImage(src, (box - w) / 2, (box - h) / 2, w, h, null);
        g.setColor(new Color(0xCC, 0xCC, 0xCC));
        g.drawRect(0, 0, box - 1, box - 1);
        g.dispose();
        return out;
    }

    private BufferedImage missingThumb() {
        BufferedImage out = new BufferedImage(THUMB_SIZE, THUMB_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(0xEE, 0xEE, 0xEE));
        g.fillRect(0, 0, THUMB_SIZE, THUMB_SIZE);
        g.setColor(new Color(0xC0, 0x30, 0x30));
        g.drawLine(4, 4, THUMB_SIZE - 4, THUMB_SIZE - 4);
        g.drawLine(THUMB_SIZE - 4, 4, 4, THUMB_SIZE - 4);
        g.setColor(new Color(0x99, 0x99, 0x99));
        g.drawRect(0, 0, THUMB_SIZE - 1, THUMB_SIZE - 1);
        g.dispose();
        return out;
    }

    /** 列表单元格：缩略图 + 显示名 + 格式/尺寸/大小/引用情况（HTML 两行）。 */
    private final class CellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                     boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            String id = String.valueOf(value);
            setIcon(thumbnail(id));
            setIconTextGap(8);
            setVerticalAlignment(SwingConstants.CENTER);
            File file = ThemeStore.imageFile(id);
            boolean decodable = ThemeStore.imageDimension(file) != null;
            String usage = ThemeStore.usageText(id);
            boolean used = !"未使用".equals(usage);
            String badge;
            if (!decodable) {
                badge = " <font color='#C03030'>[无法解码·跳过贴图]</font>";
            } else if (used) {
                badge = " <font color='#1B6B1B'>[" + escape(usage) + "]</font>";
            } else {
                badge = " <font color='#888888'>[未使用]</font>";
            }
            setText("<html><b>" + escape(ThemeStore.imageDisplayName(id)) + "</b><br>"
                    + "<font color='#555555'>" + escape(ThemeStore.imageFormatText(file))
                    + " · " + escape(ThemeStore.imageSizeText(file))
                    + " · " + escape(ThemeStore.fileSizeText(file)) + "</font>"
                    + badge
                    + "</html>");
            return this;
        }

        private String escape(String s) {
            if (s == null) {
                return "";
            }
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
