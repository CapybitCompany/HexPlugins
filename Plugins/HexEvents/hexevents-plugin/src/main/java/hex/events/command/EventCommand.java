package hex.events.command;

import hex.events.api.JoinSource;
import hex.events.lifecycle.EventLifecycleService;
import hex.events.model.EventInstance;
import hex.events.registration.RegistrationService;
import hex.events.ui.CalendarMenu;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class EventCommand implements CommandExecutor, TabCompleter {
    private final EventLifecycleService lifecycle; private final RegistrationService registrations; private final CalendarMenu menu; private final Runnable reload;
    public EventCommand(EventLifecycleService lifecycle, RegistrationService registrations, CalendarMenu menu, Runnable reload){this.lifecycle=lifecycle;this.registrations=registrations;this.menu=menu;this.reload=reload;}
    @Override public boolean onCommand(@NotNull CommandSender sender,@NotNull Command command,@NotNull String label,@NotNull String[] args){
        if(args.length==0){if(sender instanceof Player p)menu.open(p);else sendNext(sender);return true;}
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("next")){sendNext(sender);return true;}
        if(sub.equals("join")&&args.length>=2&&sender instanceof Player p){try{var r=lifecycle.requestJoin(p,UUID.fromString(args[1]),JoinSource.COMMAND);sender.sendMessage(color((r.success()?"&a":"&c")+r.message()));}catch(Exception e){sender.sendMessage(color("&cNiepoprawne ID eventu."));}return true;}
        if(sub.equals("leave")&&args.length>=2&&sender instanceof Player p){try{lifecycle.leave(p,UUID.fromString(args[1]),hex.events.api.LeaveReason.PLAYER_REQUEST);sender.sendMessage(color("&aOpuszczono event."));}catch(Exception e){sender.sendMessage(color("&cNiepoprawne ID eventu."));}return true;}
        if(!sender.hasPermission("hexevents.admin")){sender.sendMessage(color("&cBrak uprawnień."));return true;}
        switch(sub){
            case "reload"->{reload.run();sender.sendMessage(color("&aZażądano atomowego reloadu HexEvents."));}
            case "validate"->{for(String line:lifecycle.validate())sender.sendMessage(color(line.replace("[OK]","&a[OK]&r").replace("[ERROR]","&c[ERROR]&r")));}
            case "status"->{sender.sendMessage(color("&6HexEvents &7• instancje: &f"+lifecycle.allInstances().size()));lifecycle.nextEvent().ifPresent(i->sender.sendMessage(color("&7Najbliższy: &f"+i.definition().displayName()+" &7"+i.startAt())));}
            case "list"->{for(EventInstance i:lifecycle.allInstances())if(!i.state().terminal())sender.sendMessage(color("&7"+i.id()+" &f"+i.definition().id()+" &e"+i.state()+" &7"+i.startAt()));}
            default->sender.sendMessage(color("&7/event [next|join|leave] | admin: reload, validate, status, list"));
        }return true;
    }
    private void sendNext(CommandSender sender){
        var next=lifecycle.nextEvent();
        if(next.isEmpty()){sender.sendMessage(color("&7Brak zaplanowanych eventów."));return;}
        var i=next.get();
        Duration d=Duration.between(Instant.now(),i.startAt());
        long min=Math.max(0,d.toMinutes());
        int max=i.definition().capacity().maxPlayers();
        String places=max<=0?"&7• gracze w evencie: &f"+i.participants().size():"&7• miejsca: &f"+i.participants().size()+"/"+max;
        sender.sendMessage(color("&6Najbliższy event: &f"+i.definition().displayName()+" &7za &e"+min+" min &8("+i.startAt()+") "+places));
        if(sender instanceof Player player){
            lifecycle.playerRelevantEvent(player.getUniqueId()).ifPresent(relevant->{
                var status=lifecycle.admissionStatus(relevant,player.getUniqueId());
                int q=lifecycle.queuePosition(relevant,player.getUniqueId());
                int reg=lifecycle.registrationPosition(relevant,player.getUniqueId());
                if(q>0){
                    sender.sendMessage(color("&eKolejka: &f"+q+". &7• priorytet: &f"+lifecycle.queuePriority(relevant,player.getUniqueId())+" &7• oczekujących: &f"+lifecycle.queueSize(relevant)));
                }else if(status!=null){
                    sender.sendMessage(color("&7Twój status: &f"+status+(reg>0?" &7• pozycja zapisu: &f"+reg:"") ));
                }
            });
        }
    }
    private static String color(String t){return ChatColor.translateAlternateColorCodes('&',t);}
    @Override public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,@NotNull Command command,@NotNull String alias,@NotNull String[] args){if(args.length==1){List<String> v=sender.hasPermission("hexevents.admin")?List.of("next","join","leave","reload","validate","status","list"):List.of("next","join","leave");String p=args[0].toLowerCase(Locale.ROOT);return v.stream().filter(s->s.startsWith(p)).toList();}return List.of();}
}
