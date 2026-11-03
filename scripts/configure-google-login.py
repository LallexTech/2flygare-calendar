"""Import a downloaded Google web client into the linked Vercel Preview environment.
Credentials stay in memory and stdin; values and provider output are never printed.
"""
import argparse
import json
import pathlib
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("client_file", type=pathlib.Path)
args = parser.parse_args()
linked = json.loads(pathlib.Path(".vercel/project.json").read_text())
if linked["projectId"] != "prj_f1DwMdBGuPr65lep3iAljszdRaBp":
    raise SystemExit("Refusing to change an unexpected Vercel project.")
client = json.loads(args.client_file.read_text())["web"]
callback = "https://2flygarecalender-pre.vercel.app/login/oauth2/code/google"
print("Expected Preview callback:", callback)
for key, value in [("GOOGLE_CLIENT_ID", client["client_id"]),
                   ("GOOGLE_CLIENT_SECRET", client["client_secret"]),
                   ("GOOGLE_LOGIN_ENABLED", "true")]:
    base = ["npx.cmd", "--yes", "vercel@latest", "env"]
    flags = ["--scope", "alexsjogren-7420s-projects"]
    result = subprocess.run(base + ["add", key, "--environment", "preview", "--type",
                            "secret" if key.endswith("SECRET") else "config"] + flags,
                            input=value, text=True, capture_output=True)
    if result.returncode:
        result = subprocess.run(base + ["update", key, "--environment", "preview", "--type",
                                "secret" if key.endswith("SECRET") else "config", "--yes"] + flags,
                                input=value, text=True, capture_output=True)
    if result.returncode:
        raise SystemExit(key + ": update failed; credential values withheld.")
    print(key + ": configured for Preview")
