package com.dsh.mclogin.bukkit;

public final class CommandPolicyTest {
    private static int checks;
    private static void check(boolean expected,String text,boolean owned){checks++;if(CommandPolicy.allows(text,owned)!=expected)throw new AssertionError(text);}
    public static void main(String[] args){
        for(String text:new String[]{"/login password","/register password password","/mclogin login password","/mclogin:login password","/MCLOGIN:MCLOGIN REGISTER password password","/mlogin menu","/mclogin reset","/mclogin:mlogin help","/mclogin"})check(true,text,true);
        for(String text:new String[]{"/essentials:login password","/other:register password password","/minecraft:op target","/mclogin status","/mclogin reload","/mclogin compat","/changepassword old new","/unregister password","/logout","/mclogin:other","/mcloginstatus","/mclogin\u0000login password","/login:other password","/mclogin logout","/mclogin:MCLOGIN reload"})check(false,text,true);
        for(String text:new String[]{"/login password","/register password password","/mclogin login password","/mclogin:login password"})check(false,text,false);
        for(String text:new String[]{"name issued server command: /login secret", "name issued server command: /mclogin:register secret secret", "name: /mclogin reset secret secret", "/mclogin:mlogin changepassword old new"}){checks++;if(!CredentialLogFilter.containsCredentials(text))throw new AssertionError("credential not filtered");}
        for(String text:new String[]{"name issued server command: /mclogin status", "MCLogin enabled", "/mclogin reset", "/essentials:home"}){checks++;if(CredentialLogFilter.containsCredentials(text))throw new AssertionError("safe log filtered");}
        System.out.println("CommandPolicy: "+checks+" checks passed");
    }
}
