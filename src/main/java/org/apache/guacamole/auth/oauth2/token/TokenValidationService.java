/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.guacamole.auth.oauth2.token;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.apache.guacamole.auth.oauth2.conf.ConfigurationService;
import org.apache.guacamole.auth.oauth2.OAuth2UserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the OAuth2 token exchange and user info retrieval.
 */
public class TokenValidationService {

    private static final Logger logger = LoggerFactory.getLogger(TokenValidationService.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    /** HTTP baglanti ve okuma zaman asimi (milisaniye) */
    /** HTTP connection and read timeout (milliseconds) */
    private static final int HTTP_TIMEOUT_MS = 10_000;

    @Inject
    private ConfigurationService confService;

    /**
     * Retrieves user information from the OAuth2 provider's userinfo endpoint.
     *
     * @param accessToken The access token issued by the OAuth2 provider.
     * @return User info containing username and groups.
     * @throws Exception If the user info cannot be retrieved.
     */
    public OAuth2UserInfo getUserInfoFromToken(String accessToken) throws Exception {

        URI userInfoUri = confService.getUserInfoEndpoint();
        URL url = userInfoUri.toURL();

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Accept", "application/json");

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                logger.error("UserInfo endpoint returned HTTP {}", responseCode);
                throw new Exception("Failed to retrieve user info. HTTP " + responseCode);
            }

            JsonNode json;
            try (InputStream responseStream = connection.getInputStream()) {
                json = mapper.readTree(responseStream);
            }

            logger.debug("UserInfo response received with {} fields", json.size());

            // Extract username
            String usernameClaim = confService.getUsernameClaimType();
            JsonNode usernameNode = json.get(usernameClaim);
            if (usernameNode == null || usernameNode.isNull()) {
                throw new Exception("Username claim '" + usernameClaim
                        + "' not found in user info response.");
            }
            String username = usernameNode.asText();

            // Extract groups (optional)
            Set<String> groups = new HashSet<>();
            String groupsClaim = confService.getGroupsClaimType();
            JsonNode groupsNode = json.get(groupsClaim);
            if (groupsNode != null && groupsNode.isArray()) {
                for (JsonNode group : groupsNode) {
                    groups.add(group.asText());
                }
            }

            return new OAuth2UserInfo(username, groups);

        } finally {
            connection.disconnect();
        }
    }

    /**
     * Exchanges an authorization code for an access token.
     *
     * @param authorizationCode The authorization code from the IdP callback.
     * @return The access token string.
     * @throws Exception If the exchange fails.
     */
    public String exchangeCodeForToken(String authorizationCode) throws Exception {

        URI tokenUri = confService.getTokenEndpoint();
        URL url = tokenUri.toURL();

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            // URL-encode all parameter values
            String body = "grant_type=authorization_code"
                    + "&code=" + URLEncoder.encode(authorizationCode, StandardCharsets.UTF_8.name())
                    + "&redirect_uri=" + URLEncoder.encode(confService.getRedirectURI().toString(), StandardCharsets.UTF_8.name())
                    + "&client_id=" + URLEncoder.encode(confService.getClientID(), StandardCharsets.UTF_8.name())
                    + "&client_secret=" + URLEncoder.encode(confService.getClientSecret(), StandardCharsets.UTF_8.name());

            try (OutputStream os = connection.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                // Read error body for diagnostics
                try (InputStream errorStream = connection.getErrorStream()) {
                    if (errorStream != null) {
                        JsonNode errorJson = mapper.readTree(errorStream);
                        logger.error("Token exchange failed HTTP {}: {}", responseCode, errorJson);
                    }
                }
                throw new Exception("Failed to exchange authorization code. HTTP " + responseCode);
            }

            JsonNode jsonResponse;
            try (InputStream responseStream = connection.getInputStream()) {
                jsonResponse = mapper.readTree(responseStream);
            }

            if (jsonResponse.has("access_token")) {
                return jsonResponse.get("access_token").asText();
            } else {
                logger.error("Token response missing access_token field");
                throw new Exception("Access token not found in the response.");
            }

        } finally {
            connection.disconnect();
        }
    }

}
