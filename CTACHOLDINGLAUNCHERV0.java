import javax.swing.*;
import javax.swing.plaf.basic.BasicMenuUI;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.util.zip.*;

public class Program {

    private JFrame frame;
    private JTextArea console;
    private JComboBox<String> versionBox;
    private JLabel statusLabel;
    private JButton loginBtn;
    private JButton fetchBtn;
    private JButton launchBtn;
    private JTextField emailField;
    private JPasswordField passwordField;

    // HTTP Client
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // Thread pool
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private volatile boolean isLoggedIn = false;

    // Mojang auth state
    private String mojangAccessToken = null;
    private String mojangUsername = null;
    private String mojangUuid = null;

    // Version list
    private final List<String[]> versions = Collections.synchronizedList(new ArrayList<>());

    // Paths
    private static Path ROOT, VERSIONS_DIR, LIBRARIES_DIR, ASSETS_DIR, NATIVES_DIR;

    private static final String RESOURCES_URL = "https://resources.download.minecraft.net/";
    private static final String MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

    private static final Pattern JSON_PAIR_PATTERN = Pattern.compile(
        "\"([^\"]+)\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^,}\\]\\s]+))"
    );

    private static final Color BLUE_ACCENT = new Color(66, 135, 245);

    public Program() {
        initDirs();
        initUI();
    }

    private void initDirs() {
        String os = System.getProperty("os.name").toLowerCase();
        Path home;
        if (os.contains("win")) {
            home = Paths.get(System.getenv("APPDATA"), ".catclient");
        } else if (os.contains("mac")) {
            home = Paths.get(System.getProperty("user.home"), "Library", "Application Support", "catclient");
        } else {
            home = Paths.get(System.getProperty("user.home"), ".catclient");
        }
        ROOT = home;
        VERSIONS_DIR = home.resolve("versions");
        LIBRARIES_DIR = home.resolve("libraries");
        ASSETS_DIR = home.resolve("assets");
        NATIVES_DIR = home.resolve("natives");
        try {
            Files.createDirectories(VERSIONS_DIR);
            Files.createDirectories(LIBRARIES_DIR);
            Files.createDirectories(ASSETS_DIR.resolve("indexes"));
            Files.createDirectories(ASSETS_DIR.resolve("objects"));
            Files.createDirectories(NATIVES_DIR);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {}

        // UIManager settings for dark theme
        UIManager.put("Menu.background", Color.BLACK);
        UIManager.put("Menu.foreground", Color.WHITE);
        UIManager.put("Menu.selectionBackground", new Color(60, 60, 60));
        UIManager.put("Menu.selectionForeground", Color.WHITE);
        UIManager.put("MenuItem.background", Color.BLACK);
        UIManager.put("MenuItem.foreground", Color.WHITE);
        UIManager.put("MenuItem.selectionBackground", new Color(60, 60, 60));
        UIManager.put("MenuItem.selectionForeground", Color.WHITE);
        UIManager.put("PopupMenu.background", Color.BLACK);
        UIManager.put("PopupMenu.foreground", Color.WHITE);
        UIManager.put("ComboBox.background", new Color(45, 45, 45));
        UIManager.put("ComboBox.foreground", Color.WHITE);
        UIManager.put("ComboBox.selectionBackground", new Color(60, 60, 60));
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);

        frame = new JFrame("CatClient - Real Launcher");
        frame.setSize(600, 580);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                executor.shutdownNow();
                frame.dispose();
                System.exit(0);
            }
        });
        frame.setResizable(false);

        // --- BLACK MENU BAR ---
        JMenuBar menuBar = new JMenuBar();
        menuBar.setBackground(Color.BLACK);
        menuBar.setOpaque(true);
        menuBar.setBorder(BorderFactory.createEmptyBorder());

        JMenu fileMenu = new JMenu("File");
        fileMenu.setForeground(Color.WHITE);
        fileMenu.setBackground(Color.BLACK);
        fileMenu.setOpaque(true);
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.setForeground(Color.WHITE);
        exitItem.setBackground(Color.BLACK);
        exitItem.setOpaque(true);
        exitItem.addActionListener(e -> System.exit(0));
        fileMenu.add(exitItem);

        JMenu helpMenu = new JMenu("Help");
        helpMenu.setForeground(Color.WHITE);
        helpMenu.setBackground(Color.BLACK);
        helpMenu.setOpaque(true);
        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.setForeground(Color.WHITE);
        aboutItem.setBackground(Color.BLACK);
        aboutItem.setOpaque(true);
        aboutItem.addActionListener(e -> JOptionPane.showMessageDialog(frame,
                "CatClient - Real Launcher\nDownloads & runs Minecraft", "About",
                JOptionPane.INFORMATION_MESSAGE));
        helpMenu.add(aboutItem);

        menuBar.add(fileMenu);
        menuBar.add(helpMenu);
        frame.setJMenuBar(menuBar);
        // -----------------------------

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        root.setBackground(new Color(25, 25, 25));

        JLabel title = new JLabel("CatClient");
        title.setForeground(new Color(0, 255, 153));
        title.setFont(new Font("Segoe UI", Font.BOLD, 28));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel subtitle = new JLabel("Real Minecraft Launcher");
        subtitle.setForeground(new Color(150, 150, 150));
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        // --- Email / Password panel ---
        JPanel authPanel = new JPanel(new GridBagLayout());
        authPanel.setOpaque(false);
        authPanel.setMaximumSize(new Dimension(300, 80));
        authPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 5, 2, 5);

        JLabel emailLabel = new JLabel("Email:");
        emailLabel.setForeground(Color.LIGHT_GRAY);
        emailLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        authPanel.add(emailLabel, c);

        emailField = new JTextField(15);
        emailField.setBackground(new Color(45, 45, 45));
        emailField.setForeground(Color.WHITE);
        emailField.setCaretColor(Color.WHITE);
        emailField.setBorder(BorderFactory.createLineBorder(new Color(60,60,60)));
        c.gridx = 1; c.gridy = 0; c.weightx = 1;
        authPanel.add(emailField, c);

        JLabel passLabel = new JLabel("Password:");
        passLabel.setForeground(Color.LIGHT_GRAY);
        passLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        authPanel.add(passLabel, c);

        passwordField = new JPasswordField(15);
        passwordField.setBackground(new Color(45, 45, 45));
        passwordField.setForeground(Color.WHITE);
        passwordField.setCaretColor(Color.WHITE);
        passwordField.setBorder(BorderFactory.createLineBorder(new Color(60,60,60)));
        c.gridx = 1; c.gridy = 1; c.weightx = 1;
        authPanel.add(passwordField, c);

        // Buttons
        loginBtn = createButton("Login", BLUE_ACCENT);
        fetchBtn = createButton("Fetch Versions", BLUE_ACCENT);
        launchBtn = createButton("Launch Game", BLUE_ACCENT);

        versionBox = new JComboBox<>();
        versionBox.setMaximumSize(new Dimension(300, 30));
        versionBox.setAlignmentX(Component.CENTER_ALIGNMENT);
        versionBox.setBackground(new Color(45, 45, 45));
        versionBox.setForeground(Color.WHITE);
        ((JLabel)versionBox.getRenderer()).setHorizontalAlignment(JLabel.CENTER);

        console = new JTextArea(10, 40);
        console.setEditable(false);
        console.setBackground(new Color(10, 10, 10));
        console.setForeground(new Color(0, 255, 153));
        console.setFont(new Font("Consolas", Font.PLAIN, 12));
        console.setMargin(new Insets(5,5,5,5));
        
        JScrollPane scroll = new JScrollPane(console);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60)));
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);

        statusLabel = new JLabel("Ready");
        statusLabel.setForeground(Color.LIGHT_GRAY);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        root.add(title);
        root.add(subtitle);
        root.add(Box.createVerticalStrut(15));
        root.add(authPanel);
        root.add(Box.createVerticalStrut(10));
        root.add(loginBtn);
        root.add(Box.createVerticalStrut(10));
        root.add(versionBox);
        root.add(Box.createVerticalStrut(10));
        root.add(fetchBtn);
        root.add(Box.createVerticalStrut(10));
        root.add(launchBtn);
        root.add(Box.createVerticalStrut(15));
        root.add(scroll);
        root.add(Box.createVerticalStrut(10));
        root.add(statusLabel);

        frame.add(root);
        frame.setVisible(true);

        fetchBtn.addActionListener(e -> executor.submit(this::fetchVersions));
        loginBtn.addActionListener(e -> executor.submit(this::mojangLogin));
        
        launchBtn.addActionListener(e -> {
            String selectedVersion = (String) versionBox.getSelectedItem();
            if (selectedVersion == null) {
                 JOptionPane.showMessageDialog(frame, "Please fetch versions first!", "Error", JOptionPane.ERROR_MESSAGE);
                 return;
            }
            executor.submit(() -> launchGame(selectedVersion));
        });
        
        log("System initialized. Waiting for user input...");
    }

    private JButton createButton(String text, Color textColor) {
        JButton btn = new JButton(text);
        btn.setAlignmentX(Component.CENTER_ALIGNMENT);
        btn.setFocusPainted(false);
        btn.setBackground(new Color(45, 45, 45));
        btn.setForeground(textColor);
        btn.setMaximumSize(new Dimension(200, 35));
        return btn;
    }

    // --- FETCH VERSIONS ---
    private void fetchVersions() {
        log("Fetching Mojang version manifest...");
        status("Downloading manifest...");
        toggleButtons(false);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MANIFEST_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new RuntimeException("HTTP " + response.statusCode());
            }

            String json = response.body();
            parseManifest(json);

            if (versions.isEmpty()) {
                throw new RuntimeException("No release versions found in manifest.");
            }

            List<String> releaseList = new ArrayList<>();
            synchronized(versions) {
                for (String[] v : versions) {
                    if ("release".equals(v[1])) {
                        releaseList.add(v[0]);
                    }
                }
            }

            SwingUtilities.invokeLater(() -> {
                versionBox.removeAllItems();
                for (String v : releaseList) {
                    versionBox.addItem(v);
                }
                if (versionBox.getItemCount() > 0) versionBox.setSelectedIndex(0);
                status("Ready");
                toggleButtons(true);
            });

            log("Successfully loaded " + releaseList.size() + " release versions.");

        } catch (Exception e) {
            log("Error fetching versions: " + e.getMessage());
            status("Error");
            toggleButtons(true);
        }
    }

    private void parseManifest(String json) {
        versions.clear();
        int idx = json.indexOf("\"versions\"");
        if (idx < 0) return;
        int arrStart = json.indexOf('[', idx);
        if (arrStart < 0) return;
        int depth = 0, objStart = -1;
        for (int i = arrStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String obj = json.substring(objStart, i + 1);
                    String id = extractJsonValue(obj, "id");
                    String type = extractJsonValue(obj, "type");
                    String url = extractJsonValue(obj, "url");
                    if (id != null && type != null && url != null) {
                        versions.add(new String[]{id, type, url});
                    }
                    objStart = -1;
                }
            } else if (c == ']' && depth == 0) break;
        }
    }

    // --- MOJANG AUTHENTICATION ---
    private void mojangLogin() {
        if (isLoggedIn) {
            log("Already logged in as " + mojangUsername);
            return;
        }

        String email = emailField.getText().trim();
        String password = new String(passwordField.getPassword()).trim();

        if (email.isEmpty() || password.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Email and password cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        log("Authenticating with Mojang...");
        status("Logging in...");
        toggleButtons(false);

        executor.submit(() -> {
            try {
                String payload = String.format(
                    "{\"agent\":{\"name\":\"Minecraft\",\"version\":1},\"username\":\"%s\",\"password\":\"%s\"}",
                    email, password);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://authserver.mojang.com/authenticate"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
                String respBody = response.body();

                if (response.statusCode() != 200) {
                    log("Auth failed: HTTP " + response.statusCode());
                    status("Auth Failed");
                    toggleButtons(true);
                    return;
                }

                String accessToken = extractJsonValue(respBody, "accessToken");
                String uuid = extractJsonValue(respBody, "uuid");
                String name = extractJsonValue(respBody, "name");

                if (accessToken == null || uuid == null || name == null) {
                    log("Failed to parse auth response.");
                    status("Auth Failed");
                    toggleButtons(true);
                    return;
                }

                mojangAccessToken = accessToken;
                mojangUuid = uuid;
                mojangUsername = name;
                isLoggedIn = true;

                log(">>> LOGIN SUCCESSFUL! Welcome, " + mojangUsername + " <<<");
                status("Logged in as " + mojangUsername);

                SwingUtilities.invokeLater(() -> {
                    loginBtn.setText("Logged In");
                    loginBtn.setEnabled(false);
                    loginBtn.setBackground(new Color(0, 100, 50));
                    loginBtn.setForeground(Color.WHITE);
                });

            } catch (Exception e) {
                log("Login error: " + e.getMessage());
                status("Login Error");
                toggleButtons(true);
            }
        });
    }

    // --- REAL LAUNCH ---
    private void launchGame(String versionId) {
        if (!isLoggedIn || mojangAccessToken == null) {
            JOptionPane.showMessageDialog(frame, "Please log in first.", "Authentication Required", JOptionPane.WARNING_MESSAGE);
            return;
        }

        status("Preparing...");
        log("=== Starting launch for " + versionId + " ===");
        toggleButtons(false);

        executor.submit(() -> {
            try {
                // Find version URL
                String versionUrl = null;
                synchronized (versions) {
                    for (String[] v : versions) {
                        if (v[0].equals(versionId)) {
                            versionUrl = v[2];
                            break;
                        }
                    }
                }
                if (versionUrl == null) throw new Exception("Version not found in manifest");

                // Version directory and JSON
                Path versionDir = VERSIONS_DIR.resolve(versionId);
                Files.createDirectories(versionDir);
                Path jsonPath = versionDir.resolve(versionId + ".json");
                if (!Files.exists(jsonPath)) {
                    log("Downloading version JSON...");
                    String json = httpGet(versionUrl);
                    Files.write(jsonPath, json.getBytes(StandardCharsets.UTF_8));
                }
                String versionJson = new String(Files.readAllBytes(jsonPath), StandardCharsets.UTF_8);
                prog(10);

                // Download client JAR
                Path jarPath = versionDir.resolve(versionId + ".jar");
                if (!Files.exists(jarPath)) {
                    String clientUrl = findClientUrl(versionJson, versionId);
                    if (clientUrl == null) throw new Exception("No client URL found");
                    log("Downloading client JAR...");
                    download(clientUrl, jarPath);
                }
                log("Client size: " + Files.size(jarPath) / 1024 + " KB");
                prog(20);

                // Download libraries
                log("Resolving libraries...");
                List<Path> libPaths = resolveLibraries(versionJson);
                prog(55);

                // Download assets
                log("Downloading assets...");
                String assetId = getNested(versionJson, "assetIndex", "id");
                String assetUrl = getNested(versionJson, "assetIndex", "url");
                if (assetId != null && assetUrl != null) {
                    Path assetIndexPath = ASSETS_DIR.resolve("indexes").resolve(assetId + ".json");
                    if (!Files.exists(assetIndexPath)) {
                        String assetIndexJson = httpGet(assetUrl);
                        Files.write(assetIndexPath, assetIndexJson.getBytes(StandardCharsets.UTF_8));
                    }
                    String assetIndexJson = new String(Files.readAllBytes(assetIndexPath), StandardCharsets.UTF_8);
                    downloadAssets(assetIndexJson);
                } else {
                    assetId = "legacy";
                }
                prog(65);

                // Extract natives
                log("Extracting natives...");
                Path nativesDir = NATIVES_DIR.resolve(versionId);
                Files.createDirectories(nativesDir);
                extractNatives(libPaths, nativesDir);
                prog(75);

                // Build classpath
                StringBuilder classpath = new StringBuilder();
                String sep = System.getProperty("path.separator");
                for (Path lib : libPaths) {
                    if (Files.exists(lib)) {
                        classpath.append(lib.toAbsolutePath()).append(sep);
                    }
                }
                classpath.append(jarPath.toAbsolutePath());

                // Determine main class
                String mainClass = js(versionJson, "mainClass");
                if (mainClass == null) {
                    mainClass = versionId.startsWith("b1.") || versionId.startsWith("a1.") || versionId.startsWith("c0.") ?
                        "net.minecraft.launchwrapper.Launch" : "net.minecraft.client.main.Minecraft";
                }

                // Build command
                List<String> cmd = new ArrayList<>();
                cmd.add("java");
                if (osName().equals("osx")) cmd.add("-XstartOnFirstThread");
                String javaVer = System.getProperty("java.version");
                if (!javaVer.startsWith("1.") && !javaVer.startsWith("8")) {
                    try {
                        int major = Integer.parseInt(javaVer.split("[^0-9]")[0]);
                        if (major >= 21) cmd.add("--enable-native-access=ALL-UNNAMED");
                    } catch (NumberFormatException ignored) {
                        cmd.add("--enable-native-access=ALL-UNNAMED");
                    }
                }
                cmd.add("-Xmx2G");
                cmd.add("-Xms512M");
                cmd.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
                cmd.add("-cp");
                cmd.add(classpath.toString());
                cmd.add(mainClass);

                // Add Minecraft arguments
                String minecraftArgs = js(versionJson, "minecraftArguments");
                if (minecraftArgs != null) {
                    minecraftArgs = minecraftArgs
                            .replace("${auth_player_name}", mojangUsername)
                            .replace("${version_name}", versionId)
                            .replace("${game_directory}", ROOT.toAbsolutePath().toString())
                            .replace("${assets_root}", ASSETS_DIR.toAbsolutePath().toString())
                            .replace("${assets_index_name}", assetId)
                            .replace("${auth_uuid}", mojangUuid.replace("-", ""))
                            .replace("${auth_access_token}", mojangAccessToken)
                            .replace("${user_properties}", "{}")
                            .replace("${user_type}", "mojang");
                    for (String arg : minecraftArgs.split(" ")) {
                        if (!arg.isEmpty()) cmd.add(arg);
                    }
                } else {
                    cmd.add("--username"); cmd.add(mojangUsername);
                    cmd.add("--version"); cmd.add(versionId);
                    cmd.add("--gameDir"); cmd.add(ROOT.toAbsolutePath().toString());
                    cmd.add("--assetsDir"); cmd.add(ASSETS_DIR.toAbsolutePath().toString());
                    cmd.add("--assetIndex"); cmd.add(assetId);
                    cmd.add("--uuid"); cmd.add(mojangUuid.replace("-", ""));
                    cmd.add("--accessToken"); cmd.add(mojangAccessToken);
                    cmd.add("--userType"); cmd.add("mojang");
                    cmd.add("--versionType"); cmd.add("release");
                }

                log("Launch command: " + String.join(" ", cmd));
                prog(90);

                // Launch process
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(ROOT.toFile());
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                prog(100);
                status("Game running (PID " + proc.pid() + ")");

                // Stream game output to console
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        log("[MC] " + line);
                    }
                }
                int exit = proc.waitFor();
                log("Game exited with code " + exit);
                status("Ready");
                prog(0);
                toggleButtons(true);

            } catch (Exception e) {
                log("Launch error: " + e.getMessage());
                e.printStackTrace();
                status("Launch failed");
                toggleButtons(true);
            }
        });
    }

    // --- HELPER METHODS for downloading and resolving ---

    private String httpGet(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> resp = http.send(req, BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private void download(String url, Path dest) throws IOException, InterruptedException {
        Files.createDirectories(dest.getParent());
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<Path> resp = http.send(req, BodyHandlers.ofFile(dest));
        if (resp.statusCode() != 200) throw new IOException("Download failed: " + resp.statusCode());
    }

    private String findClientUrl(String versionJson, String versionId) {
        String u = getNested(versionJson, "downloads", "client", "url");
        if (u != null) return u;
        // fallback
        return "https://s3.amazonaws.com/Minecraft.Download/versions/" + versionId + "/" + versionId + ".jar";
    }

    private List<Path> resolveLibraries(String versionJson) {
        List<Path> out = new ArrayList<>();
        String os = osName();
        int libIdx = versionJson.indexOf("\"libraries\"");
        if (libIdx < 0) return out;
        int arrStart = versionJson.indexOf('[', libIdx);
        if (arrStart < 0) return out;
        int depth = 0, objStart = -1;
        for (int i = arrStart + 1; i < versionJson.length(); i++) {
            char c = versionJson.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String libObj = versionJson.substring(objStart, i + 1);
                    resolveLibrary(libObj, out, os);
                    objStart = -1;
                }
            } else if (c == ']' && depth == 0) break;
        }
        return out;
    }

    private void resolveLibrary(String libObj, List<Path> out, String os) {
        if (libObj.contains("\"rules\"") && !rulesOk(libObj, os)) return;

        // artifact path & url (3 levels: downloads.artifact.path)
        String path = getNested(libObj, "downloads", "artifact", "path");
        String url = getNested(libObj, "downloads", "artifact", "url");
        if (path != null) {
            Path file = LIBRARIES_DIR.resolve(path.replace('/', File.separatorChar));
            out.add(file);
            if (!Files.exists(file) && url != null) {
                try {
                    Files.createDirectories(file.getParent());
                    download(url, file);
                    log("  Downloaded library: " + file.getFileName());
                } catch (Exception e) {
                    log("  Failed to download library: " + e.getMessage());
                }
            }
        }

        // handle natives (4 levels: downloads.classifiers.<classifier>.path)
        String classifier = "natives-" + os;
        if (libObj.contains("\"classifiers\"") && libObj.contains(classifier)) {
            String nativePath = getNested(libObj, "downloads", "classifiers", classifier, "path");
            String nativeUrl = getNested(libObj, "downloads", "classifiers", classifier, "url");
            if (nativePath != null) {
                Path file = LIBRARIES_DIR.resolve(nativePath.replace('/', File.separatorChar));
                out.add(file);
                if (!Files.exists(file) && nativeUrl != null) {
                    try {
                        Files.createDirectories(file.getParent());
                        download(nativeUrl, file);
                        log("  Downloaded native: " + file.getFileName());
                    } catch (Exception e) {
                        log("  Failed to download native: " + e.getMessage());
                    }
                }
            }
        }
    }

    private boolean rulesOk(String libObj, String os) {
        // Simplified rule check – you may want to expand this.
        // For now, assume allowed if no disallow rule for this OS.
        if (!libObj.contains("\"rules\"")) return true;
        // Very basic: if it has a rule disallowing this OS, return false.
        // This is a stub; a proper implementation would parse the rules array.
        return !libObj.contains("\"action\":\"disallow\"") || !libObj.contains("\"name\":\"" + os + "\"");
    }

    private void extractNatives(List<Path> libPaths, Path nativesDir) {
        for (Path jar : libPaths) {
            if (jar.toString().contains("natives") && Files.exists(jar)) {
                try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();
                        if (name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib") || name.endsWith(".jnilib")) {
                            Path out = nativesDir.resolve(Paths.get(name).getFileName());
                            if (!Files.exists(out)) {
                                Files.copy(zis, out);
                            }
                        }
                    }
                } catch (Exception e) {
                    log("Error extracting natives from " + jar + ": " + e.getMessage());
                }
            }
        }
    }

    private void downloadAssets(String assetIndexJson) {
        Pattern hashPat = Pattern.compile("\"hash\"\\s*:\\s*\"([0-9a-f]{40})\"");
        Matcher m = hashPat.matcher(assetIndexJson);
        List<String> hashes = new ArrayList<>();
        while (m.find()) hashes.add(m.group(1));
        if (hashes.isEmpty()) return;

        Path objectsDir = ASSETS_DIR.resolve("objects");
        List<String> needed = new ArrayList<>();
        for (String hash : hashes) {
            Path file = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
            if (!Files.exists(file)) needed.add(hash);
        }
        if (needed.isEmpty()) {
            log("All assets already cached.");
            return;
        }
        log("Downloading " + needed.size() + " assets...");
        int threads = Math.min(8, needed.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(needed.size());
        AtomicInteger done = new AtomicInteger(0);
        for (String hash : needed) {
            pool.submit(() -> {
                try {
                    String prefix = hash.substring(0, 2);
                    Path file = objectsDir.resolve(prefix).resolve(hash);
                    Files.createDirectories(file.getParent());
                    String url = RESOURCES_URL + prefix + "/" + hash;
                    download(url, file);
                    int d = done.incrementAndGet();
                    if (d % 50 == 0) log("  Assets: " + d + "/" + needed.size());
                } catch (Exception e) {
                    log("  Asset download failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        try { latch.await(); } catch (InterruptedException ignored) {}
        pool.shutdown();
        log("Assets download complete.");
    }

    // --- Enhanced JSON helpers ---

    /** Extract a string value for a top-level key. */
    private String js(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Extract a nested value given a sequence of keys (up to any depth). */
    private String getNested(String json, String... keys) {
        if (keys.length == 0) return null;
        String current = json;
        for (String key : keys) {
            Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\{[^\\{\\}]*\\}|\"[^\"]*\"|[^,}\\]\\s]+)").matcher(current);
            if (!m.find()) return null;
            String value = m.group(1).trim();
            if (value.startsWith("{")) {
                // It's an object – continue traversal with this substring
                current = value;
            } else if (value.startsWith("\"")) {
                // Strip quotes and unescape if needed
                return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
            } else {
                // It's a literal (number, boolean, null)
                return value;
            }
        }
        return null; // Should not reach here if keys are exhausted
    }

    // For backward compatibility with existing calls
    private String jn(String json, String parent, String child, String dflt) {
        String val = getNested(json, parent, child);
        return val != null ? val : dflt;
    }

    private String jdn(String json, String a, String b, String c) {
        return getNested(json, a, b, c);
    }

    private String extractJsonValue(String json, String key) {
        Matcher m = JSON_PAIR_PATTERN.matcher(json);
        while (m.find()) {
            if (key.equals(m.group(1))) {
                if (m.group(2) != null) return m.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
                if (m.group(3) != null) return m.group(3).trim();
            }
        }
        return null;
    }

    private static String osName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    // --- LOGGING & UI helpers ---
    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            console.append("[LOG] " + msg + "\n");
            console.setCaretPosition(console.getDocument().getLength());
        });
    }

    private void status(String msg) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(msg));
    }

    private void prog(int value) {
        // could add progress bar if desired, but we'll skip for simplicity
    }
    
    private void toggleButtons(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            loginBtn.setEnabled(enabled && !isLoggedIn);
            fetchBtn.setEnabled(enabled);
            launchBtn.setEnabled(enabled);
            versionBox.setEnabled(enabled);
            emailField.setEnabled(enabled && !isLoggedIn);
            passwordField.setEnabled(enabled && !isLoggedIn);
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Program::new);
    }
}
