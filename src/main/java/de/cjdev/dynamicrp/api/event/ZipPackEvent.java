package de.cjdev.dynamicrp.api.event;

import de.cjdev.dynamicrp.DynamicRP;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

public class ZipPackEvent extends Event implements Consumer<DynamicRP.ZipEntryData> {
    private static final HandlerList HANDLER_LIST = new HandlerList();
    private final Consumer<DynamicRP.ZipEntryData> entryConsumer;

    public ZipPackEvent(Consumer<DynamicRP.ZipEntryData> entryConsumer){
        this.entryConsumer = entryConsumer;
    }

    public void accept(String name, byte[] data) {
        ZipEntry zipEntry = new ZipEntry(name);
        zipEntry.setSize(data.length);
        zipEntry.setMethod(ZipEntry.DEFLATED);

        CRC32 crc32 = new CRC32();
        crc32.update(data);
        zipEntry.setCrc(crc32.getValue());

        this.accept(new DynamicRP.ZipEntryData(zipEntry, data));
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
