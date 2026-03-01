package com.team1325;

import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.PlainTextObject;
import com.slack.api.model.block.element.ButtonElement;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.slack.api.model.block.Blocks.*;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.button;
import static com.team1325.Constants.NUM_TO_CHAR;

public class Poll {
    private SectionBlock header;
    private List<LayoutBlock> votingOptions = new ArrayList<>();
    private ContextBlock footer;
    private final ActionsBlock moveButton;
    private final JSONArray options;
    private final int pollId;
    private final String question;

    public Poll(int pollId, String question, JSONArray options, JSONObject responses, int maxResponses,
            int maxOptions, boolean isClosed, int duration, String userId) {
        this.options = options;
        this.pollId = pollId;
        LocalDateTime messageTime = LocalDateTime.now().plusSeconds(duration);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        this.question = question;
        this.header = section(s -> s.text(markdownText(
                "*" + question + "* " + (duration > 0 ? "\nPoll closes at " + messageTime.format(formatter) : "") +
                        (maxResponses > 0 ? "\nMax responses per option: " + maxResponses : "") +
                        (maxOptions > 0 ? "\nMax options per member: " + maxOptions : ""))));
        this.moveButton = ActionsBlock.builder()
                .elements(Collections.singletonList(ButtonElement
                        .builder()
                        .text(PlainTextObject.builder().text("Move to Bottom").build())
                        .actionId("move_bottom")
                        .build())).build();
        updateVotingOptions(responses, userId, isClosed);
    }

    public List<LayoutBlock> createBlocks() {
        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(header);
        blocks.add(divider());
        blocks.addAll(votingOptions);
        blocks.add(divider());
        blocks.add(footer);
        blocks.add(moveButton);
        return blocks;
    }

    /**
     * Updates vote block and footer block
     * @param responses Poll responses
     * @param userId Poll creator's UID
     * @param isClosed Whether the poll is closed
     */
    public void updateVotingOptions(JSONObject responses, String userId, boolean isClosed) {
        // Add each option as a button with vote count
        List<LayoutBlock> blocks = new ArrayList<>();
        try {
            PollService pollService = PollService.getInstance();
            String timeClosed = pollService.getTimeClosed(pollId);
            if(isClosed){
                this.header = section(s -> s.text(markdownText(
                        "*" + question + "* " + "\nPoll closed at " + timeClosed)));
            }
            for (int i = 0; i < options.length(); i++) {
                String option = options.getString(i);
                int voteCount = 0;
                List<String> voters = new ArrayList<>();

                if (responses.has(option)) {
                    JSONArray voterArray = responses.getJSONArray(option);
                    voteCount = voterArray.length();

                    for (int j = 0; j < voterArray.length(); j++) {
                        voters.add(voterArray.getString(j));
                    }
                }
                StringBuilder displayNum = new StringBuilder();
                String optionNum = String.valueOf(i + 1);
                for (int j = 0; j < optionNum.length(); j++) {
                    displayNum.append(NUM_TO_CHAR[Character.getNumericValue(optionNum.charAt(j))]);
                }
                String optionText = displayNum + " " + option + " `" + voteCount + "`";

                if (!voters.isEmpty()) {
                    String voterMentions = String.join(", ", voters.stream().map(id -> "<@" + id + ">").toArray(String[]::new));
                    optionText += "\n" + voterMentions;
                }

                if (!isClosed) {
                    String finalOptionText = optionText;
                    blocks.add(section(s -> s
                            .text(markdownText(finalOptionText))
                            .accessory(button(b -> b
                                    .text(plainText("Vote"))
                                    .actionId("vote_option")
                                    .value(pollId + ":" + option)
                                    .style("primary")))));
                } else {
                    String finalOptionText1 = optionText;
                    blocks.add(section(s -> s.text(markdownText(finalOptionText1))));
                }
            }
            votingOptions = blocks;

            // Footer
            ArrayList<String> uniqueUserIds = new ArrayList<>();

            // Count unique respondents
            for (String key : responses.keySet()) {
                if (!responses.getJSONArray(key).isEmpty()) {
                    for (int i = 0; i < responses.getJSONArray(key).length(); i++) {
                        String respondentId = responses.getJSONArray(key).getString(i);
                        if (!uniqueUserIds.contains(respondentId)) {
                            uniqueUserIds.add(respondentId);
                        }
                    }
                }
            }

            String footerText = "*Poll ID:* " + pollId + " | *Unique Responses:* " + uniqueUserIds.toArray().length + " | *Created By:* <@" + userId + ">";
            if (isClosed) {
                footerText += " | _Poll closed_";
            }

            String finalFooterText = footerText;
            footer = context(c -> c.elements(Collections.singletonList(markdownText(finalFooterText))));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
