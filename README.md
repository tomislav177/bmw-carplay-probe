# BMW CarPlay Probe (`Faza 1 + 2A ✓` → `Faza 4A: Zrcaljenje + OEM CarPlay Dock`)

Samostalna Android aplikacija (`io.bimmergestalt.bmwcarplay`) koja pretvara nerootani **Samsung Galaxy S25 (`SM-S931B`, Android 17)** u bežični CarPlay pošiljatelj za **BMW X1 F48 (`HU_ENTRYNAV2` / `BMW 04990`)** — bez roota, bez Shizukua, bez Google Android Auta i uz paralelni 5G mobilni internet!

## Što donosi `v0.4.0` (Faza 4A — Zrcaljenje zaslona + OEM CarPlay Dock)
1. **Prave Google Karte, Waze, Spotify i bilo koja Android aplikacija** na punom **`1280×480 @ 60 fps`** BMW zaslonu bez Google Android Auto certifikata (`MediaProjection` + zero-copy OpenGL ES 2.0 `MirrorDockGlRenderer`).
2. **Dva OEM rasporeda bez izobličenja (`0 %` distorzije — kružni tokovi ostaju savršeni krugovi!)**:
   - **Smart Crop + tanak OEM Dock (`122 px` ≈ `20 mm`)**: `1158×480 px` (`90,5 %` ekrana) + okomiti CarPlay dock (sat, baterija/status dodira, 4 velike ikone `~10,5 mm`, `◀ Natrag` / `⌂ Početna`).
   - **1:1 Fit + širi Dock (`240 px` ≈ `39 mm`)**: `1040×480 px` + `2×2` ikone s nazivima + ugrađene media kontrole (`⏮ ⏯ ⏭`) + `◀ Natrag` / `⌂ Početna`.
3. **Potpuna prilagodba u postavkama (primjena uživo na BMW zaslonu!)**:
   - Izbor položaja docka (**Lijevo uz vozača** ili **Desno**).
   - Klizači **Odrezivanje gore** i **Odrezivanje dolje** (`0–200 px`, spremljeno posebno po načinu prikaza) uz točan matematički inverz dodira.
   - **4 korisnički prilagodljive aplikacije u docku** — sam biraš bilo koje 4 instalirane aplikacije sa svog mobitela uz pretragu i prikaz njihovih stvarnih ikona.
4. **BMW Dodir, iDrive kotačić i tipke na volanu** (`BmwTouchAccessibilityService` + `BmwMirrorInputRouter`):
   - Dodir, povlačenje (pan karte) i dugi pritisak na BMW zaslonu izravno upravljaju zrcaljenom aplikacijom.
   - Okretanje iDrive kotačića pomiče plavi fokus kroz 4 ikone u docku, pritisak pokreće odabranu aplikaciju; iDrive `Back`/`Home` i tipke volana za medije rade izravno.
5. **Stalni potpis (`app/debug.keystore`) + BMW `MENU` povratak + Automatsko spajanje**: instalira se izravno preko `v0.3.0` bez deinstalacije!

## Potvrđena osnova uživo u BMW X1 F48 (`v0.1.7`–`v0.2.2`)
- **Automatsko bežično spajanje (0,5–1,6 s)** preko BT RFCOMM (`...cafe` / `...291a` / `...0a77`), iAP2 Link, MFi CP2.0C 20 B, `0x1D01` (`AL07FFL0665583`), `0x5702 -> 0x5703` Wi-Fi (`BMW 04990 CarPlay`, 2,4 GHz ch 11, 72 Mbps).
- **Paralelni 5G internet** (`WifiNetworkSpecifier` bez `bindProcessToNetwork`).
- **Kriptirani AirPlay RTSP + H.264 `1280×480 @ 60 fps`** (Full HomeKit Pairing, ChaCha20-Poly1305, `VirtualIap2Tunnel` za 0,37 s, **8+ min u vožnji na 60–62 fps bez prekida**).
- **1:1 BMW Touchscreen (`5 B`) + 4-smjerni iDrive kotačić (`4 B`)** preko `top-level` `hidSendReport`.

## Izgradnja i testovi
```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Gotovi Debug APK za sideload automatski se objavljuje na GitHub Release tagu [`latest_build`](https://github.com/tomislav177/bmw-carplay-probe/releases/tag/latest_build).

## Povijest verzija
- **v0.1.3–v0.1.7 (Faza 1 ✓)** — BT RFCOMM, iAP2, MFi CP2.0C 20 B, `0x5703`, foreground `WifiNetworkSpecifier` uz 5G, `GET /info` 200 OK.
- **v0.2.0–v0.2.2 (Faza 2A ✓)** — `1280×480 @ 60 fps` H.264, `VirtualIap2Tunnel`, potvrđeni 1:1 BMW Touch + 4-smjerni iDrive kotačić i snimljeni `MENU` povratak.
- **v0.3.0 (Faza 3A — arhiviran AAP test)** — potvrđeno doma na S25 da Google Android Auto (`127.0.0.1:5277`) odgovara `AAP 1.7`, ali strogo zahtijeva zatvoreni Google Automotive Link CA certifikat za mTLS; uvedeni stalni potpis (`app/debug.keystore`), `MENU` povratak i `Auto-connect`.
- **v0.4.0 (Faza 4A — aktualno)** — očišćen AAP kod; ugrađeni `MediaProjection` + OpenGL ES 2.0 `MirrorDockGlRenderer`, Smart Crop (`122 px`) & 1:1 Fit (`240 px`) CarPlay Dock, Lijevo/Desno, klizači rezanja Gore/Dolje, birač 4 aplikacije i `BmwTouchAccessibilityService` za BMW dodir, iDrive i tipke volana.

## Sljedeći koraci
- **Faza 4A (`v0.4.0`) — živi test u BMW X1 F48**: provjera zrcaljenja Google Karti/Spotifyja, oba Docka, klizača rezanja, BMW dodira i iDrive kotačića.
- **Faza 4B (`v0.5.0`)**: Nativni CarPlay Wi-Fi zvuk (RTSP Audio `type=100/102`, 48 kHz PCM preko ChaCha20 UDP RTP-a) izravno na zvučnike BMW-a.
