package com.dsh.mclogin;

import com.dsh.mclogin.config.LoginConfig;
import com.dsh.mclogin.data.YamlStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Platform adapter for the unchanged storage/crypto/anti-brute-force core. */
public final class MCLoginMod {
    public static final Logger LOGGER = LoggerFactory.getLogger("MCLogin");
    private static MCLoginMod instance;
    private LoginConfig config;
    private final YamlStorage storage;
    private MCLoginMod(LoginConfig config, YamlStorage storage) { this.config = config; this.storage = storage; }
    public static void install(LoginConfig config, YamlStorage storage) { instance = new MCLoginMod(config, storage); }
    public static MCLoginMod getInstance() { return instance; }
    public LoginConfig getConfig() { return config; }
    public YamlStorage getStorage() { return storage; }
    public void updateConfig(LoginConfig config) { this.config = config; }
}
