#!/usr/bin/env python3
"""Ensure APP_GROUPS (group.com.carlom.Klardrop) is enabled on the iOS app
and share-extension bundle IDs.

The nightly iOS job archives unsigned, then plants App Groups onto the
archive so -exportArchive has entitlements to keep. That only survives
export if the App ID itself has the capability — otherwise Xcode strips
it (or refuses to export) and the share extension cannot reach the host
app's shared container.

Idempotent: already-enabled + assigned groups are left alone. Deps: pyjwt,
cryptography (same as embed_devid_profiles.py).

Usage:
  ensure_ios_app_groups.py --p8 KEY.p8 --key-id KID --issuer ISS
"""
import sys, time, json, argparse, urllib.request, urllib.error
import jwt

API = "https://api.appstoreconnect.apple.com"
GROUP = "group.com.carlom.Klardrop"
BUNDLES = ("com.carlom.Klardrop", "com.carlom.Klardrop.Share")
SETTINGS = [{"key": "APP_GROUP_IDS", "options": [{"key": GROUP, "enabled": True}]}]


def make_token(p8_text, kid, iss):
    return jwt.encode(
        {"iss": iss, "iat": int(time.time()), "exp": int(time.time()) + 1200,
         "aud": "appstoreconnect-v1"},
        p8_text, algorithm="ES256", headers={"kid": kid, "typ": "JWT"})


def api(method, path, tok, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method,
        headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            return json.load(r) if r.length != 0 else {}
    except urllib.error.HTTPError as e:
        sys.exit(f"ASC API {method} {path} -> {e.code}: {e.read().decode()}")


def group_assigned(cap):
    for setting in cap.get("attributes", {}).get("settings") or []:
        if setting.get("key") not in ("APP_GROUP_IDS", "APP_GROUPS"):
            continue
        for opt in setting.get("options") or []:
            if opt.get("key") == GROUP:
                return True
    return False


def ensure(tok, identifier, bid_db):
    caps = api("GET", f"/v1/bundleIds/{bid_db}/bundleIdCapabilities", tok).get("data") or []
    existing = next((c for c in caps
                     if c.get("attributes", {}).get("capabilityType") == "APP_GROUPS"), None)
    if existing and group_assigned(existing):
        print(f"{identifier}: APP_GROUPS already assigns {GROUP}")
        return
    if existing:
        api("PATCH", f"/v1/bundleIdCapabilities/{existing['id']}", tok, {"data": {
            "type": "bundleIdCapabilities",
            "id": existing["id"],
            "attributes": {"settings": SETTINGS}}})
        print(f"{identifier}: assigned {GROUP} to existing APP_GROUPS")
        return
    api("POST", "/v1/bundleIdCapabilities", tok, {"data": {
        "type": "bundleIdCapabilities",
        "attributes": {"capabilityType": "APP_GROUPS", "settings": SETTINGS},
        "relationships": {
            "bundleId": {"data": {"type": "bundleIds", "id": bid_db}}}}})
    print(f"{identifier}: enabled APP_GROUPS with {GROUP}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--p8", required=True)
    ap.add_argument("--key-id", required=True)
    ap.add_argument("--issuer", required=True)
    a = ap.parse_args()
    tok = make_token(open(a.p8).read(), a.key_id, a.issuer)

    bid_by_identifier = {b["attributes"]["identifier"]: b["id"]
                         for b in api("GET", "/v1/bundleIds?limit=200", tok)["data"]}
    missing = [i for i in BUNDLES if i not in bid_by_identifier]
    if missing:
        sys.exit(f"bundle id(s) not registered: {', '.join(missing)}")
    for identifier in BUNDLES:
        ensure(tok, identifier, bid_by_identifier[identifier])


if __name__ == "__main__":
    main()
