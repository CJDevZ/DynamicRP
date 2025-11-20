package de.cjdev.dynamicrp;

public record Config(String hostName, Integer port, String externalAddress) {
    public static final Config DEFAULT = new Config("0.0.0.0", 0, "");

    public Config overlay(Config overlay) {
        return new Config(
                overlay.hostName == null ? this.hostName : overlay.hostName,
                overlay.port == null ? this.port : overlay.port,
                overlay.externalAddress == null ? this.externalAddress : overlay.externalAddress);
    }
}
