package de.cjdev.dynamicrp;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.google.gson.*;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import de.cjdev.dynamicrp.api.PackByteWriter;
import de.cjdev.dynamicrp.api.PackWriter;
import de.cjdev.dynamicrp.api.ZipPackCallback;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.plugin.manager.PaperPluginManagerImpl;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.OverlayMetadataSection;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.util.InclusiveRange;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerTextures;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.*;

public final class DynamicRP implements PluginBootstrap {

    public static DynamicRP INSTANCE;
    public static ComponentLogger LOGGER;

    public static final List<UUID> localPlayers = new ArrayList<>();

    private static HttpServer httpServer;
    private static Path configPath;
    private static int webServerPort;
    private static boolean rpRequired;
    private static String hostName;
    private static boolean useHostName;
    static boolean customHost;
    private static String localIP;
    private static String publicIP;
    private static final String resPackUrl = "http://%s:%s";
    private static File resPackFile;
    private static File overrideFolder;
    private static String resPackHash = "";
    private static final byte[] steveSkin;
    private static final byte[] playerResourcesLogo;
    private static final String[] arms;
    static boolean pluginInit;
    public static final UUID PACK = UUID.randomUUID();
    public static final UUID PLAYER_PACK = UUID.randomUUID();

    public static final List<ZipPackCallback> ZIP_PACK_CALLBACKS;

    public DynamicRP() {
        INSTANCE = this;
    }

    static boolean isLocalNetwork(InetAddress clientAddress) {
        if (clientAddress.isLoopbackAddress()) return true;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                NetworkInterface nextElement = interfaces.nextElement();

                if (!nextElement.isUp() || nextElement.isLoopback()) continue;

                for (InterfaceAddress interfaceAddress : nextElement.getInterfaceAddresses()) {
                    InetAddress localAddress = interfaceAddress.getAddress();
                    int prefix = interfaceAddress.getNetworkPrefixLength();

                    if (clientAddress.getClass() != localAddress.getClass()) continue;
                    if (prefix <= 0 || prefix > (clientAddress instanceof Inet4Address ? 32 : 128)) continue;

                    byte[] clientBytes = clientAddress.getAddress();
                    byte[] localBytes = localAddress.getAddress();

                    if (isSameSubnet(clientBytes, localBytes, prefix)) return true;
                }
            }
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }

        return false;
    }

    private static boolean isSameSubnet(byte[] ip1, byte[] ip2, int prefixLength) {
        int byteCount = prefixLength / 8;
        int bitRemainder = prefixLength % 8;

        for (int i = 0; i < byteCount; i++) {
            if (ip1[i] != ip2[i]) return false;
        }

        if (bitRemainder > 0) {
            int mask = (0xFF << (8 - bitRemainder)) & 0xFF;
            return (ip1[byteCount] & mask) == (ip2[byteCount] & mask);
        }

        return true;
    }

    public void loadConfig() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configPath.toFile());
        webServerPort = config.getInt("webserver.port", -1);
        rpRequired = config.getBoolean("required", false);
        hostName = config.getString("hostName", "");
        useHostName = !hostName.isEmpty() && !hostName.equals("0.0.0.0");
        customHost = config.getBoolean("custom-host");
    }

    @Override
    public void bootstrap(BootstrapContext context) {
        LOGGER = context.getLogger();

        configPath = context.getDataDirectory().resolve("config.yml");
        resPackFile = Path.of(context.getDataDirectory().toString(), "pack.zip").toFile();
        overrideFolder = Path.of(context.getDataDirectory().toString(), "override").toFile();
        overrideFolder.mkdirs();

        try (Writer configWriter = Files.newBufferedWriter(configPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            configWriter.write("""
                    webserver:
                      port: 0
                    required: true
                    hostName: 0.0.0.0
                    
                    # Not implemented
                    #custom-host: false
                    #CUSTOM:
                    #  upload: https://insertapi.com/upload
                    #  download: http://insertapi.com/upload/%s
                    #  auth: XXXX-XXXX-XXXX-XXXX
                    """);
        } catch (IOException ignored) {}

        // Loading Config
        loadConfig();

        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralCommandNode pluginCommand = Commands.literal("dynamicrp").requires(ctx -> ctx.getSender().isOp()).then(Commands.literal("zip").executes(ctx -> {
                CompletableFuture.runAsync(() -> {
                    if (makePack()) {
                        ctx.getSource().getSender().sendMessage("Updated Resource Pack");
                        refreshResourcePack();
                    } else {
                        ctx.getSource().getSender().sendMessage("Failed to Update Resource Pack");
                    }
                });
                return 1;
            })).then(Commands.literal("reload").executes(ctx -> {
                StopWebServer();
                loadConfig();
                StartWebServer();
                return 1;
            })).build();
            event.registrar().register(pluginCommand);
            event.registrar().register(Commands.literal("drp").redirect(pluginCommand).build());
        });
    }

    public static void sendPlayerPack(Player player, boolean local) {
        String sha1 = "";
        var buffer = new ByteArrayOutputStream();
        try (var writer = new PackByteWriter(buffer)) {
            DynamicRP.writePlayerResourcePack(player, writer);
        } catch (Exception e) {
            LOGGER.warn("Failed to write Player Resources", e);
        }
        try {
            sha1 = calculateSHA1(buffer.toByteArray());
        } catch (Exception e) {
            LOGGER.warn("Failed to calculate SHA-1", e);
        }
        var pack = ResourcePackInfo.resourcePackInfo(DynamicRP.PLAYER_PACK, URI.create(resPackUrl.formatted(useHostName ? hostName : local ? localIP : publicIP, webServerPort)).resolve("?uuid=" + player.getUniqueId()), sha1);
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(pack).required(rpRequired));
    }

    public static ResourcePackInfo getGeneratedPack(boolean local) {
        return ResourcePackInfo.resourcePackInfo(PACK, URI.create(resPackUrl.formatted(useHostName ? hostName : local ? localIP : publicIP, webServerPort)), resPackHash);
    }

    public void refreshResourcePack(Player player) {
        if (!hasResourcePack()) return;
        boolean isLocal = localPlayers.contains(player.getUniqueId());
        var packInfo = getGeneratedPack(isLocal);
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(packInfo).required(rpRequired));
    }

    public void refreshResourcePack() {
        Bukkit.getOnlinePlayers().forEach(this::refreshResourcePack);
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
        URL url = URI.create("https://checkip.amazonaws.com/").toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String publicIP = reader.readLine();
        reader.close();

        return publicIP;
    }

    static void StopWebServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            LOGGER.info("\u001B[38;2;255;85;85mStopped Web Server\u001B[0m");
        }
    }

    static void StartWebServer() {
        try {
            publicIP = getPublicIP();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            FIND_OUT_LOCAL:
            {
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
                            break FIND_OUT_LOCAL;
                        }
                    }
                }
            }
            //LOGGER.warning(localIP);

            webServerPort = Math.max(0, Math.min(webServerPort, 0xFFFF));
            httpServer = HttpServer.create(new InetSocketAddress(hostName, webServerPort), 0);

            httpServer.createContext("/", new RequestHandler());

            httpServer.start();
            webServerPort = httpServer.getAddress().getPort();
            LOGGER.info("\u001B[38;2;85;255;85mStarted Web Server on {}\u001B[0m", httpServer.getAddress().getPort());
        } catch (BindException e) {
            LOGGER.warn("\u001B[38;2;255;85;85mAddress already in use. Restart the Server after Fix\u001B[0m");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static class RequestHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {

            URI requestURI = exchange.getRequestURI(); // ?file=UntitledGunPlugin
            Map<String, String> queryParams = queryToMap(requestURI.getQuery());
            String uuid = queryParams.get("uuid");
            if (uuid != null) {
                Player player;
                if ((player = Bukkit.getPlayer(UUID.fromString(uuid))) != null) {
                    // Set headers
                    exchange.getResponseHeaders().set("Content-Type", "application/zip");
                    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=PlayerResources.zip");

                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    try (var writer = new PackByteWriter(buffer)) {
                        DynamicRP.writePlayerResourcePack(player, writer);
                    } catch (Exception e) {
                        LOGGER.warn("Failed to write Player Resources", e);
                    }
                    byte[] bytes = buffer.toByteArray();
                    exchange.sendResponseHeaders(200, bytes.length);

                    // Stream zip
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
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

    public static void writePlayerResourcePack(Player player, PackByteWriter writer) throws IOException {
        var pack = new PackMCMeta(new PackMetadataSection(Component.literal("DynamicRP Generated Player Resources"), 46, Optional.of(new InclusiveRange<>(46, 64))), new OverlayMetadataSection(Collections.emptyList())).encode();
        writer.writeString("pack.mcmeta", pack.toString());

        try {
            PlayerProfile profile = player.getPlayerProfile();
            PlayerTextures textures = profile.getTextures();
            int variant = textures.getSkinModel().ordinal();

            URL skinURL = textures.getSkin();
            if (skinURL != null) {
                try (InputStream in = skinURL.openStream()) {
                    writer.writeStream("assets/dynamicrp/textures/item/skin.png", in);
                }
            } else {
                writer.writeBytes("assets/dynamicrp/textures/item/skin.png", steveSkin);
            }
            writer.writeString("assets/dynamicrp/models/item/leftarm.json", arms[variant]);
            writer.writeString("assets/dynamicrp/models/item/rightarm.json", arms[variant + 2]);
            writer.writeBytes("pack.png", playerResourcesLogo);
        } catch (IOException ignored) {
        }
    }

    /**
     * @return Success of making pack
     */
    public boolean makePack() {
        // Loading Assets
        resPackFile.delete();
        try (FileSystem zipFs = FileSystems.newFileSystem(resPackFile.toPath(), Map.of("create", "true"))) {
            PackWriter writer = new PackWriter(zipFs);
            OverlayMetadataSection overlays = new OverlayMetadataSection(new ArrayList<>());

            // Iterate all plugins
            Plugin[] plugins = PaperPluginManagerImpl.getInstance().getPlugins();
            if (plugins != null) {
                int i = plugins.length;
                while (--i >= 0) {
                    Plugin plugin = plugins[i];
                    URL classUrl = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
                    if (classUrl == null)
                        continue;

                    try {
                        Path path = Paths.get(classUrl.toURI());

                        try (var fileSystem = FileSystems.newFileSystem(path)) {
                            addAssetPath(writer, overlays, fileSystem.getPath(""), false);
                        }
                    } catch (URISyntaxException e) {
                        LOGGER.warn("URISyntaxException while making Pack", e);
                    } catch (IOException e) {
                        LOGGER.warn("IOException while making Pack", e);
                    }
                }
            }

            // Call ZipPack Event
            for (ZipPackCallback zipPackCallback : ZIP_PACK_CALLBACKS) {
                try {
                    zipPackCallback.callback(writer);
                } catch (Exception e) {
                    LOGGER.warn("IOException while making Pack", e);
                }
            }

            // Apply Overrides
            File[] listFiles;
            if (overrideFolder.exists() && (listFiles = overrideFolder.listFiles()) != null) {
                for (File file : listFiles) {
                    if (file.isDirectory()) {
                        addAssetPath(writer, overlays, file.toPath(), true);
                    } else if (file.getName().endsWith(".zip")) {
                        try (var fileSystem = FileSystems.newFileSystem(file.toPath())) {
                            addAssetPath(writer, overlays, fileSystem.getPath(""), true);
                        } catch (IOException e) {
                            LOGGER.warn("IOException while processing ZIP {}", file, e);
                        }
                    } else {
                        LOGGER.debug("Skipping unsupported file: {}", file);
                    }
                }
            }

            // Add pack.mcmeta to the pack
            // Add overlays
            JsonElement packMCMeta = new PackMCMeta(
                    new PackMetadataSection(Component.literal("DynamicRP Generated Resource Pack"), 46, Optional.of(new InclusiveRange<>(46, 64))),
                    overlays).encode();
            writer.writeString("pack.mcmeta", packMCMeta.toString());
        } catch (Exception e) {
            LOGGER.warn("Exception while making Pack", e);
            return false;
        }

        try {
            resPackHash = calculateSHA1(resPackFile);
        } catch (Exception e) {
            LOGGER.warn("Exception hashing pack", e);
        }
        return true;
    }

    public record PackMCMeta(PackMetadataSection pack, OverlayMetadataSection overlays) {
        public static final Codec<PackMCMeta> CODEC = RecordCodecBuilder.create(instance -> instance
                .group(PackMetadataSection.CODEC.fieldOf("pack").forGetter(PackMCMeta::pack),
                        OverlayMetadataSection.TYPE.codec().fieldOf("overlays").forGetter(PackMCMeta::overlays))
                .apply(instance, PackMCMeta::new));

        public JsonElement encode() {
            return PackMCMeta.CODEC.encodeStart(JsonOps.INSTANCE, new PackMCMeta(pack, overlays)).getOrThrow();
        }
    }

    public static void addAssetPath(PackWriter writer, OverlayMetadataSection overlayMetadataSection, Path rootPath, boolean copyAll) throws IOException {
        HashSet<String> allowDirectories = new HashSet<>(Collections.singleton("assets"));

        try (var inputStream = Files.newInputStream(rootPath.resolve("pack.mcmeta"))) {
            var resourceMetaData = ResourceMetadata.fromJsonStream(inputStream);

            resourceMetaData.getSection(OverlayMetadataSection.TYPE).ifPresent(section -> {
                for (var entry : section.overlays()) {
                    // Add overlay directory for filtering later
                    allowDirectories.add(entry.overlay());
                    // Add overlay entry to the result section
                    overlayMetadataSection.overlays().add(entry);
                }
            });
        } catch (NoSuchFileException ignored) {
        } catch (IOException e) {
            throw new IOException("Failed to read or parse pack.mcmeta", e);
        }

        Files.walk(rootPath)
                .filter(Files::isRegularFile)
                .filter(path -> {
                    if (path.endsWith("pack.mcmeta")) return false;
                    if (copyAll) return true;
                    Path relative = rootPath.relativize(path);
                    Path first = relative.getNameCount() > 0 ? relative.getName(0) : null;
                    return first != null && allowDirectories.contains(first.toString());
                })
                .forEach(path -> {
                    try (var is = Files.newInputStream(path)) {
                        writer.writeStream(rootPath.relativize(path).toString(), is);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    static {

        steveSkin = Base64.getDecoder().decode("UklGRlIDAABXRUJQVlA4TEUDAAAvP8APEDehOJKk5O5w50vuJERELmkwCgCQkb1HCl4SaKB/BwW2m2wCAEiDu8NtvYlHCtvmPwAA//+XIBFCOMWkIBBCQmNbHeNsrlbJe1oITmtOay8tY2wdIq8dJXri/xEFGNeSD5SkLLFOKTY+InLCb6XHc0ALysozqGBqeeA8EVRhZoFsW3vquJlQ6Jw7SXClHEAkAef9nxH9Irbnqr2K6L8Dt20jSe4E09vozOzmDTsj74P3Ifib0W5z+bDvNeWx6y7sg9cYH0HxHsGHEHy4Ix6bIlbuQAg6eL+/RR0dpzuwh2qYbhDvMi3oFH+bDl23DQO8bE7n7fd7Ld6pE+8DOPCbPG/vXAhOS9DoHL7De/Wz3+a84YWll9cXpt/gYx8CDHchrA9wTM574pcXZiYwi6NgmW3eVx/J8cM30/cDO4pgJTjYZQOCWqQu5ufnHroc9DqnXX49zrtI0+1vnA5PT8du4DZRdN4pfhNuMdKk5XY8HA+3KU7Ui778v7q2+v5r1/l8Olkfn0wxEn9WI0spRYiZl3E6nc8DKHbRQpgpLg8b8NFZFdZXRCs/htZqm+e5ttpam+eau4owEUtBD5JzlqLDREp56ypdA+qIuSunlEvJzDovpQzrFXt1vr7eisgA47dZ0vt7kiJFx3UPULLW4E4yUEQW0mvvXdaretCz7sib0VeXbjBGR2mpXUoSIBkyPC+iqBFERD8ErdUKu6R+JRHAVOAYmJl6ZIpE4x3seQ+aEzwsyVKS8QCdxHgDEI1oyHha0rMSyeoBEAMDcRxqVZpdouWUc8oCjeAAds2JmBD6ieu1Gl1BOSsZpqi0RXJK3LFlzPnSzf7RuRe1cMEdW6tJclIDKfdYZ4QQGnI+X9Tv5QwOzub7aXNLqeGZ1tTmmXQ89sDN7p9bH6giIlK6gd1/Ba3/H5dSCjOTsm0eMyYS8z3Dhwa+a5BIaypL8pBm84CGeYaIFNR67HomH5khT7BFZCMqJBIjsukT2SxYWpshzxgKf3cGKpbleQjmGZEigeL6YP6+IRFSD0SRMOVAbVzpwkRjkHXwlkMaAOswA9uEWs2bZw4wEiG0luV5iL20Yx3L8xDMMyy0guV5CGEH2bzjR3YA");
        playerResourcesLogo = Base64.getDecoder().decode("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");

        ZIP_PACK_CALLBACKS = Collections.synchronizedList(new ArrayList<>());

        arms = new String[4];
        /// Classic Left Arm
        arms[0] = """
                {"texture_size":[64,64],"textures":{"skin":"dynamicrp:item/skin"},"elements":[{"from":[4.999,7.999,5.999],"to":[9.001,20.001,10.001],"faces":{"north":{"uv":[9,13,10,16],"texture":"#skin"},"east":{"uv":[8,13,9,16],"texture":"#skin"},"south":{"uv":[11,13,12,16],"texture":"#skin"},"west":{"uv":[10,13,11,16],"texture":"#skin"},"up":{"uv":[10,13,9,12],"texture":"#skin"},"down":{"uv":[11,12,10,13],"texture":"#skin"}}},{"from":[4.749,7.749,5.749],"to":[9.251,20.251,10.251],"rotation":{"angle":0,"axis":"y","origin":[8,8,8]},"faces":{"north":{"uv":[13,13,14,16],"texture":"#skin"},"east":{"uv":[12,13,13,16],"texture":"#skin"},"south":{"uv":[15,13,16,16],"texture":"#skin"},"west":{"uv":[14,13,15,16],"texture":"#skin"},"up":{"uv":[14,13,13,12],"texture":"#skin"},"down":{"uv":[15,12,14,13],"texture":"#skin"}}},{"from":[9.243,20.251,10.251],"to":[4.741,7.749,5.749],"rotation":{"angle":0,"axis":"z","origin":[8,8,8]},"faces":{"north":{"uv":[16,13,15,16],"rotation":180,"texture":"#skin"},"east":{"uv":[15,13,14,16],"rotation":180,"texture":"#skin"},"south":{"uv":[14,13,13,16],"rotation":180,"texture":"#skin"},"west":{"uv":[13,13,12,16],"rotation":180,"texture":"#skin"},"up":{"uv":[14,12,15,13],"texture":"#skin"},"down":{"uv":[13,13,14,12],"texture":"#skin"}}}]}""";
        /// Slim Left Arm
        arms[1] = """
                {"texture_size":[64,64],"textures":{"skin":"dynamicrp:item/skin"},"elements":[{"from":[5.999,7.999,5.999],"to":[9.001,20.001,10.001],"faces":{"north":{"uv":[9,13,9.75,16],"texture":"#skin"},"east":{"uv":[8,13,9,16],"texture":"#skin"},"south":{"uv":[10.75,13,11.5,16],"texture":"#skin"},"west":{"uv":[9.75,13,10.75,16],"texture":"#skin"},"up":{"uv":[9.75,13,9,12],"texture":"#skin"},"down":{"uv":[10.5,12,9.75,13],"texture":"#skin"}}},{"from":[5.749,7.749,5.749],"to":[9.251,20.251,10.251],"rotation":{"angle":0,"axis":"y","origin":[8,8,8]},"faces":{"north":{"uv":[13,13,13.75,16],"texture":"#skin"},"east":{"uv":[12,13,13,16],"texture":"#skin"},"south":{"uv":[14.75,13,15.5,16],"texture":"#skin"},"west":{"uv":[13.75,13,14.75,16],"texture":"#skin"},"up":{"uv":[13.75,13,13,12],"texture":"#skin"},"down":{"uv":[14.5,12,13.75,13],"texture":"#skin"}}},{"from":[9.239,20.251,10.251],"to":[5.737,7.749,5.749],"rotation":{"angle":0,"axis":"z","origin":[16,8,8]},"faces":{"north":{"uv":[15.5,13,14.75,16],"rotation":180,"texture":"#skin"},"east":{"uv":[14.75,13,13.75,16],"rotation":180,"texture":"#skin"},"south":{"uv":[13.75,13,13,16],"rotation":180,"texture":"#skin"},"west":{"uv":[13,13,12,16],"rotation":180,"texture":"#skin"},"up":{"uv":[13.75,12,14.5,13],"texture":"#skin"},"down":{"uv":[13,13,13.75,12],"texture":"#skin"}}}]}""";
        /// Classic Right Arm
        arms[2] = """
                {"texture_size":[64,64],"textures":{"skin":"dynamicrp:item/skin"},"elements":[{"from":[6.999,7.999,5.999],"to":[11.001,20.001,10.001],"faces":{"north":{"uv":[11,5,12,8],"texture":"#skin"},"east":{"uv":[10,5,11,8],"texture":"#skin"},"south":{"uv":[13,5,14,8],"texture":"#skin"},"west":{"uv":[12,5,13,8],"texture":"#skin"},"up":{"uv":[12,5,11,4],"texture":"#skin"},"down":{"uv":[13,4,12,5],"texture":"#skin"}}},{"from":[6.749,7.749,5.749],"to":[11.251,20.251,10.251],"rotation":{"angle":0,"axis":"y","origin":[8,8,8]},"faces":{"north":{"uv":[11,9,12,12],"texture":"#skin"},"east":{"uv":[10,9,11,12],"texture":"#skin"},"south":{"uv":[13,9,14,12],"texture":"#skin"},"west":{"uv":[12,9,13,12],"texture":"#skin"},"up":{"uv":[12,9,11,8],"texture":"#skin"},"down":{"uv":[13,8,12,9],"texture":"#skin"}}},{"from":[11.243,20.251,10.251],"to":[6.741,7.749,5.749],"rotation":{"angle":0,"axis":"z","origin":[24,8,8]},"faces":{"north":{"uv":[14,9,13,12],"rotation":180,"texture":"#skin"},"east":{"uv":[13,9,12,12],"rotation":180,"texture":"#skin"},"south":{"uv":[12,9,11,12],"rotation":180,"texture":"#skin"},"west":{"uv":[11,9,10,12],"rotation":180,"texture":"#skin"},"up":{"uv":[12,8,13,9],"texture":"#skin"},"down":{"uv":[11,9,12,8],"texture":"#skin"}}}]}""";
        /// Slim Right Arm
        arms[3] = """
                {"texture_size":[64,64],"textures":{"skin":"dynamicrp:item/skin"},"elements":[{"from":[6.999,7.999,5.999],"to":[10.001,20.001,10.001],"faces":{"north":{"uv":[11,5,11.75,8],"texture":"#skin"},"east":{"uv":[10,5,11,8],"texture":"#skin"},"south":{"uv":[12.75,5,13.5,8],"texture":"#skin"},"west":{"uv":[11.75,5,12.75,8],"texture":"#skin"},"up":{"uv":[11.75,5,11,4],"texture":"#skin"},"down":{"uv":[12.5,4,11.75,5],"texture":"#skin"}}},{"from":[6.749,7.749,5.749],"to":[10.251,20.251,10.251],"rotation":{"angle":0,"axis":"y","origin":[8,8,8]},"faces":{"north":{"uv":[11,9,11.75,12],"texture":"#skin"},"east":{"uv":[10,9,11,12],"texture":"#skin"},"south":{"uv":[12.75,9,13.5,12],"texture":"#skin"},"west":{"uv":[11.75,9,12.75,12],"texture":"#skin"},"up":{"uv":[11.75,9,11,8],"texture":"#skin"},"down":{"uv":[12.5,8,11.75,9],"texture":"#skin"}}},{"from":[10.247,20.251,10.251],"to":[6.745,7.749,5.749],"rotation":{"angle":0,"axis":"z","origin":[15,8,8]},"faces":{"north":{"uv":[13.5,9,12.75,12],"rotation":180,"texture":"#skin"},"east":{"uv":[12.75,9,11.75,12],"rotation":180,"texture":"#skin"},"south":{"uv":[11.75,9,11,12],"rotation":180,"texture":"#skin"},"west":{"uv":[11,9,10,12],"rotation":180,"texture":"#skin"},"up":{"uv":[11.75,8,12.5,9],"texture":"#skin"},"down":{"uv":[11,9,11.75,8],"texture":"#skin"}}}]}""";
    }
}
