package de.cjdev.dynamicrp;

import com.google.inject.Inject;
import com.moandjiezana.toml.Toml;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerResourcePackSendEvent;
import com.velocitypowered.api.event.proxy.ListenerCloseEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.slf4j.Logger;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Plugin(id = "dynamicrp-velocity", name = "DynamicRP Velocity", version = BuildConstants.VERSION, description = "Velocity Plugin of Dynamic RP", url = "https://modrinth.com/plugin/dynamic-rp", authors = {"CJDev"})
public class DynamicRPVelocity {
    static final HashMap<String, String> HOST_NAMES = new HashMap<>();
    static Logger LOGGER;
    private static Path dataDirectory;
    private Toml config;
    private static HttpServer httpServer;
    static long webServerPort;
    static String hostName;
    static boolean useHostName;
    static String localIP;
    static String publicIP;

    static final Set<UUID> localUsers = new HashSet<>();

    @Inject
    public DynamicRPVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        LOGGER = logger;
        DynamicRPVelocity.dataDirectory = dataDirectory;
        CommandManager commandManager = server.getCommandManager();
        CommandMeta commandMeta = commandManager.metaBuilder("drp-velocity").plugin(this).build();
        LiteralCommandNode<CommandSource> drpReloadCommand = BrigadierCommand.literalArgumentBuilder("drp-velocity").requires((commandSource) -> commandSource.hasPermission("drp.velocity.reload")).then(BrigadierCommand.literalArgumentBuilder("reload").executes((commandContext) -> {
            this.StopWebServer();
            this.loadConfig();
            this.StartWebServer();
            return 1;
        })).build();
        server.getCommandManager().register(commandMeta, new BrigadierCommand(drpReloadCommand));
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        this.loadConfig();
        this.StartWebServer();
    }

    @Subscribe
    public void onServerResourcePackSent(ServerResourcePackSendEvent event) {
        var connection = event.getServerConnection();
        try {
            URL url = new URL(event.getProvidedResourcePack().getUrl());
            String localHost = url.getHost();
            ServerInfo serverInfo = connection.getServerInfo();
            if (localHost.equals(DynamicRPVelocity.publicIP) || localHost.equals(DynamicRPVelocity.localIP)) {
                localHost = serverInfo.getAddress().getHostString();
            }

            Map<String, String> queryParams = mapGETQuery(url.getQuery());
            DynamicRPVelocity.HOST_NAMES.put(serverInfo.getName(), localHost + ":" + url.getPort());
            UUID userUUID = connection.getPlayer().getUniqueId();
            String host = DynamicRPVelocity.useHostName ? DynamicRPVelocity.hostName : (localUsers.contains(userUUID) ? DynamicRPVelocity.localIP : DynamicRPVelocity.publicIP);
            var newUrl = "http://" + host + ":" + DynamicRPVelocity.webServerPort + "?server=" + serverInfo.getName() + (queryParams.containsKey("uuid") ? "&uuid=" + userUUID.toString() : "");
            event.setProvidedResourcePack(event.getReceivedResourcePack().asBuilder(newUrl).build());
        } catch (MalformedURLException ignored) {
        }
    }

    private void loadConfig() {
        if (!Files.exists(dataDirectory)) {
            try {
                Files.createDirectories(dataDirectory);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Path configFile = dataDirectory.resolve("config.toml");
        if (!Files.exists(configFile)) {
            try {
                Files.writeString(configFile, "[webserver]\nport = -1\nhostName = \"0.0.0.0\"");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        this.config = (new Toml()).read(configFile.toFile());
        Toml webServer = this.config.getTable("webserver");
        if (webServer == null) {
            webServerPort = -1L;
            hostName = "0.0.0.0";
            useHostName = false;
        } else {
            webServerPort = webServer.getLong("port");
            hostName = webServer.getString("hostName");
            useHostName = hostName != null && !hostName.equals("0.0.0.0");
        }
    }

    @Subscribe
    public void onPlayerJoin(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        InetAddress address = player.getRemoteAddress().getAddress();
        if (address.isLoopbackAddress()) {
            localUsers.add(player.getUniqueId());
        }
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        localUsers.remove(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        this.StopWebServer();
    }

    private void StopWebServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            LOGGER.info("\u001b[38;2;255;85;85mStopped Web Server\u001b[0m");
        }

    }

    private static String getPublicIP() throws IOException {
        URL url = new URL("https://checkip.amazonaws.com/");
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setRequestMethod("GET");
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String publicIP = reader.readLine();
        reader.close();
        return publicIP;
    }

    private static int getFreePort() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("0.0.0.0"))) {
            return socket.getLocalPort();
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    private void StartWebServer() {
        try {
            publicIP = getPublicIP();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            while(interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isUp() && !networkInterface.isLoopback() && !networkInterface.isVirtual()) {
                    Enumeration<InetAddress> enumerate = networkInterface.getInetAddresses();

                    while(enumerate.hasMoreElements()) {
                        InetAddress address = enumerate.nextElement();
                        if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                            localIP = address.getHostAddress();
                        }
                    }
                }
            }

            webServerPort = webServerPort >= 0L && webServerPort <= 65535L ? webServerPort : (long)getFreePort();
            httpServer = HttpServer.create(new InetSocketAddress(hostName, (int)webServerPort), 0);
            httpServer.createContext("/", new RequestHandler());
            httpServer.start();
            LOGGER.info("\u001b[38;2;85;255;85mStarted Web Server on {}\u001b[0m", httpServer.getAddress().getPort());
        } catch (BindException var5) {
            LOGGER.warn("\u001b[38;2;255;85;85mAddress already in use. Restart the Server after Fix\u001b[0m");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public static String decodeURLEncoded(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception var3) {
            return value;
        }
    }

    public static Map<String, String> mapGETQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] entry = param.split("=", 2);
                if (entry.length > 1) {
                    result.put(entry[0], DynamicRPVelocity.decodeURLEncoded(entry[1]));
                } else {
                    result.put(entry[0], "");
                }
            }

        }
        return result;
    }
}
