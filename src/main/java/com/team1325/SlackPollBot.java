package com.team1325;

import com.slack.api.Slack;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.methods.MethodsClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import static com.team1325.Callbacks.closeExpiredPolls;

public class SlackPollBot {
    private static final Logger LOGGER = Logger.getLogger(SlackPollBot.class.getName());
    public static MethodsClient client;
    static void main(String[] args) throws Exception {
        if (args.length < 2) {
            LOGGER.severe("Usage: java -jar SlackPollBot-1.0.jar <SLACK_BOT_TOKEN> <SLACK_APP_TOKEN>");
            System.exit(1);
        }
        Slack slack = Slack.getInstance();
        client = slack.methods(args[0]);
        // Initialize Slack app with bot token and app token
        var config = AppConfig.builder().singleTeamBotToken(args[0]).build();
        var app = new App(config);

        LOGGER.info("Starting Slack Poll Bot");

        // Start the poll timer in a separate thread
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            final MethodsClient client = slack.methods(args[0]);

            LOGGER.info("Poll timer started.");

            while (true) {
                try {
                    closeExpiredPolls(client);

                    Thread.sleep(10000); // Check every 10 seconds
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        // Handle the create_poll shortcut
        app.globalShortcut("create_poll", Callbacks::createPoll);

        // Handle modal submission
        app.viewSubmission("poll_creation_modal", Callbacks::handlePollModal);

        // Handle poll button clicks
        app.blockAction("vote_option", Callbacks::handleVotes);

        // Handle move to bottom
        app.blockAction("move_bottom", Callbacks::moveBottom);

        // Handle /endpoll command
        app.messageShortcut("end_poll", Callbacks::endPoll);

        var socketModeApp = new SocketModeApp(args[1], app);
        socketModeApp.start();
        executor.close();
    }
}
