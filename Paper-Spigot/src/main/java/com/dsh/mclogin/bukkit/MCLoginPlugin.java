package com.dsh.mclogin.bukkit;

import com.dsh.mclogin.MCLoginMod;
import com.dsh.mclogin.api.PasswordResetVerifier;
import com.dsh.mclogin.bukkit.api.AuthenticationEvent;
import com.dsh.mclogin.bukkit.api.AuthenticationEvent.Reason;
import com.dsh.mclogin.bukkit.api.AuthenticationEvent.State;
import com.dsh.mclogin.bukkit.api.LoginService;
import com.dsh.mclogin.config.LoginConfig;
import com.dsh.mclogin.data.PlayerAccount;
import com.dsh.mclogin.data.YamlStorage;
import com.dsh.mclogin.security.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;

public final class MCLoginPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter, LoginService {
    private static final Set<String> CONFLICTS=Set.of("authme","loginsecurity","nlogin","openlogin","fastlogin","limboauth");
    private final YamlStorage storage=new YamlStorage();
    private final PasswordHasher hasher=new PasswordHasher();
    private final HashExecutor hashing=new HashExecutor();
    private final BruteForceGuard guard=new BruteForceGuard();
    private final Set<UUID> authenticated=ConcurrentHashMap.newKeySet();
    private final Map<UUID,Long> joined=new HashMap<>();
    private final Map<UUID,Long> generations=new HashMap<>();
    private long nextGeneration;
    private final ResetTickets<ResetContext> tickets=new ResetTickets<>(System::currentTimeMillis,120000);
    private volatile String blocked;
    private volatile boolean running;
    private LoginConfig settings=new LoginConfig();
    private PasswordResetVerifier verifier;
    private Plugin verifierOwner;
    private Placeholders placeholders;
    private LoginMenus menus;
    private CredentialLogFilter logFilter;

    private record ResetContext(Player player,PlayerAccount account,String hash,String salt,int iterations,int keyLength) {
        static ResetContext of(Player p,PlayerAccount a){return new ResetContext(p,a,a.getHash(),a.getSalt(),a.getIterations(),a.getKeyLength());}
        boolean matches(Player p,PlayerAccount a){return p==player&&a==account&&a!=null&&Objects.equals(a.getHash(),hash)&&Objects.equals(a.getSalt(),salt)&&a.getIterations()==iterations&&a.getKeyLength()==keyLength;}
    }
    @Override public void onEnable(){
        running=true;
        logFilter=new CredentialLogFilter();
        saveDefaultConfig();
        try{settings=LoginConfig.read(getConfig());}catch(RuntimeException e){blocked="MCLogin configuration is invalid";getLogger().severe(blocked+": "+e.getMessage());}
        MCLoginMod.install(settings,storage);
        storage.init(getDataFolder().toPath());storage.load();
        menus=new LoginMenus(this);
        getServer().getPluginManager().registerEvents(this,this);
        getServer().getPluginManager().registerEvents(menus,this);
        getServer().getPluginManager().registerEvents(new Restrictions(this,menus),this);
        for(String name:List.of("mclogin","login","register","changepassword","unregister","logout")){
            PluginCommand c=Objects.requireNonNull(getCommand(name));c.setExecutor(this);c.setTabCompleter(this);
        }
        getServer().getServicesManager().register(LoginService.class,this,this,ServicePriority.Normal);
        checkConflicts();hookPlaceholders();
        for(Player p:Bukkit.getOnlinePlayers())requireAuthentication(p,Reason.JOIN);
        Bukkit.getScheduler().runTaskTimer(this,()->{
            guard.purgeExpired();
            for(Player p:Bukkit.getOnlinePlayers())if(pending(p)){
                if(blocked!=null){p.kickPlayer(blocked);continue;}
                if(System.currentTimeMillis()-joined.getOrDefault(p.getUniqueId(),System.currentTimeMillis())>settings.getLoginTimeoutSeconds()*1000L){p.kickPlayer("MCLogin: 登录超时");continue;}
                menus.ensureOpen(p);
            }
            for(var entry:tickets.snapshot().entrySet())if(!tickets.current(entry.getKey(),entry.getValue()))cancelReset(entry.getKey());
        },20L,10L);
        getLogger().info("MCLogin Bukkit enabled; permissions use Bukkit/LuckPerms; Floodgate never bypasses authentication.");
    }
    @Override public void onDisable(){
        running=false;
        if(logFilter!=null)logFilter.remove();
        if(placeholders!=null)placeholders.unregister();
        for(Player p:Bukkit.getOnlinePlayers())if(authenticated.remove(p.getUniqueId()))fire(p,State.LOGGED_OUT,Reason.SHUTDOWN);
        for(UUID id:tickets.snapshot().keySet())cancelReset(id);
        if(menus!=null)menus.shutdown();
        getServer().getServicesManager().unregisterAll(this);hashing.shutdown();storage.shutdown();
    }
    LoginConfig settings(){return settings;}
    boolean pending(Player p){return blocked!=null||!isAuthenticated(p.getUniqueId());}
    boolean available(Player p){if(blocked!=null){p.sendMessage("§c"+blocked);return false;}if(storage.isLastLoadSuspect()){p.sendMessage("§c账号库读取异常，请联系管理员。已阻止写入。");return false;}return true;}
    @Override public boolean isAuthenticated(UUID id){return id!=null&&authenticated.contains(id)&&blocked==null;}
    @Override public boolean isRegistered(UUID id){return id!=null&&storage.has(id);}
    private void fire(Player p,State state,Reason reason){Bukkit.getPluginManager().callEvent(new AuthenticationEvent(p,state,reason));}
    private void requireAuthentication(Player p,Reason reason){
        boolean was=authenticated.remove(p.getUniqueId());joined.put(p.getUniqueId(),System.currentTimeMillis());generations.put(p.getUniqueId(),++nextGeneration);cancelReset(p.getUniqueId());menus.clear(p);
        if(was)fire(p,State.LOGGED_OUT,reason);
        fire(p,State.REQUIRED,reason);
        p.sendMessage("§e请使用登录界面或 /mclogin "+(isRegistered(p.getUniqueId())?"login <密码>":"register <密码> <确认密码>"));
        later(()->{if(p.isOnline()&&pending(p))menus.open(p);});
    }
    private void authenticate(Player p,Reason reason){authenticated.add(p.getUniqueId());cancelReset(p.getUniqueId());menus.clear(p);p.closeInventory();fire(p,State.AUTHENTICATED,reason);p.sendMessage("§aMCLogin: 登录成功");}
    @EventHandler public void onJoin(PlayerJoinEvent e){requireAuthentication(e.getPlayer(),Reason.JOIN);}
    @EventHandler public void onQuit(PlayerQuitEvent e){Player p=e.getPlayer();if(authenticated.remove(p.getUniqueId()))fire(p,State.LOGGED_OUT,Reason.QUIT);joined.remove(p.getUniqueId());generations.remove(p.getUniqueId());cancelReset(p.getUniqueId());menus.clear(p);}
    @EventHandler public void preLogin(AsyncPlayerPreLoginEvent e){String reason=blocked;if(reason!=null)e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,reason);}
    @EventHandler public void pluginEnable(PluginEnableEvent e){checkConflicts();if(e.getPlugin().getName().equalsIgnoreCase("PlaceholderAPI"))hookPlaceholders();}
    @EventHandler public void pluginDisable(PluginDisableEvent e){
        if(e.getPlugin()==verifierOwner)unregisterResetVerifier(verifierOwner);
        if(e.getPlugin().getName().equalsIgnoreCase("PlaceholderAPI")&&placeholders!=null){placeholders.unregister();placeholders=null;}
    }
    private void checkConflicts(){
        for(Plugin plugin:Bukkit.getPluginManager().getPlugins())if(CONFLICTS.contains(plugin.getName().toLowerCase(Locale.ROOT))){
            String reason="MCLogin blocked: incompatible authentication plugin "+plugin.getName();
            if(!reason.equals(blocked))getLogger().severe(reason+"; remove one authentication system and restart.");
            blocked=reason;
        }
    }
    private void hookPlaceholders(){
        if(placeholders==null&&Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")){
            try{Placeholders expansion=new Placeholders(this);if(expansion.register())placeholders=expansion;else getLogger().warning("Could not register mclogin PlaceholderAPI expansion");}
            catch(LinkageError e){getLogger().warning("PlaceholderAPI integration unavailable: "+e.getClass().getSimpleName());}
        }
    }
    public boolean isBedrock(UUID id){
        if(id==null||!Bukkit.getPluginManager().isPluginEnabled("floodgate"))return false;
        try{Class<?> api=Class.forName("org.geysermc.floodgate.api.FloodgateApi");Object instance=api.getMethod("getInstance").invoke(null);return Boolean.TRUE.equals(api.getMethod("isFloodgatePlayer",UUID.class).invoke(instance,id));}
        catch(ReflectiveOperationException|LinkageError e){return false;}
    }
    void later(Runnable action){if(running)try{Bukkit.getScheduler().runTask(this,()->{if(running)action.run();});}catch(org.bukkit.plugin.IllegalPluginAccessException ignored){ }}
    boolean owns(String input){PluginCommand c=Bukkit.getPluginCommand(CommandPolicy.root(input));return c!=null&&c.getPlugin()==this;}
    private static String ip(Player p){return p.getAddress()==null?"":p.getAddress().getAddress().getHostAddress();}
    private static boolean validPassword(String password){return password!=null&&password.length()>=8&&password.length()<=128;}
    private void busy(Player p){p.sendMessage("§e验证繁忙，请稍后重试。");}
    private boolean unlocked(Player p,PlayerAccount a){long left=guard.lockRemainingSeconds(a,ip(p));if(left>0){p.sendMessage("§c验证暂时锁定，请等待 "+left+" 秒。");return false;}return true;}
    /** Connection identity and credential fingerprint prevent late results crossing logout/reload/change. */
    private boolean current(Player p,ResetContext c,boolean expectedAuthenticated,long generation){return running&&blocked==null&&Bukkit.getPlayer(p.getUniqueId())==p&&p.isOnline()&&isAuthenticated(p.getUniqueId())==expectedAuthenticated&&Objects.equals(generations.get(p.getUniqueId()),generation)&&!storage.isLastLoadSuspect()&&c.matches(p,storage.get(p.getUniqueId()));}
    void submit(Player p,String action,String first,String second){
        if(!available(p))return;
        UUID id=p.getUniqueId();PlayerAccount account=storage.get(id);boolean logged=isAuthenticated(id);
        if(action.equals("register")){
            if(logged||account!=null){p.sendMessage("§c该账号已注册，请登录。");return;}
            if(!validPassword(first)||!Objects.equals(first,second)){p.sendMessage("§c密码须为 8–128 字符，两次输入须一致。");return;}
            if(!unlocked(p,null))return;
            String salt=hasher.generateSalt();int iterations=settings.getPbkdf2Iterations();long generation=generations.getOrDefault(id,0L);
            boolean accepted=hashing.submit(id,()->{
                try{String hash=hasher.hash(first,salt,iterations,256);later(()->{
                    if(Bukkit.getPlayer(id)!=p||!p.isOnline()||isAuthenticated(id)||storage.has(id)||!Objects.equals(generations.get(id),generation)||!available(p))return;
                    PlayerAccount added=new PlayerAccount(id,p.getName(),hash,salt,iterations,256,settings.isStoreLastIp()?ip(p):"");storage.put(added);guard.reset(id,ip(p));p.sendMessage("§aMCLogin: 注册成功");authenticate(p,Reason.REGISTER);
                });}catch(RuntimeException e){later(()->p.sendMessage("§c验证失败，请联系管理员。"));getLogger().severe("Password derivation failed: "+e.getClass().getSimpleName());}
            });if(!accepted)busy(p);return;
        }
        if(action.equals("reset")){completeReset(p,first,second);return;}
        if(!List.of("login","changepassword","unregister").contains(action))return;
        if(action.equals("login")&&logged){p.sendMessage("§e你已登录。");return;}
        if(!action.equals("login")&&!logged){p.sendMessage("§c请先登录。");return;}
        if(first==null||first.length()>128){p.sendMessage("§c无效密码。");return;}
        if(action.equals("changepassword")&&!validPassword(second)){p.sendMessage("§c新密码须为 8–128 字符。");return;}
        if(!unlocked(p,account))return;
        ResetContext captured=account==null?null:ResetContext.of(p,account);long generation=generations.getOrDefault(id,0L);String remote=ip(p);
        int policy=settings.getPbkdf2Iterations();
        PlayerAccount snapshot=account==null?null:new PlayerAccount(id,account.getUsername(),captured.hash(),captured.salt(),captured.iterations(),captured.keyLength(),"");
        boolean accepted=hashing.submit(id,()->{
            try{
                boolean ok;
                if(snapshot==null){hasher.burnTime(first,policy);ok=false;}else ok=hasher.verify(first,snapshot,policy);
                String nextSalt=null,nextHash=null;
                if(ok&&(action.equals("changepassword")||action.equals("login")&&(snapshot.getIterations()!=policy||snapshot.getKeyLength()!=256))){nextSalt=hasher.generateSalt();nextHash=hasher.hash(action.equals("changepassword")?second:first,nextSalt,policy,256);}
                final String salt=nextSalt,hash=nextHash;
                later(()->{
                    boolean valid=captured==null?running&&Bukkit.getPlayer(id)==p&&p.isOnline()&&!isAuthenticated(id)&&!storage.has(id)&&Objects.equals(generations.get(id),generation)&&blocked==null&&!storage.isLastLoadSuspect():current(p,captured,logged,generation);
                    if(!valid)return;
                    if(!ok){guard.recordFailure(account,remote);storage.markDirty();p.sendMessage("§c账号或密码错误。");return;}
                    guard.reset(id,remote);
                    if(action.equals("unregister")){storage.remove(id);requireAuthentication(p,Reason.UNREGISTER);p.sendMessage("§a账号已注销。");return;}
                    if(hash!=null)hasher.applyPrecomputed(account,salt,hash,policy,256);
                    if(action.equals("login")){account.markLoginSuccess(settings.isStoreLastIp()?remote:"");storage.markDirty();authenticate(p,Reason.LOGIN);}
                    else{cancelReset(id);storage.markDirty();p.sendMessage("§a密码已修改。");}
                });
            }catch(RuntimeException e){later(()->p.sendMessage("§c验证失败，请联系管理员。"));getLogger().severe("Password verification failed: "+e.getClass().getSimpleName());}
        });if(!accepted)busy(p);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        String action=command.getName().toLowerCase(Locale.ROOT);String[] values=args;
        if(action.equals("mclogin")){
            action=args.length==0?"menu":args[0].toLowerCase(Locale.ROOT);values=args.length==0?new String[0]:Arrays.copyOfRange(args,1,args.length);
        }
        if(Set.of("status","compat","reload").contains(action)){
            if(sender instanceof Player p&&pending(p)){sender.sendMessage("§c请先登录。");return true;}
            if(!sender.hasPermission("mclogin.admin")){sender.sendMessage("§c缺少权限 mclogin.admin");return true;}
            if(action.equals("reload")){
                try{reloadConfig();LoginConfig next=LoginConfig.read(getConfig());settings=next;MCLoginMod.getInstance().updateConfig(next);sender.sendMessage("MCLogin: 配置已重载；账号库与认证状态保持原样。");}
                catch(RuntimeException e){sender.sendMessage("MCLogin: 配置无效，保留原有设置。");}
            }else if(action.equals("compat")){
                sender.sendMessage("MCLogin integrations; lockdown="+(blocked==null?"none":blocked));
                for(String name:List.of("LuckPerms","Essentials","Vault","PlaceholderAPI","WorldEdit","WorldGuard","Geyser-Spigot","floodgate")){
                    Plugin dep=Bukkit.getPluginManager().getPlugin(name);sender.sendMessage(name+": "+(dep!=null&&dep.isEnabled()?dep.getDescription().getVersion():"absent"));
                }
            }else sender.sendMessage("MCLogin: accounts="+storage.getAccountCount()+" authenticated="+authenticated.size()+" storageHealthy="+!storage.isLastLoadSuspect()+" saveFailures="+storage.getConsecutiveSaveFailures()+" lockdown="+(blocked==null?"none":blocked));
            return true;
        }
        if(!(sender instanceof Player p)){sender.sendMessage("MCLogin: player command");return true;}
        if(!available(p))return true;
        if(action.equals("logout")){if(isAuthenticated(p.getUniqueId()))requireAuthentication(p,Reason.LOGOUT);return true;}
        if(action.equals("reset")&&values.length==0){beginReset(p);return true;}
        if(Set.of("login","register","changepassword","unregister","reset").contains(action)&&values.length>0){
            int required=Set.of("register","changepassword","reset").contains(action)?2:1;
            if(values.length!=required){p.sendMessage("§e参数数量不正确，请使用登录界面。");return true;}
            submit(p,action,values[0],values.length>1?values[1]:null);return true;
        }
        if(!action.equals("menu")&&!action.equals("help")&&!Set.of("login","register").contains(action)){p.sendMessage("§e/mclogin login|register|changepassword|unregister|logout|reset|menu");return true;}
        if(!isAuthenticated(p.getUniqueId()))menus.open(p);else p.sendMessage("§a你已登录。/mclogin changepassword <旧密码> <新密码>，/mclogin logout");
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){return List.of();}
    @Override public void registerResetVerifier(Plugin owner,PasswordResetVerifier next){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("main thread required");
        Objects.requireNonNull(owner);Objects.requireNonNull(next);if(!owner.isEnabled())throw new IllegalArgumentException("owner must be enabled");
        if(verifier!=null)throw new IllegalStateException("reset verifier already registered");verifierOwner=owner;verifier=next;
    }
    @Override public void unregisterResetVerifier(Plugin owner){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("main thread required");
        if(owner!=verifierOwner)return;for(UUID id:tickets.snapshot().keySet())cancelReset(id);verifier=null;verifierOwner=null;
    }
    boolean canReset(){return verifier!=null&&verifierOwner!=null&&verifierOwner.isEnabled();}
    void beginReset(Player p){
        UUID id=p.getUniqueId();PlayerAccount a=storage.get(id);
        if(!available(p)||isAuthenticated(id)||a==null||!canReset()){p.sendMessage("§c找回不可用，请联系管理员。");return;}
        if(!unlocked(p,a)||hashing.isInFlight(id))return;
        var ticket=tickets.begin(id,ResetContext.of(p,a));if(ticket==null){p.sendMessage("§e找回请求处理中。");return;}
        PasswordResetVerifier provider=verifier;
        try{
            CompletionStage<Boolean> stage=provider.verify(id,ticket.requestId());
            if(stage==null){cancelReset(id);return;}
            stage.whenComplete((ok,error)->later(()->{
                if(verifier!=provider||Bukkit.getPlayer(id)!=p||isAuthenticated(id)||!ticket.context().matches(p,storage.get(id))||!tickets.current(id,ticket)){cancelIfCurrent(id,ticket);return;}
                if(error!=null||!Boolean.TRUE.equals(ok)||!tickets.authorize(id,ticket)){cancelIfCurrent(id,ticket);p.sendMessage("§c身份核验失败。");return;}
                p.sendMessage("§a身份核验通过，请设置新密码。");menus.start(p,"reset");
            }));p.sendMessage("§e请完成已绑定身份的外部核验。");
        }catch(RuntimeException e){cancelReset(id);p.sendMessage("§c身份核验暂不可用。");}
    }
    private void completeReset(Player p,String password,String confirmation){
        UUID id=p.getUniqueId();var ticket=tickets.get(id);
        if(!canReset()||isAuthenticated(id)||ticket==null||!ticket.context().matches(p,storage.get(id))||!validPassword(password)||!Objects.equals(password,confirmation)||!tickets.consume(id,ticket)){p.sendMessage("§c找回授权无效或密码不符合要求。");return;}
        PasswordResetVerifier provider=verifier;String salt=hasher.generateSalt();int iterations=settings.getPbkdf2Iterations();long generation=generations.getOrDefault(id,0L);
        boolean accepted=hashing.submit(id,()->{
            try{String hash=hasher.hash(password,salt,iterations,256);later(()->{
                if(verifier==provider&&tickets.current(id,ticket)&&current(p,ticket.context(),false,generation)){
                    PlayerAccount account=storage.get(id);hasher.applyPrecomputed(account,salt,hash,iterations,256);guard.reset(id,ip(p));storage.markDirty();p.sendMessage("§a密码已重置，请使用新密码登录。");
                }
                cancelIfCurrent(id,ticket);
            });}catch(RuntimeException e){later(()->cancelIfCurrent(id,ticket));}
        });if(!accepted){cancelIfCurrent(id,ticket);busy(p);}
    }
    private void cancelIfCurrent(UUID id,ResetTickets.Ticket<ResetContext> ticket){if(tickets.get(id)==ticket)cancelReset(id);}
    private void cancelReset(UUID id){var ticket=tickets.get(id);if(ticket!=null&&tickets.remove(id,ticket)&&verifier!=null)try{verifier.cancel(ticket.requestId());}catch(RuntimeException ignored){ }}
}
