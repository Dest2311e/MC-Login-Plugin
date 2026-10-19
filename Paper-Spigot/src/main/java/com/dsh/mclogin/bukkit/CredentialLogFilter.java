package com.dsh.mclogin.bukkit;

import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.filter.AbstractFilter;

/** Suppress standard server log lines containing credential commands. Does not replace GUI privacy. */
final class CredentialLogFilter extends AbstractFilter {
    private static final Pattern SECRET=Pattern.compile("(?i)(?:^|\\s)/(?:(?:mclogin:)?(?:login|register|changepassword|unregister)|(?:mclogin:)?(?:mclogin|mlogin)\\s+(?:login|register|changepassword|unregister|reset))\\s+");
    private final LoggerContext context;
    CredentialLogFilter(){context=(LoggerContext)LogManager.getContext(false);start();context.getConfiguration().getRootLogger().addFilter(this);context.updateLoggers();}
    static boolean containsCredentials(String text){return text!=null&&SECRET.matcher(text).find();}
    @Override public Result filter(LogEvent event){return containsCredentials(event.getMessage().getFormattedMessage())?Result.DENY:Result.NEUTRAL;}
    void remove(){context.getConfiguration().getRootLogger().removeFilter(this);context.updateLoggers();stop();}
}
