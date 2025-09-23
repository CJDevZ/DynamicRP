package de.cjdev.dynamicrp.api;

@FunctionalInterface
public interface ZipPackCallback {
    void callback(PackWriter writer) throws Exception;
}
