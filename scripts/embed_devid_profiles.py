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

Deletes any same-named profile first so every run gets a fresh profile that
references the certificate currently being used to sign (handles cert rotation).

Deps: pyjwt, cryptography. Usage:
  embed_devid_profiles.py --p8 KEY.p8 --key-id KID --issuer ISS --team TEAMID \
    --cert-serial 0583FE1ABC2DE2EF \
    --bundle com.carlom.Klardrop=/path/Klardrop.app/Contents/embedded.provisionprofile \
    --bundle com.carlom.Klardrop.MacShare=/path/.../KlardropMacShare.appex/Contents/embedded.provisionprofile
"""
import sys, time, json, base64, argparse, urllib.request, urllib.error, urllib.parse
import jwt

API = "https://api.appstoreconnect.apple.com"


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


def norm_serial(s):
    return s.lower().lstrip("0")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--p8", required=True)
    ap.add_argument("--key-id", required=True)
    ap.add_argument("--issuer", required=True)
    ap.add_argument("--team", required=True)
    ap.add_argument("--cert-serial", required=True,
                    help="serial of the Developer ID cert used for signing")
    ap.add_argument("--bundle", action="append", required=True, metavar="ID=PATH",
                    help="bundleId=embedded.provisionprofile output path")
    a = ap.parse_args()
    tok = make_token(open(a.p8).read(), a.key_id, a.issuer)

    # the profile must reference the same cert we sign with, or it won't authorize the signature
    certs = api("GET", "/v1/certificates?limit=200", tok)["data"]
    cert_id = next((c["id"] for c in certs
                    if c["attributes"].get("certificateType") == "DEVELOPER_ID_APPLICATION"
                    and norm_serial(c["attributes"].get("serialNumber", "")) == norm_serial(a.cert_serial)), None)
    if not cert_id:
        sys.exit(f"no DEVELOPER_ID_APPLICATION certificate with serial {a.cert_serial} in the account")

    for spec in a.bundle:
        bundle_id, out = spec.split("=", 1)
        name = f"{bundle_id} DevID (managed)"

        bids = api("GET", f"/v1/bundleIds?filter[identifier]={bundle_id}&filter[platform]=MAC_OS&limit=10", tok)["data"]
        bids = bids or api("GET", f"/v1/bundleIds?filter[identifier]={bundle_id}&limit=10", tok)["data"]
        if not bids:
            sys.exit(f"bundle id {bundle_id} not registered in the account")
        bid_db = bids[0]["id"]

        # delete same-named profiles so we always recreate against the current cert
        for p in api("GET", f"/v1/profiles?filter[name]={urllib.parse.quote(name)}&limit=200", tok)["data"]:
            api("DELETE", f"/v1/profiles/{p['id']}", tok)

        created = api("POST", "/v1/profiles", tok, {"data": {
            "type": "profiles",
            "attributes": {"name": name, "profileType": "MAC_APP_DIRECT"},
            "relationships": {
                "bundleId": {"data": {"type": "bundleIds", "id": bid_db}},
                "certificates": {"data": [{"type": "certificates", "id": cert_id}]}}}})
        content = created["data"]["attributes"]["profileContent"]
        open(out, "wb").write(base64.b64decode(content))
        print(f"embedded {name} -> {out}")


if __name__ == "__main__":
    main()
