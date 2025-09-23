package de.cjdev.dynamicrp;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;

public class DynamicRPPlugin extends org.bukkit.plugin.java.JavaPlugin implements org.bukkit.event.Listener {
    @Override
    public void onEnable() {
        if (!DynamicRP.customHost) {
            // Starting Web Server
            DynamicRP.StartWebServer();
        }

        // Zip Packs once
        if (!DynamicRP.pluginInit) {
            DynamicRP.pluginInit = true;
            CompletableFuture.runAsync(() -> {
                if (!DynamicRP.INSTANCE.makePack()) return;
                DynamicRP.INSTANCE.refreshResourcePack();
            });
        }

        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        DynamicRP.StopWebServer();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        InetAddress address = event.getPlayer().getAddress().getAddress();
        boolean isLocal = DynamicRP.isLocalNetwork(address);
        if (isLocal) {
            DynamicRP.localPlayers.add(event.getPlayer().getUniqueId());
        } else {
            DynamicRP.localPlayers.remove(event.getPlayer().getUniqueId());
        }

        // Send Player Resource Pack
        DynamicRP.sendPlayerPack(event.getPlayer(), isLocal);

        // Send Generated Resource Pack
        DynamicRP.INSTANCE.refreshResourcePack(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        DynamicRP.localPlayers.remove(event.getPlayer().getUniqueId());
    }
}
