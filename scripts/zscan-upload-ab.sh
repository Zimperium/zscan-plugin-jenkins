#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: ZSCAN_ENDPOINT=... ZSCAN_CLIENT_ID=... ZSCAN_CLIENT_SECRET=... \
  scripts/zscan-upload-ab.sh <minimal|plugin-fields> <apk-path>

minimal       Sends buildFile and notifyUploader=false (official action payload).
plugin-fields Sends buildFile and the CI metadata fields currently sent by this plugin.

Optional environment variables:
  BRANCH_NAME             Branch value for plugin-fields mode (default: empty)
  BUILD_NUMBER            Build number for plugin-fields mode (default: empty)
  TEAM_NAME               Team to assign new apps to (default: Default)
  STATUS_TIMEOUT_SECONDS  Status-poll timeout (default: 1800)
  POLL_INTERVAL_SECONDS   Status-poll interval (default: 30)
EOF
}

if [[ $# -ne 2 ]]; then
    usage >&2
    exit 2
fi

mode=$1
apk_path=$2

if [[ "$mode" != "minimal" && "$mode" != "plugin-fields" ]]; then
    echo "Mode must be 'minimal' or 'plugin-fields'." >&2
    usage >&2
    exit 2
fi

for required_var in ZSCAN_ENDPOINT ZSCAN_CLIENT_ID ZSCAN_CLIENT_SECRET; do
    case "$required_var" in
        ZSCAN_ENDPOINT) value=${ZSCAN_ENDPOINT:-} ;;
        ZSCAN_CLIENT_ID) value=${ZSCAN_CLIENT_ID:-} ;;
        ZSCAN_CLIENT_SECRET) value=${ZSCAN_CLIENT_SECRET:-} ;;
    esac
    if [[ -z "$value" ]]; then
        echo "Required environment variable is not set: $required_var" >&2
        exit 2
    fi
done

if [[ ! -f "$apk_path" ]]; then
    echo "APK does not exist: $apk_path" >&2
    exit 2
fi

if ! command -v curl >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
    echo "This script requires curl and jq." >&2
    exit 2
fi

status_timeout=${STATUS_TIMEOUT_SECONDS:-1800}
poll_interval=${POLL_INTERVAL_SECONDS:-30}
if [[ ! "$status_timeout" =~ ^[1-9][0-9]*$ || ! "$poll_interval" =~ ^[1-9][0-9]*$ ]]; then
    echo "STATUS_TIMEOUT_SECONDS and POLL_INTERVAL_SECONDS must be positive integers." >&2
    exit 2
fi

endpoint=$ZSCAN_ENDPOINT
while [[ "$endpoint" == */ ]]; do
    endpoint=${endpoint%/}
done

tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT

request() {
    local response_file=$1
    shift
    curl --silent --show-error --output "$response_file" --write-out '%{http_code}' "$@"
}

require_success() {
    local operation=$1
    local http_code=$2
    local response_file=$3
    if [[ ! "$http_code" =~ ^2[0-9][0-9]$ ]]; then
        echo "$operation failed (HTTP $http_code):" >&2
        if jq -e . "$response_file" >/dev/null 2>&1; then
            jq . "$response_file" >&2
        else
            cat "$response_file" >&2
            echo >&2
        fi
        exit 1
    fi
}

jq -n \
    --arg clientId "$ZSCAN_CLIENT_ID" \
    --arg secret "$ZSCAN_CLIENT_SECRET" \
    '{clientId: $clientId, secret: $secret}' > "$tmp_dir/login.json"

http_code=$(request "$tmp_dir/login-response.json" \
    --request POST \
    --header 'Content-Type: application/json' \
    --data-binary @"$tmp_dir/login.json" \
    "$endpoint/api/auth/v1/api_keys/login")
require_success "Login" "$http_code" "$tmp_dir/login-response.json"

access_token=$(jq -er '.accessToken | strings | select(length > 0)' "$tmp_dir/login-response.json") || {
    echo "Login response did not contain accessToken." >&2
    exit 1
}

upload_args=(
    --request POST
    --header "Authorization: Bearer $access_token"
    --form "buildFile=@$apk_path"
)

if [[ "$mode" == "minimal" ]]; then
    upload_args+=(--form 'notifyUploader=false')
else
    upload_args+=(
        --form 'ciToolId=JKNS'
        --form 'ciToolName=Jenkins'
        --form "branchName=${BRANCH_NAME:-}"
        --form "buildNumber=${BUILD_NUMBER:-}"
    )
fi

http_code=$(request "$tmp_dir/upload-response.json" \
    "${upload_args[@]}" \
    "$endpoint/api/zdev-upload/public/v1/uploads/build")
require_success "Upload" "$http_code" "$tmp_dir/upload-response.json"

build_id=$(jq -er '.buildId | strings | select(length > 0)' "$tmp_dir/upload-response.json") || {
    echo "Upload response did not contain buildId:" >&2
    jq . "$tmp_dir/upload-response.json" >&2
    exit 1
}
echo "Upload mode: $mode"
echo "buildId: $build_id"

app_id=$(jq -r '.zdevAppId // empty' "$tmp_dir/upload-response.json")
team_id=$(jq -r '.teamId // empty' "$tmp_dir/upload-response.json")
echo "zdevAppId: ${app_id:-(none)}"

# Same behavior as the plugin: apps without a team are invisible in the console,
# so assign to TEAM_NAME (falling back to 'Default') when the upload did not set one.
if [[ -n "$team_id" ]]; then
    echo "App already belongs to team $team_id; skipping team assignment."
elif [[ -z "$app_id" ]]; then
    echo "Upload response has no zdevAppId; cannot assign a team." >&2
else
    wanted_team=${TEAM_NAME:-Default}
    http_code=$(request "$tmp_dir/teams-response.json" \
        --header "Authorization: Bearer $access_token" \
        "$endpoint/api/auth/public/v1/teams")
    require_success "Team list request" "$http_code" "$tmp_dir/teams-response.json"
    team_id=$(jq -r --arg n "$wanted_team" '[.content[]? | select(.name == $n) | .id][0] // empty' "$tmp_dir/teams-response.json")
    if [[ -z "$team_id" && "$wanted_team" != "Default" ]]; then
        echo "Team '$wanted_team' not found; trying 'Default'."
        wanted_team=Default
        team_id=$(jq -r '[.content[]? | select(.name == "Default") | .id][0] // empty' "$tmp_dir/teams-response.json")
    fi
    if [[ -z "$team_id" ]]; then
        echo "Team '$wanted_team' not found; app will not be visible in the console until assigned." >&2
    else
        jq -n --arg t "$team_id" '{teamId: $t}' > "$tmp_dir/assign.json"
        # the app record can lag the upload briefly, so retry 404s for up to ~1 minute
        for attempt in 1 2 3 4 5 6; do
            http_code=$(request "$tmp_dir/assign-response.json" \
                --request PUT \
                --header "Authorization: Bearer $access_token" \
                --header 'Content-Type: application/json' \
                --data-binary @"$tmp_dir/assign.json" \
                "$endpoint/api/zdev-app/public/v1/apps/$app_id/upload")
            [[ "$http_code" == "404" && $attempt -lt 6 ]] || break
            sleep 10
        done
        if [[ "$http_code" =~ ^2[0-9][0-9]$ ]]; then
            echo "Assigned app $app_id to team '$wanted_team' ($team_id)."
        else
            echo "Team assignment failed (HTTP $http_code); continuing:" >&2
            cat "$tmp_dir/assign-response.json" >&2; echo >&2
        fi
    fi
fi

deadline=$((SECONDS + status_timeout))
while (( SECONDS < deadline )); do
    http_code=$(request "$tmp_dir/status-response.json" \
        --get \
        --header "Authorization: Bearer $access_token" \
        --data-urlencode "buildId=$build_id" \
        "$endpoint/api/zdev-app/public/v1/assessments/status")

    if [[ "$http_code" == "404" ]]; then
        echo "Assessment status not available yet (HTTP 404); retrying."
    else
        require_success "Assessment status request" "$http_code" "$tmp_dir/status-response.json"
        analysis=$(jq -er '.zdevMetadata.analysis | strings' "$tmp_dir/status-response.json") || {
            echo "Status response missing zdevMetadata.analysis:" >&2
            jq . "$tmp_dir/status-response.json" >&2
            exit 1
        }
        echo "Scan status: $analysis"

        case "$analysis" in
            Done)
                echo "Final status payload:"
                jq . "$tmp_dir/status-response.json"
                exit 0
                ;;
            Failed)
                echo "Final status payload:" >&2
                jq . "$tmp_dir/status-response.json" >&2
                exit 1
                ;;
        esac
    fi

    sleep "$poll_interval"
done

echo "Timed out after ${status_timeout}s waiting for buildId $build_id." >&2
if [[ -f "$tmp_dir/status-response.json" ]]; then
    echo "Last status payload:" >&2
    jq . "$tmp_dir/status-response.json" >&2 || cat "$tmp_dir/status-response.json" >&2
fi
exit 1
