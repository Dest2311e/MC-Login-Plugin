package com.dsh.mclogin.config;

import org.bukkit.configuration.file.FileConfiguration;

/** Validated, immutable snapshot. Invalid values never silently loosen restrictions. */
public final class LoginConfig {
    private int iterations=600000, timeout=120, autosave=60;
    private int maxFails=5, lock=300, window=600, ipMax=20, ipLock=300, ipWindow=600;
    private boolean ipGuard=true, storeIp=false, ui=true;
    public LoginConfig() { }
    public void register() { /* Defaults already initialized; core test fallback. */ }
    public static LoginConfig read(FileConfiguration f) {
        LoginConfig c = new LoginConfig();
        c.iterations = integer(f,"security.pbkdf2-iterations",64000,10000000);
        c.timeout = integer(f,"security.login-timeout-seconds",10,3600);
        c.autosave = integer(f,"storage.autosave-seconds",1,3600);
        c.maxFails = integer(f,"security.max-failed-attempts",1,100);
        c.lock = integer(f,"security.lock-seconds",1,86400);
        c.window = integer(f,"security.fail-window-seconds",1,86400);
        c.ipMax = integer(f,"security.ip-max-failed-attempts",1,10000);
        c.ipLock = integer(f,"security.ip-lock-seconds",1,86400);
        c.ipWindow = integer(f,"security.ip-fail-window-seconds",1,86400);
        c.ipGuard = bool(f,"security.ip-guard"); c.storeIp = bool(f,"storage.store-ip"); c.ui = bool(f,"ui.enabled");
        return c;
    }
    private static int integer(FileConfiguration f,String k,int min,int max) {
        Object x=f.get(k);
        if (!(x instanceof Number n) || n.doubleValue()!=n.intValue() || n.intValue()<min || n.intValue()>max)
            throw new IllegalArgumentException("Invalid configuration: "+k);
        return n.intValue();
    }
    private static boolean bool(FileConfiguration f,String k) {
        if (!(f.get(k) instanceof Boolean b)) throw new IllegalArgumentException("Invalid configuration: "+k);
        return b;
    }
    public int getPbkdf2Iterations(){return iterations;}
    public int getLoginTimeoutSeconds(){return timeout;}
    public int getAutosaveIntervalSeconds(){return autosave;}
    public int getMaxFailedAttempts(){return maxFails;}
    public int getLockSeconds(){return lock;}
    public int getFailWindowSeconds(){return window;}
    public int getIpMaxFailedAttempts(){return ipMax;}
    public int getIpLockSeconds(){return ipLock;}
    public int getIpFailWindowSeconds(){return ipWindow;}
    public boolean isEnableIpGuard(){return ipGuard;}
    public boolean isStoreLastIp(){return storeIp;}
    public boolean isUiEnabled(){return ui;}
}
