package com.dsh.mclogin.bukkit;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import java.util.Locale;

/** Optional class, instantiated only while PlaceholderAPI is enabled. */
final class Placeholders extends PlaceholderExpansion {
    private final MCLoginPlugin plugin;
    Placeholders(MCLoginPlugin plugin){this.plugin=plugin;}
    @Override public String getIdentifier(){return "mclogin";}
    @Override public String getAuthor(){return "DSH";}
    @Override public String getVersion(){return "1.1.0";}
    @Override public boolean persist(){return true;}
    @Override public String onRequest(OfflinePlayer player,String parameter){
        if(player==null)return "false";
        return switch(parameter.toLowerCase(Locale.ROOT)){
            case "authenticated" -> Boolean.toString(plugin.isAuthenticated(player.getUniqueId()));
            case "registered" -> Boolean.toString(plugin.isRegistered(player.getUniqueId()));
            case "status" -> plugin.isAuthenticated(player.getUniqueId())?"authenticated":"required";
            case "bedrock" -> Boolean.toString(plugin.isBedrock(player.getUniqueId()));
            default -> null;
        };
    }
}
