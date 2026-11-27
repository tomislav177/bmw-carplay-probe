# BMW CarPlay Probe (Faza 1)

Samostalna Android dijagnostička aplikacija (`io.bimmergestalt.bmwcarplay`) za bežično CarPlay spajanje i protokolarnu dijagnostiku na **BMW X1 F48 (`HU_ENTRYNAV2` / `BMW 04990`)** s nerootanog **Samsung Galaxy S25 (`SM-S931B`)**.

## Što Faza 1 radi
1. **Bluetooth RFCOMM** — Sluša iPhone Wireless iAP2 UUID (`...decacafe`) u Insecure (zadano) ili Secure načinu + opcionalni blagi odlazni klijent (`...decacaff`) s filtriranjem satova/slušalica + pomoćnik za prvo uparivanje (`ACTION_REQUEST_DISCOVERABLE`).
2. **iAP2 Link Layer** — `FF 55 02 00 EE 10`, `FF 5A` okviri, dvojni komplement checksum, ispravna BMW `SYN` / `SYN|ACK` obrada bez ping-ponga i bez suvišnog ACK-a, dinamičko preuzimanje Control Session ID-a iz tablice sesija auta, fragmentacija/reassembly.
3. **iAP2 Control Session (`0x4040` TLV) & Jednosmjerni MFi** — Jednosmjerni MFi (`0xAA00..0xAA05` prvo za Control Session v1, s X.509 inspekcijom certifikata i adaptivnim fallbackom duljine izazova `32B -> 20B -> 16B` + trajna memorija po autu i serijskom broju), zatim `0x1D00..0x1D02` identifikacija (čitanje serijskog broja `AL07FFL0665583`).
4. **BMW Wi-Fi vjerodajnice & spajanje** — Obavezno slanje `0x5702`, dekodiranje `0x5703`, trajno slušanje kasnih `0x4301` / nepoznatih `0xXXXX`, te spajanje na 5 GHz BMW Wi-Fi putem `WifiNetworkSpecifier` bez prekidanja BT iAP2 veze.
5. **Bonjour/mDNS & AirPlay `GET /info`** — Oglašavanje `_carplay-ctrl._tcp` (sa stvarnom BT MAC adresom mobitela) i `_device-info._tcp`, odgovor `200 OK` na `/ctrl-int/1/connect`, otkrivanje `_airplay._tcp` strogo na `carNetwork`, provjera `features` bita 38 (`HK_PAIRING_AND_ENCRYPT`) i parsiranje `GET /info` `bplist00` odgovora.

## Izgradnja i testovi
```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Gotovi Debug APK za sideload automatski se objavljuje na GitHub Release tagu [`latest_build`](https://github.com/tomislav177/bmw-carplay-probe/releases/tag/latest_build).

## Povijest verzija
- **v0.1.3** — potvrđeno u pravom BMW X1 F48: BT RFCOMM, iAP2 Link, MFi (20 B izazov) i identifikacija prolaze 100%.
- **v0.1.4** — strogo poštivanje BMW prozora slanja (`maxOut=5`), `BluetoothProfileGuard` (odspajanje samo HFP/A2DP za BMW), `0xEA00` za CarPlay EA protokol, SDP UUID dijagnostika.
- **v0.1.5** — registracija Apple CarPlay SDP UUID-ova (`2d8d2466-...` i `02030302-...`) uz `...decacafe`, bez odspajanja HFP/A2DP usred dolazne RFCOMM veze → **POTVRĐENO U AUTU**: koraci 1–4 zeleni, BMW `F48-EntryEVO-0317` prihvatio Galaxy S25 kao CarPlay (Control v2) i poslao `0x5703` (`BMW 04990 CarPlay`, 32-znakovna lozinka, kanal 11)!
- **v0.1.6** — foreground-svjesno `WifiNetworkSpecifier` spajanje s hitnom obavijesti kad `0x5703` stigne u pozadini + automatsko pokretanje pri povratku u aplikaciju, pasivni Wi-Fi monitor i UI gumbi (*Ponovi Wi-Fi spajanje*, *Kopiraj lozinku*, *Već sam spojen → Korak 6*), `MulticastLock`, AutoIP `169.254/16` CIDR podrška za mDNS i `GET /info` (`qualifier=txtAirPlay` + `{}` fallback).
- **v0.1.7** — hitni ispravak prekida BMW Wi-Fi veze nakon ~1 s (pasivni Wi-Fi monitor više nikada ne odjavljuje aktivni `WifiNetworkSpecifier` callback!), čuvanje zadnje prave sesije loga pri ponovnom pokretanju aplikacije i globalni crash-logger na disk.
