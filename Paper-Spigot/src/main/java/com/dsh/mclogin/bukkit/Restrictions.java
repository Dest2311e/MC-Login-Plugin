package com.dsh.mclogin.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.*;

/** Only pending authentication is restricted; authenticated events are left to protection plugins. */
final class Restrictions implements Listener {
    private final MCLoginPlugin plugin;
    private final LoginMenus menus;
    Restrictions(MCLoginPlugin plugin,LoginMenus menus){this.plugin=plugin;this.menus=menus;}
    @EventHandler(priority=EventPriority.HIGHEST) public void command(PlayerCommandPreprocessEvent e){
        if(plugin.pending(e.getPlayer())&&!CommandPolicy.allows(e.getMessage(),plugin.owns(e.getMessage()))){e.setCancelled(true);e.getPlayer().sendMessage("§c请先登录。使用 /mclogin login 或登录界面。");}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void commands(PlayerCommandSendEvent e){
        if(plugin.pending(e.getPlayer()))e.getCommands().removeIf(label->!CommandPolicy.allows("/"+label,plugin.owns(label)));
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void chat(AsyncPlayerChatEvent e){if(plugin.pending(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void move(PlayerMoveEvent e){
        if(plugin.pending(e.getPlayer())&&e.getTo()!=null&&(e.getFrom().getX()!=e.getTo().getX()||e.getFrom().getY()!=e.getTo().getY()||e.getFrom().getZ()!=e.getTo().getZ()))e.setTo(e.getFrom());
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void teleport(PlayerTeleportEvent e){if(plugin.pending(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void portal(PlayerPortalEvent e){if(plugin.pending(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e){if(plugin.pending(e.getPlayer())){e.setCancelled(true);e.setUseInteractedBlock(Event.Result.DENY);e.setUseItemInHand(Event.Result.DENY);}}
    @EventHandler(priority=EventPriority.HIGHEST) public void entityInteract(PlayerInteractEntityEvent e){if(plugin.pending(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void atEntity(PlayerInteractAtEntityEvent e){if(plugin.pending(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void blockBreak(BlockBreakEvent e){if(plugin.pending(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void blockPlace(BlockPlaceEvent e){if(plugin.pending(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void drop(PlayerDropItemEvent e){if(plugin.pending(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p&&plugin.pending(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void swap(PlayerSwapHandItemsEvent e){if(plugin.pending(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void held(PlayerItemHeldEvent e){if(plugin.pending(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void consume(PlayerItemConsumeEvent e){if(plugin.pending(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void damage(EntityDamageEvent e){if(e.getEntity() instanceof Player p&&plugin.pending(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void attack(EntityDamageByEntityEvent e){
        if(e.getDamager() instanceof Player p&&plugin.pending(p))e.setCancelled(true);
        else if(e.getDamager() instanceof org.bukkit.entity.Projectile projectile&&projectile.getShooter() instanceof Player p&&plugin.pending(p))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void vehicle(VehicleEnterEvent e){if(e.getEntered() instanceof Player p&&plugin.pending(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void open(InventoryOpenEvent e){if(e.getPlayer() instanceof Player p&&plugin.pending(p)&&!menus.owns(p,e.getInventory())&&!menus.openingAnvil(p,e.getInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){if(e.getWhoClicked() instanceof Player p&&plugin.pending(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p&&plugin.pending(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void respawn(PlayerRespawnEvent e){plugin.later(()->{if(plugin.pending(e.getPlayer()))menus.open(e.getPlayer());});}
}
