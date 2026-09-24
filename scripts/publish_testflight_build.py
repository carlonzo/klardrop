#!/usr/bin/env python3
"""Assign an already-uploaded TestFlight build to an external beta group.

Why this exists: `xcodebuild -exportArchive ... -destination upload` only lands
the build in App Store Connect — it does not attach it to any tester group, so
it sits "Processing"/"Ready to Submit" and no external tester ever sees it.
This script waits for Apple to finish processing the just-uploaded build, then
adds it to the named external group and (if needed) submits it for Apple's
mandatory Beta App Review, which is required before external testers can
install a build for the first time on a given version.

Same App Store Connect API auth xcodebuild already uses (the API key file);
same JWT/urllib approach as scripts/embed_devid_profiles.py.

Deps: pyjwt, cryptography. Usage:
  publish_testflight_build.py --p8 KEY.p8 --key-id KID --issuer ISS \
    --bundle-id com.carlom.Klardrop --build-number 1234 --marketing-version 1.0.1 \
    --group-name "Klardrop External Testers"
"""
import sys, time, json, argparse, urllib.request, urllib.error, urllib.parse
import jwt

API = "https://api.appstoreconnect.apple.com"


def make_token(p8_text, kid, iss):
    return jwt.encode(
        {"iss": iss, "iat": int(time.time()), "exp": int(time.time()) + 1200,
         "aud": "appstoreconnect-v1"},
        p8_text, algorithm="ES256", headers={"kid": kid, "typ": "JWT"})


def api(method, path, tok, body=None, retries=4, backoff=3.0, ok_statuses=()):
    data = json.dumps(body).encode() if body is not None else None
    for attempt in range(retries + 1):
        req = urllib.request.Request(API + path, data=data, method=method,
            headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req) as r:
                return json.load(r) if r.length != 0 else {}
        except urllib.error.HTTPError as e:
            err_body = e.read().decode()
            if e.code in ok_statuses:
                print(f"ASC API {method} {path} -> {e.code} (treated as OK): {err_body}", file=sys.stderr)
                return {}
            if e.code in (429, 500, 502, 503, 504) and attempt < retries:
                wait_time = backoff * (2 ** attempt)
                print(f"ASC API {method} {path} -> {e.code}: retrying in {wait_time:.1f}s (attempt {attempt + 1}/{retries})...",
                      file=sys.stderr, flush=True)
                time.sleep(wait_time)
                continue
            sys.exit(f"ASC API {method} {path} -> {e.code}: {err_body}")


def find_app_id(bundle_id, tok):
    apps = api("GET", f"/v1/apps?filter[bundleId]={urllib.parse.quote(bundle_id)}&limit=1", tok)["data"]
    if not apps:
        sys.exit(f"no app registered for bundle id {bundle_id}")
    return apps[0]["id"]


def wait_for_build(app_id, build_number, marketing_version, tok, timeout_minutes, poll_seconds):
    deadline = time.time() + timeout_minutes * 60
    path = ("/v1/builds?filter[app]=" + app_id
            + "&filter[version]=" + urllib.parse.quote(build_number)
            + "&filter[preReleaseVersion.version]=" + urllib.parse.quote(marketing_version)
            + "&limit=1")
    while True:
        found = api("GET", path, tok)["data"]
        if found:
            state = found[0]["attributes"]["processingState"]
            print(f"build {marketing_version} ({build_number}) processingState={state}", flush=True)
            if state == "VALID":
                return found[0]["id"]
            if state in ("FAILED", "INVALID"):
                sys.exit(f"build {marketing_version} ({build_number}) finished processing as {state} — cannot publish")
        else:
            print(f"build {marketing_version} ({build_number}) not visible in App Store Connect yet", flush=True)
        if time.time() >= deadline:
            sys.exit(f"timed out after {timeout_minutes}m waiting for build {marketing_version} ({build_number}) to finish processing")
        time.sleep(poll_seconds)


def find_group_id(app_id, group_name, tok):
    groups = api("GET", f"/v1/apps/{app_id}/betaGroups?filter[name]={urllib.parse.quote(group_name)}&limit=1", tok)["data"]
    if not groups:
        sys.exit(f'no beta group named "{group_name}" on this app — create it once in App Store Connect '
                  "(TestFlight > External Testing) before the next nightly run")
    return groups[0]["id"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--p8", required=True)
    ap.add_argument("--key-id", required=True)
    ap.add_argument("--issuer", required=True)
    ap.add_argument("--bundle-id", required=True)
    ap.add_argument("--build-number", required=True, help="CFBundleVersion of the uploaded build")
    ap.add_argument("--marketing-version", required=True, help="CFBundleShortVersionString of the uploaded build")
    ap.add_argument("--group-name", required=True)
    ap.add_argument("--timeout-minutes", type=int, default=45,
                     help="how long to wait for Apple to finish processing the upload")
    ap.add_argument("--poll-seconds", type=int, default=30)
    a = ap.parse_args()

    tok = make_token(open(a.p8).read(), a.key_id, a.issuer)
    app_id = find_app_id(a.bundle_id, tok)
    build_id = wait_for_build(app_id, a.build_number, a.marketing_version, tok, a.timeout_minutes, a.poll_seconds)
    group_id = find_group_id(app_id, a.group_name, tok)

    api("POST", f"/v1/builds/{build_id}/relationships/betaGroups", tok, {
        "data": [{"type": "betaGroups", "id": group_id}]})
    print(f'build {build_id} added to beta group "{a.group_name}"', flush=True)

    # First build of a version added to an external group needs Apple's Beta App Review.
    # A prior submission for this build (e.g. Apple auto-created one on group assignment)
    # is a 409/422 here — that's not a failure, the build is already in the review pipeline.
    api("POST", "/v1/betaAppReviewSubmissions", tok, {
        "data": {"type": "betaAppReviewSubmissions",
                 "relationships": {"build": {"data": {"type": "builds", "id": build_id}}}}},
        ok_statuses=(409, 422))
    print(f"build {build_id} submitted for Beta App Review (or already submitted)", flush=True)


if __name__ == "__main__":
    main()
