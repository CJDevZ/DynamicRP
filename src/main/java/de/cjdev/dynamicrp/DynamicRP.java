package de.cjdev.dynamicrp;

import com.sun.net.httpserver.HttpServer;
import de.cjdev.dynamicrp.api.event.ZipPackEvent;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class DynamicRP extends JavaPlugin implements Listener {
    private static DynamicRP plugin;
    public static Logger LOGGER;

    private static HttpServer httpServer;
    private static int webServerPort;
    private static String localIP;
    private static String publicIP;
    private static final String resPackUrl = "http://%s:%s";
    private static File resPackFile;
    private static File overrideFolder;
    private static String resPackHash;

    @Override
    public void onEnable() {
        plugin = this;
        LOGGER = getLogger();

        resPackFile = Path.of(getDataPath().toString(), "pack.zip").toFile();
        overrideFolder = Path.of(getDataPath().toString(), "override").toFile();

        this.saveDefaultConfig();
        overrideFolder.mkdirs();

        // Loading Config
        FileConfiguration config = getConfig();
        webServerPort = config.getInt("webserver.port");

        Bukkit.getPluginManager().registerEvents(this, this);

        // Zip Packs
        new BukkitRunnable() {
            @Override
            public void run() {
                if (zipPack())
                    C_refreshResourcePack();
            }
        }.runTaskAsynchronously(this);

        // Starting Web Server
        StartWebServer();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(Commands.literal("drp").requires(ctx -> ctx.getSender().isOp()).then(Commands.literal("zip").executes(ctx -> {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (zipPack()) {
                        ctx.getSource().getSender().sendMessage("Updated Resource Pack");
                        C_refreshResourcePack();
                    } else {
                        ctx.getSource().getSender().sendMessage("Resource Pack up to date");
                    }
                }
            }.runTaskAsynchronously(this);
            return 1;
        }).build()).build()));
    }

    @Override
    public void onDisable() {
        StopWebServer();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        boolean isLocal = false;
        if (event.getPlayer().getAddress().getAddress().isLoopbackAddress()) {
            LOGGER.warning(event.getPlayer().getName() + " is LOCAL!!");
            isLocal = true;
        }
        event.getPlayer().getPersistentDataContainer().set(new NamespacedKey(this, "isLocal"), PersistentDataType.BOOLEAN, isLocal);

        refreshResourcePack(event.getPlayer());
    }

    public void C_refreshResourcePack(Player player) {
        if (!hasResourcePack()) return;
        boolean isLocal = Boolean.TRUE.equals(player.getPersistentDataContainer().get(new NamespacedKey(this, "isLocal"), PersistentDataType.BOOLEAN));
        String ip = isLocal ? "127.0.0.1" : publicIP; // isLocal ? localIP : publicIP;
        LOGGER.warning(ip);
        player.setResourcePack(String.format(resPackUrl, ip, DynamicRP.webServerPort), DynamicRP.resPackHash, true);
    }

    public void refreshResourcePack(Player player){
        plugin.C_refreshResourcePack(player);
    }

    public void C_refreshResourcePack() {
        Bukkit.getOnlinePlayers().forEach(this::C_refreshResourcePack);
    }

    public static void refreshResourcePack() {
        plugin.C_refreshResourcePack();
    }

    private static boolean hasResourcePack() {
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

    public static CompletableFuture<Void> callZipPackEventAsync(Consumer<ZipEntryData> consumer) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        // Run the event on the main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            ZipPackEvent zipPackEvent = new ZipPackEvent(consumer);
            Bukkit.getPluginManager().callEvent(zipPackEvent);
            future.complete(null);
        });

        return future;
    }

    private void StartWebServer() {
        try {
            publicIP = getPublicIP();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();

                // Ignore loopback, down, or virtual interfaces
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }

                Enumeration<InetAddress> enumerate = networkInterface.getInetAddresses();
                while (enumerate.hasMoreElements()) {
                    InetAddress address = enumerate.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        localIP = address.getHostAddress();
                    }
                }
            }
            LOGGER.warning(localIP);

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
            LOGGER.info("\u001B[38;2;85;255;85mStarted Web Server\u001B[0m");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void StopWebServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            LOGGER.info("\u001B[38;2;255;85;85mStopped Web Server\u001B[0m");
        }
    }

    /// Returns if the hash changed or not, or if it failed (false) ¯\_(ツ)_/¯
    public boolean zipPack() {
        getDataFolder().mkdirs();
        if (hasResourcePack()) {
            try {
                resPackHash = calculateSHA1(resPackFile);
            } catch (Exception e) {
                return false;
            }
        }

        // Loading Assets
        List<ZipEntryData> entries = new ArrayList<>();

        for (@NotNull Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            URL classUrl = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            if (classUrl == null)
                continue;

            String path = URLDecoder.decode(classUrl.getPath(), StandardCharsets.UTF_8).substring(1);

            try (ZipFile jarFile = new ZipFile(Path.of(path).toFile())) {
                addAssetsFromZipEntries(entries::add, jarFile);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        OVERRIDE_FOLDER:
        {
            File[] listFiles;
            if (!overrideFolder.exists() || (listFiles = overrideFolder.listFiles()) == null)
                break OVERRIDE_FOLDER;
            for (File file : listFiles) {
                if (!file.getName().endsWith(".zip"))
                    continue;
                try (ZipFile zipFile = new ZipFile(file)) {
                    addAssetsFromZipEntries(entries::add, zipFile);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        try {
            DynamicRP.callZipPackEventAsync(entries::add).get();
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.warning(e.getMessage());
        }

        // Add pack.mcmeta to the zip
        byte[] packMCMetaData = "{\"pack\":{\"description\":\"\",\"pack_format\":46,\"supported_formats\":{\"max_inclusive\":55,\"min_inclusive\":42}}}".getBytes(StandardCharsets.UTF_8);
        CRC32 packMCMetaCrc = new CRC32();
        packMCMetaCrc.update(packMCMetaData);
        ZipEntry packMCMetaEntry = new ZipEntry("pack.mcmeta");
        packMCMetaEntry.setTime(0);
        packMCMetaEntry.setSize(packMCMetaData.length);
        packMCMetaEntry.setMethod(ZipEntry.DEFLATED);
        packMCMetaEntry.setCrc(packMCMetaCrc.getValue());
        entries.add(new ZipEntryData(packMCMetaEntry, packMCMetaData));

        // Set to store unique elements we have already seen
        Set<String> seen = new HashSet<>();

        // Iterate backwards through the list
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (seen.contains(entries.get(i).entry.getName())) {
                // Remove duplicate
                entries.remove(i);
            } else {
                // Mark this element as seen
                seen.add(entries.get(i).entry.getName());
            }
        }

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(resPackFile))) {
            for (ZipEntryData entryData : entries) {
                ZipEntry zipEntry = new ZipEntry(entryData.entry().getName());
                zipEntry.setTime(entryData.entry.getTime());
                zipEntry.setSize(entryData.entry.getSize());
                zipEntry.setMethod(entryData.entry.getMethod());
                zipEntry.setComment(entryData.entry.getComment());
                zipEntry.setCrc(entryData.entry.getCrc());
                zos.putNextEntry(zipEntry);
                zos.write(entryData.content());
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (hasResourcePack()) {
            try {
                String newHash = calculateSHA1(resPackFile);
                if (resPackHash == null || !resPackHash.equals(newHash)) {
                    resPackHash = newHash;
                    return true;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    public static void addAssetsFromZipEntries(Consumer<ZipEntryData> entryConsumer, ZipFile zipFile) throws IOException {
        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
        while (zipEntries.hasMoreElements()) {
            ZipEntry zipEntry = zipEntries.nextElement();
            if (!zipEntry.getName().startsWith("assets/") || zipEntry.isDirectory())
                continue;
            try (InputStream is = zipFile.getInputStream(zipEntry)) {
                byte[] content = is.readAllBytes();
                entryConsumer.accept(new ZipEntryData(zipEntry, content));
            }
        }
    }

    public record ZipEntryData(ZipEntry entry, byte[] content) {
    }
}
