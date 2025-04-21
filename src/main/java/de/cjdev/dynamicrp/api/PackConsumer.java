package de.cjdev.dynamicrp.api;

import de.cjdev.dynamicrp.DynamicRP;

import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

public class PackConsumer implements Consumer<DynamicRP.ZipEntryData> {

    private final Consumer<DynamicRP.ZipEntryData> CONSUMER;

    public PackConsumer(Consumer<DynamicRP.ZipEntryData> CONSUMER) {
        this.CONSUMER = CONSUMER;
    }

    @Override
    public void accept(DynamicRP.ZipEntryData zipEntryData) {
        CONSUMER.accept(zipEntryData);
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
}
