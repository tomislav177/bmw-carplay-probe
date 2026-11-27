# AGENTS.md — BMW CarPlay Probe (`tomislav177/bmw-carplay-probe`)

> **Trajna memorija projekta za AI agente i buduće faze razvoja.**
> Ovo je samostalna, osobna Android aplikacija za sideload (`applicationId = "io.bimmergestalt.bmwcarplay"`).
> **STROGO PRAVILO**: Nikada ne diraj niti mijenjaj postojeći repozitorij `tomislav177/bmw` (`io.bimmergestalt.bmwmirror.v3`) niti `tomislav177/bmw-carplay-bridge`.

---

## 1. Pravila komunikacije i rada

- **Jezik komunikacije**: Uvijek odgovaraj korisniku na **hrvatskom jeziku** (jasno, konkretno, praktično).
- **Kod i komentari**: Nazivi klasa, metoda, varijabli i tehnički komentari u kodu na **engleskom**.
- **UI i dijagnostika**: Svi tekstovi u korisničkom sučelju i dijagnostičke poruke na **hrvatskom**.
- **Osobna sideload aplikacija (NIJE za Google Play Store)**:
  - `minifyEnabled = false` (bez R8/ProGuarda).
  - **0.0% potrošnje baterije u mirovanju**: bez `BOOT_COMPLETED` receivera, bez `WorkManager`-a i bez skrivenih pozadinskih servisa kada sesija nije pokrenuta.
- **Licencna higijena**: Čista vlastita Kotlin implementacija prema iAP2 / AirPlay / AAP specifikaciji i MIT referencama (`shaonialife/cardeck`, `HaToan/carplay-wifi-extractor`). Bez kopiranja GPL-3.0 koda (`xcertplay`, `DiPlay`, `DroidCar`) i bez ugrađivanja tuđih Apple/Carlinkit privatnih ključeva ili certifikata (iAP2 autentifikacija je jednosmjerna — mobitel u iPhone/sender ulozi ne treba MFi čip ni Apple certifikat).

---

## 2. Potvrđeni ciljani hardver (100% uživo verificirano u vozilu)

### Vozilo (100% potvrđeno živim logovima `v0.1.3`–`v0.2.2`)
- **Model**: BMW X1 sDrive18i (F48)
- **Head Unit**: Alpine `HU_ENTRYNAV2` (EntryEvo, model `F48-EntryEVO-0317`, firmware `EntryEVO_O2014C1R`, AirPlay `AirTunes/280.33.8`)
- **HMI Firmware**: `BMW ID6L / EntryEvo_ID5_2007_Release ID5_2007-510-2012A Build 33 - Rev:210125 2020-03-12 10:34:41`
- **Zaslon (`displays[0]` iz kriptiranog `/info`)**: **`1280×480` px @ `60 fps`** (fizički `209×78 mm`, puni BMW Fullscreen, `primaryInputDevice = 1` Touchscreen, `features = 26` = Knobs + High-Fidelity Touch + Touchpad)
- **Tvorničke opcije**: `6UN` (Navigacija), `6CP` (Apple CarPlay Preparation — **AKTIVNO**), `6NW` (Wireless Charging)
- **Hardverska činjenica**: USB-C utor ispod naslona za ruku ima **samo napajanje** (nema podatkovnih vodova do `HU_ENTRYNAV2`). Žičani CarPlay / USB adapteri ne rade. Jedini brzi video kanal je **bežični CarPlay** (`Bluetooth RFCOMM iAP2 -> Car Wi-Fi -> AirPlay H.264`).
- **Bluetooth ime auta**: `BMW 04990`
- **Bluetooth BR/EDR MAC adresa auta**: `F8:30:02:FC:79:A4`
- **CarPlay Wi-Fi AP auta (WiFiman + `0x5703` potvrđeno)**:
  - SSID: `BMW 04990 CarPlay`, BSSID: `F8:30:02:FC:79:A5` (Texas Instruments WiLink8), WPA/WPA2-PSK (32-znakovna lozinka iz `0x5703`)
  - **Frekvencija i brzina**: **2,4 GHz, kanal 11 (20 MHz), 802.11n (Wi-Fi 4), 72 Mbps PHY** (vršni kapacitet 150 Mbps), signal **`-37` do `-39 dBm`** u kabini
  - **IP podmreža**: BMW gateway `172.16.222.49:7000`, mobitel `172.16.222.50/28` (plus IPv6 link-local `fe80::fa30:2ff:fefc:79a5%wlan0`)
- **Potvrđeni iAP2 serijski broj head unita (iz `0x1D01`)**: `AL07FFL0665583` (`PPID = 2GA5JE4AXM`)
- **Audio mogućnosti auta (iz kriptiranog `/info`)**:
  - `type=100` (`default`, `telephony`, `speechRecognition`, `alert`, output latency `65 ms` / `209.6 ms`, input latency `205.5 ms`), `type=101` (`default`, `65 ms`), `type=102` (`media`, `65 ms`, `audioOutputFormats=12582912`).

### Mobitel (100% potvrđeno)
- **Model**: Samsung Galaxy S25 (`SM-S931B`), Android 17 (SDK 37), One UI, `2340×1080` OLED, **NEROOTAN** (bez roota i bez Shizukua!)
- **Stvarna Bluetooth MAC adresa mobitela**: `7C:7B:BF:74:40:67`
  - **Kritično ograničenje Androida 12–17**: `BluetoothAdapter.getAddress()` na nerootanom mobitelu uvijek vraća lažni `02:00:00:00:00:00`. Head unit uspoređuje MAC unutar iAP2 (`0x4300`, `0x4E0E`) i Bonjour `_carplay-ctrl._tcp` (`id=`) sa stvarnom BT RFCOMM adresom; lažna adresa uzrokuje tiho odbacivanje sesije. Stvarna MAC adresa se čuva u postavkama aplikacije (zadano `7C:7B:BF:74:40:67`).
- **Uređaji koje skener MORA ignorirati**: `Galaxy Watch6 Classic (M77L)` (`AC:80:FB:DA:A7:CB`) te svi pametni satovi i slušalice.
- **Google Android Auto (potvrđeno u postavkama S25)**: *Postavke razvojnog programa* otključane, `Bežični Android Auto` uključen; službeni lokalni *Head Unit Server* (`127.0.0.1:5277`) pokreće se bez roota i bez Shizukua iz izbornika `⋮ → Pokreni poslužitelj glavne jedinice`.

---

## 3. Status i plan razvoja po fazama

1. **FAZA 1 (`v0.1.3`–`v0.1.7`) — GOTOVO I 100% POTVRĐENO U AUTU ✓**:
   - BT RFCOMM (`...cafe` / `...291a` / `...0a77`), iAP2 Link Layer, jednosmjerni MFi (CP2.0C 20 B), `0x1D01` identifikacija (`AL07FFL0665583`), `0x5702 -> 0x5703` Wi-Fi vjerodajnice, foreground `WifiNetworkSpecifier` (uz aktivan 5G internet!), mDNS/Bonjour (`_carplay-ctrl._tcp`, `_device-info._tcp`, `_airplay._tcp`) i RTSP `GET /info`.
2. **FAZA 2A (`v0.2.0`–`v0.2.2`) — GOTOVO I 100% POTVRĐENO U AUTU ✓**:
   - Full HomeKit Pairing (`/pair-setup` M1–M6 + `/pair-verify` M1–M4 → ChaCha20-Poly1305, MTU ≤ `1422 B`).
   - Kriptirani `GET /info` → dinamički **`1280×480 @ 60 fps`** Fullscreen + 4 HID uređaja.
   - `modesChanged`, Screen Stream `110`, `RECORD`, 3 neovisna keep-alivea (UDP 5s, `/feedback` 1s, Video `opcode=5` `bplist00` stats 1s) + `6.5 Mbps` `BT.709` hardware `MediaCodec` H.264 stream (**dokazano 8+ minuta bez ijednog pada pri 60–62 fps u vožnji!**).
   - `VirtualIap2Tunnel` (Wi-Fi iAP2 handoff gotov za **0,37 s** → BMW trajno pamti S25 i sam se spaja za 0,5–1,6 s bez ponovnog uparivanja, uredno gasi BT RFCOMM i preuzima sve preko Wi-Fi-ja kao pravom iPhoneu).
   - **100% verificiran 1:1 Touchscreen (`1280×480`, 5B `top-level` `hidSendReport`) i 4-smjerni iDrive kotačić (4B `top-level` `hidSendReport`: `LIJEVO`, `DESNO`, `GORE`, `DOLJE`, `KOTAČIĆ +/-`, `OTPUŠTENO`)** (`session_20261127_193530.log`).
3. **FAZA 3 (`v0.3.x`) — SLJEDEĆI KORAK: Google Android Auto most (`127.0.0.1:5277`)**:
   - **Faza 3A (`v0.3.0`)**:
     - Povratak iz BMW `MENU` natrag u CarPlay (`requestUI` / `changeModes eNOTIFICATION_DONE` → automatski `modesChanged` + IDR okvir).
     - Stalni potpis APK-a u CI (da nadogradnje idu direktno preko stare verzije bez deinstalacije).
     - AAP klijent (`127.0.0.1:5277` TCP + TLS/SSL + protobuf kanali: Control, Video, Input, Sensor/NightMode) + prikaz živog **Google Android Auto** sučelja (Google Maps, Waze, Spotify) preko cijelog BMW **`1280×480 @ 60 fps`** zaslona + mapiranje BMW **Touchscreena** i **iDrive kotačića** u AAP `TouchEvent` / `RotaryEvent` / `KeyEvent`!
   - **Faza 3B (`v0.3.1`)**:
     - Nativni CarPlay zvuk preko Wi-Fi-ja (RTSP `SETUP` Audio stream `type=100/102`, 48 kHz stereo PCM preko ChaCha20 UDP RTP-a) za glazbu i glasovnu navigaciju izravno na BMW zvučnike.
   - **Faza 3C (`v0.3.2`)**:
     - Uplink mikrofona auta (tipka za glas na volanu → Google Assistant), automatski Day/Night sinkroniziran sa svjetlima BMW-a (`nightMode` + AAP Sensor kanal) i opciono 1-klik pokretanje.

---

## 4. Arhitektura paketa (`io.bimmergestalt.bmwcarplay`)

```text
app/src/main/java/io/bimmergestalt/bmwcarplay/
├── ProbeApp.kt                       # Globalni disk crash-logger (ne utječe na mirovanje)
├── config/
│   └── ProbePrefs.kt                 # Postavke (BT MAC, ciljani BMW, MFi duljina, RFCOMM, HEX, HomeKit Ed25519)
├── diag/
│   ├── DiagnosticLogger.kt           # Live log + automatsko spremanje filesDir/logs/session_*.log (zadnjih 10)
│   └── HexDump.kt                    # Čitljiv HEX/ASCII ispis i sanitizacija NUL bajtova
├── bt/
│   ├── BluetoothConstants.kt         # CarPlay SDP UUID-ovi (...cafe / ...caff / ...291a / ...0a77 / ...88ec)
│   ├── DeviceFilter.kt               # Filtriranje BMW uređaja i strogo odbacivanje satova/slušalica
│   ├── BluetoothEventMonitor.kt      # Dinamički ACL / Bond BroadcastReceiver (samo dok sesija radi)
│   ├── BluetoothProfileGuard.kt      # Provjera/odspajanje samo HFP/A2DP za BMW (nikad usred dolaznog RFCOMM-a)
│   ├── DuplexByteStream.kt           # Odvojeni RX i TX lockovi za BluetoothSocket streamove
│   └── RfcommTransport.kt            # Dvostruka RFCOMM uloga s atomskim generationId i listom socketa
├── iap2/
│   ├── link/
│   │   ├── Iap2LinkPacket.kt         # FF55/FF5A zaglavlje, checksum, LinkSyncPayload, FrameStreamScanner
│   │   ├── Iap2LinkEngine.kt         # Čisti state machine (BMW SYN/SYN|ACK, maxOut prozor, dinamički SID)
│   │   └── Iap2LinkChannel.kt        # I/O petlja + heartbeat ACK + retransmit timer
│   └── csm/
│       ├── Iap2MessageIds.kt         # Registar CSM ID-ova (0x1Dxx, 0xAAxx, 0x57xx, 0x43xx, 0x4Exx, 0x68xx, 0xEAxx)
│       ├── Iap2CsmCodec.kt           # 0x4040 okvir, TLV enkoder/dekoder, CsmReassembler, HEX/TLV dump
│       ├── Iap2Parsers.kt            # 0x1D01, X.509 certifikat, 0x5703, 0x4301, iPhone 0x1D01 builder
│       └── Iap2SessionCoordinator.kt # Cijeli BT iAP2 tok + trajni keep-alive pump
├── net/
│   ├── CarWifiConnector.kt           # Foreground WifiNetworkSpecifier (bez bindProcessToNetwork -> 5G ostaje živ!)
│   └── IpSubnetMatcher.kt            # Provjera podmreže (CIDR + 169.254/16 AutoIP)
├── airplay/
│   ├── BinaryPlist.kt                # Čisti Kotlin bplist00 enkoder/parser (unsigned portovi, root na offsetu 8)
│   ├── AirPlayFeatures.kt            # Parsiranje 64-bitnog features polja i detekcija bita 38 (HK_PAIRING)
│   ├── CarPlayCtrlServer.kt          # TCP server + NSD oglašavanje _carplay-ctrl._tcp i _device-info._tcp
│   ├── AirPlayMdnsBrowser.kt         # NSD otkrivanje _airplay._tcp vezano uz carNetwork (IPv4 + IPv6 link-local)
│   ├── AirPlayInfoClient.kt          # Neenkriptirani RTSP GET /info + fallback na gateway:7000
│   ├── crypto/
│   │   ├── Tlv8Codec.kt              # Kodiranje i spajanje fragmentiranih TLV8 zapisa (>255 B)
│   │   ├── AirPlayCrypto.kt          # ChaCha20-Poly1305, X25519, Ed25519, HKDF-SHA512, AES-128-CTR (BouncyCastle)
│   │   ├── HomeKitSrp6a.kt           # RFC 5054 3072-bit SRP-6a SHA-512 (PIN "3939")
│   │   ├── AirPlayPairingClient.kt   # Adaptivni HomeKit Pairing (Full / Transient 0x10 / Fast-Path)
│   │   └── RtspControlCipher.kt      # ChaCha20-Poly1305 RTSP kriptiranje (strogi MTU <= 1422 B)
│   └── rtsp/
│       ├── TimingServer.kt           # UDP NTP timing server (0xD2 -> 0xD3) na carNetwork
│       ├── VirtualIap2Tunnel.kt      # Virtualni Wi-Fi iAP2 handoff preko iAPSendMessage (FIFO worker)
│       ├── CarPlayHidDecoder.kt      # Fleksibilni top-level/params hidSendReport ekstraktor + BMW Touch/Knob/Media/Phone dekoder
│       └── CarPlayRtspSession.kt     # Kompletan CarPlay RTSP + Event + 3x Keep-Alive + Video orkestrator
├── video/
│   ├── CarPlayVideoFraming.kt        # 128-bajtno zaglavlje (VideoConfig/VideoFrame/KeepAlive opcode=5), avcC, SPS level 40
│   ├── CarPlayVideoSender.kt         # MediaCodec H.264 (6.5 Mbps, BT.709, B=0) -> ChaCha20 TCP dataPort
│   └── TestPatternRenderer.kt        # Faza 2A dijagnostička slika (2 px obrub, gradijenti, Touch križić + iDrive)
├── session/
│   ├── ProbeSessionState.kt          # Stanje 7 vizualnih koraka i sažetci za UI
│   └── ProbeOrchestrator.kt          # Orkestrator BT -> iAP2 -> Wi-Fi -> mDNS -> /info -> CarPlay Video
├── service/
│   └── ProbeForegroundService.kt     # ForegroundService (connectedDevice), PARTIAL_WAKE_LOCK, gumb Zaustavi
└── ui/
    └── MainActivity.kt               # Material 3 sučelje na hrvatskom, dozvole, pomoć za uparivanje, live log
```

---

## 5. Kritične BMW/iAP2/AirPlay zamke koje kod rješava (100% potvrđene uživo)

1. **Lažna BT MAC adresa (`02:00:00:00:00:00`)**: Nikad ne koristi `BluetoothAdapter.getAddress()` za protokol — koristi stvarnu spremljenu MAC adresu (`7C:7B:BF:74:40:67`).
2. **Zombi RFCOMM dretve**: Svaki `BluetoothServerSocket` i `BluetoothSocket` registrira se prije blokirajućeg `accept()`/`connect()` uz atomski `generationId`.
3. **RX/TX deadlock**: `DuplexByteStream` koristi zasebne `rxLock` i `txLock` objekte.
4. **BMW višestruki čisti `SYN`**: Svaki novi čisti `SYN` dobiva `SYN|ACK` s novim `sentSequence` i `ack = peerSynSeq`, bez prethodnog golog ACK-a. Identični duplikat `SYN` ponavlja isti `SYN|ACK` bez povećanja `sentSequence`. Na dolazni `SYN|ACK` nikada se ne šalje `SYN|ACK`.
5. **Dinamički Control Session ID i verzija (`Control SID=1`)**: Koristi se ID koji BMW predloži u tablici sesija (`1`, ne fiksni `10`). U Control v1 MFi (`0xAA00..0xAA05`) MORA ići prije identifikacije (`0x1D00..0x1D02`).
6. **MFi 2.0C (CP2.0C) 20-bajtni izazov**: `BMW 04990` šalje Apple MFi 2.0C X.509 certifikat (`908 B`, `SHA1withRSA / RSA`, `CN=IPA_2222AA190918AA06AA7612AA285231`), koji prima **strogo 20-bajtni** izazov (`AUTO` mod automatski počinje s 20 B za RSA certifikate).
7. **Pozdravni burst i obavezno slanje `0x5702`**: Odmah nakon `0xAA05` i `0x1D02` šalje se pozdravni niz, zatim `0x5702` i `0x4300`.
8. **Ne prekidati BT iAP2 kod Wi-Fi spajanja**: Bluetooth RFCOMM ostaje otvoren dok BMW sam ne završi Wi-Fi iAP2 handoff i sam ne zatvori BT.
9. **NUL-padding u stringovima (`0x4301` IP adrese)**: Svi TLV stringovi brišu završne `0x00` bajtove.
10. **Paralelni 5G internet + CarPlay Wi-Fi**: Nikada ne pozivati globalni `bindProcessToNetwork(carNetwork)` — umjesto toga svi CarPlay TCP/UDP socketi koriste izravno `carNetwork.socketFactory` / `carNetwork.bindSocket(...)`, pa 5G na Galaxy S25 ostaje 100% aktivan za Google Maps / Spotify tijekom cijele vožnje!
11. **BinaryPlist predznak portova**: Portovi `32768..65535` kodiraju se u 4 bajta (`0x12`), 1/2/4-bajtni integeri dekodiraju se bez predznaka, root objekt počinje na bajtu 8.
12. **Prozor slanja `maxOut=5`**: `Iap2LinkEngine` strogo poštuje `maxOutgoingWindow()` (`pendingControlQueue`).
13. **HFP/A2DP profili**: `BluetoothProfileGuard` odspaja HFP/A2DP samo prije pokretanja RFCOMM-a (nikad usred prihvaćene dolazne veze).
14. **`0xEA00` samo za CarPlay EA protokol** (`com.bmw.carplay`).
15. **CarPlay SDP UUID-ovi**: `RfcommTransport` registrira sva 3 Apple SDP UUID-a (`...cafe`, `...291a`, `...0a77`) — obavezno da BMW prihvati uređaj kao CarPlay (Profile B, Control v2).
16. **Foreground `WifiNetworkSpecifier` + pasivni monitor**: Nikad ne odjavljivati aktivni `specifierCallback` iz pasivnog monitora (ruši Wi-Fi nakon ~1 s!).
17. **Kriptirani RTSP + H.264 (`AirTunes/280.33.8`)**: Full `/pair-setup` + `/pair-verify` za ~0,3 s, `1422 B` ChaCha MTU, kriptirani `GET /info` bez `qualifier` filtra otključava `displays[0] = 1280×480 @ 60 fps` i `hidDevices[0..3]`, 3 neovisna keep-alivea (UDP 5s, `/feedback` 1s, Video `opcode=5` `bplist00` stats 1s), `VideoConfig` `SPS level_idc = 40` + `float32 LE` dimenzije.
18. **Virtualni Wi-Fi iAP2 handoff (`VirtualIap2Tunnel`, potvrđeno u `v0.2.1`–`v0.2.2`)**:
    - BMW odmah nakon `RECORD` pokreće virtualni iAP2 na Event kanalu (`iAPSendMessage FF 55 02 00 EE 10`). Odgovor šaljemo na **`Control :7000`**; handoff prolazi za **0,37–0,43 s**, BMW uredno zatvara BT RFCOMM/ACL i **trajno pamti mobitel u ConnectedDriveu** (sva iduća spajanja idu automatski za 0,5–1,6 s bez ponovnog uparivanja, čak i nakon reinstalacije APK-a!).
    - Nakon završetka virtualnog iAP2 handoffa, `hidSetInputMode` re-arm MORA ići isključivo neblokirajuće na **Event kanal** (nikad sinkroni `Control :7000` unutar virtualnog iAP2 callbacka, jer usred BT ACL zatvaranja može izazvati 5s `Read timed out`).
19. **TOČNA STRUKTURA BMW `hidSendReport` PAKETA (`session_20261127_193530.log`, `v0.2.2` — 100% POTVRĐENO UŽIVO!)**:
    - **KRITIČNA RAZLIKA BMW `AirTunes/280.33.8`**: BMW šalje `hidSendReport` ključeve na **VRHU bplist rječnika (`top-level`), BEZ ugniježđenog `"params"` rječnika!**
      ```text
      {
        type: "hidSendReport"
        uuid: "e5f7a68d-7b0f-4305-984b-974f677a22cc"   // ili ...15aa za iDrive kotačić
        hidReport: <Data 5 B ili 4 B>
      }
      ```
    - **Touchscreen (`TouchDescriptor`, `uuid = ...22cc`, 5 bajtova)**:
      - `[tip_u8 (0x01=dolje, 0x00=gore)][X_u16LE (0..1280)][Y_u16LE (0..480)]`
      - Primjer iz loga: `01 AD 00 EF 00` → `DODIR DOLJE (173, 239)`, `00 53 00 B0 00` → `DODIR GORE (83, 176)` — savršena 1:1 kalibracija u pikselima zaslona!
    - **iDrive kotačić (`KnobDescriptor`, `uuid = ...15aa`, 4 bajta)**:
      - `[buttons (bit0=Klik, bit1=Home, bit2=Back)][X_s8][Y_s8][Wheel_s8]`
      - Potvrđeno iz loga: `LIJEVO` = `00 81 00 00` (`X=-127`), `DESNO` = `00 7F 00 00` (`X=+127`), `GORE` = `00 00 81 00` (`Y=-127`), `DOLJE` = `00 00 7F 00` (`Y=+127`), `KOTAČIĆ +2 ↻` = `00 00 00 02`, `OTPUŠTENO` = `00 00 00 00`!
20. **Povratak iz BMW `MENU` natrag u CarPlay (`session_20261127_193530.log` L1290–L1487 — snimljeno uživo za Fazu 3A!)**:
    - Kad vozač stisne `MENU`, BMW šalje `changeModes (reasonStr="eNOTIFICATION_URGENT", resourceID=1, transferType=3)` (privremeno posuđuje ekran, stream i dalje živi).
    - Kad se vozač vrati na `Apple CarPlay`, BMW šalje `changeModes (reasonStr="eNOTIFICATION_DONE", resourceID=1, transferType=4)` i zatim **`requestUI (params={ url: "" })`**.
    - **Akcija za `v0.3.0`**: na `requestUI` i `changeModes (eNOTIFICATION_DONE)` odmah poslati `modesChanged (mainScreen=take)` + zatražiti svježi H.264 **IDR ključni okvir** (`requestKeyFrame()`) kako bi se slika vratila u milisekundi!
