package com.team1325;

import com.slack.api.bolt.context.builtin.*;
import com.slack.api.bolt.request.builtin.*;
import com.slack.api.bolt.response.Response;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import static com.team1325.Constants.pollCreationView;

public class Callbacks {
    private static final Logger LOGGER = Logger.getLogger(Callbacks.class.getName());
    private static final PollService pollService = PollService.getInstance();
    public static Response createPoll(GlobalShortcutRequest req, GlobalShortcutContext ctx) throws SlackApiException, IOException {
        // Open a modal for poll creation
        ctx.client().viewsOpen(r -> r.triggerId(req.getPayload().getTriggerId()).view(pollCreationView));
        LOGGER.info(req.getPayload().getUser().getUsername() + " opened poll creation modal.");
        return ctx.ack();
    }

    public static Response handlePollModal(ViewSubmissionRequest req, ViewSubmissionContext ctx) {
        int durationInt = 0;
        boolean toggleDuration = true;
        var payload = req.getPayload();
        var stateValues = payload.getView().getState().getValues();

        String channelId = stateValues.get("channel_input_block").get("selected_channel_id").getSelectedConversation();
        String question = stateValues.get("question_block").get("question_input").getValue();
        String optionsText = stateValues.get("options_block").get("options_input").getValue();

        if (stateValues.get("duration_block").get("duration_input").getSelectedDateTime() == null) {
            toggleDuration = false;
        } else{
            durationInt = stateValues.get("duration_block").get("duration_input").getSelectedDateTime();
        }

        String maxResponsesStr = stateValues.get("max_responses_block").get("max_responses_input").getValue();
        String maxOptionsStr = stateValues.get("max_options_block").get("max_options_input").getValue();

        Instant selectedInstant = Instant.ofEpochSecond(durationInt);
        Instant now = Instant.now();

        int duration = Math.toIntExact(ChronoUnit.SECONDS.between(now, selectedInstant));
        if (!toggleDuration){
            duration = 604800;
        }else if(selectedInstant.isBefore(now)) {
            Map<String, String> errors = new HashMap<>();
            errors.put("duration_block", "You must select a time in the future.");

            return ctx.ack(r -> r.responseAction("errors").errors(errors));
        }
        int maxResponses = Integer.parseInt(maxResponsesStr);
        int maxOptions = Integer.parseInt(maxOptionsStr);

        // Parse options
        String[] optionLines = optionsText.split("\n");
        JSONArray optionsJson = new JSONArray();

        for (String option : optionLines) {
            if (!option.trim().isEmpty()) {
                optionsJson.put(option.trim());
            }
        }

        // Store poll in database and get the ID back immediately
        JSONObject responsesJson = new JSONObject();
        int pollId;
        try {
            pollId = pollService.insertPoll(channelId, question, optionsJson.toString(), duration, maxResponses, maxOptions,
                    responsesJson.toString(), payload.getUser().getId());
            Poll poll = new Poll(pollId, question, optionsJson, responsesJson, maxResponses, maxOptions, false, duration, ctx.getRequestUserId());
            pollService.updatePollObj(pollId, poll);
            var result = ctx.client().chatPostMessage(r -> r.channel(channelId).blocks(poll.createBlocks()).text("New poll: " + question));

            // Store message timestamp and channel for later updates
            if (result.isOk()) {
                pollService.updateMessageTs(pollId, result.getTs());
                pollService.updateChannelId(pollId, result.getChannel());
            } else {
                LOGGER.severe(" Failed to post poll: " + result.getError());
            }
        } catch (SQLException | SlackApiException | IOException e) {
            throw new RuntimeException(e);
        }

        LOGGER.info("Poll #" + pollId + " created by " + payload.getUser().getUsername());

        return ctx.ack();
    }

    public static Response handleVotes(BlockActionRequest req, ActionContext ctx) throws SlackApiException, IOException{
        var payload = req.getPayload();
        String actionValue = payload.getActions().getFirst().getValue();

        String[] parts = actionValue.split(":", 2);
        int pollId = Integer.parseInt(parts[0]);
        String option = parts[1];
        String userId = payload.getUser().getId();

        // Get poll data first to check if it exists
        PollData pollData;
        try {
            pollData = pollService.getPollData(pollId);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        if (pollData == null) {
            LOGGER.severe("ERROR: Poll not found with ID: " + pollId);
            ctx
                    .client()
                    .chatPostEphemeral(
                            r -> r.channel(payload.getChannel().getId()).user(userId).text("⚠️ Poll not found. It may have been deleted."));
            return ctx.ack();
        }

        // Update vote in database
        try {
            pollService.toggleVote(pollId, userId, option);
        } catch (SQLException e) {
            // If there's an error (like max votes reached), send ephemeral message
            LOGGER.warning("⚠️ Vote error: " + e.getMessage());
            ctx.client().chatPostEphemeral(r -> r.channel(payload.getChannel().getId()).user(userId).text("⚠️ " + e.getMessage()));
            return ctx.ack();
        }

        // Get updated poll data after vote
        try {
            pollData = pollService.getPollData(pollId);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        if (pollData == null) {
            LOGGER.severe("ERROR: Poll disappeared after vote!");
            return ctx.ack();
        }

        // Update the message to show the new vote
        try {
            Poll poll = pollService.getPollObj(pollId);
            poll.updateVotingOptions(pollData.responses(), pollData.userId(), false);

            PollData finalPollData = pollData;
            ctx
                    .client()
                    .chatUpdate(r -> r
                            .channel(payload.getChannel().getId())
                            .ts(payload.getMessage().getTs())
                            .blocks(poll.createBlocks())
                            .text("Poll: " + finalPollData.question()));
        }  catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return ctx.ack();
    }

    public static Response moveBottom(BlockActionRequest req, ActionContext ctx) throws SlackApiException, IOException{
        var payload = req.getPayload();
        String messageTsOld =  payload.getMessage().getTs();
        try {
            int pollId = pollService.getPollId(messageTsOld);
            if (req.getPayload().getUser().getId().equals(pollService.getPollData(pollId).userId())) {
                Poll poll = pollService.getPollObj(pollId);
                PollData pollData = pollService.getPollData(pollId);
                poll.updateVotingOptions(pollData.responses(), pollData.userId(), pollData.closed());
                var result = ctx.client().chatPostMessage(r -> {
                    try {
                        return r
                                .channel(payload.getChannel().getId())
                                .blocks(poll.createBlocks())
                                .text("Poll: " + pollService.getPollData(pollId).question());
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
                if (result.isOk()) {
                    pollService.updateMessageTs(pollId, result.getTs());
                    ctx.client().chatDelete(r -> r.token(ctx.getBotToken())
                            .channel(req.getPayload().getChannel().getId())
                            .ts(messageTsOld)
                    );
                    LOGGER.info("Poll #" + pollId + " moved");
                } else {
                    LOGGER.severe(" Failed to post poll: " + result.getError());
                }
            } else{
                ctx
                        .client()
                        .chatPostEphemeral(
                                r -> r.channel(
                                        payload.getChannel().getId())
                                        .user(payload.getUser().getId())
                                        .text("⚠️ You are not the owner of this poll!"));
                return ctx.ack();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return ctx.ack();
    }

    public static Response endPoll(MessageShortcutRequest req, MessageShortcutContext ctx) {
        try {
            String messageTs = req.getPayload().getMessage().getTs();
            String channelId = req.getPayload().getChannel().getId();
            String userId = req.getPayload().getUser().getId();

            if (messageTs == null) {
                LOGGER.severe("ERROR: Message Ts is null");
                ctx.ack();
                sendEphemeralError(ctx, channelId, userId, "Could not find message for this poll.");
                return ctx.ack();
            }

            int pollId;
            try {
                pollId = pollService.getPollId(messageTs);
            } catch (NumberFormatException e) {
                LOGGER.severe("ERROR: Invalid poll ID!");
                ctx.ack();
                sendEphemeralError(ctx, channelId, userId, "Invalid poll ID.");
                return ctx.ack();
            }

            PollData pollData = pollService.getPollData(pollId);

            if (pollData == null) {
                LOGGER.severe("ERROR: Poll not found with ID: " + pollId);
                ctx.ack();
                sendEphemeralError(ctx, channelId, userId, "Poll not found. It may have been deleted.");
                return ctx.ack();
            }

            if (!Objects.equals(pollData.userId(), userId)) {
                LOGGER.severe("ERROR: User does not belong to this poll!");
                ctx.ack();
                sendEphemeralError(ctx, channelId, userId, "Only the poll creator can close the poll.");
                return ctx.ack();
            }

            // Close Poll
            pollService.setTimeClosed(pollId);
            Poll poll = pollService.getPollObj(pollId);
            poll.updateVotingOptions(pollData.responses(), pollData.userId(), true);

            // Update the original poll message
            ctx.client().chatUpdate(r -> r
                    .channel(pollData.channelId())
                    .ts(messageTs)
                    .blocks(poll.createBlocks())
            );

            pollService.closePoll(pollId);
            LOGGER.info("Poll #" + pollId + " closed by " + req.getPayload().getUser().getName());

            // Ack success
            sendEphemeralError(ctx, channelId, userId, "Poll #" + pollId + " has been closed.");
            return ctx.ack();

        } catch (Exception e) {
            LOGGER.severe("Runtime Error: " + e.getMessage());
            try {
                return ctx.ack();
            } catch (Exception ackEx) {
                throw new RuntimeException(e);
            }
        }
    }

    // Helper method to keep code clean
    private static void sendEphemeralError(MessageShortcutContext ctx, String channelId, String userId, String text) {
        try {
            ctx.client().chatPostEphemeral(r -> r
                    .channel(channelId)
                    .user(userId)
                    .text(text)
            );
        } catch (Exception e) {
            LOGGER.severe("Failed to send error message: " + e.getMessage());
        }
    }


    // Poll timer to close expired polls
    public static void closeExpiredPolls(MethodsClient client) throws SQLException, SlackApiException, IOException {
        List<Integer> expiredPolls = pollService.getExpiredPolls();

        for (int expiredPoll : expiredPolls) {
            PollData pollData = pollService.getPollData(expiredPoll);
            String messageTs = pollService.getMessageTs(pollData.id());
            pollService.setTimeClosed(pollData.id());
            if (messageTs != null) {
                Poll poll = pollService.getPollObj(pollData.id());
                poll.updateVotingOptions(pollData.responses(), pollData.userId(), true);

                client.chatUpdate(r -> r.channel(pollData.channelId()).ts(messageTs).blocks(poll.createBlocks()));
                pollService.closePoll(pollData.id());
                LOGGER.info("Poll #" + pollData.id() + " automatically closed due to expiration.");
            }
        }
    }
}
