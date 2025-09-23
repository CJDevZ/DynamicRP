package de.cjdev.dynamicrp.api;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public record PackByteWriter(ZipOutputStream zos) implements Closeable {

    public PackByteWriter(OutputStream outputStream) {
        this(new ZipOutputStream(outputStream));
    }

    public void writeBytes(String path, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(path));
        zos.write(data);
        zos.closeEntry();
    }

    public void writeString(String path, String string) throws IOException {
        writeBytes(path, string.getBytes(StandardCharsets.UTF_8));
    }

    public void writeStream(String path, InputStream inputStream) throws IOException {
        zos.putNextEntry(new ZipEntry(path));
        inputStream.transferTo(zos);
        zos.closeEntry();
    }

    @Override
    public void close() throws IOException {
        zos.close();
    }
}
