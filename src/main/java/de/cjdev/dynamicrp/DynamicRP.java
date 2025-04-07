package de.cjdev.dynamicrp;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import de.cjdev.dynamicrp.api.event.ZipPackEvent;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.resource.ResourcePackCallback;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackInfoLike;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
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
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.zip.*;

public final class DynamicRP extends JavaPlugin implements Listener {
    private static DynamicRP plugin;
    public static Logger LOGGER;

    private static HttpServer httpServer;
    private static int webServerPort;
    private static boolean customHost;
    private static boolean rpRequired;
    private static String localIP;
    private static String publicIP;
    private static final String resPackUrl = "http://%s:%s";
    private static File resPackFile;
    private static File overrideFolder;
    private static String resPackHash;
    public static Optional<Component> prompt = Optional.empty();
    private static final byte[] steveSkin;
    private static final byte[] playerResourcesLogo;
    private static ResourcePack resourcePack;
    private static final PlayerResourcePack playerResourcePack;

    static class PackRequest implements ResourcePackRequest {
        private ResourcePackCallback callback = ResourcePackCallback.noOp();
        private UUID uuid;
        private boolean local;
        public @NotNull String playerResourceHash = "";

        public void setPlayer(@Nullable UUID uuid, boolean local) {
            this.uuid = uuid;
            this.local = local;

            Player player;
            if (uuid != null && (player = Bukkit.getPlayer(uuid)) != null) {
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                try {
                    DynamicRP.addPlayerResources(player, byteArrayOutputStream);
                    this.playerResourceHash = calculateSHA1(byteArrayOutputStream.toByteArray());
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public @NotNull List<ResourcePackInfo> packs() {
            List<ResourcePackInfo> packs = new ArrayList<>(2);
            packs.add(resourcePack.forPlayer(this.local));
            if (uuid != null) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    packs.add(playerResourcePack.forPlayer(uuid, this.local));
                }
            }
            return packs;
        }

        @Override
        public @NotNull ResourcePackRequest packs(@NotNull Iterable<? extends ResourcePackInfoLike> packs) {
            return this;
        }

        @Override
        public @NotNull ResourcePackCallback callback() {
            return callback;
        }

        @Override
        public @NotNull ResourcePackRequest callback(@NotNull ResourcePackCallback cb) {
            callback = cb;
            return this;
        }

        @Override
        public boolean replace() {
            return true;
        }

        @Override
        public @NotNull ResourcePackRequest replace(boolean replace) {
            return this;
        }

        @Override
        public boolean required() {
            return rpRequired;
        }

        @Override
        public @Nullable Component prompt() {
            return prompt.orElse(null);
        }
    }

    private static final PackRequest packRequest = new PackRequest();

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
        customHost = config.getBoolean("custom-host");
        webServerPort = config.getInt("webserver.port");
        rpRequired = config.getBoolean("required");

        Bukkit.getPluginManager().registerEvents(this, this);

        // Zip Packs
        new BukkitRunnable() {
            @Override
            public void run() {
                if (zipPack())
                    C_refreshResourcePack();
            }
        }.runTaskAsynchronously(this);

        if (!customHost) {
            // Starting Web Server
            StartWebServer();
        }

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
        InetAddress address = event.getPlayer().getAddress().getAddress();
        if (address.isLoopbackAddress()) {
            //LOGGER.warning(event.getPlayer().getName() + " is LOCAL!!");
            isLocal = true;
        }
        event.getPlayer().getPersistentDataContainer().set(new NamespacedKey(this, "isLocal"), PersistentDataType.BOOLEAN, isLocal);

        refreshResourcePack(event.getPlayer());
    }

    private static class PlayerResourcePack implements ResourcePackInfo {

        private static final UUID id;
        private URI uri;
        private UUID uuid;

        @Override
        public @NotNull UUID id() {
            return id;
        }

        @Override
        public @NotNull URI uri() {
            return this.uri;
        }

        public PlayerResourcePack forPlayer(@NotNull UUID uuid, boolean local) {
            this.uuid = uuid;
            return local ? forLocal() : forPublic();
        }

        public PlayerResourcePack forLocal() {
            this.uri = URI.create(resPackUrl.formatted(localIP, webServerPort)).resolve("?uuid=" + uuid.toString());
            return this;
        }

        public PlayerResourcePack forPublic() {
            this.uri = URI.create(resPackUrl.formatted(publicIP, webServerPort)).resolve("?uuid=" + uuid.toString());
            return this;
        }

        @Override
        public @NotNull String hash() {
            return packRequest.playerResourceHash;
        }

        static {
            id = UUID.nameUUIDFromBytes("1551f557c29c58ef62a4359e7035e9ed1d4ba8ac".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static class ResourcePack implements ResourcePackInfo {

        private URI uri;

        private static final UUID id;

        public ResourcePack() {
        }

        @Override
        public @NotNull UUID id() {
            return id;
        }

        @Override
        public @NotNull URI uri() {
            return this.uri;
        }

        public ResourcePack forPlayer(boolean local) {
            return local ? forLocal() : forPublic();
        }

        public ResourcePack forLocal() {
            this.uri = URI.create(resPackUrl.formatted(localIP, webServerPort));
            return this;
        }

        public ResourcePack forPublic() {
            this.uri = URI.create(resPackUrl.formatted(publicIP, webServerPort));
            return this;
        }

        @Override
        public @NotNull String hash() {
            return resPackHash;
        }

        static {
            id = UUID.fromString("c5baacec-d2f3-4d46-bbc4-0820d9d73eed");
        }
    }

    public void C_refreshResourcePack(Player player) {
        if (!hasResourcePack()) return;
        boolean isLocal = Boolean.TRUE.equals(player.getPersistentDataContainer().get(new NamespacedKey(this, "isLocal"), PersistentDataType.BOOLEAN));
        String ip = isLocal ? "127.0.0.1" : publicIP; // isLocal ? localIP : publicIP;
        //LOGGER.warning(ip);
        packRequest.setPlayer(player.getUniqueId(), isLocal);
        player.sendResourcePacks(packRequest);
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

    private static String calculateSHA1(byte[] raw) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (ByteArrayInputStream bais = new ByteArrayInputStream(raw)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = bais.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) {
            hash.append(String.format("%02x", b));
        }
        return hash.toString();
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
            //LOGGER.warning(localIP);

            webServerPort = webServerPort < 0 || webServerPort > 65535 ? getFreePort() : webServerPort;
            httpServer = HttpServer.create(new InetSocketAddress(webServerPort), 0);

            httpServer.createContext("/", new RequestHandler());

            httpServer.start();
            LOGGER.info("\u001B[38;2;85;255;85mStarted Web Server on %s\u001B[0m".formatted(httpServer.getAddress().getPort()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static class RequestHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {

            URI requestURI = exchange.getRequestURI(); // ?file=UntitledGunPlugin
            Map<String, String> queryParams = queryToMap(requestURI.getQuery());
            File resourcePack;
            String uuid = queryParams.get("uuid");
            if (uuid != null) {
                Player player;
                if ((player = Bukkit.getPlayer(UUID.fromString(uuid))) != null) {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

                    DynamicRP.addPlayerResources(player, byteArrayOutputStream);

                    // Set headers
                    exchange.getResponseHeaders().set("Content-Type", "application/zip");
                    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=PlayerResources.zip");
                    byte[] bytes = byteArrayOutputStream.toByteArray();
                    exchange.sendResponseHeaders(200, bytes.length);

                    // Stream zip
                    try (OutputStream os = exchange.getResponseBody()) {
                        try (InputStream is = new ByteArrayInputStream(bytes)) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = is.read(buffer)) != -1) {
                                os.write(buffer, 0, bytesRead);
                            }
                        }
                    }
                    return;
                }
                String response = "File not found!";
                byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(404, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
                return;
            }

            // Set headers
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=pack.zip");
            exchange.sendResponseHeaders(200, resPackFile.length());

            // Stream file
            try (OutputStream os = exchange.getResponseBody()) {
                try (InputStream is = new FileInputStream(resPackFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }
            }

        }

        private Map<String, String> queryToMap(String query) {
            Map<String, String> result = new HashMap<>();
            if (query == null) return result;

            for (String param : query.split("&")) {
                String[] entry = param.split("=", 2);
                if (entry.length > 1) {
                    result.put(entry[0], decode(entry[1]));
                } else {
                    result.put(entry[0], "");
                }
            }
            return result;
        }

        private String decode(String value) {
            try {
                return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return value;
            }
        }
    }

    public static void addPlayerResources(Player player, ByteArrayOutputStream outputStream) throws IOException {
        byte[] packMCMetaData = "{\"pack\":{\"description\":\"Player Resources\",\"pack_format\":46,\"supported_formats\":{\"max_inclusive\":55,\"min_inclusive\":42}}}".getBytes(StandardCharsets.UTF_8);

        try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
            WriteBytesToZip(zos, "pack.mcmeta", packMCMetaData);
            try {
                PlayerProfile profile = player.getPlayerProfile();
                PlayerTextures textures = profile.getTextures();
                byte[] leftArm;
                byte[] rightArm;
                if (textures.getSkinModel() == PlayerTextures.SkinModel.CLASSIC) {
                    leftArm = "{\"texture_size\":[64,64],\"textures\":{\"skin\":\"dynamicrp:item/skin\"},\"elements\":[{\"from\":[4.999,7.999,5.999],\"to\":[9.001,20.001,10.001],\"rotation\":{\"angle\":0,\"axis\":\"y\",\"origin\":[8,8,8]},\"faces\":{\"north\":{\"uv\":[9,13,10,16],\"texture\":\"#skin\"},\"east\":{\"uv\":[8,13,9,16],\"texture\":\"#skin\"},\"south\":{\"uv\":[11,13,12,16],\"texture\":\"#skin\"},\"west\":{\"uv\":[10,13,11,16],\"texture\":\"#skin\"},\"up\":{\"uv\":[10,13,9,12],\"texture\":\"#skin\"},\"down\":{\"uv\":[11,12,10,13],\"texture\":\"#skin\"}}},{\"from\":[4.749,7.749,5.749],\"to\":[9.251,20.251,10.251],\"rotation\":{\"angle\":0,\"axis\":\"y\",\"origin\":[8,8,8]},\"faces\":{\"north\":{\"uv\":[13,13,14,16],\"texture\":\"#skin\"},\"east\":{\"uv\":[12,13,13,16],\"texture\":\"#skin\"},\"south\":{\"uv\":[15,13,16,16],\"texture\":\"#skin\"},\"west\":{\"uv\":[14,13,15,16],\"texture\":\"#skin\"},\"up\":{\"uv\":[14,13,13,12],\"texture\":\"#skin\"},\"down\":{\"uv\":[15,12,14,13],\"texture\":\"#skin\"}}},{\"from\":[9.243,20.251,10.251],\"to\":[4.741,7.749,5.749],\"rotation\":{\"angle\":0,\"axis\":\"z\",\"origin\":[8,8,8]},\"faces\":{\"north\":{\"uv\":[16,13,15,16],\"rotation\":180,\"texture\":\"#skin\"},\"east\":{\"uv\":[15,13,14,16],\"rotation\":180,\"texture\":\"#skin\"},\"south\":{\"uv\":[14,13,13,16],\"rotation\":180,\"texture\":\"#skin\"},\"west\":{\"uv\":[13,13,12,16],\"rotation\":180,\"texture\":\"#skin\"},\"up\":{\"uv\":[14,12,15,13],\"texture\":\"#skin\"},\"down\":{\"uv\":[13,13,14,12],\"texture\":\"#skin\"}}}]}".getBytes(StandardCharsets.UTF_8);
                    rightArm = "{\"texture_size\":[64,64],\"textures\":{\"skin\":\"dynamicrp:item/skin\"},\"elements\":[{\"from\":[6.999,7.999,5.999],\"to\":[11.001,20.001,10.001],\"rotation\":{\"angle\":0,\"axis\":\"y\",\"origin\":[8,8,8]},\"faces\":{\"north\":{\"uv\":[11,5,12,8],\"texture\":\"#skin\"},\"east\":{\"uv\":[10,5,11,8],\"texture\":\"#skin\"},\"south\":{\"uv\":[13,5,14,8],\"texture\":\"#skin\"},\"west\":{\"uv\":[12,5,13,8],\"texture\":\"#skin\"},\"up\":{\"uv\":[12,5,11,4],\"texture\":\"#skin\"},\"down\":{\"uv\":[13,4,12,5],\"texture\":\"#skin\"}}},{\"from\":[6.749,7.749,5.749],\"to\":[11.251,20.251,10.251],\"rotation\":{\"angle\":0,\"axis\":\"y\",\"origin\":[8,8,8]},\"faces\":{\"north\":{\"uv\":[11,9,12,12],\"texture\":\"#skin\"},\"east\":{\"uv\":[10,9,11,12],\"texture\":\"#skin\"},\"south\":{\"uv\":[13,9,14,12],\"texture\":\"#skin\"},\"west\":{\"uv\":[12,9,13,12],\"texture\":\"#skin\"},\"up\":{\"uv\":[12,9,11,8],\"texture\":\"#skin\"},\"down\":{\"uv\":[13,8,12,9],\"texture\":\"#skin\"}}},{\"from\":[11.243,20.251,10.251],\"to\":[6.741,7.749,5.749],\"rotation\":{\"angle\":0,\"axis\":\"z\",\"origin\":[24,8,8]},\"faces\":{\"north\":{\"uv\":[14,9,13,12],\"rotation\":180,\"texture\":\"#skin\"},\"east\":{\"uv\":[13,9,12,12],\"rotation\":180,\"texture\":\"#skin\"},\"south\":{\"uv\":[12,9,11,12],\"rotation\":180,\"texture\":\"#skin\"},\"west\":{\"uv\":[11,9,10,12],\"rotation\":180,\"texture\":\"#skin\"},\"up\":{\"uv\":[12,8,13,9],\"texture\":\"#skin\"},\"down\":{\"uv\":[11,9,12,8],\"texture\":\"#skin\"}}}]}".getBytes(StandardCharsets.UTF_8);
                } else {
                    leftArm = "{\"texture_size\":[64,64],\"textures\":{\"skin\":\"dynamicrp:item/skin\"},\"elements\":[{\"from\":[5.999,7.999,5.999],\"to\":[9.001,20.001,10.001],\"rotation\":{\"angle\":0,\"axis\":\"y\",\"origin\":[8,8,8]},\"faces\":{\"north\":{\"uv\":[9,13,9.75,16],\"texture\":\"#skin\"},\"east\":{\"uv\":[8,13,9,16],\"texture\":\"#skin\"},\"south\":{\"uv\":[10.75,13,11.5,16],\"texture\":\"#skin\"},\"west\":{\"uv\":[9.75,13,10.75,16],\"texture\":\"#skin\"},\"up\":{\"uv\":[9.75,13,9,12],\"texture\":\"#skin\"},\"down\":{\"uv\":[10.5,12,9.75,13],\"texture\":\"#skin\"}}},{\"from\":[5.749,7.749,5.749],\"to\":[9.251,20.251,10.251],\"rotation\":{\"angle\":0,\"axis\":\"y\",\"origin\":[8,8,8]},\"faces\":{\"north\":{\"uv\":[13,13,13.75,16],\"texture\":\"#skin\"},\"east\":{\"uv\":[12,13,13,16],\"texture\":\"#skin\"},\"south\":{\"uv\":[14.75,13,15.5,16],\"texture\":\"#skin\"},\"west\":{\"uv\":[13.75,13,14.75,16],\"texture\":\"#skin\"},\"up\":{\"uv\":[13.75,13,13,12],\"texture\":\"#skin\"},\"down\":{\"uv\":[14.5,12,13.75,13],\"texture\":\"#skin\"}}},{\"from\":[9.239,20.251,10.251],\"to\":[5.737,7.749,5.749],\"rotation\":{\"angle\":0,\"axis\":\"z\",\"origin\":[16,8,8]},\"faces\":{\"north\":{\"uv\":[15.5,13,14.75,16],\"rotation\":180,\"texture\":\"#skin\"},\"east\":{\"uv\":[14.75,13,13.75,16],\"rotation\":180,\"texture\":\"#skin\"},\"south\":{\"uv\":[13.75,13,13,16],\"rotation\":180,\"texture\":\"#skin\"},\"west\":{\"uv\":[13,13,12,16],\"rotation\":180,\"texture\":\"#skin\"},\"up\":{\"uv\":[13.75,12,14.5,13],\"texture\":\"#skin\"},\"down\":{\"uv\":[13,13,13.75,12],\"texture\":\"#skin\"}}}]}".getBytes(StandardCharsets.UTF_8);
                    rightArm = "{\"texture_size\":[64,64],\"textures\":{\"skin\":\"dynamicrp:item/skin\"},\"elements\":[{\"from\":[6.999,7.999,5.999],\"to\":[10.001,20.001,10.001],\"rotation\":{\"angle\":0,\"axis\":\"y\",\"origin\":[8,8,8]},\"faces\":{\"north\":{\"uv\":[11,5,11.75,8],\"texture\":\"#skin\"},\"east\":{\"uv\":[10,5,11,8],\"texture\":\"#skin\"},\"south\":{\"uv\":[12.75,5,13.5,8],\"texture\":\"#skin\"},\"west\":{\"uv\":[11.75,5,12.75,8],\"texture\":\"#skin\"},\"up\":{\"uv\":[11.75,5,11,4],\"texture\":\"#skin\"},\"down\":{\"uv\":[12.5,4,11.75,5],\"texture\":\"#skin\"}}},{\"from\":[6.749,7.749,5.749],\"to\":[10.251,20.251,10.251],\"rotation\":{\"angle\":0,\"axis\":\"y\",\"origin\":[8,8,8]},\"faces\":{\"north\":{\"uv\":[11,9,11.75,12],\"texture\":\"#skin\"},\"east\":{\"uv\":[10,9,11,12],\"texture\":\"#skin\"},\"south\":{\"uv\":[12.75,9,13.5,12],\"texture\":\"#skin\"},\"west\":{\"uv\":[11.75,9,12.75,12],\"texture\":\"#skin\"},\"up\":{\"uv\":[11.75,9,11,8],\"texture\":\"#skin\"},\"down\":{\"uv\":[12.5,8,11.75,9],\"texture\":\"#skin\"}}},{\"from\":[10.247,20.251,10.251],\"to\":[6.745,7.749,5.749],\"rotation\":{\"angle\":0,\"axis\":\"z\",\"origin\":[15,8,8]},\"faces\":{\"north\":{\"uv\":[13.5,9,12.75,12],\"rotation\":180,\"texture\":\"#skin\"},\"east\":{\"uv\":[12.75,9,11.75,12],\"rotation\":180,\"texture\":\"#skin\"},\"south\":{\"uv\":[11.75,9,11,12],\"rotation\":180,\"texture\":\"#skin\"},\"west\":{\"uv\":[11,9,10,12],\"rotation\":180,\"texture\":\"#skin\"},\"up\":{\"uv\":[11.75,8,12.5,9],\"texture\":\"#skin\"},\"down\":{\"uv\":[11,9,11.75,8],\"texture\":\"#skin\"}}}]}".getBytes(StandardCharsets.UTF_8);
                }

                URL skinURL = textures.getSkin();
                if (skinURL != null) {
                    try (InputStream in = skinURL.openStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                        WriteBytesToZip(zos, "assets/dynamicrp/textures/item/skin.png", out.toByteArray());
                    }
                } else {
                    WriteBytesToZip(zos, "assets/dynamicrp/textures/item/skin.png", steveSkin);
                }
                WriteBytesToZip(zos, "assets/dynamicrp/models/item/leftarm.json", leftArm);
                WriteBytesToZip(zos, "assets/dynamicrp/models/item/rightarm.json", rightArm);
                WriteBytesToZip(zos, "pack.png", playerResourcesLogo);
            } catch (IOException ignored) {
            }
        }
    }

    private void StopWebServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            LOGGER.info("\u001B[38;2;255;85;85mStopped Web Server\u001B[0m");
        }
    }

    private static void WriteBytesToZip(ZipOutputStream stream, String name, byte[] raw) throws IOException {
        CRC32 packMCMetaCrc = new CRC32();
        packMCMetaCrc.update(raw);
        ZipEntry packMCMetaEntry = new ZipEntry(name);
        packMCMetaEntry.setTime(0);
        packMCMetaEntry.setSize(raw.length);
        packMCMetaEntry.setMethod(ZipEntry.DEFLATED);
        packMCMetaEntry.setCrc(packMCMetaCrc.getValue());

        stream.putNextEntry(packMCMetaEntry);
        stream.write(raw);
        stream.closeEntry();
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

            try {
                Path path = Paths.get(classUrl.toURI());

                ZipFile jarFile = new ZipFile(path.toFile());
                addAssetsFromZipEntries(entries::add, jarFile);
            } catch (URISyntaxException ignored) {
            } catch (IOException e) {
                LOGGER.warning(e.getMessage());
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

        DynamicRP.writePack(resPackFile, entries);

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

    public static void writePack(File file, List<ZipEntryData> entries) {
        if (entries.isEmpty())
            return;
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(file))) {
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
    }

    public static void addAssetsFromZipEntries(Consumer<ZipEntryData> entryConsumer, ZipFile zipFile) throws IOException {
        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
        while (zipEntries.hasMoreElements()) {
            ZipEntry zipEntry = zipEntries.nextElement();
            if (!zipEntry.getName().startsWith("assets/") || zipEntry.isDirectory()) {
                continue;
            }
            try (InputStream is = zipFile.getInputStream(zipEntry)) {
                byte[] content = is.readAllBytes();
                entryConsumer.accept(new ZipEntryData(zipEntry, content));
            }
        }
    }

    public record ZipEntryData(ZipEntry entry, byte[] content) {
    }

    static {

        steveSkin = Base64.getDecoder().decode("UklGRlIDAABXRUJQVlA4TEUDAAAvP8APEDehOJKk5O5w50vuJERELmkwCgCQkb1HCl4SaKB/BwW2m2wCAEiDu8NtvYlHCtvmPwAA//+XIBFCOMWkIBBCQmNbHeNsrlbJe1oITmtOay8tY2wdIq8dJXri/xEFGNeSD5SkLLFOKTY+InLCb6XHc0ALysozqGBqeeA8EVRhZoFsW3vquJlQ6Jw7SXClHEAkAef9nxH9Irbnqr2K6L8Dt20jSe4E09vozOzmDTsj74P3Ifib0W5z+bDvNeWx6y7sg9cYH0HxHsGHEHy4Ix6bIlbuQAg6eL+/RR0dpzuwh2qYbhDvMi3oFH+bDl23DQO8bE7n7fd7Ld6pE+8DOPCbPG/vXAhOS9DoHL7De/Wz3+a84YWll9cXpt/gYx8CDHchrA9wTM574pcXZiYwi6NgmW3eVx/J8cM30/cDO4pgJTjYZQOCWqQu5ufnHroc9DqnXX49zrtI0+1vnA5PT8du4DZRdN4pfhNuMdKk5XY8HA+3KU7Ui778v7q2+v5r1/l8Olkfn0wxEn9WI0spRYiZl3E6nc8DKHbRQpgpLg8b8NFZFdZXRCs/htZqm+e5ttpam+eau4owEUtBD5JzlqLDREp56ypdA+qIuSunlEvJzDovpQzrFXt1vr7eisgA47dZ0vt7kiJFx3UPULLW4E4yUEQW0mvvXdaretCz7sib0VeXbjBGR2mpXUoSIBkyPC+iqBFERD8ErdUKu6R+JRHAVOAYmJl6ZIpE4x3seQ+aEzwsyVKS8QCdxHgDEI1oyHha0rMSyeoBEAMDcRxqVZpdouWUc8oCjeAAds2JmBD6ieu1Gl1BOSsZpqi0RXJK3LFlzPnSzf7RuRe1cMEdW6tJclIDKfdYZ4QQGnI+X9Tv5QwOzub7aXNLqeGZ1tTmmXQ89sDN7p9bH6giIlK6gd1/Ba3/H5dSCjOTsm0eMyYS8z3Dhwa+a5BIaypL8pBm84CGeYaIFNR67HomH5khT7BFZCMqJBIjsukT2SxYWpshzxgKf3cGKpbleQjmGZEigeL6YP6+IRFSD0SRMOVAbVzpwkRjkHXwlkMaAOswA9uEWs2bZw4wEiG0luV5iL20Yx3L8xDMMyy0guV5CGEH2bzjR3YA");
        playerResourcesLogo = Base64.getDecoder().decode("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");

        resourcePack = new ResourcePack();
        playerResourcePack = new PlayerResourcePack();

    }
}
