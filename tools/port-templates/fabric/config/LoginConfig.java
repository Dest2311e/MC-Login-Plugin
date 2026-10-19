package com.dsh.mclogin.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric configuration, using the same option names and bounds as Forge. */
public final class LoginConfig {
    private final Properties values = new Properties();

    public void register() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("mclogin-server.properties");
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); }
            }
            values.putIfAbsent("maxFailedAttempts", String.valueOf(5));
            values.putIfAbsent("lockSeconds", String.valueOf(300));
            values.putIfAbsent("failWindowSeconds", String.valueOf(600));
            values.putIfAbsent("ipMaxFailedAttempts", String.valueOf(20));
            values.putIfAbsent("ipLockSeconds", String.valueOf(300));
            values.putIfAbsent("ipFailWindowSeconds", String.valueOf(600));
            values.putIfAbsent("loginTimeoutSeconds", String.valueOf(120));
            values.putIfAbsent("allowMove", String.valueOf(false));
            values.putIfAbsent("allowInteract", String.valueOf(false));
            values.putIfAbsent("allowChat", String.valueOf(false));
            values.putIfAbsent("minPasswordLength", String.valueOf(6));
            values.putIfAbsent("maxPasswordLength", String.valueOf(64));
            values.putIfAbsent("pbkdf2Iterations", String.valueOf(600000));
            values.putIfAbsent("autosaveIntervalSeconds", String.valueOf(60));
            values.putIfAbsent("storeLastIp", String.valueOf(true));
            values.putIfAbsent("enableSessionAutoLogin", String.valueOf(false));
            values.putIfAbsent("sessionTimeoutMinutes", String.valueOf(5));
            values.putIfAbsent("language", String.valueOf("zh_cn"));
            values.putIfAbsent("enableSounds", String.valueOf(true));
            values.putIfAbsent("enableLoginUi", String.valueOf(true));
            values.putIfAbsent("soundVolume", String.valueOf(1.0));
            values.putIfAbsent("soundPitch", String.valueOf(1.0));
            values.putIfAbsent("enableIpGuard", String.valueOf(true));
            validate();
            if (!Files.exists(file)) {
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    values.store(writer, "MCLogin: restart server after editing; same option names as Forge");
                }
            }
        } catch (IOException ex) { throw new IllegalStateException("Cannot load MCLogin config " + file, ex); }
    }

    private void validate() {
        getMaxFailedAttempts();
        getLockSeconds();
        getFailWindowSeconds();
        getIpMaxFailedAttempts();
        getIpLockSeconds();
        getIpFailWindowSeconds();
        getLoginTimeoutSeconds();
        isAllowMove();
        isAllowInteract();
        isAllowChat();
        getMinPasswordLength();
        getMaxPasswordLength();
        getPbkdf2Iterations();
        getAutosaveIntervalSeconds();
        isStoreLastIp();
        isEnableSessionAutoLogin();
        getSessionTimeoutMinutes();
        getLanguage();
        isEnableSounds();
        isEnableLoginUi();
        getSoundVolume();
        getSoundPitch();
        isEnableIpGuard();
        if (getMinPasswordLength() > getMaxPasswordLength()) throw new IllegalArgumentException("minPasswordLength exceeds maxPasswordLength");
    }
    private int integer(String key, int fallback, int min, int max) {
        int n = Integer.parseInt(values.getProperty(key, Integer.toString(fallback)));
        if (n < min || n > max) throw new IllegalArgumentException("MCLogin config out of range: " + key);
        return n;
    }
    private double decimal(String key, double fallback, double min, double max) {
        double n = Double.parseDouble(values.getProperty(key, Double.toString(fallback)));
        if (!Double.isFinite(n) || n < min || n > max) throw new IllegalArgumentException("MCLogin config out of range: " + key);
        return n;
    }
    private boolean flag(String key, boolean fallback) {
        String value = values.getProperty(key, Boolean.toString(fallback));
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException("MCLogin config must be true/false: " + key);
        return Boolean.parseBoolean(value);
    }
    public int getMaxFailedAttempts() { return integer("maxFailedAttempts", 5, 1, 100); }
    public int getLockSeconds() { return integer("lockSeconds", 300, 5, 86400); }
    public int getFailWindowSeconds() { return integer("failWindowSeconds", 600, 10, 86400); }
    public int getIpMaxFailedAttempts() { return integer("ipMaxFailedAttempts", 20, 1, 1000); }
    public int getIpLockSeconds() { return integer("ipLockSeconds", 300, 5, 86400); }
    public int getIpFailWindowSeconds() { return integer("ipFailWindowSeconds", 600, 10, 86400); }
    public int getLoginTimeoutSeconds() { return integer("loginTimeoutSeconds", 120, 0, 3600); }
    public boolean isAllowMove() { return flag("allowMove", false); }
    public boolean isAllowInteract() { return flag("allowInteract", false); }
    public boolean isAllowChat() { return flag("allowChat", false); }
    public int getMinPasswordLength() { return integer("minPasswordLength", 6, 1, 128); }
    public int getMaxPasswordLength() { return integer("maxPasswordLength", 64, 8, 256); }
    public int getPbkdf2Iterations() { return integer("pbkdf2Iterations", 600000, 64000, 1000000); }
    public int getAutosaveIntervalSeconds() { return integer("autosaveIntervalSeconds", 60, 10, 3600); }
    public boolean isStoreLastIp() { return flag("storeLastIp", true); }
    public boolean isEnableSessionAutoLogin() { return flag("enableSessionAutoLogin", false); }
    public int getSessionTimeoutMinutes() { return integer("sessionTimeoutMinutes", 5, 0, 15); }
    public String getLanguage() { return values.getProperty("language", "zh_cn"); }
    public boolean isEnableSounds() { return flag("enableSounds", true); }
    public boolean isEnableLoginUi() { return flag("enableLoginUi", true); }
    public double getSoundVolume() { return decimal("soundVolume", 1.0, 0.0, 1.0); }
    public double getSoundPitch() { return decimal("soundPitch", 1.0, 0.5, 2.0); }
    public boolean isEnableIpGuard() { return flag("enableIpGuard", true); }
}
