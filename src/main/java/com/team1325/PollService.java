package com.team1325;

import com.google.gson.Gson;
import com.slack.api.util.json.GsonFactory;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static com.team1325.Constants.*;

public class PollService {
    private static final Logger LOGGER = Logger.getLogger(PollService.class.getName());
    private static final String URL = "jdbc:sqlite:./polls.db";

    private static PollService instance;

    /**
     * Constructor that initializes the database and creates the polls table if it doesn't exist.
     */
    private PollService() {

        try (Connection conn = DriverManager.getConnection(URL); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static PollService getInstance() {
        return instance == null ? instance = new PollService() : instance;
    }

    /**
     * Inserts a new poll into the database.
     *
     * @param channelId          channel ID where the poll is created
     * @param question           poll question
     * @param optionsJson        JSON string of poll options
     * @param duration           duration of the poll in seconds
     * @param optionMaxResponses maximum responses allowed per option
     * @param memberMaxOptions   maximum options a member can select
     * @param responsesJson      JSON string of responses
     * @param userId             ID of the user who created the poll
     *
     * @return the generated poll ID
     *
     * @throws SQLException general SQL exception
     */
    public int insertPoll(String channelId, String question, String optionsJson, int duration, int optionMaxResponses, int memberMaxOptions,
            String responsesJson, String userId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL);
             PreparedStatement pstmt = conn.prepareStatement(INSERT_POLL, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, channelId);
            pstmt.setString(2, question);
            pstmt.setString(3, optionsJson);
            pstmt.setInt(4, duration);
            pstmt.setInt(5, optionMaxResponses);
            pstmt.setInt(6, memberMaxOptions);
            pstmt.setString(7, responsesJson);
            pstmt.setLong(8, System.currentTimeMillis() / 1000);
            pstmt.setString(9, userId);
            pstmt.setString(10, "");
            pstmt.setString(11, "");
            pstmt.executeUpdate();

            // Get the generated ID immediately in the same connection
            try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getInt(1);
                } else {
                    LOGGER.severe("ERROR: Creating poll failed, no ID obtained.");
                    throw new SQLException("Creating poll failed, no ID obtained.");
                }
            }
        }
    }

    /**
     * Updates the message timestamp for a given poll.
     *
     * @param pollId    the ID of the poll to update
     * @param messageTs the new message timestamp
     *
     * @throws SQLException general SQL exception
     */
    public void updateMessageTs(int pollId, String messageTs) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL); PreparedStatement pstmt = conn.prepareStatement(UPDATE_MESSAGE_TS)) {
            pstmt.setString(1, messageTs);
            pstmt.setInt(2, pollId);
            pstmt.executeUpdate();
        }
    }

    /**
     * Updates the channel ID for a given poll.
     *
     * @param pollId    the ID of the poll to update
     * @param channelId the new channel ID
     *
     * @throws SQLException general SQL exception
     */
    public void updateChannelId(int pollId, String channelId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL); PreparedStatement pstmt = conn.prepareStatement(UPDATE_CHANNEL_ID)) {
            pstmt.setString(1, channelId);
            pstmt.setInt(2, pollId);
            pstmt.executeUpdate();
        }
    }

    /**
     * Retrieves the message timestamp for a given poll.
     *
     * @param pollId the ID of the poll
     *
     * @return the message timestamp as a String, or null if not found
     *
     * @throws SQLException general SQL exception
     */
    public String getMessageTs(int pollId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL); PreparedStatement pstmt = conn.prepareStatement(GET_MESSAGE_TS)) {
            pstmt.setInt(1, pollId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("messageTs");
                }
            }
        }

        return null;
    }

    /**
     * Toggles a user's vote for a specific option in a poll.
     *
     * @param pollId the ID of the poll
     * @param userId the ID of the user
     * @param option the option to toggle vote for
     *
     * @throws SQLException general SQL exception
     */
    public void toggleVote(int pollId, String userId, String option) throws SQLException {
        PollData data = getPollData(pollId);

        if (data == null) {
            throw new SQLException("Poll not found");
        }

        JSONObject responses = data.responses();

        // Get or create the voters array for this option
        JSONArray voters = responses.has(option) ? responses.getJSONArray(option) : new JSONArray();

        // Check if user already voted for this option
        boolean alreadyVoted = false;
        int voteIndex = -1;

        for (int i = 0; i < voters.length(); i++) {
            if (voters.getString(i).equals(userId)) {
                alreadyVoted = true;
                voteIndex = i;
                break;
            }
        }

        if (alreadyVoted) {
            // Remove vote
            voters.remove(voteIndex);
        } else {
            // Check if user has reached max options limit
            if (data.maxOptions() > 0) {
                int userVoteCount = countUserVotes(responses, userId);

                if (userVoteCount >= data.maxOptions()) {
                    throw new SQLException("User has reached maximum number of votes");
                }
            }

            // Check if option has reached max responses
            if (data.maxResponses() > 0 && voters.length() >= data.maxResponses()) {
                throw new SQLException("This option has reached maximum responses");
            }

            // Add vote
            voters.put(userId);
        }

        responses.put(option, voters);

        // Update database
        try (Connection conn = DriverManager.getConnection(URL); PreparedStatement pstmt = conn.prepareStatement(UPDATE_RESPONSES)) {
            pstmt.setString(1, responses.toString());
            pstmt.setInt(2, pollId);
            pstmt.executeUpdate();
        }
    }

    /**
     * Counts the number of votes a user has made across all options.
     *
     * @param responses the JSON object containing all responses
     * @param userId    the ID of the user
     *
     * @return the count of votes
     */
    private int countUserVotes(JSONObject responses, String userId) {
        int count = 0;

        for (String key : responses.keySet()) {
            JSONArray voters = responses.getJSONArray(key);

            for (int i = 0; i < voters.length(); i++) {
                if (voters.getString(i).equals(userId)) {
                    count++;
                }
            }
        }

        return count;
    }

    /**
     * Retrieves poll data for a given poll ID.
     *
     * @param pollId the ID of the poll
     *
     * @return PollData object containing poll details
     *
     * @throws SQLException general SQL exception
     */
    public PollData getPollData(int pollId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL); PreparedStatement pstmt = conn.prepareStatement(GET_POLL_DATA)) {
            pstmt.setInt(1, pollId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new PollData(rs.getInt("id"), rs.getString("channelId"), rs.getString("question"),
                            new JSONArray(rs.getString("options")), rs.getInt("duration"), rs.getInt("optionMaxResponses"),
                            rs.getInt("memberMaxOptions"), new JSONObject(rs.getString("responses")), rs.getString("messageTs"),
                            rs.getInt("closed") == 1, rs.getString("userId"));
                } else {
                    LOGGER.severe("ERROR: Poll data not found.");
                    LOGGER.severe("❌ No poll found with ID: " + pollId);
                }
            }
        }

        return null;
    }

    /**
     * Closes a poll by setting its closed status to true.
     *
     * @param pollId the ID of the poll to close
     *
     * @throws SQLException general SQL exception
     */
    public void closePoll(int pollId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL); PreparedStatement pstmt = conn.prepareStatement(UPDATE_POLL_STATUS)) {
            pstmt.setInt(1, pollId);
            pstmt.executeUpdate();
        }
    }

    /**
     * Retrieves IDs of polls that have expired based on their duration.
     *
     * @return list of expired poll IDs
     *
     * @throws SQLException general SQL exception
     */
    public List<Integer> getExpiredPolls() throws SQLException {
        long currentTime = System.currentTimeMillis() / 1000;
        List<Integer> pollId = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(URL); PreparedStatement pstmt = conn.prepareStatement(GET_EXPIRED_POLLS)) {
            pstmt.setLong(1, currentTime);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    pollId.add(rs.getInt("id"));
                }
            }
        }

        return pollId;
    }

    /**
     * Gets poll object from db and deserializes it
     *
     * @param pollId ID of poll
     *
     * @return Poll object
     */
    public Poll getPollObj(int pollId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL); PreparedStatement pstmt = conn.prepareStatement(GET_POLL_OBJECT)) {
            pstmt.setInt(1, pollId);
            try (ResultSet rs = pstmt.executeQuery()) {
                Gson gson = GsonFactory.createSnakeCase();
                if (rs.next()) {
                    return gson.fromJson(rs.getString("pollObj"), Poll.class);
                }
            }
        }
        return null;
    }

    /**
     * Updates database's poll object with new data
     *
     * @param pollId  ID of poll
     * @param pollObj Poll Object
     */
    public void updatePollObj(int pollId, Poll pollObj) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL); PreparedStatement pstmt = conn.prepareStatement(UPDATE_POLL_OBJECT)) {
            Gson gson = GsonFactory.createSnakeCase();
            pstmt.setString(1, (String) gson.toJson(pollObj));
            pstmt.setInt(2, pollId);

            pstmt.executeUpdate();
        }
    }

    /**
     * Retrieves the poll ID associated with a given message timestamp.
     *
     * @param messageTs the message timestamp
     *
     * @return the poll ID, or 0 if not found
     *
     * @throws SQLException general SQL exception
     */
    public int getPollId(String messageTs) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL); PreparedStatement pstmt = conn.prepareStatement(GET_POLL_ID)) {
            pstmt.setString(1, messageTs);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                }
            }
        }
        return 0;
    }

    /**
     * Sets the timeClosed field for a poll to the current time.
     *
     * @param pollId ID of the poll
     */
    public void setTimeClosed(int pollId) {
        try (Connection conn = DriverManager.getConnection(URL); PreparedStatement pstmt = conn.prepareStatement(SET_TIME_CLOSED)) {
            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            pstmt.setString(1, now.format(formatter));
            pstmt.setInt(2, pollId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieves the timeClosed field for a given poll.
     *
     * @param pollId ID of the poll
     *
     * @return timeClosed as a String, or null if not found
     *
     * @throws SQLException general SQL exception
     */
    public String getTimeClosed(int pollId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL); PreparedStatement pstmt = conn.prepareStatement(GET_TIME_CLOSED)) {
            pstmt.setInt(1, pollId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("timeClosed");
                }
            }
        }
        return null;
    }
}
