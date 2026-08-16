---
name: android-release
description: Cut an android release end to end — Linear release ticket, versionName bump PR, tag, watch the signed build publish to R2, verify force-upgrade stamping + prune, close tickets. Use when the user asks to release, ship, tag, or deploy the android app.
---

# Android release train (SB-351)

The full pipeline for shipping a `vX.Y.Z` release. Preconditions: `main` is
green and contains everything meant to ship.

## 0. Pre-flight

```bash
git checkout main && git pull
./gradlew -q testLocalDebugUnitTest          # must be green
grep versionName app/build.gradle.kts        # current version
```

Optional but recommended when an emulator is handy: run the `/android-e2e`
launch smoke against a fresh `assembleLocalDebug`.

## 1. Linear release ticket (user convention: every piece of work gets one)

Create via the linear-crud skill: repo `MTA`, type `chore`, title
`Android: release vX.Y.Z (<one-line summary>)`, body listing the SB tickets
shipping in it.

## 2. Version bump PR

```bash
git checkout -b chore/version-X.Y.Z main
# edit app/build.gradle.kts: versionName = "X.Y.Z"
git commit -am "chore: bump versionName to X.Y.Z (SB-<release-ticket>)" # + Co-Authored-By trailer
git push -u origin chore/version-X.Y.Z
gh pr create ...   # body: "Fixes SB-<release-ticket>" + shipped-ticket list
```

Wait for CI (`gh run watch <id> --exit-status`), then
`gh pr merge <n> --squash --delete-branch`.

## 3. Tag → release workflow

```bash
git checkout main && git pull
git tag vX.Y.Z && git push origin vX.Y.Z
```

Wait for the run to appear, then watch it:

```bash
until gh run list --workflow android-release.yml --limit 1 \
  --json databaseId,headBranch -q '.[0] | select(.headBranch=="vX.Y.Z") | .databaseId' \
  | grep -q .; do sleep 5; done
RID=$(gh run list --workflow android-release.yml --limit 1 --json databaseId -q '.[0].databaseId')
gh run watch "$RID" --exit-status
```

## 4. Verify the release did ALL of its jobs

```bash
gh run view "$RID" --log | grep -E "minversioncode=[0-9]|Pruning|Published build"
```

Must show: `minversioncode=<previous build>` (force-upgrade floor moved up,
SB-328) and `Pruning old build <n>.apk` (keep-2 policy, SB-327). If either is
missing, the release is broken — investigate before telling the user it shipped.

## 5. Close out

Move the release ticket (and any tickets that shipped) to Done via linear-crud.
Remind the user: phones on the previous release get the update banner; phones
two+ releases back get the blocking force-upgrade screen.
