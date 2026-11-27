# BMW CarPlay Probe (`Faza 1 + Faza 2A ✓` → `Faza 3: Android Auto`)

Samostalna Android aplikacija (`io.bimmergestalt.bmwcarplay`) koja pretvara nerootani **Samsung Galaxy S25 (`SM-S931B`, Android 17)** u bežični CarPlay pošiljatelj za **BMW X1 F48 (`HU_ENTRYNAV2` / `BMW 04990`)** — bez roota, bez Shizukua i uz paralelni 5G mobilni internet!

## Što je 100% potvrđeno uživo u BMW X1 F48 (`v0.2.2`)
1. **Automatsko bežično spajanje bez ponovnog uparivanja** — BT RFCOMM (`...decacafe` / `...291a` / `...0a77`), iAP2 Link Layer, jednosmjerni MFi (CP2.0C 20 B), `0x1D01` identifikacija (`AL07FFL0665583`), `0x5702 -> 0x5703` Wi-Fi vjerodajnice (`BMW 04990 CarPlay`, 2,4 GHz ch 11, 72 Mbps). Nakon prvog uparivanja BMW se sam spaja za **0,5–1,6 s**.
2. **Paralelni 5G internet** — `WifiNetworkSpecifier` veže isključivo CarPlay TCP/UDP sockete na `carNetwork`, dok 5G mobilni podaci ostaju aktivni za Google Maps, Waze i Spotify.
3. **Kriptirani AirPlay RTSP + H.264 `1280×480 @ 60 fps` Fullscreen** — Full HomeKit Pairing (`/pair-setup` + `/pair-verify`, ChaCha20-Poly1305, MTU ≤ `1422 B`), dinamički `displays[0]` iz kriptiranog `/info`, 3 neovisna keep-alivea i `6.5 Mbps` BT.709 hardverski H.264 stream (**testirano 8+ min u vožnji bez ijednog pada pri 60–62 fps!**).
4. **Virtualni Wi-Fi iAP2 handoff (`VirtualIap2Tunnel`)** — završava za **0,37 s**, nakon čega BMW gasi BT RFCOMM i trajno drži sesiju preko Wi-Fi-ja baš kao s pravim iPhoneom.
5. **1:1 Touchscreen + 4-smjerni iDrive kotačić** — potvrđeni sirovi `top-level` `hidSendReport` paketi iz BMW-a:
   - **`TouchDescriptor`** (`5 B`): `[tip][X_u16LE 0..1280][Y_u16LE 0..480]` — prati prst i povlačenje točno u piksel!
   - **`KnobDescriptor`** (`4 B`): `[Klik/Home/Back][X_s8 lijevo/desno][Y_s8 gore/dolje][Wheel_s8 okretanje]`.

## Izgradnja i testovi
```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Gotovi Debug APK za sideload automatski se objavljuje na GitHub Release tagu [`latest_build`](https://github.com/tomislav177/bmw-carplay-probe/releases/tag/latest_build).

## Povijest verzija
- **v0.1.3** — potvrđeno u pravom BMW X1 F48: BT RFCOMM, iAP2 Link, MFi (20 B izazov) i identifikacija prolaze 100%.
- **v0.1.4** — strogo poštivanje BMW prozora slanja (`maxOut=5`), `BluetoothProfileGuard`, `0xEA00` za CarPlay EA protokol, SDP UUID dijagnostika.
- **v0.1.5** — registracija Apple CarPlay SDP UUID-ova (`2d8d2466-...` i `02030302-...`) → BMW prihvatio Galaxy S25 kao CarPlay (Control v2) i poslao `0x5703` Wi-Fi vjerodajnice.
- **v0.1.6** — foreground-svjesno `WifiNetworkSpecifier` spajanje, pasivni Wi-Fi monitor, `MulticastLock`, AutoIP CIDR podrška i `GET /info`.
- **v0.1.7** — ispravak prekida Wi-Fi veze nakon ~1 s + disk crash-logger → **FAZA 1 100% POTVRĐENA U AUTU** (`172.16.222.49:7000`, `AirTunes/280.33.8`, `features = 0x6144540380`).
- **v0.2.0 (Faza 2A)** — prva živa **`1280×480 @ 60 fps`** H.264 testna slika na BMW zaslonu iz prvog pokušaja.
- **v0.2.1 (Faza 2A stabilnost)** — `VirtualIap2Tunnel` (riješen 10s prekid i trajno spremanje uređaja u BMW ConnectedDriveu — **8+ min stabilnog rada na 60–62 fps u vožnji uz 5G!**).
- **v0.2.2 (Faza 2A HID — 65/65 testova)** — fleksibilni `top-level` + `params` `hidSendReport` ekstraktor, dvokanalno naoružavanje `hidSetInputMode` (neblokirajući Event re-arm), odvojeni rozi Touch križić i žuti iDrive prikaz, utišani 5s keepalive logovi → **FAZA 2A 100% ZAVRŠENA I POTVRĐENA U AUTU!** (uživo potvrđeni 1:1 touch `(X, Y)` i svi smjerovi iDrive kotačića `LIJEVO/DESNO/GORE/DOLJE/OKRETANJE`).
- **v0.3.0 (Faza 3A — 93/93 testa)** — čisti Kotlin **Android Auto AAP klijent (`127.0.0.1:5277`)** (Version 1.6, mTLS 1.2 uz uvoz korisničkog HU certifikata `.p12`/`.pem`, ServiceDiscovery `1280×720` s `margin_height=240` → aktivnih `1280×480 @ 60 fps`, `AapGlSurfaceRenderer` hardverski H.264 GPU crop most, 1:1 BMW dodir + 4-smjerni iDrive + `SCROLL_WHEEL`, čisti `ByeBye` prekid), gumb **„Testiraj AA doma (5277)“**, prečac za **Android Auto postavke**, prekidač **„Automatski spoji pri otvaranju“** (1 klik ili Samsung Rutine), trenutni povratak iz BMW `MENU` (`requestUI` / `eNOTIFICATION_DONE` → `modesChanged` + IDR) i stalni potpis APK-a (`app/debug.keystore`) za buduće nadogradnje bez deinstalacije!

## Sljedeći koraci (`v0.3.x`)
- **Faza 3A (`v0.3.0`) — spremno za testiranje doma i u BMW X1 F48**: kućni test `127.0.0.1:5277` + živa provjera Android Auta na punom `1280×480 @ 60 fps` zaslonu, dodira, iDrive kotačića i povratka iz BMW `MENU`.
- **Faza 3B (`v0.3.1`)**: Nativni CarPlay zvuk preko Wi-Fi-ja (RTSP Audio `type=100/102`, 48 kHz PCM preko ChaCha20 UDP RTP-a) izravno na zvučnike auta.
- **Faza 3C (`v0.3.2`)**: Mikrofon auta (Google Assistant) i završno poliranje svakodnevnog iskustva.
