package com.dsh.mclogin.testfixture;

import com.dsh.mclogin.bukkit.api.*;
import com.dsh.mclogin.api.PasswordResetVerifier;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Test-only trusted verifier and observer. Never package or install on a real server. */
public final class CompatibilityFixture extends JavaPlugin implements Listener,CommandExecutor {
    private LoginService service;
    private final Map<UUID,CompletableFuture<Boolean>> approvals=new HashMap<>();
    private final Map<UUID,List<String>> events=new HashMap<>();
    @Override public void onEnable(){
        service=Objects.requireNonNull(getServer().getServicesManager().load(LoginService.class));
        getServer().getPluginManager().registerEvents(this,this);
        Objects.requireNonNull(getCommand("compatprobe")).setExecutor(this);
    }
    @EventHandler public void transition(AuthenticationEvent event){events.computeIfAbsent(event.getPlayer().getUniqueId(),id->new ArrayList<>()).add(event.getState()+":"+event.getReason());}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(sender instanceof Player){sender.sendMessage("console only");return true;}
        if(args.length==0)return true;
        if(args[0].equals("verifier")){
            service.registerResetVerifier(this,new PasswordResetVerifier(){
                @Override public java.util.concurrent.CompletionStage<Boolean> verify(UUID id,UUID request){CompletableFuture<Boolean> future=new CompletableFuture<>();approvals.put(id,future);return future;}
                @Override public void cancel(UUID request){ }
            });sender.sendMessage("verifier registered");return true;
        }
        if(args[0].equals("withdraw")){service.unregisterResetVerifier(this);sender.sendMessage("verifier removed");return true;}
        if(args.length<2)return true;
        Player p=Bukkit.getPlayerExact(args[1]);if(p==null){sender.sendMessage("player absent");return true;}
        switch(args[0]){
            case "status" -> sender.sendMessage("auth="+service.isAuthenticated(p.getUniqueId())+" registered="+service.isRegistered(p.getUniqueId())+" admin="+p.hasPermission("mclogin.admin")+" events="+events.getOrDefault(p.getUniqueId(),List.of()));
            case "break" -> {var event=new org.bukkit.event.block.BlockBreakEvent(p.getLocation().clone().add(2,-1,0).getBlock(),p);Bukkit.getPluginManager().callEvent(event);sender.sendMessage("cancelled="+event.isCancelled());}
            case "teleport" -> sender.sendMessage("teleport="+p.teleport(p.getLocation().clone().add(3,0,0)));
            case "inventory" -> {var inv=Bukkit.createInventory(null,27,"Foreign plugin inventory");p.openInventory(inv);sender.sendMessage("inventory="+(p.getOpenInventory().getTopInventory()==inv));}
            case "approve" -> {var future=approvals.remove(p.getUniqueId());sender.sendMessage("approved="+(future!=null&&future.complete(true)));}
            case "deny" -> {var future=approvals.remove(p.getUniqueId());sender.sendMessage("denied="+(future!=null&&future.complete(false)));}
        }
        return true;
    }
}
