# OwnerNote — production check (20 Oct 2026)

Branch `claude/production-check`, cut from `master` at 68c3be0. main and master are not touched.
What could be checked here (code, Kai logic, strings, build config) is checked. Anything that needs a real phone or the
Play Console is listed under **Needs a phone / Play Console** at the end.

Legend: ✅ pass · ⚠️ works but has a gap · ❌ fails / blocker · 📱 needs a real phone or the Play Console

---

## 0. Release blockers (fix before upload)

| # | What | Why it matters | Fix (owner decides) |
|---|------|----------------|---------------------|
| B1 ❌ | **The release keystore `OwnerNote Keystore/ownernote.jks` and its passwords are in the git repo, and the repo is PUBLIC** (`vijayrajadurai/OwnerNote`, visibility: public). `app/build.gradle.kts` has `storePassword` / `keyPassword` in plain text. | Anyone can sign an APK as OwnerNote. If this key is the Play **upload** key, they could try to push updates. | 1. Make the repo private. 2. In Play Console → App integrity → request an **upload key reset** and make a new key. 3. Keep the new `.jks` and passwords out of git (`local.properties` / env vars). Removing the file in a new commit is not enough — it stays in history. |
| B2 ❌ | **TTS proxy key `newonx2026secret` is in `app/build.gradle.kts`** (public repo). | Anyone can call your Sarvam TTS proxy and use up your quota. | Change the key on the Vercel proxy, keep the new one out of git. (Any key shipped inside an APK can be pulled out, so also limit the proxy per device / per day.) |
| B3 ⚠️ | Subscription is a stub: `BillingConfig.IS_BILLING_ENABLED = false`, subscribe / manage are TODO. The More → Subscription screen shows plans as unavailable. | Play review is fine with it disabled, but don't promise paid plans in the listing. | Either hide the Subscription entry for launch, or connect Play Billing later. |

---

## 1. Functional testing

### Buttons and screens (code audit)
- ✅ Every `Routes` constant has a NavHost destination (VoiceEntry / VoiceEntryScan are registered in a loop).
- ✅ Every More-menu entry opens a screen. No button with an empty `onClick` (one disabled dropdown item only, on purpose).
- ✅ Default no-op callbacks on screens are all wired in `ShopAiApp`.
- ⚠️ Mic permission refused → Kai Chat and Morning work did nothing (silent mic). **Fixed on this branch:** a toast now says "Microphone permission is required." / "மைக்ரோஃபோன் அனுமதி தேவை."

**More menu (the buttons that are needed)**

| Group | Button (id) | Opens |
|-------|-------------|-------|
| Main | Profile (`profile`) | Profile |
| Main | Categories (`categories`) | Inventory |
| Main | Transactions (`transactions`) | Billing |
| Main | Reports (`reports`) | AI Insights |
| Main | Reminders (`reminders`) | Reminders |
| Main | Notifications (`notifications`) | Notifications |
| Main | Settings (`settings`) | Settings |
| Main | Help (`help`) | Help |
| Business tools | Ask business (`ask_business`) | Kai business Q&A |
| Business tools | Group buying (`group_buying`) | Group buying |
| Business tools | Handwritten note (`handwritten`) | Note scan |
| Business tools | Voice entry (`voice`) | Voice entry |
| Business tools | Subscription (`subscription`) | ⚠️ stub (B3) |
| Business tools | Old-list import (`party_import`) | List import (also the Import pill on Customers) |
| Business tools | HSN master (`hsn`) | HSN |
| Business tools | Books settings (`books_settings`) | Books settings |
| Business tools | Offers (`offers`) | Offers |
| Business tools | About (`about`) | About |
| Business tools | Privacy (`privacy`) | Privacy policy |

Daily-use set (what an owner needs every day): Home Kai card, Customers (to collect), Suppliers (to pay), Stock,
Reminders, Daily Sales Note, Day close. Rarely used, could move lower: Group buying, Offers, HSN master, Books settings, About.

### Language (Tamil / Tanglish / English)
- ✅ English 1431 strings; Tamil was missing 32 (Customers / Suppliers / party history / txn chooser / home net position…).
  **Fixed on this branch** — all 32 now have Tamil, using the app's own words (vara vendiyathu / kodukkanum), no format mismatches.
- ✅ Kai answers in the language the owner used: Tamil script in → Tamil reply; Tanglish in → Tanglish; English in → English (probe, typed and mic).
- ⚠️ **Kai does not convert an entry to Tamil.** A name / note typed in English stays as typed ("Kumar", "Colgate"); screens show the
  app's language around it. Converting names to Tamil letters would be a new feature — not done (your rule: ask before core changes).
- ⚠️ Tamil text uses the phone's own Tamil font (the app's fonts are Inter / Outfit, Latin only). Looks fine on Pixel / Samsung;
  📱 check on a cheap phone.

### Credit / debit, stock, reminders, GST, expiry — see section 7 (Kai) for the never-fed-words test

---

## 2. Unit / integration / regression

| Suite | Result |
|-------|--------|
| Kai test classes (JVM harness, 86 classes) | SEE_SUITE |
| GST (`BooksTaxTest` 12 + new `BooksGstReleaseTest` 8) | ✅ 20 / 20 |
| Old-list import (`PartyListImportTest`) | ✅ 3 / 3 |
| Room / Compose / instrumented tests | 📱 need Android SDK — run `./gradlew test connectedAndroidTest` in Android Studio |

Known failures already on main (not new): `KaiCatalogueShopTest`, `KaiBusinessBrainTest.cashBookAnswers`.

---

## 3. Real device — 📱
Not possible here. Test on: one Android 8/9 phone (minSdk 26), one Android 13/14 (notification permission), one Android 15/16
(targetSdk 36). Check: mic in a noisy shop, reminder alarm with screen locked + DND, battery saver (Xiaomi / Oppo / Vivo kill
background alarms — ask the owner to allow "autostart"), small screen 5", Tamil font, dark mode off.

---

## 4. Security / privacy

| Check | Result |
|-------|--------|
| Secrets in code | ❌ B1 keystore + passwords, ❌ B2 TTS key (public repo). `google-services.json` is normal to ship, but restrict its API key to the app's package + SHA-1 in Google Cloud. |
| Logs | ✅ OCR / bill logs only in DEBUG builds; no phone numbers or amounts logged in release. |
| `android:usesCleartextTraffic="true"` | ⚠️ All real URLs are https. It is on only for `-PAPI_BASE_URL=http://<lan-ip>` debug testing. Better: move it to a debug-only manifest / network-security-config. |
| `android:allowBackup="true"` | ⚠️ The books DB goes to the owner's Google backup (good for a new phone) — owner decides. No `dataExtractionRules` file. |
| Exported components | ✅ Only MainActivity (launcher) and the reCAPTCHA return link (`owner-note.firebaseapp.com`). Reminder screen, FileProvider not exported. |
| Permissions | ✅ Ad-ID permissions removed. ⚠️ Location (coarse + fine), Contacts, Camera, Mic, exact alarm, full-screen intent all need a reason in the Play **Data safety** form and permission declaration (USE_EXACT_ALARM / full-screen intent need the "alarm / reminder app" declaration). |
| Per-shop data | ✅ Books open per business; Kai's private memory is opened per (shop, owner). |

## 5. Performance
- ✅ First Kai mic line 3373 ms → 233 ms (PR #133, waiting for your ok — not on this branch).
- ⚠️ Server `shop-ai-api.onrender.com`: a sleeping Render server takes ~30–50 s on the first call. Bill-reading waits 8 s then uses
  the phone's own reading, so the app doesn't hang — but server features are slow first thing in the morning.
- 📱 Cold start, scroll jank, memory: Android Studio Profiler / Play pre-launch report.

## 6. Data integrity
- ✅ Nothing is written before Confirm (every Kai probe line checks it).
- ✅ Postings carry a `clientKey` → a double tap / retry never posts twice; import skips people already in the books.
- ✅ Same-day duplicate entry → Kai warns ("records-la already ₹4,000 irukku — adhey-aa, illa pudhu-aa?").
- ✅ Room migrations 1→5 present, no destructive fallback. Books DB is version 1 (next schema change needs a migration).
- ✅ Opening balance guard (`OPENING_EXISTS`).

---

## 7. AI (Kai) — words never fed to Kai, typed and as the ta-IN mic writes them

Probe: a shop with Kumar ₹4,000, Ramesh ₹2,500, Selvam ₹6,000, Lakshmi ₹1,000 to collect; ABC Traders ₹10,000, Murugan Stores
₹4,000 to pay; Colgate 240, Rice 250 kg, Oil 240 bottles, Baniyan 50, Lux 60, Maggi 0 (low). Every line played typed and in Tamil
script; checked in the books / stock / reminders, never on Kai's words alone.

### ✅ Works (typed and mic)
- Credit given: "Selvam 450 baaki solli vaanginaan", "Lakshmi 1200 credit-la eduthutu ponaanga", "gave 900 on credit to Ramesh",
  "குமாருக்கு ஐநூறு ரூபாய் கடன் கொடுத்தேன்".
- Payment in: "Kumar rendaayiram thanthaan", "Selvam ezhunooru thiruppi koduthaan", "Lakshmi paid 500 by phonepe" (typed, UPI).
- Payment out / debit: "Murugan Stores-ku 3000 anuppiten", "paid 4000 to ABC Traders" (typed), "ABC Traders kitta 7000 maal kadanaa eduthen".
- Stock with units: "Colgate 2 dozen vandhuruchu" (+24), "Rice 500 gram vandhuchu" (+0.5 kg), "Rice 1.5 kg kettu pochu", "sold 6 lux soap",
  new product "Dettol 5 vandhuchu" (asks what it needs, then adds).
- A stock line with a verb Kai never heard ("kondu vandhaar", "irakkunom", "sindhiduchu", "thiruppi koduthen", "250 gram eduthen") →
  Kai asks "vandhucha, illa pochaa?" — never guesses — and the owner's one word finishes it right (11 / 12, typed and mic).
- Reminders: "30 nimisham kazhichu tea kudikka sollu", "ovvoru Monday … kadai suththam" (repeats), "remind me on Friday 6 pm to pay ABC Traders".
- Questions: who owes most, Murugan Stores due, "theerapora saamaan enna" → Maggi, total to collect ₹13,500, total to pay ₹14,000,
  off-topic "IPL score" (no money read out).
- Clarifying: "Kumar 500" → asks which way; "Ramesh-ku kadan kuduthen" → asks amount, "600" → draft → saved ₹600; "Colgate vandhuchu" →
  asks how many; "remind pannu" → asks what / when. Kai does not read the owner's line back.
- Expiry: "kaalavadhi aaga pora porul enna", "indha vaaram expire aagura stock", "which items will expire this week", FEFO "endha batch
  first vikkanum". **7-day alert**: exactly the 8 batches expired or within 7 days, nothing beyond, Tamil / Tanglish / English; no
  notification when nothing is due. ✅
- Daily Sales (salon): "gold facial 900 aachu", "full kaal wax 500", "saree draping 300 cash", "oru aambala cutting" → Gents Haircut;
  "hand wax 250" → asks Full / Half arms; a ledger / stock line is never a service.
- GST: 5 / 12 / 18 / 28 % + cess, MRP-inclusive, inter-state IGST, round-off, GSTIN check digit — all hand-worked figures match.

### ❌ Wrong answer (needs a fix — Kai rule change, waiting for your ok)
| # | Said | Kai did | Should |
|---|------|---------|--------|
| K1 | "naalai kaalai 9:30-ku Kumar-kitta baaki ketkanum **nyabagapaduthu**" (typed) | read Kumar's balance, no reminder | set a reminder tomorrow 9:30 (the mic's "ஞாபகப்படுத்து" already works — typed ≠ mic) |
| K2 | "naalu naal kazhichu **kaalai 10 manikku** GST file…" (typed + mic) | reminder in 96 hours (11:00) — the time was dropped | 12 Oct 10:00 AM |
| K3 | mic "ரிமைண்ட் மீ **டுமாரோ** அட் 5 பிஎம்…" | reminder **today** 5 PM | tomorrow 5 PM (breaks the rule "an unknown day is never today") |
| K4 | mic "லட்சுமி **பெய்டு** 500 பை போன்பே" | read Lakshmi's balance, no entry | payment in ₹500 UPI (typed works — typed ≠ mic) |
| K5 | "gold facial **onnu** 900", "keratin **panninom** 3500", "pedicure **onnu** 450" | asks "evlo?" — the price was already said | draft at the price said |
| K6 | mic "ஃபுல் **கால்** வாக்ஸ் 500" | plain "Waxing" (கால் read as "call") | Full Legs Waxing (typed works — typed ≠ mic) |

### ⚠️ Asked plainly, but could understand (gaps)
- English sentences said into the Tamil mic ("ஹூ ஓஸ் மீ தி மோஸ்ட்", "விச் ப்ராடக்ட்ஸ் ஆர் ரன்னிங் லோ", "ரிசீவ்டு", "சோல்டு", "ஆடட்", "பாக்ஸஸ்") → Kai asks
  what it's about / which way. Typed English works.
- New person with only "baaki": "Vignesh 650 baaki" → asks "credit / stock / reminder?" instead of "Vignesh-ku ₹650 credit-aa?".
- Tamil service names: "kaadhu kuthu" (Ear Piercing), "mudi colour" (Hair Colour) → not known, asked.
- Expiry: "already expire aana" shows the 30-day list (expired first) instead of only expired; "paal packet" not matched to Milk (Tamil "பால்" is).

## 8. End-to-end workflow — 📱
On a phone: new install → login (OTP) → shop setup → add customer → credit ₹500 by mic → collect ₹200 → Daily Sales Note
→ Day close → share PDF → reminder fires with screen locked → uninstall / reinstall → restore from backup.

## 9. Usability / accessibility
- ✅ Money in tabular digits (green-UI branch, PR #128, not merged).
- 📱 TalkBack labels, 200 % font size, Tamil text not cut in buttons — check on a phone.

## 10. Release build / Play Store
| Item | State |
|------|-------|
| versionCode / versionName | 12 / 0.1.8 — bump for the upload |
| minSdk / targetSdk / compileSdk | 26 / 36 / 36 ✅ (latest Android) |
| R8 minify + shrinkResources | ✅ on, Crashlytics mapping kept |
| Signing | ❌ B1 |
| Release AAB, pre-launch report, ANR / crash rate, Data safety form, permission declarations, privacy policy URL, store listing in Tamil + English | 📱 Play Console |

---

### Release checklist
- [ ] B1 keystore out of the public repo, upload key reset
- [ ] B2 TTS key changed
- [ ] B3 Subscription hidden or billing on
- [ ] K1–K6 Kai fixes (after your ok)
- [x] Tamil text for every screen
- [x] Mic-denied message
- [x] GST checked by hand
- [x] Nothing written before Confirm; no double posting
- [ ] PR #133 (speed + reminder volume) merged
- [ ] Phone matrix (section 3) and end-to-end (section 8) done
- [ ] Play Console: AAB, Data safety, permission declarations, pre-launch report
