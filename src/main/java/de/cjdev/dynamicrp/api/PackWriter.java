package de.cjdev.dynamicrp.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public record PackWriter(FileSystem zipFs) {

    public Path ensureWritablePath(String path) throws IOException {
        Path pathInZip = zipFs.getPath(path);
        if (pathInZip.getParent() != null) {
            Files.createDirectories(pathInZip.getParent());
        }
        return pathInZip;
    }

    public void writeBytes(String path, byte[] data) throws IOException {
        Files.write(this.ensureWritablePath(path), data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    public void writeString(String path, String string) throws IOException {
        writeBytes(path, string.getBytes(StandardCharsets.UTF_8));
    }

    public void writeStream(String path, InputStream inputStream) throws IOException {
        Files.copy(inputStream, this.ensureWritablePath(path), StandardCopyOption.REPLACE_EXISTING);
    }
}
