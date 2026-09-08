#!/usr/bin/env python3
"""Create Developer ID (MAC_APP_DIRECT) provisioning profiles via the App Store
Connect API and embed them into the given .app/.appex bundles.

Why this exists: a sandboxed macOS app distributed with Developer ID that
declares an App Group must ship an embedded provisioning profile to authorize
that entitlement. Without it macOS refuses to spawn the bundle — RunningBoard
"Launchd job spawn failed", NSPOSIXErrorDomain 163. `xcodebuild -exportArchive`
with method=developer-id does NOT embed one (even with -allowProvisioningUpdates),
so we create and embed them ourselves, then re-sign to seal them.

A profile is bound to one App ID, so each bundle (app + share extension) needs
its own. The App Group does not need to be listed *inside* the macOS profile —
the profile only has to exist and match the bundle ID + signing certificate.

Reuses any existing, active profile referencing the team's Developer ID
Application certificates. Only creates a new profile if none exists, has
expired, or certs changed. Deleting and immediately recreating profiles every run
causes eventual consistency race conditions in App Store Connect (returning 500
UNEXPECTED_ERROR).

Deps: pyjwt, cryptography. Usage:
  embed_devid_profiles.py --p8 KEY.p8 --key-id KID --issuer ISS --team TEAMID \
    --bundle com.carlom.Klardrop=/path/Klardrop.app/Contents/embedded.provisionprofile \
    --bundle com.carlom.Klardrop.MacShare=/path/.../KlardropMacShare.appex/Contents/embedded.provisionprofile
"""
import sys, time, json, base64, argparse, urllib.request, urllib.error, urllib.parse, os
from datetime import datetime, timezone, timedelta
import jwt

API = "https://api.appstoreconnect.apple.com"


def make_token(p8_text, kid, iss):
    return jwt.encode(
        {"iss": iss, "iat": int(time.time()), "exp": int(time.time()) + 1200,
         "aud": "appstoreconnect-v1"},
        p8_text, algorithm="ES256", headers={"kid": kid, "typ": "JWT"})


def api(method, path, tok, body=None, retries=4, backoff=3.0):
    data = json.dumps(body).encode() if body is not None else None
    for attempt in range(retries + 1):
        req = urllib.request.Request(API + path, data=data, method=method,
            headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req) as r:
                return json.load(r) if r.length != 0 else {}
        except urllib.error.HTTPError as e:
            err_body = e.read().decode()
            if e.code in (429, 500, 502, 503, 504) and attempt < retries:
                wait_time = backoff * (2 ** attempt)
                print(f"ASC API {method} {path} -> {e.code}: retrying in {wait_time:.1f}s (attempt {attempt + 1}/{retries})...",
                      file=sys.stderr, flush=True)
                time.sleep(wait_time)
                continue
            sys.exit(f"ASC API {method} {path} -> {e.code}: {err_body}")


def parse_iso(iso_str):
    if not iso_str:
        return None
    try:
        return datetime.fromisoformat(iso_str.replace("Z", "+00:00"))
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--p8", required=True)
    ap.add_argument("--key-id", required=True)
    ap.add_argument("--issuer", required=True)
    ap.add_argument("--team", required=True)
    ap.add_argument("--force-recreate", action="store_true",
                    help="force deletion and recreation of profiles even if active")
    ap.add_argument("--bundle", action="append", required=True, metavar="ID=PATH",
                    help="bundleId=embedded.provisionprofile output path")
    a = ap.parse_args()
    tok = make_token(open(a.p8).read(), a.key_id, a.issuer)
    now = datetime.now(timezone.utc)

    # Reference unexpired Developer ID Application certs so the profile is valid
    # whichever one ends up signing.
    cert_ids = []
    for c in api("GET", "/v1/certificates?limit=200", tok)["data"]:
        if c["attributes"].get("certificateType") != "DEVELOPER_ID_APPLICATION":
            continue
        exp = parse_iso(c["attributes"].get("expirationDate"))
        if exp and exp <= now:
            continue
        cert_ids.append(c["id"])

    if not cert_ids:
        sys.exit("no valid DEVELOPER_ID_APPLICATION certificate in the account")

    # Map identifier -> db id with an EXACT match. filter[identifier] is a prefix match, so
    # querying "com.carlom.Klardrop" also returns "...MacShare"/"...Share" — match exactly here
    # or both profiles end up bound to the wrong bundle id.
    bid_by_identifier = {b["attributes"]["identifier"]: b["id"]
                         for b in api("GET", "/v1/bundleIds?limit=200", tok)["data"]}

    for spec in a.bundle:
        bundle_id, out = spec.split("=", 1)
        name = f"{bundle_id} DevID (managed)"

        bid_db = bid_by_identifier.get(bundle_id)
        if not bid_db:
            sys.exit(f"bundle id {bundle_id} not registered in the account")

        # Check for existing profile
        existing_profiles = api(
            "GET",
            f"/v1/profiles?filter[name]={urllib.parse.quote(name)}&include=certificates&limit=200",
            tok
        ).get("data", [])

        profile_content = None
        if not a.force_recreate:
            for p in existing_profiles:
                attrs = p.get("attributes", {})
                if attrs.get("profileType") != "MAC_APP_DIRECT":
                    continue
                if attrs.get("profileState") != "ACTIVE":
                    continue

                exp = parse_iso(attrs.get("expirationDate"))
                if exp and exp <= now + timedelta(days=7):
                    continue  # Expired or expiring soon

                rel_certs = {c["id"] for c in p.get("relationships", {}).get("certificates", {}).get("data", [])}
                if not rel_certs.intersection(cert_ids):
                    continue  # Does not reference any active cert

                content = attrs.get("profileContent")
                if not content:
                    full_p = api("GET", f"/v1/profiles/{p['id']}", tok)
                    content = full_p.get("data", {}).get("attributes", {}).get("profileContent")

                if content:
                    profile_content = content
                    print(f"reusing active {name} -> {out}", flush=True)
                    break

        if not profile_content:
            # Need to create a new profile. Delete any stale same-named profiles first.
            deleted_any = False
            for p in existing_profiles:
                api("DELETE", f"/v1/profiles/{p['id']}", tok)
                deleted_any = True

            # If we deleted profiles, give Apple's backend a brief moment to settle
            # to avoid 500 UNEXPECTED_ERROR eventual consistency conflicts.
            if deleted_any:
                time.sleep(3)

            created = api("POST", "/v1/profiles", tok, {"data": {
                "type": "profiles",
                "attributes": {"name": name, "profileType": "MAC_APP_DIRECT"},
                "relationships": {
                    "bundleId": {"data": {"type": "bundleIds", "id": bid_db}},
                    "certificates": {"data": [{"type": "certificates", "id": cid} for cid in cert_ids]}}}})
            profile_content = created["data"]["attributes"]["profileContent"]
            print(f"created {name} -> {out}", flush=True)

        os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
        with open(out, "wb") as f:
            f.write(base64.b64decode(profile_content))


if __name__ == "__main__":
    main()
