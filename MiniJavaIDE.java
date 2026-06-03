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
    private static Map<String, Path> editorFileMap = new LinkedHashMap<>();

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

        // ===== History =====
        JList<String> historyList = new JList<>(historyModel);
        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        historyList.addListSelectionListener(e -> {

            String selected = historyList.getSelectedValue();

            if (selected != null) {

                String code = historyCodeMap.get(selected);
                String className = getClassName(code);

                openOrUpdateTab(frame, tabbedPane, className, code, null);

            }
        });

        JScrollPane historyPane = new JScrollPane(historyList);
        historyPane.setPreferredSize(new Dimension(220, 0));

        // ===== Tab drop support =====
        addFileDropSupport(frame, tabbedPane, tabbedPane);

        // ===== Run =====
        runButton.addActionListener(e -> {

            outputArea.setText("");

            try {

                JTextArea codeArea = getCurrentEditor(tabbedPane);

                if (codeArea == null) {

                    outputArea.setText("No file is open");
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

    // ===== File drop support =====
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

                        openOrUpdateTab(frame, tabbedPane, className, content, file.toPath());

                    }

                } catch (Exception ex) {

                    JOptionPane.showMessageDialog(frame,
                            "Failed to read file: " + ex.getMessage());

                }
            }
        });
    }

    // ===== Green JAR button exports a single double-clickable JAR =====
    private static void exportJar(JFrame frame, JTextArea area) {

        Path tempDir = null;

        try {

            String code = area.getText();
            String className = getClassName(code);

            Path desktop = Paths.get(
                    System.getProperty("user.home"),
                    "Desktop"
            );

            Path jarFile = desktop.resolve(className + ".jar");
            tempDir = Files.createTempDirectory("mini-java-ide-jar-");
            Path sourceDir = tempDir.resolve("source");
            Path classesDir = tempDir.resolve("classes");

            Files.createDirectories(sourceDir);
            Files.createDirectories(classesDir);

            Path javaFile = sourceDir.resolve(className + ".java");

            Files.writeString(javaFile, code, StandardCharsets.UTF_8);

            ProcessBuilder compileBuilder = new ProcessBuilder(
                    "javac",
                    "-encoding",
                    "UTF-8",
                    "-d",
                    classesDir.toString(),
                    javaFile.toString()
            );

            Process compile = compileBuilder.start();
            compile.waitFor();

            if (compile.exitValue() != 0) {

                String error = readStream(compile.getErrorStream());
                JOptionPane.showMessageDialog(frame,
                        "Compilation failed:\n" + error,
                        "JAR Export Failed",
                        JOptionPane.ERROR_MESSAGE);
                return;

            }

            Path manifest = tempDir.resolve("manifest.txt");

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
                    classesDir.toString(),
                    "."
            );

            Process jar = jarBuilder.start();
            jar.waitFor();

            if (jar.exitValue() != 0) {

                String error = readStream(jar.getErrorStream());
                JOptionPane.showMessageDialog(frame,
                        "JAR export failed:\n" + error,
                        "JAR Export Failed",
                        JOptionPane.ERROR_MESSAGE);
                return;

            }

            JOptionPane.showMessageDialog(frame,
                    "JAR exported to Desktop:\n" + jarFile);

        } catch (Exception ex) {

            JOptionPane.showMessageDialog(frame,
                    "JAR export failed: " + ex.getMessage(),
                    "JAR Export Failed",
                    JOptionPane.ERROR_MESSAGE);

        } finally {

            if (tempDir != null) {

                try {

                    deleteDirectory(tempDir);

                } catch (IOException ex) {

                    ex.printStackTrace();

                }
            }
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {

        if (!Files.exists(directory)) return;

        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {

            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {

                        try {

                            Files.deleteIfExists(path);

                        } catch (IOException ex) {

                            throw new UncheckedIOException(ex);

                        }
                    });
        } catch (UncheckedIOException ex) {

            throw ex.getCause();

        }
    }

    private static String readStream(InputStream stream) throws IOException {

        StringBuilder builder = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                stream, StandardCharsets.UTF_8))) {

            String line;

            while ((line = reader.readLine()) != null) {

                builder.append(line).append("\n");

            }
        }

        return builder.toString();
    }

    private static JPanel createTabHeader(
            JFrame frame,
            JTabbedPane tabbedPane,
            String className,
            JTextArea area,
            JScrollPane scroll
    ) {

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.setOpaque(false);

        JLabel titleLabel = new JLabel(className);

        JButton jarButton = new JButton("JAR");
        jarButton.setForeground(Color.WHITE);
        jarButton.setBackground(new Color(39, 174, 96));
        jarButton.setFocusPainted(false);
        jarButton.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        jarButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        jarButton.addActionListener(e -> exportJar(frame, area));

        JButton javaButton = new JButton("JAVA");
        javaButton.setForeground(Color.WHITE);
        javaButton.setBackground(new Color(230, 126, 34));
        javaButton.setFocusPainted(false);
        javaButton.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        javaButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        javaButton.addActionListener(e -> saveJavaFile(frame, className, area));

        JButton closeButton = new JButton("X");
        closeButton.setOpaque(false);
        closeButton.setContentAreaFilled(false);
        closeButton.setBorderPainted(false);
        closeButton.setFocusPainted(false);
        closeButton.setMargin(new Insets(0, 4, 0, 4));
        closeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeButton.addActionListener(e -> {

            int index = tabbedPane.indexOfComponent(scroll);

            if (index != -1) {

                tabbedPane.remove(index);
                editorMap.remove(className);
                editorFileMap.remove(className);

            }
        });

        panel.add(titleLabel);
        panel.add(jarButton);
        panel.add(javaButton);
        panel.add(closeButton);

        return panel;
    }

    private static void saveJavaFile(JFrame frame, String className, JTextArea area) {

        Path javaFile = editorFileMap.get(className);

        if (javaFile == null) {

            showToast(frame, "Original Java file not found");
            return;

        }

        try {

            Files.writeString(javaFile, area.getText(), StandardCharsets.UTF_8);
            showToast(frame, "Changes saved");

        } catch (IOException ex) {

            showToast(frame, "Save failed: " + ex.getMessage());

        }
    }

    private static void showToast(JFrame frame, String message) {

        JWindow toast = new JWindow(frame);
        JLabel label = new JLabel(message);

        label.setOpaque(true);
        label.setBackground(new Color(45, 45, 45));
        label.setForeground(Color.WHITE);
        label.setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
        label.setFont(UIManager.getFont("Label.font").deriveFont(15f));

        toast.add(label);
        toast.pack();

        Point frameLocation = frame.getLocationOnScreen();
        int x = frameLocation.x + (frame.getWidth() - toast.getWidth()) / 2;
        int y = frameLocation.y + frame.getHeight() - toast.getHeight() - 90;

        toast.setLocation(x, y);
        toast.setAlwaysOnTop(true);
        toast.setVisible(true);

        javax.swing.Timer timer = new javax.swing.Timer(1800, e -> {

            toast.setVisible(false);
            toast.dispose();

        });

        timer.setRepeats(false);
        timer.start();
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
            String code,
            Path sourceFile
    ) {

        if (editorMap.containsKey(className)) {

            JTextArea area = editorMap.get(className);
            area.setText(code);

            if (sourceFile != null) {

                editorFileMap.put(className, sourceFile);

            }

            tabbedPane.setSelectedComponent(area.getParent().getParent());

        } else {

            JTextArea area = new JTextArea();
            area.setText(code);

            Font font = UIManager.getFont("TextArea.font").deriveFont(16f);
            area.setFont(font);

            // The text area can still accept dropped Java files.
            addFileDropSupport(frame, tabbedPane, area);

            JScrollPane scroll = new JScrollPane(area);

            tabbedPane.addTab(className, scroll);
            tabbedPane.setTabComponentAt(
                    tabbedPane.indexOfComponent(scroll),
                    createTabHeader(frame, tabbedPane, className, area, scroll)
            );
            tabbedPane.setSelectedComponent(scroll);

            editorMap.put(className, area);

            if (sourceFile != null) {

                editorFileMap.put(className, sourceFile);

            }

        }
    }
}
