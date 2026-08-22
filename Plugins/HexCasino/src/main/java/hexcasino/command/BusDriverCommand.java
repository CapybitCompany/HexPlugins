package hexcasino.command;

import hexcasino.HexCasinoPlugin;
import hexcasino.Text;
import hexcasino.machine.BusDriverService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class BusDriverCommand implements CommandExecutor, TabCompleter {
    private final HexCasinoPlugin plugin;
    public BusDriverCommand(HexCasinoPlugin plugin){this.plugin=Objects.requireNonNull(plugin);}

    @Override public boolean onCommand(@NotNull CommandSender sender,@NotNull Command command,@NotNull String label,@NotNull String[] args){
        if(!sender.hasPermission("hexcasino.admin")){sender.sendMessage(Text.component("&cBrak uprawnień."));return true;}
        BusDriverService service=plugin.busDriverService();
        if(service==null){sender.sendMessage(Text.component("&cBusDriver nie jest uruchomiony."));return true;}
        if(args.length==1&&args[0].equalsIgnoreCase("verify")){for(String line:service.verificationLines())sender.sendMessage(Text.component(line));return true;}
        if(args.length==2&&args[0].equalsIgnoreCase("board")){
            try{int id=Integer.parseInt(args[1]);for(String line:service.boardLines(id))sender.sendMessage(Text.component(line));}
            catch(NumberFormatException ex){sender.sendMessage(Text.component("&cNieprawidłowy numer planszy."));}
            return true;
        }
        sender.sendMessage(Text.component("&7Użycie: &f/busdriver verify &8| &f/busdriver board <1-100>"));return true;
    }

    @Override public List<String> onTabComplete(@NotNull CommandSender sender,@NotNull Command command,@NotNull String alias,@NotNull String[] args){
        if(!sender.hasPermission("hexcasino.admin"))return List.of();
        if(args.length==1){String p=args[0].toLowerCase(Locale.ROOT);return List.of("verify","board").stream().filter(v->v.startsWith(p)).toList();}
        return List.of();
    }
}
