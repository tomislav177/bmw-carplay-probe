# AGENTS.md — BMW CarPlay Probe (`tomislav177/bmw-carplay-probe`)

> **Trajna memorija projekta za AI agente i buduće faze razvoja.**
> Ovo je samostalna, osobna Android aplikacija za sideload (`applicationId = "io.bimmergestalt.bmwcarplay"`).
> **STROGO PRAVILO**: Nikada ne diraj niti mijenjaj postojeći repozitorij `tomislav177/bmw` (`io.bimmergestalt.bmwmirror.v3`).

---

## 1. Pravila komunikacije i rada

- **Jezik komunikacije**: Uvijek odgovaraj korisniku na **hrvatskom jeziku** (jasno, konkretno, praktično).
- **Kod i komentari**: Nazivi klasa, metoda, varijabli i tehnički komentari u kodu na **engleskom**.
- **UI i dijagnostika**: Svi tekstovi u korisničkom sučelju i dijagnostičke poruke na **hrvatskom**.
- **Osobna sideload aplikacija (NIJE za Google Play Store)**:
  - `minifyEnabled = false` (bez R8/ProGuarda).
  - **0.0% potrošnje baterije u mirovanju**: bez `BOOT_COMPLETED` receivera, bez `WorkManager`-a i bez skrivenih pozadinskih servisa kada test nije pokrenut.
- **Licencna higijena**: Čista vlastita Kotlin implementacija prema iAP2 / AirPlay specifikaciji i MIT referencama (`shaonialife/cardeck`, `HaToan/carplay-wifi-extractor`). Bez kopiranja GPL-3.0 koda (`xcertplay`, `DiPlay`, `DroidCar`) i bez ugrađivanja tuđih Apple/Carlinkit privatnih ključeva ili certifikata (iAP2 autentifikacija je jednosmjerna — mobitel u iPhone/sender ulozi ne treba MFi čip ni Apple certifikat).

---

## 2. Potvrđeni ciljani hardver (stvarno vozilo i mobitel)

### Vozilo (100% potvrđeno dijagnostikom)
- **Model**: BMW X1 sDrive18i (F48)
- **Head Unit**: Alpine `HU_ENTRYNAV2` (EntryEvo)
- **HMI Firmware**: `BMW ID6L / EntryEvo_ID5_2007_Release ID5_2007-510-2012A Build 33 - Rev:210125 2020-03-12 10:34:41`
- **Zaslon**: `1280×480` (touchscreen + iDrive rotacijski kotačić + tipke na volanu)
- **Tvorničke opcije**: `6UN` (Navigacija), `6CP` (Apple CarPlay Preparation — **AKTIVNO**), `6NW` (Wireless Charging)
- **Hardverska činjenica**: USB-C utor ispod naslona za ruku ima **samo napajanje** (nema podatkovnih vodova do `HU_ENTRYNAV2`). Žičani CarPlay / USB adapteri ne rade. Jedini brzi video kanal je **bežični CarPlay** (`Bluetooth RFCOMM iAP2 -> 5 GHz Car Wi-Fi -> AirPlay H.264`).
- **Bluetooth ime auta**: `BMW 04990`
- **Bluetooth BR/EDR MAC adresa auta**: `F8:30:02:FC:79:A4`
- **Potvrđeni iAP2 serijski broj head unita (iz `0x1D01`)**: `AL07FFL0665583`

### Mobitel (100% potvrđeno)
- **Model**: Samsung Galaxy S25 (`SM-S931B`), Android 17 (SDK 37), One UI, `2340×1080` OLED, **NEROOTAN** (bez roota i bez Shizukua!)
- **Stvarna Bluetooth MAC adresa mobitela**: `7C:7B:BF:74:40:67`
  - **Kritično ograničenje Androida 12–17**: `BluetoothAdapter.getAddress()` na nerootanom mobitelu uvijek vraća lažni `02:00:00:00:00:00`. Head unit uspoređuje MAC unutar iAP2 (`0x4300`, `0x4E0E`) i Bonjour `_carplay-ctrl._tcp` (`id=`) sa stvarnom BT RFCOMM adresom; lažna adresa uzrokuje tiho odbacivanje sesije. Stvarna MAC adresa se čuva u postavkama aplikacije (zadano `7C:7B:BF:74:40:67`).
- **Uređaji koje skener MORA ignorirati**: `Galaxy Watch6 Classic (M77L)` (`AC:80:FB:DA:A7:CB`) te svi pametni satovi i slušalice.

---

## 3. Plan razvoja u 3 faze

1. **FAZA 1 (Trenutna — implementirana)**:
   - Kompletno spajanje, rukovanje i dijagnostički snimač paketa:
     - Korak 1: Bluetooth RFCOMM (Server Insecure/Secure na `...decacafe` + opcionalni blagi klijent na `...decacaff` + pomoćnik za prvo uparivanje `ACTION_REQUEST_DISCOVERABLE`).
     - Korak 2: iAP2 Link Layer (`FF 55 02 00 EE 10`, `FF 5A` okviri, dvojni komplement checksum, BMW višestruki `SYN`/`SYN|ACK` bez kolizije i ping-ponga, dinamičko preuzimanje Control Session ID-a iz BMW tablice sesija, fragmentacija/reassembly).
     - Korak 3: iAP2 Control Session (`0x4040` TLV) — `0x1D00..0x1D02` identifikacija (čitanje `AL07FFL0665583` i tablica poruka), jednosmjerni MFi `0xAA00..0xAA05` s X.509 inspekcijom certifikata i adaptivnim fallbackom duljine izazova (`32B -> 20B -> 16B`) + trajna memorija po serijskom broju.
     - Korak 4: Wi-Fi vjerodajnice (`0x5702` uvijek šalji -> `0x5703` dekodiranje + trajno slušanje kasnih `0x4301` / `0x4E0D` / nepoznatih `0xXXXX`) + `WifiNetworkSpecifier` spajanje uz zadržavanje aktivne BT iAP2 veze!
     - Korak 5 & 6: Bonjour/mDNS (`_carplay-ctrl._tcp`, `_device-info._tcp`, `_airplay._tcp`) vezano strogo na `carNetwork`, HTTP/RTSP `GET /ctrl-int/1/connect -> 200 OK`, analiza `features` bita 38 (`HK_PAIRING_AND_ENCRYPT`), RTSP `GET /info` + čisti Kotlin `BinaryPlist` parser/enkoder (ispravan predznak za portove `32768..65535`, root objekt na offsetu 8).
   - Bez video enkodera u Fazi 1.
2. **FAZA 2 (Nakon potvrde stvarnih logova Faze 1 iz BMW X1)**:
   - AirPlay `pair-verify` (ChaCha20-Poly1305) ili `et=NONE` AES-128-CTR fallback ovisno o `features` bitu 38 iz Faze 1.
   - RTSP `SETUP` + `RECORD`, 3 neovisna keep-alive otkucaja (UDP 5s, RTSP `/feedback` 1s, Screen stream keepAlive 1s).
   - Slanje testnog `1280×480` H.264 kolor-gradijenta (`MediaCodec`) + `MediaProjection` zrcaljenje u 60 fps.
3. **FAZA 3 (Nadogradnja)**:
   - Dvosmjerni Event kanal (iDrive kotačić, tipke na volanu, touchscreen HID izvještaji).
   - Opcionalni most na lokalni Android Auto Head Unit Server (`127.0.0.1:5277`).

---

## 4. Arhitektura paketa (`io.bimmergestalt.bmwcarplay`)

```text
app/src/main/java/io/bimmergestalt/bmwcarplay/
├── config/
│   └── ProbePrefs.kt                 # Postavke (BT MAC, ciljani BMW, MFi duljina, RFCOMM način, HEX, memorija po serijskom broju)
├── diag/
│   ├── DiagnosticLogger.kt           # Live log + automatsko spremanje filesDir/logs/session_*.log (zadnjih 10)
│   └── HexDump.kt                    # Čitljiv HEX/ASCII ispis i sanitizacija NUL bajtova
├── bt/
│   ├── BluetoothConstants.kt         # CarPlay UUID-ovi (...cafe / ...caff) i poznate MAC adrese
│   ├── DeviceFilter.kt               # Filtriranje BMW uređaja i strogo odbacivanje satova/slušalica
│   ├── BluetoothEventMonitor.kt      # Dinamički ACL / Bond BroadcastReceiver (samo dok sesija radi)
│   ├── DuplexByteStream.kt           # Odvojeni RX i TX lockovi za BluetoothSocket streamove
│   └── RfcommTransport.kt            # Dvostruka RFCOMM uloga s atomskim generationId i listom socketa
├── iap2/
│   ├── link/
│   │   ├── Iap2LinkPacket.kt         # FF55/FF5A zaglavlje, checksum, LinkSyncPayload, FrameStreamScanner
│   │   ├── Iap2LinkEngine.kt         # Čisti state machine (BMW SYN/SYN|ACK, dinamički sessionId, fragmentacija)
│   │   └── Iap2LinkChannel.kt        # I/O petlja + heartbeat ACK + retransmit timer
│   └── csm/
│       ├── Iap2MessageIds.kt         # Registar CSM ID-ova (0x1Dxx, 0xAAxx, 0x57xx, 0x43xx, 0x4Exx, 0x68xx, 0xEAxx)
│       ├── Iap2CsmCodec.kt           # 0x4040 okvir, TLV enkoder/dekoder, CsmReassembler, HEX/TLV dump
│       ├── Iap2Parsers.kt            # 0x1D01, X.509 certifikat, 0x5703, 0x4301, iPhone 0x1D01 builder
│       └── Iap2SessionCoordinator.kt # Cijeli Faza 1 iAP2 tok + trajni keep-alive pump
├── net/
│   └── CarWifiConnector.kt           # WifiNetworkSpecifier (Open/WPA2/WPA3), Network binding, LinkProperties
├── airplay/
│   ├── BinaryPlist.kt                # Čisti Kotlin bplist00 enkoder/parser (unsigned portovi, root na offsetu 8)
│   ├── AirPlayFeatures.kt            # Parsiranje 64-bitnog features polja i detekcija bita 38 (HK_PAIRING)
│   ├── CarPlayCtrlServer.kt          # TCP server + NSD oglašavanje _carplay-ctrl._tcp i _device-info._tcp
│   ├── AirPlayMdnsBrowser.kt         # NSD otkrivanje _airplay._tcp vezano uz carNetwork (IPv4 + IPv6 link-local)
│   └── AirPlayInfoClient.kt          # RTSP GET /info na carNetwork + fallback na gateway:7000 + dekoder zaslona/HID-a
├── session/
│   ├── ProbeSessionState.kt          # Stanje 6 vizualnih koraka i sažetci za UI
│   └── ProbeOrchestrator.kt          # Orkestrator Faze 1 (BT -> iAP2 -> Wi-Fi -> mDNS -> AirPlay /info)
├── service/
│   └── ProbeForegroundService.kt     # ForegroundService (connectedDevice), PARTIAL_WAKE_LOCK, gumb Zaustavi
└── ui/
    └── MainActivity.kt               # Material 3 sučelje na hrvatskom, dozvole, pomoć za uparivanje, live log
```

---

## 5. Kritične BMW/iAP2/AirPlay zamke koje kod rješava

1. **Lažna BT MAC adresa (`02:00:00:00:00:00`)**: Nikad ne koristi `BluetoothAdapter.getAddress()` za protokol — koristi stvarnu spremljenu MAC adresu (`7C:7B:BF:74:40:67`).
2. **Zombi RFCOMM dretve**: Svaki `BluetoothServerSocket` i `BluetoothSocket` registrira se prije blokirajućeg `accept()`/`connect()` uz atomski `generationId`.
3. **RX/TX deadlock**: `DuplexByteStream` koristi zasebne `rxLock` i `txLock` objekte.
4. **BMW višestruki čisti `SYN`**: Svaki novi čisti `SYN` dobiva `SYN|ACK` s novim `sentSequence` i `ack = peerSynSeq`, bez prethodnog golog ACK-a. Identični duplikat `SYN` ponavlja isti `SYN|ACK` bez povećanja `sentSequence`. Na dolazni `SYN|ACK` nikada se ne šalje `SYN|ACK`. LSP parametri se vjerno odjekuju (potvrđeno na `BMW 04990`: `maxOut=5, maxLen=2058, retxMs=1500, ackMs=100, maxRetx=30, maxAck=3, sessions={1:Control(v1), 2:FileTransfer(v1), 3:EA(v1)}`).
5. **Dinamički Control Session ID i verzija (`Control SID=1 v1`)**: Koristi se ID koji BMW predloži u tablici sesija (`1`, ne fiksni `10`). **POTVRĐENO U AUTU (`session_20261127_112839.log`)**: Alpine `HU_ENTRYNAV2` pregovara **Control Session v1**, u kojoj po Apple iAP2 specifikaciji **MFi autentifikacija (`0xAA00 -> 0xAA05`) MORA ići PRIJE identifikacije (`0x1D00 -> 0x1D02`)**! Kad se `0x1D00` pošalje prije `0xAA00`, BMW ga na link sloju ACK-a za 11–35 ms, ali na CSM sloju tiho ignorira.
6. **MFi 2.0C (CP2.0C) detekcija i duljina izazova**: **POTVRĐENO U AUTU (`session_20261127_120001.log`)** — `BMW 04990` (`HU_ENTRYNAV2`) šalje pravi Apple MFi 2.0C X.509 certifikat (`908 B`, `SHA1withRSA / RSA`, `CN=IPA_2222AA190918AA06AA7612AA285231`). CP2.0C hardverski čip prima **strogo 20-bajtni** SHA-1 izazov (`32B` i `16B` blokiraju `seq=102`). Zato `AUTO` mod za `SHA1withRSA / RSA` certifikate automatski kreće s `20B (5s) -> 32B (3s) -> 16B (3s)`, a za MFi 3.0 (`EC/SHA256`) s `32B -> 20B -> 16B`, uz trajnu memoriju po autu/serijskom broju i ručni odabir u UI.
7. **Pozdravni burst i obavezno slanje `0x5702`**: Odmah nakon `0xAA05` i `0x1D02` šalje se iPhone pozdravni niz (`0x4E0C/A/9/B/D/E`, bez 2s pauze), zatim `0x5702` (uvijek, čak i ako nije u `0x1D01` listi) i `0x4300`.
8. **Ne prekidati BT iAP2 kod Wi-Fi spajanja**: Bluetooth RFCOMM ostaje otvoren tijekom cijelog Wi-Fi/mDNS/AirPlay toka.
9. **NUL-padding u stringovima (`0x4301` IP adrese)**: Svi TLV stringovi brišu sve završne `0x00` bajtove, a log zamjenjuje `\u0000` s `␀`.
10. **Vezanje prometa uz `carNetwork`**: Svi socketi i mDNS upiti koriste `carNetwork` + `bindProcessToNetwork(carNetwork)` kako 4G/5G ne bi preuzeo pakete.
11. **BinaryPlist predznak portova**: Portovi `32768..65535` kodiraju se u 4 bajta (`0x12`), a 1/2/4-bajtni integeri dekodiraju se bez predznaka; root objekt počinje na bajtu 8.
12. **Prozor slanja `maxOut=5` (v0.1.4)**: **POTVRĐENO U AUTU (`session_20261127_121503.log`, v0.1.3)** — koraci 1–3 (BT, iAP2 Link, MFi 20B → `0xAA03` 128 B potpis, `0x1D01` `Entry Evo`/`AL07FFL0665583`) prolaze 100%. Ali v0.1.3 je poslao 9 CSM okvira odjednom (`seq=105..113`) iako BMW prihvaća samo 5 nepotvrđenih; `0x5702`/`0x4300` su pali izvan prozora. `Iap2LinkEngine` sada strogo drži `maxOutgoingWindow()` (red `pendingControlQueue`, fire-and-forget okviri oslobađaju slot nakon 1.5 s).
13. **HFP/A2DP profili (v0.1.4)**: Dok su "Pozivi (HFP)" i "Zvuk (A2DP)" aktivni za `BMW 04990`, iDrive ostaje u BT telefon/medija načinu (`0x5000`/`0x4C00`). `BluetoothProfileGuard` odspaja SAMO te profile (nikad ACL/RFCOMM, nikad satove/slušalice), uz ručnu rezervu: Postavke → Bluetooth → zupčanik uz BMW 04990 → isključi Pozivi i Zvuk.
14. **`0xEA00` samo za CarPlay EA protokol** (`com.bmw.carplay`), redoslijed `0x4E0D` → `0x5702` → `0x4E0E`.
