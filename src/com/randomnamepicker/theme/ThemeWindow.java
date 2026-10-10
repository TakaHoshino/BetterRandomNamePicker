package com.randomnamepicker.theme;

import com.randomnamepicker.core.LogManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;

/**
 * **背景主题独立页面**（宿主内建主题，本轮 D1/D2/D3）：图库图片列表管理（左）+ 活体预览（右）
 * + 全局与按窗口类别参数（右下），所有改动**即时持久化并即时重排所有已开窗口**。
 * <p>
 * 形态取舍：**无模态、单实例**（`openWindow` 复用已开实例）——主题调参必须能"边调边看"真实窗口，
 * 模态页会把主窗一起锁住。本类自身**不参与贴图**（类名不在 {@code BackdropManager} 映射表内，D8），
 * 以免编辑主题的窗口被主题覆盖、影响判读。
 * </p>
 */
public class ThemeWindow extends JDialog {

    private static final String EMPTY_GLOBAL = "（无）";
    private static final String EMPTY_CATEGORY = "（继承全局）";
    private static final String PREVIEW_SELECTED = "图库选中图（试贴）";

    private static ThemeWindow instance;

    private final JCheckBox enabledCheck = new JCheckBox("启用照片背景");
    private final JLabel statusLabel = new JLabel();
    private final JButton previewWindowButton = new JButton("独立预览窗口…");
    private final JButton resetButton = new JButton("恢复默认");
    private final JButton closeButton = new JButton("关闭");

    private final ThemeGalleryPanel gallery = new ThemeGalleryPanel();
    private final ThemePreviewPanel preview = new ThemePreviewPanel();
    private final JComboBox<String> previewTargetCombo = new JComboBox<>();
    private final JCheckBox previewMockCheck = new JCheckBox("显示模拟内容", true);

    private final ImageCombo globalImageCombo = new ImageCombo();
    private final JComboBox<String> globalModeCombo = new JComboBox<>(BackdropMode.labels());
    private final JButton globalCropButton = new JButton("裁剪…");
    private final JSlider maskSlider = new JSlider(SwingConstants.HORIZONTAL,
            ThemeStore.MASK_MIN, ThemeStore.MASK_MAX, ThemeStore.MASK_DEFAULT);
    private final JLabel maskLabel = new JLabel();
    private final JComboBox<String> tierCombo = new JComboBox<>(
            new String[]{"表格区原生", "表格浅透（浅）", "表格浅透（中）"});

    private final ImageCombo[] catImageCombos = new ImageCombo[ThemeStore.CATEGORY_IDS.length];
    private final JComboBox<String>[] catModeCombos;
    private final JButton[] catCropButtons = new JButton[ThemeStore.CATEGORY_IDS.length];

    // ---- 动态背景（动图 / 帧序列 / 背景音，2026-10 路线 A） ----
    private final ImageCombo mediaCombo = new ImageCombo();
    private final ImageCombo audioCombo = new ImageCombo();
    private final JButton importAnimButton = new JButton("导入动图 GIF…");
    private final JButton importSeqButton = new JButton("导入帧序列目录…");
    private final JButton deleteMediaButton = new JButton("删除");
    private final JButton importAudioButton = new JButton("导入音频…");
    private final JButton previewAudioButton = new JButton("试听/停止");
    private final JButton deleteAudioButton = new JButton("删除");
    private final JSpinner fpsSpinner = new JSpinner(new SpinnerNumberModel(
            MediaLibrary.DEFAULT_FPS, MediaLibrary.FPS_MIN, MediaLibrary.FPS_MAX, 1));
    private final JCheckBox loopCheck = new JCheckBox("循环", true);
    private final JCheckBox muteCheck = new JCheckBox("静音", true);
    private final JSlider volumeSlider = new JSlider(SwingConstants.HORIZONTAL, 0, 100, MediaLibrary.VOLUME_DEFAULT);
    private final JLabel volumeLabel = new JLabel();
    private final JLabel mediaInfoLabel = new JLabel();

    private ThemePreviewWindow previewWindow;
    private boolean syncing;

    @SuppressWarnings("unchecked")
    private ThemeWindow(Window owner) {
        super(owner, "背景主题设置", ModalityType.MODELESS);
        catModeCombos = new JComboBox[ThemeStore.CATEGORY_IDS.length];
        for (int i = 0; i < ThemeStore.CATEGORY_IDS.length; i++) {
            catImageCombos[i] = new ImageCombo();
            catModeCombos[i] = new JComboBox<>(BackdropMode.labels());
        }

        setLayout(new BorderLayout(8, 8));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1040, 700);
        setMinimumSize(new Dimension(860, 560));
        setLocationRelativeTo(owner);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (instance == ThemeWindow.this) {
                    instance = null;
                }
            }

            @Override
            public void windowActivated(WindowEvent e) {
                // 重新聚焦时与 store 对齐：外部改动（其它窗口/插件/直接改配置）也能反映到列表与预览
                if (!syncing) {
                    syncFromStore();
                    gallery.refresh();
                }
            }
        });

        wireEvents();
        syncFromStore();
    }

    // ===================== 单实例开放入口 =====================

    /** 打开独立页（EDT 安全；已开则前置复用）。 */
    public static void openWindow(final Window owner) {
        if (SwingUtilities.isEventDispatchThread()) {
            openOnEdt(owner);
        } else {
            SwingUtilities.invokeLater(() -> openOnEdt(owner));
        }
    }

    private static void openOnEdt(Window owner) {
        if (instance != null && instance.isDisplayable()) {
            instance.toFront();
            instance.requestFocus();
            return;
        }
        instance = new ThemeWindow(owner);
        instance.setVisible(true);
        LogManager.log("主题-独立页打开（图库 " + ThemeStore.listImages().size() + " 张，启用="
                + ThemeStore.isEnabled() + "）", "THEME_DEBUG");
    }

    // ===================== 版面 =====================

    private JPanel buildHeader() {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        enabledCheck.setFont(font(13));
        statusLabel.setFont(font(12));
        previewWindowButton.setFont(font(12));
        resetButton.setFont(font(12));
        closeButton.setFont(font(12));
        header.add(enabledCheck);
        header.add(statusLabel);
        header.add(Box.createHorizontalStrut(12));
        header.add(previewWindowButton);
        header.add(resetButton);
        header.add(closeButton);
        return header;
    }

    private JPanel buildBody() {
        gallery.setListener(new ThemeGalleryPanel.Listener() {
            @Override
            public void onGallerySelectionChanged(String imageId) {
                preview.setSelectedImageId(imageId);
            }

            @Override
            public void onGalleryContentChanged() {
                // 图库变化（导入/删除/替换/改名/设为全局）：参数下拉与预览同步，并即时重排真实窗口
                syncFromStore();
                applyToWindows();
            }
        });

        JPanel right = new JPanel(new BorderLayout(4, 4));
        right.add(buildPreviewArea(), BorderLayout.NORTH);
        right.add(new JScrollPane(buildParams()), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, gallery, right);
        split.setResizeWeight(0.34);
        split.setDividerLocation(360);
        JPanel body = new JPanel(new BorderLayout());
        body.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        body.add(split, BorderLayout.CENTER);
        return body;
    }

    private JPanel buildPreviewArea() {
        JPanel panel = new JPanel(new BorderLayout(4, 2));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        previewTargetCombo.setFont(font(11));
        previewTargetCombo.addItem(PREVIEW_SELECTED);
        for (String label : ThemeStore.categoryLabels()) {
            previewTargetCombo.addItem(label);
        }
        previewTargetCombo.setSelectedIndex(1); // 默认预览“主窗口”
        previewMockCheck.setFont(font(11));
        previewMockCheck.setSelected(true);
        JLabel hint = new JLabel("（预览与真实窗口同一渲染路径；拖动“独立预览窗口”可验证缩放重排）");
        hint.setFont(font(11));
        row.add(new JLabel("预览："));
        row.add(previewTargetCombo);
        row.add(previewMockCheck);
        row.add(hint);
        panel.add(row, BorderLayout.NORTH);
        panel.add(preview, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        return panel;
    }

    private JPanel buildParams() {
        JPanel params = new JPanel();
        params.setLayout(new BoxLayout(params, BoxLayout.Y_AXIS));
        params.setBorder(BorderFactory.createEmptyBorder(2, 2, 8, 2));

        // ---- 全局背景 ----
        JPanel globalPanel = section("全局背景（未单独指定的窗口类别继承此设置）");
        JPanel globalRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        globalRow.add(label("图片：", 46));
        globalImageCombo.setFont(font(11));
        globalImageCombo.setPreferredSize(new Dimension(210, 26));
        globalRow.add(globalImageCombo);
        globalRow.add(label("适配：", 46));
        globalModeCombo.setFont(font(11));
        globalModeCombo.setPreferredSize(new Dimension(160, 26));
        globalRow.add(globalModeCombo);
        globalCropButton.setFont(font(11));
        globalCropButton.setToolTipText("自由裁切（仅“自由裁切”模式生效）");
        globalRow.add(globalCropButton);
        globalPanel.add(globalRow);

        JPanel maskRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        maskRow.add(label("遮罩：", 46));
        maskSlider.setPreferredSize(new Dimension(200, 44));
        maskSlider.setMajorTickSpacing(60);
        maskSlider.setMinorTickSpacing(15);
        maskSlider.setPaintTicks(true);
        maskLabel.setFont(font(12));
        maskRow.add(maskSlider);
        maskRow.add(maskLabel);
        maskRow.add(label("表格浅透：", 70));
        tierCombo.setFont(font(11));
        tierCombo.setMaximumRowCount(3);
        maskRow.add(tierCombo);
        globalPanel.add(maskRow);
        params.add(globalPanel);
        params.add(Box.createVerticalStrut(6));

        // ---- 动态背景（动图 / 帧序列 / 背景音） ----
        params.add(buildMediaSection());
        params.add(Box.createVerticalStrut(6));

        // ---- 按窗口类别 ----
        JPanel catPanel = section("按窗口类别指定（未指定=继承全局图与全局适配）");
        for (int i = 0; i < ThemeStore.CATEGORY_IDS.length; i++) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1));
            row.add(label(ThemeStore.categoryLabel(ThemeStore.CATEGORY_IDS[i]), 80));
            catImageCombos[i].setFont(font(11));
            catImageCombos[i].setPreferredSize(new Dimension(210, 24));
            catImageCombos[i].setMaximumRowCount(12);
            row.add(catImageCombos[i]);
            catModeCombos[i].setFont(font(11));
            catModeCombos[i].setPreferredSize(new Dimension(160, 24));
            row.add(catModeCombos[i]);
            catCropButtons[i] = new JButton("裁");
            catCropButtons[i].setFont(font(10));
            catCropButtons[i].setPreferredSize(new Dimension(46, 24));
            catCropButtons[i].setToolTipText("自由裁切（仅“自由裁切”模式生效）");
            row.add(catCropButtons[i]);
            catPanel.add(row);
        }
        params.add(catPanel);
        return params;
    }

    /** 动态背景分节：动图/帧序列（全局）+ 背景音 + 帧率/循环/音量/静音。 */
    private JPanel buildMediaSection() {
        JPanel panel = section("动态背景（动图 / 帧序列 / 背景音，零依赖路线 A）");

        JPanel animRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        animRow.add(label("动态背景：", 70));
        mediaCombo.setFont(font(11));
        mediaCombo.setPreferredSize(new Dimension(210, 24));
        mediaCombo.setMaximumRowCount(12);
        animRow.add(mediaCombo);
        for (JButton b : new JButton[]{importAnimButton, importSeqButton, deleteMediaButton}) {
            b.setFont(font(11));
            animRow.add(b);
        }
        panel.add(animRow);

        JPanel paramRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        paramRow.add(label("帧序列帧率：", 90));
        fpsSpinner.setFont(font(11));
        ((JSpinner.DefaultEditor) fpsSpinner.getEditor()).getTextField()
                .setColumns(3);
        paramRow.add(fpsSpinner);
        paramRow.add(new JLabel("fps"));
        loopCheck.setFont(font(11));
        paramRow.add(loopCheck);
        paramRow.add(Box.createHorizontalStrut(10));
        paramRow.add(label("背景音：", 60));
        audioCombo.setFont(font(11));
        audioCombo.setPreferredSize(new Dimension(160, 24));
        audioCombo.setMaximumRowCount(12);
        paramRow.add(audioCombo);
        for (JButton b : new JButton[]{importAudioButton, previewAudioButton, deleteAudioButton}) {
            b.setFont(font(11));
            paramRow.add(b);
        }
        panel.add(paramRow);

        JPanel volumeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        volumeRow.add(label("音量：", 70));
        volumeSlider.setPreferredSize(new Dimension(180, 40));
        volumeSlider.setMajorTickSpacing(25);
        volumeSlider.setMinorTickSpacing(5);
        volumeSlider.setPaintTicks(true);
        volumeLabel.setFont(font(11));
        volumeRow.add(volumeSlider);
        volumeRow.add(volumeLabel);
        muteCheck.setFont(font(11));
        volumeRow.add(muteCheck);
        panel.add(volumeRow);

        JPanel infoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        mediaInfoLabel.setFont(font(11));
        mediaInfoLabel.setForeground(new java.awt.Color(0x44, 0x44, 0x44));
        infoRow.add(mediaInfoLabel);
        panel.add(infoRow);

        JLabel hint = new JLabel("优先级：某类别单独指定的静态图 > 全局动态背景 > 全局静态图；"
                + "GIF 用自带帧时长，帧序列目录用上面的帧率。背景音支持 WAV/AIFF/AU（PCM）。");
        hint.setFont(font(11));
        panel.add(hint);
        return panel;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JLabel hint = new JLabel("改动即时生效并写入配置；未导入图片或未启用时所有窗口零变化；"
                + "“恢复默认”会关闭主题并清空图片/动态背景引用（不删除媒体文件）。");
        hint.setFont(font(11));
        footer.add(hint);
        return footer;
    }

    private JPanel section(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new TitledBorder(
                BorderFactory.createLineBorder(new java.awt.Color(0xBB, 0xBB, 0xBB), 1),
                title, TitledBorder.LEFT, TitledBorder.TOP, font(12).deriveFont(Font.BOLD)));
        return p;
    }

    private JLabel label(String text, int width) {
        JLabel l = new JLabel(text);
        l.setFont(font(12));
        l.setPreferredSize(new Dimension(width, 24));
        return l;
    }

    private Font font(int size) {
        return new Font("微软雅黑", Font.PLAIN, size);
    }

    // ===================== 事件接线 =====================

    private void wireEvents() {
        enabledCheck.addActionListener(e -> {
            if (syncing) {
                return;
            }
            ThemeStore.setEnabled(enabledCheck.isSelected());
            applyToWindows();
        });

        resetButton.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "恢复默认：关闭照片背景、遮罩/表格档回默认、清空全局与全部类别的图片指定。\n"
                            + "图库文件会保留。确定继续吗？",
                    "恢复默认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
            ThemeStore.resetToDefaults();
            LogManager.log("主题-恢复默认", "THEME_DEBUG");
            syncFromStore();
            gallery.refresh();
            applyToWindows();
        });

        closeButton.addActionListener(e -> dispose());
        previewWindowButton.addActionListener(e -> openPreviewWindow());

        previewTargetCombo.addActionListener(e -> {
            if (syncing) {
                return;
            }
            int idx = previewTargetCombo.getSelectedIndex();
            preview.setCategory(idx <= 0 ? ThemePreviewPanel.TARGET_SELECTED_IMAGE
                    : ThemeStore.CATEGORY_IDS[idx - 1]);
        });
        previewMockCheck.addActionListener(e -> preview.setMockContentVisible(previewMockCheck.isSelected()));

        globalImageCombo.addActionListener(e -> {
            if (syncing) {
                return;
            }
            ThemeStore.setGlobalImage(globalImageCombo.getSelectedId());
            afterParamChange();
        });
        globalModeCombo.addActionListener(e -> {
            if (syncing) {
                return;
            }
            ThemeStore.setGlobalMode(selectedMode(globalModeCombo));
            afterParamChange();
        });
        globalCropButton.addActionListener(e -> chooseCrop(null));

        maskSlider.addChangeListener(e -> {
            if (syncing) {
                return;
            }
            int v = maskSlider.getValue();
            maskLabel.setText(v + (v == 0 ? "（关闭）" : ""));
            ThemeStore.setMask(v);
            preview.refresh();
            BackdropManager.getInstance().reapplyAll();
        });

        tierCombo.addActionListener(e -> {
            if (syncing) {
                return;
            }
            ThemeStore.setTableTier(tierCombo.getSelectedIndex());
            preview.refresh();
            BackdropManager.getInstance().reapplyAll();
        });

        for (int i = 0; i < ThemeStore.CATEGORY_IDS.length; i++) {
            final int idx = i;
            catImageCombos[i].addActionListener(e -> {
                if (syncing) {
                    return;
                }
                ThemeStore.setCategoryImage(ThemeStore.CATEGORY_IDS[idx], catImageCombos[idx].getSelectedId());
                afterParamChange();
            });
            catModeCombos[i].addActionListener(e -> {
                if (syncing) {
                    return;
                }
                ThemeStore.setCategoryMode(ThemeStore.CATEGORY_IDS[idx], selectedMode(catModeCombos[idx]));
                afterParamChange();
            });
            catCropButtons[i].addActionListener(e -> chooseCrop(ThemeStore.CATEGORY_IDS[idx]));
        }

        // ---- 动态背景 / 背景音（路线 A） ----
        mediaCombo.addActionListener(e -> {
            if (syncing) {
                return;
            }
            MediaLibrary.setMediaId(mediaCombo.getSelectedId());
            afterParamChange();
        });
        audioCombo.addActionListener(e -> {
            if (syncing) {
                return;
            }
            MediaLibrary.setAudioId(audioCombo.getSelectedId());
            afterParamChange();
        });
        importAnimButton.addActionListener(e -> importAnimation());
        importSeqButton.addActionListener(e -> importFrameSequence());
        deleteMediaButton.addActionListener(e -> deleteSelectedMedia(false));
        importAudioButton.addActionListener(e -> importAudio());
        previewAudioButton.addActionListener(e -> ThemeAudio.getInstance().previewNow());
        deleteAudioButton.addActionListener(e -> deleteSelectedMedia(true));
        fpsSpinner.addChangeListener(e -> {
            if (syncing) {
                return;
            }
            MediaLibrary.setFps(((Number) fpsSpinner.getValue()).intValue());
            afterParamChange();
        });
        loopCheck.addActionListener(e -> {
            if (syncing) {
                return;
            }
            MediaLibrary.setLoop(loopCheck.isSelected());
        });
        muteCheck.addActionListener(e -> {
            if (syncing) {
                return;
            }
            MediaLibrary.setMuted(muteCheck.isSelected());
            ThemeAudio.getInstance().syncFromStore();
        });
        volumeSlider.addChangeListener(e -> {
            if (syncing) {
                return;
            }
            int v = volumeSlider.getValue();
            volumeLabel.setText(v == 0 ? "0（静音）" : String.valueOf(v));
            MediaLibrary.setVolume(v);
            ThemeAudio.getInstance().syncFromStore();
        });
    }

    // ===================== 动态背景 / 背景音操作 =====================

    private void importAnimation() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导入动图 GIF 或序列 ZIP（需多帧）");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "动图/序列 (gif, zip)", "gif", "zip"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String id = MediaLibrary.importAnimation(chooser.getSelectedFile());
        if (id == null) {
            JOptionPane.showMessageDialog(this,
                    "导入失败：GIF 需为多帧动图、ZIP 需含 ≥2 张可解码图片。",
                    "导入动态背景失败", JOptionPane.WARNING_MESSAGE);
            return;
        }
        MediaLibrary.setMediaId(id);
        syncFromStore();
        gallery.refresh();
        applyToWindows();
    }

    private void importFrameSequence() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择帧序列目录（目录内图片按文件名自然序，≥2 张）");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String id = MediaLibrary.importFrameSequence(chooser.getSelectedFile());
        if (id == null) {
            JOptionPane.showMessageDialog(this,
                    "导入失败：目录需含 ≥2 张可解码图片，且不超过 " + MediaLibrary.MAX_SEQ_FRAMES + " 张。",
                    "导入帧序列失败", JOptionPane.WARNING_MESSAGE);
            return;
        }
        MediaLibrary.setMediaId(id);
        syncFromStore();
        gallery.refresh();
        applyToWindows();
    }

    private void importAudio() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导入背景音（WAV/AIFF/AU，PCM）");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "音频 (wav, aiff, aif, au)", "wav", "aiff", "aif", "au"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String id = MediaLibrary.importAudio(chooser.getSelectedFile());
        if (id == null) {
            JOptionPane.showMessageDialog(this,
                    "导入失败：仅支持本机音频系统能解析的 WAV/AIFF/AU（PCM）。",
                    "导入背景音失败", JOptionPane.WARNING_MESSAGE);
            return;
        }
        MediaLibrary.setAudioId(id);
        MediaLibrary.setMuted(false); // 明确导入了音频 → 自动取消静音，便于立刻确认效果
        syncFromStore();
        applyToWindows();
    }

    private void deleteSelectedMedia(boolean audio) {
        String id = audio ? audioCombo.getSelectedId() : mediaCombo.getSelectedId();
        if (id == null || id.isEmpty()) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "确定删除 “" + MediaLibrary.mediaDisplayName(id) + "” 吗？\n"
                        + MediaLibrary.mediaInfoText(id) + "\n"
                        + "引用它的位置会被清空，磁盘文件一并删除。",
                "删除媒体", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        MediaLibrary.deleteMediaAndCleanup(id);
        syncFromStore();
        applyToWindows();
    }

    private BackdropMode selectedMode(JComboBox<String> combo) {
        int i = Math.max(0, combo.getSelectedIndex());
        BackdropMode[] all = BackdropMode.values();
        return all[Math.min(i, all.length - 1)];
    }

    /** 参数变更后的统一收尾：同步图库展示（引用情况可能变）+ 预览 + 真实窗口。 */
    private void afterParamChange() {
        gallery.refresh();
        preview.refresh();
        updateStatus();
        updateMediaInfo();
        updateCropEnabled();
        BackdropManager.getInstance().reapplyAll();
    }

    private void applyToWindows() {
        preview.refresh();
        updateStatus();
        updateMediaInfo();
        BackdropManager.getInstance().reapplyAll();
    }

    private void chooseCrop(String category) {
        String imageId = category == null ? ThemeStore.getGlobalImage()
                : ThemeStore.categoryResolvedImageId(category);
        File file = ThemeStore.imageFile(imageId);
        Rectangle2D.Double initial = ThemeStore.parseCrop(ThemeStore.configCropString(category));
        Rectangle2D.Double crop = CropSelectorDialog.showDialog(this, file, initial);
        if (crop == null) {
            return;
        }
        if (category == null) {
            ThemeStore.setGlobalCrop(crop);
        } else {
            ThemeStore.setCategoryCrop(category, crop);
        }
        afterParamChange();
    }

    private void openPreviewWindow() {
        String target = preview.getCategory();
        if (previewWindow == null || !previewWindow.isDisplayable()) {
            previewWindow = new ThemePreviewWindow(this);
        }
        previewWindow.setPreviewTarget(target, preview.getSelectedImageId(),
                previewMockCheck.isSelected());
        previewWindow.setVisible(true);
        previewWindow.toFront();
    }

    // ===================== 同步 / 状态 =====================

    private void syncFromStore() {
        syncing = true;
        try {
            enabledCheck.setSelected(ThemeStore.isEnabled());
            int mask = ThemeStore.getMask();
            maskSlider.setValue(mask);
            maskLabel.setText(mask == 0 ? "0（关闭）" : String.valueOf(mask));
            tierCombo.setSelectedIndex(ThemeStore.getTableTier());

            List<String> images = ThemeStore.listImageIdsSorted();
            globalImageCombo.setItems(images, EMPTY_GLOBAL, ThemeStore.getGlobalImage());
            selectMode(globalModeCombo, ThemeStore.globalModeNow());
            for (int i = 0; i < ThemeStore.CATEGORY_IDS.length; i++) {
                String cid = ThemeStore.CATEGORY_IDS[i];
                catImageCombos[i].setItems(images, EMPTY_CATEGORY, directCategoryImage(cid));
                selectMode(catModeCombos[i], ThemeStore.categoryModeNow(cid));
            }

            // 动态背景 / 背景音
            mediaCombo.setItems(MediaLibrary.listAnimationIds(), EMPTY_GLOBAL,
                    MediaLibrary.getMediaId(), MediaLibrary::mediaDisplayName);
            audioCombo.setItems(MediaLibrary.listAudioIds(), EMPTY_GLOBAL,
                    MediaLibrary.getAudioId(), MediaLibrary::mediaDisplayName);
            fpsSpinner.setValue(MediaLibrary.getFps());
            loopCheck.setSelected(MediaLibrary.isLoop());
            muteCheck.setSelected(MediaLibrary.isMuted());
            int volume = MediaLibrary.getVolume();
            volumeSlider.setValue(volume);
            volumeLabel.setText(volume == 0 ? "0（静音）" : String.valueOf(volume));
            updateMediaInfo();

            preview.setSelectedImageId(gallery.getSelectedImageId());
        } finally {
            syncing = false;
        }
        updateStatus();
        updateCropEnabled();
        preview.refresh();
    }

    /** 类别**直接指定**的图片 id（不继承全局；空串=未指定）。 */
    private String directCategoryImage(String category) {
        return ThemeStore.categoryHasOwnImage(category) ? ThemeStore.categoryImageIdNow(category) : "";
    }

    private void selectMode(JComboBox<String> combo, BackdropMode mode) {
        BackdropMode[] all = BackdropMode.values();
        for (int i = 0; i < all.length; i++) {
            if (all[i] == mode) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        combo.setSelectedIndex(0);
    }

    private void updateCropEnabled() {
        globalCropButton.setEnabled(ThemeStore.imageFile(ThemeStore.getGlobalImage()) != null
                && selectedMode(globalModeCombo) == BackdropMode.CROP);
        for (int i = 0; i < ThemeStore.CATEGORY_IDS.length; i++) {
            catCropButtons[i].setEnabled(ThemeStore.imageFile(
                    ThemeStore.categoryResolvedImageId(ThemeStore.CATEGORY_IDS[i])) != null
                    && selectedMode(catModeCombos[i]) == BackdropMode.CROP);
        }
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append(ThemeStore.isEnabled() ? "已启用" : "未启用（窗口零变化，右侧为效果预览）");
        sb.append(" · 图库 ").append(ThemeStore.listImages().size()).append(" 张");
        String globalImage = ThemeStore.getGlobalImage();
        sb.append(" · 全局：").append(globalImage.isEmpty()
                ? "无图" : ThemeStore.imageDisplayName(globalImage));
        sb.append(" · ").append(ThemeStore.globalModeNow().label());
        sb.append(" · 遮罩 ").append(ThemeStore.getMask());
        String mediaId = MediaLibrary.getMediaId();
        sb.append(" · 动态背景：").append(mediaId.isEmpty() ? "无" : MediaLibrary.mediaDisplayName(mediaId));
        int overridden = 0;
        for (String cid : ThemeStore.CATEGORY_IDS) {
            if (ThemeStore.categoryHasOwnImage(cid)) {
                overridden++;
            }
        }
        sb.append(" · 单独指定类别 ").append(overridden).append("/").append(ThemeStore.CATEGORY_IDS.length);
        statusLabel.setText(sb.toString());
    }

    /** 动态背景/背景音元信息行（不可用文件也如实说明原因）。 */
    private void updateMediaInfo() {
        String mediaId = MediaLibrary.getMediaId();
        String audioId = MediaLibrary.getAudioId();
        StringBuilder sb = new StringBuilder();
        sb.append("动态背景：");
        if (mediaId.isEmpty()) {
            sb.append("未设置（未指定类别的窗口用全局静态图）");
        } else if (MediaLibrary.isUsableAnimation(mediaId)) {
            sb.append(MediaLibrary.mediaDisplayName(mediaId)).append(" · ").append(MediaLibrary.mediaInfoText(mediaId));
        } else {
            sb.append(MediaLibrary.mediaDisplayName(mediaId)).append(" · 不可用（文件丢失或多帧不足）");
        }
        sb.append("　|　背景音：");
        if (audioId.isEmpty()) {
            sb.append("未设置");
        } else if (MediaLibrary.isUsableAudio(MediaLibrary.mediaFile(audioId))) {
            sb.append(MediaLibrary.mediaDisplayName(audioId)).append(" · ").append(MediaLibrary.mediaInfoText(audioId));
            sb.append(MediaLibrary.isMuted() ? "（静音中）" : "（播放中）");
        } else {
            sb.append(MediaLibrary.mediaDisplayName(audioId)).append(" · 不可用（需 WAV/AIFF/AU）");
        }
        mediaInfoLabel.setText(sb.toString());
    }

    // ===================== 图片下拉（显示别名，内部映射 id） =====================

    /** 媒体/图片下拉：第 0 项是空项（无/继承），其余按传入 id 顺序对应；展示名由 labeller 决定。 */
    private static final class ImageCombo extends JComboBox<String> {

        private List<String> ids = java.util.Collections.emptyList();

        void setItems(List<String> idList, String emptyItem, String selectedId) {
            setItems(idList, emptyItem, selectedId, ThemeStore::imageDisplayName);
        }

        void setItems(List<String> idList, String emptyItem, String selectedId,
                      java.util.function.Function<String, String> labeller) {
            removeAllItems();
            ids = idList != null ? idList : java.util.Collections.emptyList();
            addItem(emptyItem);
            int sel = 0;
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                addItem(labeller.apply(id));
                if (id.equals(selectedId)) {
                    sel = i + 1;
                }
            }
            setSelectedIndex(sel);
        }

        String getSelectedId() {
            int idx = getSelectedIndex();
            if (idx <= 0 || ids == null || idx - 1 >= ids.size()) {
                return "";
            }
            return ids.get(idx - 1);
        }
    }
}
