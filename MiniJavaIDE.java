import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
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
    private static Map<Path, Component> folderTabMap = new LinkedHashMap<>();

    private static int versionCounter = 1;

    public static int[] findNames(String name, String target) {

        if (name == null || name.isEmpty() || target == null || target.isEmpty()) {

            return new int[0];

        }

        java.util.List<Integer> lineNumbers = new ArrayList<>();
        String[] lines = target.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {

            if (lines[i].contains(name)) {

                lineNumbers.add(i + 1);

            }

        }

        int[] result = new int[lineNumbers.size()];

        for (int i = 0; i < lineNumbers.size(); i++) {

            result[i] = lineNumbers.get(i);

        }

        return result;
    }

    public static void main(String[] args) {

        JFrame frame = new JFrame("Mini Java IDE");
        frame.setSize(1000, 600);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        Font font = UIManager.getFont("TextArea.font").deriveFont(16f);

        JTextArea outputArea = createOutputArea(font);
        JScrollPane outputScroll = new JScrollPane(outputArea);
        JPanel outputPanel = new JPanel(new BorderLayout());
        outputPanel.add(outputScroll, BorderLayout.CENTER);

        final JTextArea[] runOutputArea = {null};

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

            StringBuilder runOutput = new StringBuilder();

            try {

                JTextArea codeArea = getCurrentEditor(tabbedPane);

                if (codeArea == null) {

                    showRunOutput(outputPanel, outputScroll, runOutputArea, font, "No file is open");
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

                String line;
                java.util.List<String> compileErrorLines = readLines(compile.getErrorStream());
                appendErrorLines(runOutput, compileErrorLines, code, fileName);

                if (compile.exitValue() != 0) {

                    showRunOutputIfNeeded(outputPanel, outputScroll, runOutputArea, font, runOutput);
                    return;

                }

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

                    runOutput.append(line).append("\n");

                }

                java.util.List<String> runErrorLines = readLines(errorReader);
                appendErrorLines(runOutput, runErrorLines, code, fileName);

                showRunOutputIfNeeded(outputPanel, outputScroll, runOutputArea, font, runOutput);

            } catch (Exception ex) {

                showRunOutput(outputPanel, outputScroll, runOutputArea, font, "Error:\n" + ex.getMessage());

            }

        });

        JSplitPane verticalSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                tabbedPane,
                outputPanel
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

                        if (file.isDirectory()) {

                            openFolderTab(frame, tabbedPane, file.toPath());

                        } else {

                            String content =
                                    Files.readString(file.toPath(), StandardCharsets.UTF_8);

                            String className = getClassName(content);

                            openOrUpdateTab(frame, tabbedPane, className, content, file.toPath());

                        }

                    }

                } catch (Exception ex) {

                    JOptionPane.showMessageDialog(frame,
                            "Failed to read file: " + ex.getMessage());

                }
            }
        });
    }

    private static java.util.List<String> readLines(InputStream stream) throws IOException {

        return readLines(new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
    }

    private static java.util.List<String> readLines(BufferedReader reader) throws IOException {

        java.util.List<String> lines = new ArrayList<>();
        String line;

        while ((line = reader.readLine()) != null) {

            lines.add(line);

        }

        return lines;
    }

    private static JTextArea createOutputArea(Font font) {

        JTextArea area = new JTextArea();

        area.setEditable(false);
        area.setFont(font);

        return area;
    }

    private static void showRunOutputIfNeeded(
            JPanel outputPanel,
            Component baseOutputComponent,
            JTextArea[] runOutputArea,
            Font font,
            StringBuilder output
    ) {

        if (output.length() > 0) {

            showRunOutput(outputPanel, baseOutputComponent, runOutputArea, font, output.toString());

        }
    }

    private static void showRunOutput(
            JPanel outputPanel,
            Component baseOutputComponent,
            JTextArea[] runOutputArea,
            Font font,
            String output
    ) {

        if (runOutputArea[0] == null) {

            runOutputArea[0] = createOutputArea(font);

            JPanel closePanel = new JPanel(new BorderLayout());
            closePanel.add(new JScrollPane(runOutputArea[0]), BorderLayout.CENTER);

            JButton closeButton = new JButton("X");
            closeButton.setOpaque(false);
            closeButton.setContentAreaFilled(false);
            closeButton.setBorderPainted(false);
            closeButton.setFocusPainted(false);
            closeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 2));
            buttonPanel.setOpaque(false);
            buttonPanel.add(closeButton);

            closePanel.add(buttonPanel, BorderLayout.NORTH);

            closeButton.addActionListener(e -> {

                outputPanel.removeAll();
                outputPanel.add(baseOutputComponent, BorderLayout.CENTER);
                outputPanel.revalidate();
                outputPanel.repaint();
                runOutputArea[0] = null;

            });

            outputPanel.removeAll();
            outputPanel.add(closePanel, BorderLayout.CENTER);

        }

        runOutputArea[0].setText(output);
        runOutputArea[0].setCaretPosition(0);
        outputPanel.revalidate();
        outputPanel.repaint();
    }

    private static void appendErrorLines(
            StringBuilder output,
            java.util.List<String> lines,
            String code,
            String fileName
    ) {

        for (int i = 0; i < lines.size(); i++) {

            String line = lines.get(i);
            int hintedLine = getHintedLineNumber(line, fileName);
            int preciseLine = getPreciseLineNumber(lines, i, code, hintedLine);

            if (preciseLine != -1) {

                line = replaceErrorLineNumber(line, fileName, preciseLine);

            }

            output.append(line).append("\n");

        }
    }

    private static String replaceErrorLineNumber(String errorLine, String fileName, int preciseLine) {

        Pattern compilePattern = Pattern.compile("(" + Pattern.quote(fileName) + ":)(\\d+)(:)");
        Matcher compileMatcher = compilePattern.matcher(errorLine);

        if (compileMatcher.find()) {

            String replacement = compileMatcher.group(1) + preciseLine + compileMatcher.group(3);

            return compileMatcher.replaceFirst(Matcher.quoteReplacement(replacement));

        }

        Pattern runtimePattern = Pattern.compile("(\\([^)]*\\.java:)(\\d+)(\\))");
        Matcher runtimeMatcher = runtimePattern.matcher(errorLine);

        if (runtimeMatcher.find()) {

            String replacement = runtimeMatcher.group(1) + preciseLine + runtimeMatcher.group(3);

            return runtimeMatcher.replaceFirst(Matcher.quoteReplacement(replacement));

        }

        return errorLine;
    }

    private static int getHintedLineNumber(String errorLine, String fileName) {

        Pattern compilePattern = Pattern.compile(Pattern.quote(fileName) + ":(\\d+):");
        Matcher compileMatcher = compilePattern.matcher(errorLine);

        if (compileMatcher.find()) {

            return Integer.parseInt(compileMatcher.group(1));

        }

        Pattern runtimePattern = Pattern.compile("\\([^)]*\\.java:(\\d+)\\)");
        Matcher runtimeMatcher = runtimePattern.matcher(errorLine);

        if (runtimeMatcher.find()) {

            return Integer.parseInt(runtimeMatcher.group(1));

        }

        return -1;
    }

    private static int getPreciseLineNumber(
            java.util.List<String> errorLines,
            int errorLineIndex,
            String code,
            int hintedLine
    ) {

        if (hintedLine == -1) {

            return -1;

        }

        String name = getErrorSearchText(errorLines, errorLineIndex, code, hintedLine);

        if (name == null || name.isEmpty()) {

            return hintedLine;

        }

        int[] foundLines = findNames(name, code);

        if (foundLines.length == 0) {

            return hintedLine;

        }

        return findClosestLine(foundLines, hintedLine);
    }

    private static String getErrorSearchText(
            java.util.List<String> errorLines,
            int errorLineIndex,
            String code,
            int hintedLine
    ) {

        if (errorLineIndex + 1 < errorLines.size()) {

            String nextLine = errorLines.get(errorLineIndex + 1).trim();

            if (!nextLine.isEmpty() && !nextLine.startsWith("^")) {

                return nextLine;

            }
        }

        String[] codeLines = code.split("\n", -1);

        if (hintedLine >= 1 && hintedLine <= codeLines.length) {

            return codeLines[hintedLine - 1].trim();

        }

        return "";
    }

    private static int findClosestLine(int[] foundLines, int hintedLine) {

        int closestLine = foundLines[0];
        int closestDistance = Math.abs(foundLines[0] - hintedLine);

        for (int i = 1; i < foundLines.length; i++) {

            int distance = Math.abs(foundLines[i] - hintedLine);

            if (distance < closestDistance) {

                closestLine = foundLines[i];
                closestDistance = distance;

            }
        }

        return closestLine;
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

    private static void openFolderTab(JFrame frame, JTabbedPane tabbedPane, Path folderPath) {

        Path normalizedPath = folderPath.toAbsolutePath().normalize();

        if (folderTabMap.containsKey(normalizedPath)) {

            tabbedPane.setSelectedComponent(folderTabMap.get(normalizedPath));
            return;

        }

        JPanel folderPanel = new JPanel(new BorderLayout());
        folderPanel.setBackground(Color.WHITE);
        addFileDropSupport(frame, tabbedPane, folderPanel);

        tabbedPane.addTab(normalizedPath.getFileName().toString(), folderPanel);
        tabbedPane.setTabComponentAt(
                tabbedPane.indexOfComponent(folderPanel),
                createFolderTabHeader(frame, tabbedPane, normalizedPath, folderPanel)
        );
        tabbedPane.setSelectedComponent(folderPanel);
        folderTabMap.put(normalizedPath, folderPanel);
    }

    private static JPanel createFolderTabHeader(
            JFrame frame,
            JTabbedPane tabbedPane,
            Path folderPath,
            JPanel folderPanel
    ) {

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.setOpaque(false);

        JLabel titleLabel = new JLabel(folderPath.getFileName().toString());

        JButton openButton = new JButton("OPEN");
        styleFolderToggleButton(openButton, true);
        openButton.addActionListener(e -> toggleFolderList(
                frame,
                tabbedPane,
                folderPath,
                folderPanel,
                openButton
        ));

        JButton closeButton = new JButton("X");
        closeButton.setOpaque(false);
        closeButton.setContentAreaFilled(false);
        closeButton.setBorderPainted(false);
        closeButton.setFocusPainted(false);
        closeButton.setMargin(new Insets(0, 4, 0, 4));
        closeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeButton.addActionListener(e -> {

            int index = tabbedPane.indexOfComponent(folderPanel);

            if (index != -1) {

                tabbedPane.remove(index);
                folderTabMap.remove(folderPath);

            }
        });

        panel.add(titleLabel);
        panel.add(openButton);
        panel.add(closeButton);

        return panel;
    }

    private static void toggleFolderList(
            JFrame frame,
            JTabbedPane tabbedPane,
            Path folderPath,
            JPanel folderPanel,
            JButton openButton
    ) {

        if (folderPanel.getComponentCount() > 0) {

            folderPanel.removeAll();
            styleFolderToggleButton(openButton, true);

        } else {

            folderPanel.add(createFolderList(frame, tabbedPane, folderPath), BorderLayout.NORTH);
            styleFolderToggleButton(openButton, false);

        }

        folderPanel.revalidate();
        folderPanel.repaint();
    }

    private static void styleFolderToggleButton(JButton button, boolean openState) {

        button.setText(openState ? "OPEN" : "CLOSE");
        button.setForeground(Color.WHITE);
        button.setBackground(openState ? new Color(52, 152, 219) : new Color(121, 85, 72));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private static JScrollPane createFolderList(JFrame frame, JTabbedPane tabbedPane, Path folderPath) {

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(Color.WHITE);
        listPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        try {

            java.util.List<Path> folders = new ArrayList<>();
            java.util.List<Path> files = new ArrayList<>();

            try (java.util.stream.Stream<Path> stream = Files.list(folderPath)) {

                stream.sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                        .forEach(path -> {

                            if (Files.isDirectory(path)) {

                                folders.add(path);

                            } else {

                                files.add(path);

                            }
                        });
            }

            for (Path folder : folders) {

                listPanel.add(createFolderItem(frame, tabbedPane, folder, true));
                listPanel.add(Box.createVerticalStrut(2));

            }

            for (Path file : files) {

                listPanel.add(createFolderItem(frame, tabbedPane, file, false));
                listPanel.add(Box.createVerticalStrut(2));

            }

        } catch (IOException ex) {

            JLabel errorLabel = new JLabel("Failed to open folder: " + ex.getMessage());
            errorLabel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            listPanel.add(errorLabel);

        }

        JScrollPane scrollPane = new JScrollPane(listPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setPreferredSize(new Dimension(0, 180));

        return scrollPane;
    }

    private static JPanel createFolderItem(
            JFrame frame,
            JTabbedPane tabbedPane,
            Path path,
            boolean folder
    ) {

        RoundedItemPanel itemPanel = new RoundedItemPanel();
        itemPanel.setLayout(new BorderLayout(8, 0));
        itemPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 6));
        itemPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        itemPanel.setPreferredSize(new Dimension(0, 34));

        JLabel nameLabel = new JLabel(path.getFileName().toString());
        itemPanel.add(nameLabel, BorderLayout.CENTER);

        JButton openButton = new JButton("OPEN");
        openButton.setForeground(Color.WHITE);
        openButton.setBackground(new Color(52, 152, 219));
        openButton.setFocusPainted(false);
        openButton.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        openButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        openButton.setVisible(false);
        openButton.addActionListener(e -> openPath(frame, tabbedPane, path));
        itemPanel.add(openButton, BorderLayout.EAST);

        MouseAdapter hoverAdapter = new MouseAdapter() {

            @Override
            public void mouseEntered(MouseEvent e) {

                openButton.setVisible(true);
                itemPanel.revalidate();
                itemPanel.repaint();

            }

            @Override
            public void mouseExited(MouseEvent e) {

                SwingUtilities.invokeLater(() -> {

                    Point mousePoint = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(mousePoint, itemPanel);

                    if (!itemPanel.contains(mousePoint)) {

                        openButton.setVisible(false);
                        itemPanel.revalidate();
                        itemPanel.repaint();

                    }
                });
            }
        };

        itemPanel.addMouseListener(hoverAdapter);
        nameLabel.addMouseListener(hoverAdapter);
        openButton.addMouseListener(hoverAdapter);

        return itemPanel;
    }

    private static void openPath(JFrame frame, JTabbedPane tabbedPane, Path path) {

        try {

            if (Files.isDirectory(path)) {

                openFolderTab(frame, tabbedPane, path);

            } else {

                String content = Files.readString(path, StandardCharsets.UTF_8);
                String className = getClassName(content);

                openOrUpdateTab(frame, tabbedPane, className, content, path);

            }

        } catch (IOException ex) {

            JOptionPane.showMessageDialog(frame,
                    "Failed to open: " + ex.getMessage());

        }
    }

    private static class RoundedItemPanel extends JPanel {

        RoundedItemPanel() {

            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(238, 241, 244));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.dispose();

            super.paintComponent(g);
        }
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

        if (!(comp instanceof JScrollPane)) return null;

        JScrollPane scroll = (JScrollPane) comp;

        if (!(scroll.getViewport().getView() instanceof JTextArea)) return null;

        return (JTextArea) scroll.getViewport().getView();
    }

    private static void addLineNumbers(JTextArea area, JScrollPane scroll) {

        scroll.setRowHeaderView(new LineNumberView(area));
    }

    private static class LineNumberView extends JComponent implements DocumentListener {

        private static final int HORIZONTAL_PADDING = 6;

        private final JTextArea textArea;
        private int lastLineCount = -1;

        LineNumberView(JTextArea textArea) {

            this.textArea = textArea;
            setFont(textArea.getFont());
            setBackground(new Color(245, 245, 245));
            setForeground(new Color(120, 120, 120));

            textArea.getDocument().addDocumentListener(this);
            textArea.addPropertyChangeListener("font", e -> {

                setFont(textArea.getFont());
                updatePreferredWidth();
                repaint();

            });

            updatePreferredWidth();
        }

        @Override
        protected void paintComponent(Graphics g) {

            super.paintComponent(g);

            Rectangle clip = g.getClipBounds();
            g.setColor(getBackground());
            g.fillRect(clip.x, clip.y, clip.width, clip.height);
            g.setColor(getForeground());
            g.setFont(getFont());

            FontMetrics metrics = g.getFontMetrics();
            Element root = textArea.getDocument().getDefaultRootElement();

            int startOffset = textArea.viewToModel2D(new Point(0, clip.y));
            int endOffset = textArea.viewToModel2D(new Point(0, clip.y + clip.height));
            int startLine = root.getElementIndex(startOffset);
            int endLine = root.getElementIndex(endOffset);

            for (int line = startLine; line <= endLine; line++) {

                try {

                    Rectangle lineBounds = textArea.modelToView2D(root.getElement(line).getStartOffset()).getBounds();
                    String lineNumber = String.valueOf(line + 1);
                    int x = getWidth() - HORIZONTAL_PADDING - metrics.stringWidth(lineNumber);
                    int y = lineBounds.y + lineBounds.height - metrics.getDescent();

                    g.drawString(lineNumber, x, y);

                } catch (BadLocationException ex) {

                    break;

                }

            }
        }

        @Override
        public void insertUpdate(DocumentEvent e) {

            documentChanged();

        }

        @Override
        public void removeUpdate(DocumentEvent e) {

            documentChanged();

        }

        @Override
        public void changedUpdate(DocumentEvent e) {

            documentChanged();

        }

        private void documentChanged() {

            int lineCount = getLineCount();

            if (lineCount != lastLineCount) {

                updatePreferredWidth();

            }

            repaint();
        }

        private void updatePreferredWidth() {

            lastLineCount = getLineCount();

            int digits = String.valueOf(lastLineCount).length();
            FontMetrics metrics = getFontMetrics(getFont());
            int width = metrics.charWidth('0') * digits + HORIZONTAL_PADDING * 2;

            setPreferredSize(new Dimension(width, Integer.MAX_VALUE));
            revalidate();
        }

        private int getLineCount() {

            return Math.max(1, textArea.getDocument().getDefaultRootElement().getElementCount());
        }
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
            addLineNumbers(area, scroll);

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
