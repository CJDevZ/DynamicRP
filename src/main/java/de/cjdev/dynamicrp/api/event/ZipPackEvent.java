package de.cjdev.dynamicrp.api.event;

import de.cjdev.dynamicrp.DynamicRP;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

public class ZipPackEvent extends Event implements Consumer<DynamicRP.ZipEntryData> {
    private static final HandlerList HANDLER_LIST = new HandlerList();
    private final Consumer<DynamicRP.ZipEntryData> entryConsumer;

    public ZipPackEvent(Consumer<DynamicRP.ZipEntryData> entryConsumer){
        this.entryConsumer = entryConsumer;
    }

    @Override
    public void accept(DynamicRP.ZipEntryData zipEntryData) {
        this.entryConsumer.accept(zipEntryData);
    }

    public static HandlerList getHandlerList(){
        return HANDLER_LIST;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }
}
