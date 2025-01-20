package de.cjdev.dynamicrp;

import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class DynamicRP extends JavaPlugin implements Listener {
    public static Logger LOGGER;

    private static HttpServer httpServer;
    private static int webServerPort;
    private static String publicIP;
    private static final String resPackUrl = "http://%s:%s";
    private static File resPackFile;
    private static String resPackHash;

    @Override
    public void onEnable() {
        LOGGER = getLogger();

        resPackFile = Path.of(getDataPath().toString(), "pack.zip").toFile();

        this.saveDefaultConfig();

        // Loading Config
        FileConfiguration config = getConfig();
        webServerPort = config.getInt("webserver.port");

        Bukkit.getPluginManager().registerEvents(this, this);

        // Zip Packs
        new BukkitRunnable() {
            @Override
            public void run() {
                zipPack();
            }
        }.runTask(this);

        // Starting Web Server
        StartWebServer();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(Commands.literal("drp").requires(ctx -> ctx.getExecutor().isOp()).then(Commands.literal("zip").executes(ctx -> {
            if (zipPack())
                refreshResourcePack();
            return 1;
        }).build()).build()));
    }

    @Override
    public void onDisable() {
        StopWebServer();
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        //event.getPlugin().getClass().
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event){
        refreshResourcePack(event.getPlayer());
    }

    public static void refreshResourcePack(Player player) {
        if (!resPackFile.exists()) return;
        player.setResourcePack(String.format(resPackUrl, publicIP, DynamicRP.webServerPort), DynamicRP.resPackHash, true);
    }

    public static void refreshResourcePack(){
        Bukkit.getOnlinePlayers().forEach(DynamicRP::refreshResourcePack);
    }

    private boolean hasResourcePack(){
        return resPackFile.exists();
    }

    private static String calculateSHA1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) {
            hash.append(String.format("%02x", b));
        }
        return hash.toString();
    }

    private static String getPublicIP() throws IOException {
        URL url = new URL("https://checkip.amazonaws.com/");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String publicIP = reader.readLine();
        reader.close();

        return publicIP;
    }

    private static int getFreePort() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("0.0.0.0"))) {
            return socket.getLocalPort(); // Returns an available port.
        } catch (Exception e) {
            e.printStackTrace();
            return -1; // Handle the error as needed.
        }
    }

    private void StartWebServer() {
        try {
            publicIP = getPublicIP();

            webServerPort = webServerPort < 0 || webServerPort > 65535 ? getFreePort() : webServerPort;
            httpServer = HttpServer.create(new InetSocketAddress(webServerPort), 0);

            httpServer.createContext("/", exchange -> {
                if (!hasResourcePack()) {
                    String response = "File not found!";
                    exchange.sendResponseHeaders(404, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                }

                // Set headers
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=" + resPackFile.getName());
                exchange.sendResponseHeaders(200, resPackFile.length());

                // Stream the file
                try (OutputStream os = exchange.getResponseBody()) {
                    InputStream is = new FileInputStream(resPackFile);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }
            });

            httpServer.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void StopWebServer(){
        if(httpServer != null){
            httpServer.stop(0);
            LOGGER.info(ChatColor.BLUE + "Stopped Web Server");
        }
    }

    ///
    /// Returns if the hash changed or not, or if it failed ¯\_(ツ)_/¯ (false)
    ///
    public boolean zipPack() {
        getDataFolder().mkdir();
        try {
            if (hasResourcePack()) {
                resPackHash = calculateSHA1(resPackFile);
            }
        } catch (Exception e) {
            return false;
        }

        // Loading Assets
        List<JarEntryData> entries = new ArrayList<>();

        for (File file : getServer().getPluginsFolder().listFiles()) {
            if (!file.getName().endsWith(".jar"))
                continue;

            try (JarFile jarFile = new JarFile(file)) {
                Enumeration<JarEntry> jarEntries = jarFile.entries();
                while (jarEntries.hasMoreElements()) {
                    JarEntry entry = jarEntries.nextElement();

                    if (entry.getName().startsWith("assets/") && !entry.isDirectory()) {
                        try(InputStream is = jarFile.getInputStream(entry)){
                            byte[] content = is.readAllBytes();
                            entries.add(new JarEntryData(entry, content));
                        }
                    }
                }
            } catch (IOException e) {
                return false;
            }
        }

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(resPackFile))) {
            for (JarEntryData entryData : entries) {
                ZipEntry zipEntry = new ZipEntry(entryData.entry().getName());
                zos.putNextEntry(zipEntry);

                zos.write(entryData.content());
                zos.closeEntry();
            }
            ZipEntry packMCMetaEntry = new ZipEntry("pack.mcmeta");
            zos.putNextEntry(packMCMetaEntry);
            zos.write("{\"pack\":{\"description\":\"\",\"pack_format\":46}}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (hasResourcePack()) {
            try {
                String newHash = calculateSHA1(resPackFile);
                if(resPackHash == null || !resPackHash.equals(newHash)){
                    resPackHash = newHash;
                    return true;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private record JarEntryData(JarEntry entry, byte[] content) { }
}
