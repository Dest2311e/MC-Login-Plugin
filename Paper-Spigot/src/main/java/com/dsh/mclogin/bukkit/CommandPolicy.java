package com.dsh.mclogin.bukkit;

import java.util.Locale;
import java.util.Set;

/** Restrict unauthenticated commands by exact namespace and registered owner. */
public final class CommandPolicy {
    private static final Set<String> ROOTS=Set.of("mclogin","mlogin","login","register","changepassword","unregister","logout");
    private static final Set<String> PUBLIC=Set.of("login","register","reset","menu","help");
    private CommandPolicy() { }
    public static String root(String text) {
        String s=text.startsWith("/")?text.substring(1):text;
        int end=0;while(end<s.length()&&!Character.isWhitespace(s.charAt(end)))end++;
        return s.substring(0,end).toLowerCase(Locale.ROOT);
    }
    public static boolean allows(String text, boolean owned) {
        if (!owned) return false;
        String root=root(text);
        if(root.startsWith("mclogin:")) root=root.substring(8);
        if(!ROOTS.contains(root)) return false;
        if(!root.equals("mclogin")&&!root.equals("mlogin")) return root.equals("login")||root.equals("register");
        String s=text.startsWith("/")?text.substring(1):text;
        String[] parts=s.trim().split("\\s+",3);
        return parts.length==1||PUBLIC.contains(parts[1].toLowerCase(Locale.ROOT));
    }
}
