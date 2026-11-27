# BMW CarPlay Probe (Faza 1)

Samostalna Android dijagnostička aplikacija (`io.bimmergestalt.bmwcarplay`) za bežično CarPlay spajanje i protokolarnu dijagnostiku na **BMW X1 F48 (`HU_ENTRYNAV2` / `BMW 04990`)** s nerootanog **Samsung Galaxy S25 (`SM-S931B`)**.

## Što Faza 1 radi
1. **Bluetooth RFCOMM** — Sluša iPhone Wireless iAP2 UUID (`...decacafe`) u Insecure (zadano) ili Secure načinu + opcionalni blagi odlazni klijent (`...decacaff`) s filtriranjem satova/slušalica + pomoćnik za prvo uparivanje (`ACTION_REQUEST_DISCOVERABLE`).
2. **iAP2 Link Layer** — `FF 55 02 00 EE 10`, `FF 5A` okviri, dvojni komplement checksum, ispravna BMW `SYN` / `SYN|ACK` obrada bez ping-ponga i bez suvišnog ACK-a, dinamičko preuzimanje Control Session ID-a iz tablice sesija auta, fragmentacija/reassembly.
3. **iAP2 Control Session (`0x4040` TLV) & Jednosmjerni MFi** — `0x1D00..0x1D02` identifikacija (čitanje serijskog broja `AL07FFL0665583`), `0xAA00..0xAA05` s X.509 inspekcijom certifikata i adaptivnim fallbackom duljine izazova (`32B -> 20B -> 16B`) + trajna memorija po serijskom broju.
4. **BMW Wi-Fi vjerodajnice & spajanje** — Obavezno slanje `0x5702`, dekodiranje `0x5703`, trajno slušanje kasnih `0x4301` / nepoznatih `0xXXXX`, te spajanje na 5 GHz BMW Wi-Fi putem `WifiNetworkSpecifier` bez prekidanja BT iAP2 veze.
5. **Bonjour/mDNS & AirPlay `GET /info`** — Oglašavanje `_carplay-ctrl._tcp` (sa stvarnom BT MAC adresom mobitela) i `_device-info._tcp`, odgovor `200 OK` na `/ctrl-int/1/connect`, otkrivanje `_airplay._tcp` strogo na `carNetwork`, provjera `features` bita 38 (`HK_PAIRING_AND_ENCRYPT`) i parsiranje `GET /info` `bplist00` odgovora.

## Izgradnja i testovi
```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Gotovi Debug APK za sideload automatski se objavljuje na GitHub Release tagu [`latest_build`](https://github.com/tomislav177/bmw-carplay-probe/releases/tag/latest_build).
