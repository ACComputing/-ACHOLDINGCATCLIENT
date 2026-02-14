import javax.swing.*;
import javax.swing.plaf.basic.BasicMenuUI;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.*;

public class Program {

    private JFrame frame;
    private JTextArea console;
    private JComboBox<String> versionBox;
    private JLabel statusLabel;
    private JButton loginBtn;
    private JButton fetchBtn;
    private JButton launchBtn;

    // HTTP Client
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // Thread pool
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private volatile boolean isPolling = false;
    private volatile boolean isLoggedIn = false;

    // Pre-compiled regex for JSON parsing
    private static final Pattern JSON_PAIR_PATTERN = Pattern.compile(
        "\"([^\"]+)\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^,}\\]\\s]+))"
    );

    // Blue accent color for buttons
    private static final Color BLUE_ACCENT = new Color(66, 135, 245);

    public Program() {
        initUI();
    }

    private void initUI() {
        // Use cross-platform look and feel for consistent color handling
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {}

        // Set UIManager properties for a consistent dark theme
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
        UIManager.put("ComboBox.buttonBackground", new Color(45, 45, 45));
        UIManager.put("ComboBox.buttonShadow", new Color(30, 30, 30));
        UIManager.put("ComboBox.buttonDarkShadow", Color.BLACK);
        UIManager.put("ComboBox.buttonHighlight", new Color(60, 60, 60));
        UIManager.put("ComboBox.disabledBackground", new Color(30, 30, 30));
        UIManager.put("ComboBox.disabledForeground", new Color(100, 100, 100));

        frame = new JFrame("CatClient - Fixed Edition");
        frame.setSize(600, 500);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                isPolling = false;
                executor.shutdownNow();
                frame.dispose();
                System.exit(0);
            }
        });
        frame.setResizable(false);

        // --- FULLY BLACK MENU BAR ---
        JMenuBar menuBar = new JMenuBar();
        menuBar.setBackground(Color.BLACK);
        menuBar.setOpaque(true);
        menuBar.setBorder(BorderFactory.createEmptyBorder()); // remove any border

        // File menu
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

        // Help menu
        JMenu helpMenu = new JMenu("Help");
        helpMenu.setForeground(Color.WHITE);
        helpMenu.setBackground(Color.BLACK);
        helpMenu.setOpaque(true);
        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.setForeground(Color.WHITE);
        aboutItem.setBackground(Color.BLACK);
        aboutItem.setOpaque(true);
        aboutItem.addActionListener(e -> JOptionPane.showMessageDialog(frame,
                "CatClient Fixed\nOptimized Launcher v1.0", "About",
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

        JLabel subtitle = new JLabel("Optimized Launcher");
        subtitle.setForeground(new Color(150, 150, 150));
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        // Buttons with blue text
        loginBtn = createButton("Login with Microsoft", BLUE_ACCENT);
        fetchBtn = createButton("Fetch Versions", BLUE_ACCENT);
        launchBtn = createButton("Launch Game", BLUE_ACCENT);

        // Version dropdown – manually set colors to override any remaining defaults
        versionBox = new JComboBox<>();
        versionBox.setMaximumSize(new Dimension(300, 30));
        versionBox.setAlignmentX(Component.CENTER_ALIGNMENT);
        versionBox.setBackground(new Color(45, 45, 45));
        versionBox.setForeground(Color.WHITE);
        ((JLabel)versionBox.getRenderer()).setHorizontalAlignment(JLabel.CENTER);

        // Customize the popup list of the combo box
        if (versionBox.getUI() instanceof javax.swing.plaf.basic.BasicComboBoxUI) {
            // Force the popup to be black (the UIManager settings should already do this)
            versionBox.setLightWeightPopupEnabled(false); // optional
        }

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
        root.add(Box.createVerticalStrut(20));
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
        loginBtn.addActionListener(e -> executor.submit(this::microsoftLogin));
        
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
        btn.setForeground(textColor); // blue text
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
                    .uri(URI.create("https://launchermeta.mojang.com/mc/game/version_manifest.json"))
                    .timeout(Duration.ofSeconds(10)) 
                    .header("Accept", "application/json") 
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new RuntimeException("HTTP " + response.statusCode());
            }

            String json = response.body();
            List<String> versions = new ArrayList<>();
            
            int versionsStart = json.indexOf("\"versions\"");
            if (versionsStart != -1) {
                int arrayStart = json.indexOf("[", versionsStart);
                int arrayEnd = json.lastIndexOf("]");
                
                if (arrayStart != -1 && arrayEnd != -1) {
                    String versionArray = json.substring(arrayStart + 1, arrayEnd);
                    String[] rawObjects = versionArray.split("\\}\\s*,");
                    
                    for (String obj : rawObjects) {
                        if (versions.size() >= 50) break;
                        if (!obj.endsWith("}")) obj += "}";
                        
                        String id = extractJsonValue(obj, "id");
                        String type = extractJsonValue(obj, "type");
                        
                        if (id != null && "release".equals(type)) {
                            versions.add(id);
                        }
                    }
                }
            }

            if (versions.isEmpty()) {
                throw new RuntimeException("No release versions found in manifest.");
            }

            SwingUtilities.invokeLater(() -> {
                versionBox.removeAllItems();
                for (String v : versions) {
                    versionBox.addItem(v);
                }
                if (versionBox.getItemCount() > 0) versionBox.setSelectedIndex(0);
                status("Ready");
                toggleButtons(true);
            });

            log("Successfully loaded " + versions.size() + " release versions.");

        } catch (Exception e) {
            log("Error fetching versions: " + e.getMessage());
            status("Error");
            toggleButtons(true);
        }
    }

    // --- MICROSOFT LOGIN ---
    private void microsoftLogin() {
        if (isPolling || isLoggedIn) {
            log("Login invalid or already in progress.");
            return;
        }

        log("=== Initiating Microsoft Auth ===");
        status("Requesting Device Code...");
        toggleButtons(false);

        String clientId = "00000000402b5328"; 
        String scope = "XboxLive.signin offline_access";

        try {
            String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) + 
                          "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"))
                    .timeout(Duration.ofSeconds(10))
                    .POST(BodyPublishers.ofString(body))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .build();

            HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
            String respBody = response.body();

            if (response.statusCode() != 200) {
                log("Auth init failed: " + respBody);
                status("Auth Failed");
                toggleButtons(true);
                return;
            }

            String deviceCode = extractJsonValue(respBody, "device_code");
            String userCode = extractJsonValue(respBody, "user_code");
            String verificationUri = extractJsonValue(respBody, "verification_uri");
            
            String intervalStr = extractJsonValue(respBody, "interval");
            int interval = 5;
            try {
                if (intervalStr != null) interval = Integer.parseInt(intervalStr);
            } catch (NumberFormatException ignored) {}

            if (userCode == null || verificationUri == null) {
                log("Failed to parse auth response.");
                toggleButtons(true);
                return;
            }

            log("------------------------------------------------");
            log("ACTION REQUIRED:");
            log("1. Code copied to clipboard: " + userCode);
            log("2. Opening: " + verificationUri);
            log("3. Paste the code and confirm.");
            log("------------------------------------------------");
            
            status("Waiting for user...");

            try {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(userCode), null);
            } catch (Exception ex) {
                log("Clipboard access failed (copy code manually).");
            }

            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                try {
                    Desktop.getDesktop().browse(new URI(verificationUri));
                } catch (Exception ex) {
                    log("Could not open browser automatically: " + ex.getMessage());
                }
            }

            startPolling(clientId, deviceCode, interval);

        } catch (Exception e) {
            log("Auth error: " + e.getMessage());
            status("Error");
            toggleButtons(true);
        }
    }

    private void startPolling(String clientId, String deviceCode, int intervalSeconds) {
        isPolling = true;
        executor.submit(() -> {
            long startTime = System.currentTimeMillis();
            long timeout = 15 * 60 * 1000; // 15 mins
            int currentInterval = intervalSeconds;

            while (isPolling && (System.currentTimeMillis() - startTime < timeout)) {
                try {
                    for (int i = 0; i < currentInterval; i++) {
                        if (!isPolling) return;
                        Thread.sleep(1000);
                    }

                    String body = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", StandardCharsets.UTF_8) +
                            "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                            "&device_code=" + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8);

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/token"))
                            .timeout(Duration.ofSeconds(10))
                            .POST(BodyPublishers.ofString(body))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Accept", "application/json")
                            .build();

                    HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
                    String json = response.body();

                    if (response.statusCode() == 200) {
                        String accessToken = extractJsonValue(json, "access_token");
                        
                        log(">>> AUTHENTICATION SUCCESSFUL! <<<");
                        if (accessToken != null && accessToken.length() > 15) {
                            log("Access Token acquired: " + accessToken.substring(0, 15) + "...");
                        }
                        
                        status("Logged In");
                        isPolling = false;
                        isLoggedIn = true;
                        SwingUtilities.invokeLater(() -> {
                            toggleButtons(true);
                            loginBtn.setText("Logged In (MS)");
                            loginBtn.setEnabled(false);
                            loginBtn.setBackground(new Color(0, 100, 50));
                            loginBtn.setForeground(Color.WHITE); // keep text readable
                        });
                        return;
                    }

                    if (json.contains("authorization_pending")) {
                        // continue polling
                    } else if (json.contains("expired_token")) {
                        log("Code expired. Please try again.");
                        isPolling = false;
                    } else if (json.contains("slow_down")) {
                        currentInterval += 5;
                        log("Polling slowed down. New interval: " + currentInterval + "s");
                    } else {
                        if (response.statusCode() >= 400 && response.statusCode() < 500 && !json.contains("pending")) {
                            log("Auth rejected: " + json);
                            isPolling = false;
                        }
                    }

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    isPolling = false;
                } catch (Exception e) {
                    log("Polling network error: " + e.getMessage());
                }
            }
            
            if (isPolling) {
                log("Auth timed out.");
                status("Timeout");
                isPolling = false;
                toggleButtons(true);
            }
        });
    }

    // --- LAUNCH ---
    private void launchGame(String version) {
        status("Launching...");
        log("PREPARING LAUNCH FOR: Minecraft " + version);
        toggleButtons(false);
        
        try {
            log("Checking file integrity...");
            Thread.sleep(600);
            log("Downloading native libraries...");
            Thread.sleep(600);
            log("Validating assets...");
            Thread.sleep(600);
            
            SwingUtilities.invokeLater(() -> {
                 JOptionPane.showMessageDialog(frame, 
                     "Launcher simulation complete for version " + version + ".\n" +
                     "Token and Version IDs are ready for process builder.", 
                     "Launch Success", JOptionPane.INFORMATION_MESSAGE);
                 status("Ready");
                 toggleButtons(true);
            });
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            toggleButtons(true);
        }
    }

    // --- UTILITIES ---
    private String extractJsonValue(String json, String key) {
        Matcher m = JSON_PAIR_PATTERN.matcher(json);
        while (m.find()) {
            if (key.equals(m.group(1))) {
                if (m.group(2) != null) {
                    return m.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
                } else if (m.group(3) != null) {
                    return m.group(3).trim();
                }
            }
        }
        return null;
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            console.append("[LOG] " + msg + "\n");
            console.setCaretPosition(console.getDocument().getLength());
        });
    }

    private void status(String msg) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(msg));
    }
    
    private void toggleButtons(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            loginBtn.setEnabled(enabled && !isLoggedIn);
            fetchBtn.setEnabled(enabled);
            launchBtn.setEnabled(enabled);
            versionBox.setEnabled(enabled);
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Program::new);
    }
}
