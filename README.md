# Guacamole OAuth2 Authentication Extension

An OAuth2 Authorization Code Flow extension for [Apache Guacamole](https://guacamole.apache.org/) 1.6.0. Enables Single Sign-On (SSO) with any standard OAuth2/OIDC identity provider.

Guacamole's bundled OpenID extension only supports the implicit flow (`response_type=id_token`). This extension implements the authorization code flow (`response_type=code`), which is the recommended approach for server-side applications per [OAuth 2.0 Security Best Practices](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-security-topics).

## Why this extension?

Guacamole's built-in OpenID extension has several open issues that prevent it from working with many identity providers:

- **[GUACAMOLE-1200](https://issues.apache.org/jira/browse/GUACAMOLE-1200)** — No authorization code flow. The bundled extension implements only the OAuth2 implicit flow. The implicit flow is [deprecated by the IETF](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-security-topics#section-2.1.2), and many modern IdPs (Kanidm, some Keycloak configurations) refuse to issue tokens via implicit flow entirely.

- **[GUACAMOLE-1094](https://issues.apache.org/jira/browse/GUACAMOLE-1094)** — Hard-coded `response_type=id_token`. The bundled extension cannot be configured to use `response_type=code` or `response_type=token`, causing failures with providers like AWS Cognito, GCP Identity Platform, and others that reject the `id_token` response type.

- **[GUACAMOLE-805](https://issues.apache.org/jira/browse/GUACAMOLE-805)** — Redirect loop when the `id_token` parameter is not the first fragment parameter. The bundled extension's client-side token parsing assumed a specific parameter ordering in the URL fragment, causing infinite redirect loops with some IdPs. (Fixed in Guacamole 1.2.0 for `id_token`, but this extension avoids the class of issue entirely by using server-side code exchange instead of client-side fragment parsing.)

This extension addresses all three issues by implementing the standard authorization code flow with server-side token exchange.

## How it works

1. User visits Guacamole and is redirected to the OAuth2 authorization endpoint
2. User authenticates with the identity provider
3. IdP redirects back to Guacamole with an authorization code
4. Guacamole exchanges the code for an access token (server-side)
5. Guacamole calls the userinfo endpoint to retrieve username and groups
6. User is authenticated and a Guacamole session is created

## Installation

1. Build the extension (see [Building](#building) below)

2. Copy the JAR to the Guacamole extensions directory:

   ```
   cp target/guacamole-auth-sso-oauth2-1.6.0.jar /etc/guacamole/extensions/
   ```

3. Configure `guacamole.properties` (see below)

4. Restart Guacamole (typically Tomcat):

   ```
   sudo systemctl restart tomcat9
   ```

## Configuration

Add the following to your `guacamole.properties` file:

```properties
# OAuth2 endpoints (required)
oauth2-authorization-endpoint: https://idp.example.com/oauth2/authorize
oauth2-token-endpoint: https://idp.example.com/oauth2/token
oauth2-user-info-endpoint: https://idp.example.com/oauth2/userinfo

# OAuth2 client credentials (required)
oauth2-client-id: guacamole
oauth2-client-secret: your-client-secret
oauth2-redirect-uri: https://guacamole.example.com/guacamole/

# Scopes to request (default: "email profile")
# Use space-separated values per OAuth2 spec
oauth2-scope: openid email profile

# Claim used as the Guacamole username (default: "username")
oauth2-username-claim-type: preferred_username

# Claim containing group memberships (default: "groups")
oauth2-groups-claim-type: groups

# How long state tokens remain valid, in minutes (default: 10)
oauth2-max-state-validity: 10

# Make OAuth2 the primary authentication method
extension-priority: oauth2
```

### Property reference

| Property | Required | Default | Description |
|---|---|---|---|
| `oauth2-authorization-endpoint` | Yes | — | IdP authorization URL |
| `oauth2-token-endpoint` | Yes | — | IdP token exchange URL |
| `oauth2-user-info-endpoint` | Yes | — | IdP userinfo URL |
| `oauth2-client-id` | Yes | — | OAuth2 client ID |
| `oauth2-client-secret` | Yes | — | OAuth2 client secret |
| `oauth2-redirect-uri` | Yes | — | Redirect URI (must match IdP configuration) |
| `oauth2-scope` | No | `email profile` | Space-separated scopes |
| `oauth2-username-claim-type` | No | `username` | Userinfo claim for the Guacamole username |
| `oauth2-groups-claim-type` | No | `groups` | Userinfo claim for group memberships |
| `oauth2-max-state-validity` | No | `10` | State token validity in minutes |

### TLS with internal CAs

If your IdP uses a certificate signed by an internal CA, you must import the CA into the JVM truststore used by Guacamole:

```bash
keytool -importcert -noprompt -trustcacerts \
  -alias my-internal-ca \
  -file /path/to/ca.crt \
  -keystore /path/to/custom-cacerts \
  -storepass changeit
```

Then set the JVM truststore via `CATALINA_OPTS`:

```
CATALINA_OPTS="-Djavax.net.ssl.trustStore=/path/to/custom-cacerts -Djavax.net.ssl.trustStorePassword=changeit"
```

## Building

Requires Maven 3.x and Java 11+.

The extension depends on `guacamole-auth-sso-base`, which is not published to Maven Central. Extract it from a running Guacamole 1.6.0 installation:

```bash
# Extract SSO base from the bundled OpenID extension
jar xf /path/to/guacamole-auth-sso-openid.jar guacamole-auth-sso-base-1.6.0.jar

# Install into local Maven repository
mvn install:install-file \
  -Dfile=guacamole-auth-sso-base-1.6.0.jar \
  -DgroupId=org.apache.guacamole \
  -DartifactId=guacamole-auth-sso-base \
  -Dversion=1.6.0 \
  -Dpackaging=jar \
  -DgeneratePom=true

# Build
mvn clean package
```

The built JAR is at `target/guacamole-auth-sso-oauth2-1.6.0.jar`.

A `build.sh` script is also provided for building via a containerized Maven environment (requires `kubectl`).

## Security

- **CSRF protection** — cryptographically random state tokens are generated for each login attempt and validated on callback (single-use, time-limited)
- **Server-side token exchange** — the client secret and access token never reach the browser
- **No token logging** — userinfo responses and token payloads are not written to logs
- **Connection timeouts** — HTTP calls to the IdP have 10-second connect and read timeouts

## License

Licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).
