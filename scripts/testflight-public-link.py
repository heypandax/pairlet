#!/usr/bin/env python3
"""Serve a marketing version through a TestFlight PUBLIC LINK — raw App Store Connect API.

Runs from testflight-public-link.yml (the ASC API key lives only in repo secrets), but works
anywhere these are set:
  ASC_KEY_P8 (base64 .p8) / ASC_KEY_ID / ASC_ISSUER_ID / VERSION / GROUP_NAME
  METADATA_DIR (optional, default fastlane/metadata)

Order matters and each step is idempotent, so re-dispatching after a partial failure is safe:
  1. newest VALID (ASC-processed) build of VERSION
  2. app-level TestFlight "Test Information" — Apple refuses external distribution without it:
     beta review contact (copied from the App Store review details when blank) and per-locale
     beta description + feedback email (copied from the store listing)
  3. What to Test for the build, from fastlane/metadata/<locale>/release_notes.txt
  4. find-or-create the external beta group, public link switched on — an existing public-link
     group is reused whatever its name, so a link that's already been shared never changes
  5. attach the build and submit it for Beta App Review (first build of a version is a human
     review; installs via the link start only once it clears)

These logs are PUBLIC (public repo): the tester-facing link is printed on purpose, but contact
names/emails/phones are never echoed.
"""

import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

API = "https://api.appstoreconnect.apple.com"
BUNDLE_ID = "com.panda.ccpocket"
LOCALES = ["en-US", "zh-Hans"]

KEY = base64.b64decode(os.environ["ASC_KEY_P8"]).decode()
KID = os.environ["ASC_KEY_ID"]
ISS = os.environ["ASC_ISSUER_ID"]
VERSION = os.environ["VERSION"]
GROUP_NAME = os.environ.get("GROUP_NAME") or "Public Beta"
META = os.environ.get("METADATA_DIR", "fastlane/metadata")


def token():
    now = int(time.time())
    return jwt.encode({"iss": ISS, "iat": now, "exp": now + 900, "aud": "appstoreconnect-v1"},
                      KEY, algorithm="ES256", headers={"kid": KID})


def call(method, path, payload=None):
    req = urllib.request.Request(API + path, method=method,
                                 data=json.dumps(payload).encode() if payload is not None else None)
    req.add_header("Authorization", f"Bearer {token()}")
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as r:
            body = r.read()
            return r.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def die(msg):
    print(f"::error::{msg}")
    sys.exit(1)


def errors(body):
    # terse error rendering: ASC's title/detail only, never our request payloads
    rendered = "; ".join(f"{e.get('status')} {e.get('title')}: {e.get('detail')}"
                         for e in body.get("errors", []))
    return rendered or json.dumps(body)[:300]


# ---- 1. app + newest processed build of VERSION -------------------------------------------------

st, body = call("GET", f"/v1/apps?filter[bundleId]={BUNDLE_ID}")
apps = body.get("data", [])
if st != 200 or not apps:
    die(f"app {BUNDLE_ID} not found in ASC ({st}): {errors(body)}")
app_id = apps[0]["id"]

q = urllib.parse.urlencode({"filter[app]": app_id, "filter[preReleaseVersion.version]": VERSION,
                            "sort": "-uploadedDate", "limit": 10})
st, body = call("GET", f"/v1/builds?{q}")
if st != 200:
    die(f"listing builds failed ({st}): {errors(body)}")
all_builds = body.get("data", [])
builds = [b for b in all_builds
          if b["attributes"].get("processingState") == "VALID" and not b["attributes"].get("expired")]
if not builds:
    states = [f"{b['attributes'].get('version')}:{b['attributes'].get('processingState')}" for b in all_builds]
    die(f"no processed build of {VERSION} — dispatch ios-release.yml first "
        f"(or wait out PROCESSING and re-dispatch). seen: {states or 'none'}")
build = builds[0]
build_id, build_no = build["id"], build["attributes"]["version"]
print(f"build: {VERSION} ({build_no})")

# ---- 2. app-level test information ---------------------------------------------------------------

st, body = call("GET", f"/v1/apps/{app_id}/betaAppReviewDetail")
detail = body.get("data")
if st != 200 or not detail:
    die(f"reading betaAppReviewDetail failed ({st}): {errors(body)}")
contact = detail["attributes"]
if contact.get("contactEmail") and contact.get("contactFirstName") and contact.get("contactPhone"):
    print("beta review contact: already set")
    contact_email = contact["contactEmail"]
else:
    # blank on apps that never used TestFlight external — copy what App Review already has
    st, vers = call("GET", f"/v1/apps/{app_id}/appStoreVersions?limit=5")
    src = None
    for v in vers.get("data", []):
        st2, rd = call("GET", f"/v1/appStoreVersions/{v['id']}/appStoreReviewDetail")
        a = (rd.get("data") or {}).get("attributes") or {}
        if st2 == 200 and a.get("contactEmail"):
            src = a
            break
    if not src:
        die("beta review contact is blank and there's no App Store review contact to copy — "
            "fill it once by hand: ASC > App > TestFlight > Test Information")
    patch = {"data": {"type": "betaAppReviewDetails", "id": detail["id"], "attributes": {
        k: src.get(k) for k in ("contactFirstName", "contactLastName", "contactPhone", "contactEmail")}}}
    st, body = call("PATCH", f"/v1/betaAppReviewDetails/{detail['id']}", patch)
    if st != 200:
        die(f"copying beta review contact failed ({st}): {errors(body)}")
    print("beta review contact: copied from the App Store review details")
    contact_email = src["contactEmail"]

store_desc = {}


def store_description(loc):
    # beta description sourced from the live store listing, fetched once across locales
    if not store_desc:
        _, vers = call("GET", f"/v1/apps/{app_id}/appStoreVersions?limit=5")
        for v in vers.get("data", []):
            _, ls = call("GET", f"/v1/appStoreVersions/{v['id']}/appStoreVersionLocalizations?limit=50")
            for l in ls.get("data", []):
                a = l["attributes"]
                if a.get("description"):
                    store_desc.setdefault(a["locale"], a["description"])
            if store_desc:
                break
    return store_desc.get(loc) or next(iter(store_desc.values()), None)


st, body = call("GET", f"/v1/apps/{app_id}/betaAppLocalizations?limit=50")
beta_locs = {l["attributes"]["locale"]: l for l in body.get("data", [])}
for loc in LOCALES:
    cur = beta_locs.get(loc)
    if cur and cur["attributes"].get("feedbackEmail") and cur["attributes"].get("description"):
        print(f"beta test information [{loc}]: already set")
        continue
    desc = (cur or {}).get("attributes", {}).get("description") or store_description(loc)
    if not desc:
        die(f"no store description found to seed the beta description [{loc}] — set it in ASC by hand")
    attrs = {"feedbackEmail": contact_email, "description": desc}
    if cur:
        st, body = call("PATCH", f"/v1/betaAppLocalizations/{cur['id']}",
                        {"data": {"type": "betaAppLocalizations", "id": cur["id"], "attributes": attrs}})
    else:
        st, body = call("POST", "/v1/betaAppLocalizations",
                        {"data": {"type": "betaAppLocalizations",
                                  "attributes": {**attrs, "locale": loc},
                                  "relationships": {"app": {"data": {"type": "apps", "id": app_id}}}}})
    if st not in (200, 201):
        die(f"beta test information [{loc}] failed ({st}): {errors(body)}")
    print(f"beta test information [{loc}]: filled (description from the store listing)")

# ---- 3. What to Test for this build --------------------------------------------------------------

st, body = call("GET", f"/v1/builds/{build_id}/betaBuildLocalizations?limit=50")
bbl = {l["attributes"]["locale"]: l["id"] for l in body.get("data", [])}
for loc in LOCALES:
    path = os.path.join(META, loc, "release_notes.txt")
    if not os.path.exists(path):
        print(f"what to test [{loc}]: no {path}, skipped")
        continue
    text = open(path, encoding="utf-8").read().strip()[:4000]
    if loc in bbl:
        st, body = call("PATCH", f"/v1/betaBuildLocalizations/{bbl[loc]}",
                        {"data": {"type": "betaBuildLocalizations", "id": bbl[loc],
                                  "attributes": {"whatsNew": text}}})
    else:
        st, body = call("POST", "/v1/betaBuildLocalizations",
                        {"data": {"type": "betaBuildLocalizations",
                                  "attributes": {"whatsNew": text, "locale": loc},
                                  "relationships": {"build": {"data": {"type": "builds", "id": build_id}}}}})
    if st not in (200, 201):
        die(f"what to test [{loc}] failed ({st}): {errors(body)}")
    print(f"what to test [{loc}]: set from {path}")

# ---- 4. external beta group with the public link -------------------------------------------------

st, body = call("GET", f"/v1/betaGroups?{urllib.parse.urlencode({'filter[app]': app_id, 'limit': 50})}")
if st != 200:
    die(f"listing beta groups failed ({st}): {errors(body)}")
external = [g for g in body.get("data", []) if not g["attributes"].get("isInternalGroup")]
group = (next((g for g in external if g["attributes"]["name"] == GROUP_NAME), None)
         or next((g for g in external if g["attributes"].get("publicLinkEnabled")), None))
if group is None:
    st, body = call("POST", "/v1/betaGroups",
                    {"data": {"type": "betaGroups",
                              "attributes": {"name": GROUP_NAME, "publicLinkEnabled": True,
                                             "publicLinkLimitEnabled": True, "publicLinkLimit": 10000},
                              "relationships": {"app": {"data": {"type": "apps", "id": app_id}}}}})
    if st != 201:
        die(f"creating beta group failed ({st}): {errors(body)}")
    group = body["data"]
    print(f"beta group: created \"{group['attributes']['name']}\"")
elif not group["attributes"].get("publicLinkEnabled"):
    st, body = call("PATCH", f"/v1/betaGroups/{group['id']}",
                    {"data": {"type": "betaGroups", "id": group["id"],
                              "attributes": {"publicLinkEnabled": True,
                                             "publicLinkLimitEnabled": True, "publicLinkLimit": 10000}}})
    if st != 200:
        die(f"enabling the public link failed ({st}): {errors(body)}")
    group = body["data"]
    print(f"beta group: \"{group['attributes']['name']}\" public link switched on")
else:
    print(f"beta group: reusing \"{group['attributes']['name']}\" — its already-shared link stays stable")

public_link = group["attributes"].get("publicLink")
if not public_link:  # link minting can lag the enable by a beat
    time.sleep(3)
    _, body = call("GET", f"/v1/betaGroups/{group['id']}")
    public_link = body.get("data", {}).get("attributes", {}).get("publicLink")
if not public_link:
    die("group reports publicLinkEnabled but no link — check ASC > TestFlight by hand")

# ---- 5. attach the build, submit for Beta App Review ---------------------------------------------

st, body = call("POST", f"/v1/betaGroups/{group['id']}/relationships/builds",
                {"data": [{"type": "builds", "id": build_id}]})
if st in (200, 204):
    print(f"build {VERSION} ({build_no}): attached to the group")
elif "already" in errors(body).lower():
    print(f"build {VERSION} ({build_no}): already in the group")
else:
    die(f"attaching the build failed ({st}): {errors(body)}")

st, body = call("GET", f"/v1/betaAppReviewSubmissions?{urllib.parse.urlencode({'filter[build]': build_id})}")
subs = body.get("data", [])
if subs:
    print(f"beta app review: already submitted — state {subs[0]['attributes'].get('betaReviewState')}")
else:
    st, body = call("POST", "/v1/betaAppReviewSubmissions",
                    {"data": {"type": "betaAppReviewSubmissions",
                              "relationships": {"build": {"data": {"type": "builds", "id": build_id}}}}})
    if st == 201:
        print(f"beta app review: submitted — state {body['data']['attributes'].get('betaReviewState')}")
    else:
        # a later build of an approved train can refuse with "already reviewed" — the external
        # build state below is the ground truth for whether that's fine or a real failure
        print(f"beta app review: submission refused ({st}): {errors(body)}")

_, body = call("GET", f"/v1/builds/{build_id}/buildBetaDetail")
ext_state = (body.get("data") or {}).get("attributes", {}).get("externalBuildState")
print(f"external build state: {ext_state}")
if ext_state in ("READY_FOR_BETA_SUBMISSION", "MISSING_EXPORT_COMPLIANCE", "PROCESSING_EXCEPTION"):
    die(f"build never reached beta review (state {ext_state}) — see the submission error above")

summary = f"""## TestFlight public link

**{public_link}**

- group: {group['attributes']['name']}
- build: {VERSION} ({build_no})
- external build state: {ext_state}

Installs start once Beta App Review clears (first build of a version is a human review, usually
well under a day; until then the link page says it isn't accepting new testers).
"""
print("\n" + summary)
if os.environ.get("GITHUB_STEP_SUMMARY"):
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
        f.write(summary)
