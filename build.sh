#!/bin/bash
# Build guacamole-auth-sso-oauth2 for Guacamole 1.6.0
#
# Extracts guacamole-auth-sso-base from the running Guacamole pod (if not
# already cached), then builds the extension JAR using a Maven pod in k8s.
#
# Requires: kubectl with access to the cluster
# Produces: target/guacamole-auth-sso-oauth2-1.6.0.jar

set -euo pipefail

GUAC_VERSION="1.6.0"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KUBECONFIG_PATH="${KUBECONFIG:-$SCRIPT_DIR/../DevSecOps/Infra/k8s/kube.conf}"
BUILD_NS="default"
BUILD_POD="maven-builder"

echo "=== Building guacamole-auth-sso-oauth2 ${GUAC_VERSION} ==="

# 1. Extract guacamole-auth-sso-base from the running Guacamole pod.
#    This JAR is bundled inside the OpenID extension and is not published
#    to Maven Central, so we pull it from the running image.
SSO_BASE_JAR="guacamole-auth-sso-base-${GUAC_VERSION}.jar"
if [ ! -f "$SCRIPT_DIR/$SSO_BASE_JAR" ]; then
    echo "Extracting $SSO_BASE_JAR from running Guacamole pod..."
    kubectl --kubeconfig="$KUBECONFIG_PATH" exec -n guacamole deployment/guacamole -c guacamole -- \
        sh -c "cd /tmp && jar xf guacamole-auth-sso-openid.jar $SSO_BASE_JAR && cat $SSO_BASE_JAR" \
        > "$SCRIPT_DIR/$SSO_BASE_JAR"

    if [ ! -s "$SCRIPT_DIR/$SSO_BASE_JAR" ]; then
        echo "ERROR: Failed to extract $SSO_BASE_JAR"
        rm -f "$SCRIPT_DIR/$SSO_BASE_JAR"
        exit 1
    fi
    echo "Extracted $SSO_BASE_JAR"
else
    echo "Using cached $SSO_BASE_JAR"
fi

# 2. Create a temporary Maven builder pod
echo "Starting Maven builder pod..."
kubectl --kubeconfig="$KUBECONFIG_PATH" run "$BUILD_POD" -n "$BUILD_NS" \
    --image=maven:3.9-eclipse-temurin-21 \
    --restart=Never \
    --command -- sleep 600 \
    --overrides='{"spec":{"terminationGracePeriodSeconds":5}}' 2>/dev/null || true

echo "Waiting for builder pod..."
kubectl --kubeconfig="$KUBECONFIG_PATH" wait --for=condition=Ready pod/"$BUILD_POD" -n "$BUILD_NS" --timeout=120s

# 3. Copy source to the builder pod
echo "Copying source to builder pod..."
kubectl --kubeconfig="$KUBECONFIG_PATH" cp "$SCRIPT_DIR" "$BUILD_NS/$BUILD_POD:/build" 2>/dev/null

# 4. Install SSO base JAR and build
echo "Building..."
kubectl --kubeconfig="$KUBECONFIG_PATH" exec -n "$BUILD_NS" "$BUILD_POD" -- bash -c "
    set -e

    # Install the SSO base JAR as a local Maven dependency
    mvn install:install-file \
        -Dfile=/build/$SSO_BASE_JAR \
        -DgroupId=org.apache.guacamole \
        -DartifactId=guacamole-auth-sso-base \
        -Dversion=${GUAC_VERSION} \
        -Dpackaging=jar \
        -q

    # Build the extension
    cd /build
    mvn clean package -q
"

# 5. Copy the built JAR back
echo "Retrieving built JAR..."
mkdir -p "$SCRIPT_DIR/target"
kubectl --kubeconfig="$KUBECONFIG_PATH" cp \
    "$BUILD_NS/$BUILD_POD:/build/target/guacamole-auth-sso-oauth2-${GUAC_VERSION}.jar" \
    "$SCRIPT_DIR/target/guacamole-auth-sso-oauth2-${GUAC_VERSION}.jar" 2>/dev/null

# 6. Clean up builder pod
echo "Cleaning up builder pod..."
kubectl --kubeconfig="$KUBECONFIG_PATH" delete pod "$BUILD_POD" -n "$BUILD_NS" --grace-period=0 --force 2>/dev/null || true

JAR_PATH="$SCRIPT_DIR/target/guacamole-auth-sso-oauth2-${GUAC_VERSION}.jar"
if [ -f "$JAR_PATH" ] && [ -s "$JAR_PATH" ]; then
    echo ""
    echo "=== Build successful ==="
    echo "Output: $JAR_PATH"
    echo "Size: $(du -h "$JAR_PATH" | cut -f1)"
else
    echo "ERROR: Build failed — JAR not found or empty"
    exit 1
fi
