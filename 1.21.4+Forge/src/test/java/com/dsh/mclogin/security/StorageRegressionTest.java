package com.dsh.mclogin.security;

import com.dsh.mclogin.data.PlayerAccount;
import com.dsh.mclogin.data.YamlStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class StorageRegressionTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void run() throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("build", "regression-evidence")), "storage-");
        YamlStorage storage = new YamlStorage();
        storage.init(root.resolve("reload"));
        UUID id = UUID.randomUUID();
        PlayerAccount account = new PlayerAccount();
        account.setUuid(id.toString());
        new PasswordHasher().applyNewPassword(account, "password-test", 64000);
        storage.put(account);
        try {
            check(storage.saveNow(), "initial save");
            String valid = Files.readString(storage.getFile());
            check(storage.load() && storage.has(id), "valid account roundtrip");
            for (String bad : new String[] { "version: 1\naccouts: {}\n", "",
                    "accounts:\n  'invalid-uuid':\n    hash: 'x'\n    salt: 'x'\n",
                    "accounts:\n  '" + id + "':\n    username: 'test'\n" }) {
                Files.writeString(storage.getFile(), bad);
                check(!storage.load(), "reject invalid root or account");
                check(storage.has(id), "preserve original cached account");
                check(storage.isLastLoadSuspect(), "surface load failure");
                check(!storage.saveNow(), "block write after failed load");
                check(Files.readString(storage.getFile()).equals(bad), "preserve original damaged file");
            }
            Files.writeString(storage.getFile(), valid);
            check(storage.load() && !storage.isLastLoadSuspect(), "recover after file repaired");
            storage.remove(id);
            check(storage.saveNow() && storage.load(), "empty store roundtrip");
            check(!storage.isLastLoadSuspect() && storage.getAccountCount() == 0, "valid empty store");
            Files.writeString(storage.getFile(), "# MCLogin legacy empty file\nversion: 1\naccounts:\n");
            check(storage.load() && !storage.isLastLoadSuspect(), "upgrade from 1.0.0 empty store");
        } finally { storage.shutdown(); }

        YamlStorage cold = new YamlStorage();
        cold.init(root.resolve("cold"));
        String bad = "accounts:\n    '" + id + "':\n        hash: 'x'\n";
        Files.writeString(cold.getFile(), bad);
        check(!cold.load() && cold.isLastLoadSuspect(), "cold invalid load is not a healthy empty store");
        check(!cold.shutdown(), "shutdown refuses to overwrite corrupt input");
        check(Files.readString(cold.getFile()).equals(bad), "original input remains intact on shutdown");
        System.out.println("Storage: " + checks + " checks passed; evidence=" + root);
    }
}
