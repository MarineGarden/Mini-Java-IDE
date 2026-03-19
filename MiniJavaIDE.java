import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.dnd.*;
import java.awt.datatransfer.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class MiniJavaIDE {

    private static DefaultListModel<String> historyModel = new DefaultListModel<>();
    private static Map<String, String> historyCodeMap = new LinkedHashMap<>();
    private static Map<String, JTextArea> editorMap = new LinkedHashMap<>();

    private static int versionCounter = 1;

    public static void main(String[] args) {

        JFrame frame = new JFrame("Mini Java IDE");
        frame.setSize(1000, 600);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        JTextArea outputArea = new JTextArea();
        outputArea.setEditable(false);

        Font font = UIManager.getFont("TextArea.font").deriveFont(16f);
        outputArea.setFont(font);

        JButton runButton = new JButton("Run");

        // ===== 歷史 =====
        JList<String> historyList = new JList<>(historyModel);
        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        historyList.addListSelectionListener(e -> {

            String selected = historyList.getSelectedValue();

            if (selected != null) {

                String code = historyCodeMap.get(selected);
                String className = getClassName(code);

                openOrUpdateTab(frame, tabbedPane, className, code);

            }
        });

        JScrollPane historyPane = new JScrollPane(historyList);
        historyPane.setPreferredSize(new Dimension(220, 0));

        // ===== Tab區拖曳 =====
        addFileDropSupport(frame, tabbedPane, tabbedPane);

        // ===== Run =====
        runButton.addActionListener(e -> {

            outputArea.setText("");

            try {

                JTextArea codeArea = getCurrentEditor(tabbedPane);

                if (codeArea == null) {

                    outputArea.setText("沒有開啟檔案");
                    return;

                }

                String code = codeArea.getText();

                String className = getClassName(code);
                String fileName = className + ".java";

                Files.writeString(
                        new File(fileName).toPath(),
                        code,
                        StandardCharsets.UTF_8
                );

                ProcessBuilder compileBuilder =
                        new ProcessBuilder("javac", "-encoding", "UTF-8", fileName);

                Process compile = compileBuilder.start();
                compile.waitFor();

                BufferedReader compileError =
                        new BufferedReader(new InputStreamReader(
                                compile.getErrorStream(), StandardCharsets.UTF_8));

                String line;

                while ((line = compileError.readLine()) != null) {

                    outputArea.append(line + "\n");

                }

                if (compile.exitValue() != 0) return;

                String versionName = className + " v" + versionCounter++;

                historyModel.addElement(versionName);
                historyCodeMap.put(versionName, code);

                ProcessBuilder runBuilder =
                        new ProcessBuilder("java", "-cp", ".", className);

                Process run = runBuilder.start();

                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(
                                run.getInputStream(), StandardCharsets.UTF_8));

                BufferedReader errorReader =
                        new BufferedReader(new InputStreamReader(
                                run.getErrorStream(), StandardCharsets.UTF_8));

                while ((line = reader.readLine()) != null) {

                    outputArea.append(line + "\n");

                }

                while ((line = errorReader.readLine()) != null) {

                    outputArea.append(line + "\n");

                }

            } catch (Exception ex) {

                outputArea.setText("Error:\n" + ex.getMessage());

            }

        });

        JSplitPane verticalSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                tabbedPane,
                new JScrollPane(outputArea)
        );

        verticalSplit.setDividerLocation(350);

        JSplitPane mainSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                verticalSplit,
                historyPane
        );

        mainSplit.setDividerLocation(750);

        frame.add(mainSplit, BorderLayout.CENTER);
        frame.add(runButton, BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // ===== 新增檔案拖曳支援 =====
    private static void addFileDropSupport(JFrame frame, JTabbedPane tabbedPane, Component target) {

        new DropTarget(target, new DropTargetAdapter() {

            @Override
            public void drop(DropTargetDropEvent dtde) {

                try {

                    dtde.acceptDrop(DnDConstants.ACTION_COPY);

                    @SuppressWarnings("unchecked")
                    java.util.List<File> droppedFiles =
                            (java.util.List<File>) dtde.getTransferable()
                                    .getTransferData(DataFlavor.javaFileListFlavor);

                    for (File file : droppedFiles) {

                        String content =
                                Files.readString(file.toPath(), StandardCharsets.UTF_8);

                        String className = getClassName(content);

                        openOrUpdateTab(frame, tabbedPane, className, content);

                    }

                } catch (Exception ex) {

                    JOptionPane.showMessageDialog(frame,
                            "讀取檔案失敗: " + ex.getMessage());

                }
            }
        });
    }

    // ===== 拖曳生成JAR =====
    private static void addJarExportFeature(JFrame frame, JTextArea area) {

        final Point[] dragStart = {null};
        final boolean[] isDragging = {false};
        final boolean[] exported = {false};

        area.addMouseListener(new MouseAdapter() {

            public void mousePressed(MouseEvent e) {

                if (SwingUtilities.isLeftMouseButton(e)) {

                    dragStart[0] = e.getPoint();
                    isDragging[0] = false;
                    exported[0] = false;

                }
            }

            public void mouseReleased(MouseEvent e) {

                dragStart[0] = null;
                isDragging[0] = false;
                exported[0] = false;

            }
        });

        area.addMouseMotionListener(new MouseMotionAdapter() {

            public void mouseDragged(MouseEvent e) {

                if (dragStart[0] == null) return;

                if (dragStart[0].distance(e.getPoint()) > 5) {
                    isDragging[0] = true;
                }

                if (!isDragging[0] || exported[0]) return;

                Point p = SwingUtilities.convertPoint(area, e.getPoint(), frame);

                if (!frame.getBounds().contains(p)) {

                    try {

                        String code = area.getText();
                        String className = getClassName(code);

                        Path desktop = Paths.get(
                                System.getProperty("user.home"),
                                "Desktop"
                        );

                        Path javaFile = desktop.resolve(className + ".java");
                        Path jarFile = desktop.resolve(className + ".jar");

                        Files.writeString(javaFile, code, StandardCharsets.UTF_8);

                        ProcessBuilder compileBuilder =
                                new ProcessBuilder("javac", javaFile.toString());

                        compileBuilder.directory(desktop.toFile());

                        Process compile = compileBuilder.start();
                        compile.waitFor();

                        if (compile.exitValue() != 0) return;

                        Path manifest = desktop.resolve("manifest.txt");

                        Files.writeString(
                                manifest,
                                "Manifest-Version: 1.0\nMain-Class: " + className + "\n",
                                StandardCharsets.UTF_8
                        );

                        ProcessBuilder jarBuilder = new ProcessBuilder(
                                "jar",
                                "cfm",
                                jarFile.toString(),
                                manifest.toString(),
                                "-C",
                                desktop.toString(),
                                "."
                        );

                        jarBuilder.directory(desktop.toFile());

                        Process jar = jarBuilder.start();
                        jar.waitFor();

                        exported[0] = true;

                    } catch (Exception ex) {

                        ex.printStackTrace();

                    }

                }
            }
        });
    }

    private static JTextArea getCurrentEditor(JTabbedPane tabbedPane) {

        Component comp = tabbedPane.getSelectedComponent();

        if (comp == null) return null;

        JScrollPane scroll = (JScrollPane) comp;

        return (JTextArea) scroll.getViewport().getView();
    }

    private static String getClassName(String code) {

        Pattern pattern = Pattern.compile("class\\s+(\\w+)");
        Matcher matcher = pattern.matcher(code);

        if (matcher.find()) return matcher.group(1);

        return "UnknownClass";
    }

    private static void openOrUpdateTab(
            JFrame frame,
            JTabbedPane tabbedPane,
            String className,
            String code
    ) {

        if (editorMap.containsKey(className)) {

            JTextArea area = editorMap.get(className);
            area.setText(code);

            tabbedPane.setSelectedComponent(area.getParent().getParent());

        } else {

            JTextArea area = new JTextArea();
            area.setText(code);

            Font font = UIManager.getFont("TextArea.font").deriveFont(16f);
            area.setFont(font);

            addJarExportFeature(frame, area);

            // ⭐ 這裡新增文字區域拖曳
            addFileDropSupport(frame, tabbedPane, area);

            JScrollPane scroll = new JScrollPane(area);

            tabbedPane.addTab(className, scroll);
            tabbedPane.setSelectedComponent(scroll);

            editorMap.put(className, area);

        }
    }
}