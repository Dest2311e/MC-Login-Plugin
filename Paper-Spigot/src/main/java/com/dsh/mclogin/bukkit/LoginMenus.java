package com.dsh.mclogin.bukkit;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.*;

/** Uses Bukkit inventories only: no NMS, ProtocolLib, client mod or chat password capture. */
final class LoginMenus implements Listener {
    private final MCLoginPlugin plugin;
    private final Map<UUID,Entry> entries=new HashMap<>();
    private final Set<UUID> switching=new HashSet<>();
    private final java.lang.reflect.Method openAnvil;
    private static final class Entry {
        final Inventory inventory;final String action;final String first;
        Entry(Inventory inventory,String action,String first){this.inventory=inventory;this.action=action;this.first=first;}
    }
    LoginMenus(MCLoginPlugin plugin){
        this.plugin=plugin;
        java.lang.reflect.Method method=null;
        try{method=org.bukkit.entity.HumanEntity.class.getMethod("openAnvil",Location.class,boolean.class);}
        catch(NoSuchMethodException ignored){plugin.getLogger().warning("Paper openAnvil API absent: use /mclogin commands; authentication GUI disabled on this server.");}
        openAnvil=method;
    }
    boolean owns(Player p,Inventory inv){Entry e=entries.get(p.getUniqueId());return e!=null&&e.inventory==inv;}
    boolean openingAnvil(Player p,Inventory inventory){return switching.contains(p.getUniqueId())&&inventory.getType()==InventoryType.ANVIL;}
    boolean ownsView(Player p){return owns(p,p.getOpenInventory().getTopInventory());}
    void clear(Player p){Entry old=entries.remove(p.getUniqueId());if(old!=null&&old.inventory instanceof AnvilInventory)old.inventory.clear();}
    void shutdown(){for(Player p:Bukkit.getOnlinePlayers())if(ownsView(p)){clear(p);p.closeInventory();}entries.clear();}
    private static ItemStack item(Material material,String label){ItemStack stack=new ItemStack(material);ItemMeta meta=stack.getItemMeta();meta.setDisplayName(label);stack.setItemMeta(meta);return stack;}
    void ensureOpen(Player p){if(plugin.settings().isUiEnabled()&&!ownsView(p))open(p);}
    void open(Player p){
        if(openAnvil==null||!plugin.settings().isUiEnabled()||!plugin.pending(p))return;
        clear(p);
        switching.add(p.getUniqueId());
        try{
            Inventory inv=Bukkit.createInventory(null,27,"MCLogin · 身份验证");
            inv.setItem(13,item(plugin.isRegistered(p.getUniqueId())?Material.LIME_DYE:Material.EMERALD,plugin.isRegistered(p.getUniqueId())?"§a登录":"§a注册"));
            if(plugin.isRegistered(p.getUniqueId())&&plugin.canReset())inv.setItem(15,item(Material.TRIPWIRE_HOOK,"§e找回密码"));
            entries.put(p.getUniqueId(),new Entry(inv,"menu",null));p.openInventory(inv);
        }finally{switching.remove(p.getUniqueId());}
    }
    void start(Player p,String action){anvil(p,action,null);}
    private void anvil(Player p,String action,String first){
        if(openAnvil==null||!plugin.pending(p))return;
        clear(p);
        switching.add(p.getUniqueId());
        try{
            InventoryView view;
            try{view=(InventoryView)openAnvil.invoke(p,null,true);}
            catch(ReflectiveOperationException e){p.sendMessage("§c无法打开铁砧，请使用 /mclogin 命令。");return;}
            if(view==null){p.sendMessage("§e无法打开铁砧，请使用 /mclogin "+action+" 命令。");return;}
            AnvilInventory inv=(AnvilInventory)view.getTopInventory();
            entries.put(p.getUniqueId(),new Entry(inv,action,first));
            inv.setItem(0,item(Material.PAPER,""));inv.setRepairCost(0);
            p.sendMessage(first==null?"§e在铁砧输入框输入密码，点击右侧提交。":"§e再次输入密码确认，点击右侧提交。");
        }finally{switching.remove(p.getUniqueId());}
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void prepare(PrepareAnvilEvent event){
        if(!(event.getView().getPlayer() instanceof Player p)||!owns(p,event.getInventory()))return;
        event.getInventory().setRepairCost(0);
        event.getInventory().setMaximumRepairCost(Integer.MAX_VALUE);
        event.setResult(item(Material.LIME_DYE,"§a提交（密码不会显示在物品名中）"));
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void click(InventoryClickEvent event){
        if(!(event.getWhoClicked() instanceof Player p))return;
        Entry entry=entries.get(p.getUniqueId());
        if(entry==null||event.getView().getTopInventory()!=entry.inventory)return;
        event.setCancelled(true);
        if(event.getClick()!=ClickType.LEFT&&event.getClick()!=ClickType.RIGHT)return;
        if(entry.action.equals("menu")){
            if(event.getRawSlot()==13)plugin.later(()->{if(owns(p,entry.inventory)&&plugin.pending(p))start(p,plugin.isRegistered(p.getUniqueId())?"login":"register");});
            else if(event.getRawSlot()==15&&plugin.canReset())plugin.later(()->{if(owns(p,entry.inventory))plugin.beginReset(p);});
            return;
        }
        if(event.getRawSlot()!=2||!(entry.inventory instanceof AnvilInventory inv))return;
        String password=inv.getRenameText();if(password==null||password.length()>128)return;
        plugin.later(()->{
            if(!p.isOnline()||!plugin.pending(p)||!owns(p,entry.inventory))return;
            if((entry.action.equals("register")||entry.action.equals("reset"))&&entry.first==null){
                if(password.length()<8){p.sendMessage("§c密码至少 8 字符。");return;}
                anvil(p,entry.action,password);
            }else plugin.submit(p,entry.action,entry.first==null?password:entry.first,entry.first==null?null:password);
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p&&owns(p,e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)
    public void close(InventoryCloseEvent e){
        if(!(e.getPlayer() instanceof Player p)||switching.contains(p.getUniqueId())||!owns(p,e.getInventory()))return;
        if(e.getInventory() instanceof AnvilInventory)e.getInventory().clear();
        entries.remove(p.getUniqueId());plugin.later(()->{if(p.isOnline()&&plugin.pending(p))open(p);});
    }
}
