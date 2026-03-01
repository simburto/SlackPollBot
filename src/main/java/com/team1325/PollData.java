package com.team1325;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Data class to hold poll information.
 */
public record PollData(int id, String channelId, String question, JSONArray options, int duration, int maxResponses, int maxOptions,
        JSONObject responses, String messageTs, boolean closed, String userId) {}
