package com.team1325;

import com.slack.api.model.block.element.ConversationsFilter;
import com.slack.api.model.view.View;
import com.slack.api.model.view.ViewClose;
import com.slack.api.model.view.ViewSubmit;
import com.slack.api.model.view.ViewTitle;

import java.util.Arrays;

import static com.slack.api.model.block.Blocks.asBlocks;
import static com.slack.api.model.block.Blocks.input;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.*;

public class Constants {
    // APP CONSTANTS
    public static final View pollCreationView = View
            .builder()
            .callbackId("poll_creation_modal")
            .type("modal")
            .title(ViewTitle.builder().type("plain_text").text("Create Poll").build())
            .submit(ViewSubmit.builder().type("plain_text").text("Create").build())
            .close(ViewClose.builder().type("plain_text").text("Cancel").build())
            .blocks(asBlocks(input(input -> input
                    .blockId("channel_input_block")
                    .label(plainText("Destination Channel"))
                    .element(conversationsSelect(cs -> cs
                            .actionId("selected_channel_id")
                            .placeholder(plainText("Select a channel..."))
                            .responseUrlEnabled(true)
                            .defaultToCurrentConversation(true)
                            .filter(ConversationsFilter.builder().include(Arrays.asList("public", "private")).build())))), input(i -> i
                    .blockId("question_block")
                    .label(plainText("Question"))
                    .element(plainTextInput(pti -> pti.actionId("question_input")))), input(i -> i
                    .blockId("options_block")
                    .label(plainText("Options (one per line)"))
                    .element(plainTextInput(pti -> pti
                            .actionId("options_input")
                            .multiline(true)
                            .placeholder(plainText("Option 1\nOption 2\nOption 3..."))))), input(i -> i
                    .blockId("duration_block")
                    .label(plainText("Time Limit"))
                    .optional(true)
                    .element(datetimePicker(dtp -> dtp
                            .actionId("duration_input")))), input(i -> i
                    .blockId("max_responses_block")
                    .label(plainText("Max responses per option (0 for unlimited)"))
                    .optional(true)
                    .element(plainTextInput(pti -> pti.actionId("max_responses_input").initialValue("0")))), input(i -> i
                    .blockId("max_options_block")
                    .label(plainText("Max votes per member (0 for unlimited)"))
                    .optional(true)
                    .element(plainTextInput(pti -> pti.actionId("max_options_input").initialValue("0"))))))
            .build();

    // SQL CONSTANTS
    public static final String CREATE_TABLE = """
                                              CREATE TABLE IF NOT EXISTS polls (
                                              id INTEGER PRIMARY KEY AUTOINCREMENT,
                                              channelId TEXT NOT NULL,
                                              question TEXT NOT NULL,
                                              options TEXT NOT NULL,
                                              duration INTEGER NOT NULL,
                                              optionMaxResponses INTEGER NOT NULL,
                                              memberMaxOptions INTEGER NOT NULL,
                                              responses TEXT NOT NULL,
                                              messageTs TEXT,
                                              createdAt INTEGER NOT NULL,
                                              closed BOOLEAN DEFAULT 0,
                                              userId TEXT NOT NULL,
                                              pollObj TEXT NOT NULL,
                                              timeClosed TEXT
                                              )
                                              """;

    public static final String INSERT_POLL = """
                                             INSERT INTO polls (channelId, question, options, duration, optionMaxResponses, memberMaxOptions, responses, createdAt, userId, pollObj, timeClosed)
                                             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                             """;

    public static final String UPDATE_MESSAGE_TS = """
                                                   UPDATE polls SET messageTs = ? WHERE id = ?""";

    public static final String UPDATE_CHANNEL_ID = """
                                                   UPDATE polls SET channelId = ? WHERE id = ?""";

    public static final String UPDATE_RESPONSES = """
                                                  UPDATE polls SET responses = ? WHERE id = ?""";

    public static final String UPDATE_POLL_STATUS = """
                                                    UPDATE polls SET closed = 1 WHERE id = ?""";

    public static final String GET_MESSAGE_TS = """
                                                SELECT messageTs FROM polls WHERE id = ?""";

    public static final String GET_POLL_DATA = """
                                               SELECT * FROM polls WHERE id = ?""";

    public static final String GET_EXPIRED_POLLS = """
                                                   SELECT id FROM polls WHERE closed = 0 AND (createdAt + duration) <= ? AND duration > 0""";

    public static final String GET_POLL_OBJECT = """
                                                SELECT pollObj FROM polls WHERE id = ?""";

    public static final String UPDATE_POLL_OBJECT = """
                                                    UPDATE polls SET pollObj = ? WHERE id = ?""";

    public static final String GET_POLL_ID = """
                                             SELECT id FROM polls WHERE messageTs = ?""";

    public static final String SET_TIME_CLOSED = """
                                                 UPDATE polls SET timeClosed = ? WHERE id = ?""";

    public static final String GET_TIME_CLOSED = """
                                                 SELECT timeClosed FROM polls WHERE id = ?""";

    public static final String[] NUM_TO_CHAR = {
            ":zero:", ":one:", ":two:", ":three:", ":four:",
            ":five:", ":six:", ":seven:", ":eight:", ":nine:"
    };
}
