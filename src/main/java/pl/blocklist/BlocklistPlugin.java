package pl.blocklist;

import com.google.gson.Gson;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.asynchttpclient.Dsl.asyncHttpClient;

public class BlocklistPlugin extends JavaPlugin implements Listener {

    private static final String GENERATE_VOTE_ENDPOINT = "https://api.blocklist.pl/api/external-votes/";

    private static final String VALIDATE_VOTE_ENDPOINT = "https://api.blocklist.pl/api/external-votes?code=";

    private AsyncHttpClient httpClient;

    private String serverSlug;

    private Gson serializer;

    private List<String> rewardCommands;

    private List<String> commandAliases;

    private static final String MAIN_VOTE_COMMAND = "blvote";

    @Override
    public void onEnable() {
        this.httpClient = asyncHttpClient();

        this.saveDefaultConfig();
        FileConfiguration config = this.getConfig();

        this.commandAliases = config.getStringList("command-aliases");
        PluginCommand voteCommand = this.getCommand(MAIN_VOTE_COMMAND);
        if (voteCommand != null) {
            voteCommand.setExecutor(this);
        }

        this.rewardCommands = config.getStringList("reward-commands");

        this.serverSlug = config.getString("server-id");

        this.serializer = new Gson();

        this.getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        try {
            this.httpClient.close();
        } catch (IOException exc) {
            this.getLogger().warning("Error while closing http client");
            exc.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void voteCommandAlias(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        String[] splitted = message.split(" ");
        String command = splitted[0];
        for (String commandAlias : this.commandAliases) {
            if (command.equalsIgnoreCase("/" + commandAlias)) {
                event.setMessage("/" + MAIN_VOTE_COMMAND);
                break;
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player executor = (Player) sender;
        this.startVoteProcess(executor);
        return true;
    }

    private void startVoteProcess(Player executor) {
        InetSocketAddress socketAddress = executor.getAddress();
        String ip = socketAddress.getAddress().getHostAddress();

        //TODO: Maybe cache?
        CompletableFuture<Response> maybeGenerateVoteRawResponse = this.sendGenerateVoteRequest(ip);

        executor.sendMessage(ChatColor.YELLOW + "Rozpoczęto proces głosowania...");

        maybeGenerateVoteRawResponse.whenComplete((generateVoteRawResponse, exception) -> {
           if (exception != null) {
               exception.printStackTrace();
               executor.sendMessage(ChatColor.RED + "Wystąpił błąd. Skontaktuj się z administratorem serwera.");
               return;
           }

           int statusCode = generateVoteRawResponse.getStatusCode();

           if (statusCode == 429) {
               executor.sendMessage(ChatColor.RED + "Możesz oddać tylko jeden głos w ciągu 24 godzin.");
               return;
           }

           if (statusCode == 409) {
               executor.sendMessage(ChatColor.RED + "Jesteś już w trakcie głosowania.");
               return;
           }

           if (statusCode != 200) {
               executor.sendMessage(ChatColor.RED + "Wystąpił błąd. Kod odpowiedzi: " + statusCode);
               return;
           }

           String body = generateVoteRawResponse.getResponseBody();
           GenerateVoteResponse generateVoteResponse = this.serializer.fromJson(body, GenerateVoteResponse.class);

           String url = generateVoteResponse.getUrl();
           String code = generateVoteResponse.getCode();

           executor.sendMessage(ChatColor.GREEN + "Wygenerowany adres głosowania: " + url);

           this.startValidateProcess(executor, code);
        });
    }

    private void startValidateProcess(Player executor, String code) {
        CompletableFuture<Response> maybeValidateVoteRawResponse = this.sendValidateVoteRequest(code);

        maybeValidateVoteRawResponse.whenComplete((validateVoteResponse, exception) -> {
            if (exception != null) {
                exception.printStackTrace();
                executor.sendMessage(ChatColor.RED + "Wystąpił błąd. Skontaktuj się z administratorem serwera.");
                return;
            }

            int statusCode = validateVoteResponse.getStatusCode();

            if (statusCode == 410 || statusCode == 504) {
                executor.sendMessage(ChatColor.RED + "Czas na zagłosowanie minął.");
                return;
            }

            if (statusCode != 200) {
                executor.sendMessage(ChatColor.RED + "Nie wykryto oddanego głosu, spróbuj ponownie później. Kod odpowiedzi: " + statusCode);
                return;
            }

            executor.sendMessage(ChatColor.GREEN + "Pomyślnie oddałeś głos na serwer.");

            //Switch to main thread
            this.getServer().getScheduler().runTask(this, () -> this.executeRewardCommands(executor));
        });
    }

    private void executeRewardCommands(Player executor) {
        String name = executor.getName();

        Server server = this.getServer();
        ConsoleCommandSender consoleSender = server.getConsoleSender();

        for (String rewardCommand : this.rewardCommands) {
            String replaced = rewardCommand
                    .replace("%player-name%", name);

            server.dispatchCommand(consoleSender, replaced);
        }
    }

    private CompletableFuture<Response> sendGenerateVoteRequest(String ip) {
        GenerateVoteRequest generateVoteRequest = new GenerateVoteRequest(ip);
        String json = this.serializer.toJson(generateVoteRequest);

        String finalUrl = GENERATE_VOTE_ENDPOINT + this.serverSlug;

        return this.httpClient
                .preparePost(finalUrl)
                .setBody(json)
                .addHeader("Content-Type", "application/json")
                .execute()
                .toCompletableFuture();
    }

    private CompletableFuture<Response> sendValidateVoteRequest(String code) {
        String finalUrl = VALIDATE_VOTE_ENDPOINT + code;

        return this.httpClient
                .prepareHead(finalUrl)
                .execute()
                .toCompletableFuture();
    }
}
