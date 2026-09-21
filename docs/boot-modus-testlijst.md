# Boot-modus: testlijst voor op de boot

De boot-modus (klokje in de balk) is getest met de simulatie. Het echte zoeken, de echte ronde en het echte
publiceren zijn nog nooit tegen een W2K-2 gedraaid. Deze lijst is bedoeld om dat in één keer na te lopen.
Alles wat de modus doet staat ook in de log (`nmea2log.log`, zichtbaar in de app en per USB/adb te halen),
elke regel met datum en tijd.

## Vooraf (thuis, 5 minuten)

- [ ] In Instellingen: W2K-2-gebruiker en -wachtwoord ingevuld, publiceren (WordPress of SFTP) ingevuld.
- [ ] Boot-modus-instellingen nagelopen: ronde-interval, haven (stil en motor uit, standaard 30 en 10 minuten),
      van boord (standaard 20 minuten), "Automatisch beginnen…" naar wens.
- [ ] Telefoon opgeladen, mobiele data aan (nodig om te publiceren; de hotspot van de telefoon is het netwerk van de W2K-2).

## 1. Starten en zoeken

1. Zet de hotspot aan en start de boot-modus (klokje). Bij de allereerste keer verschijnt een vraag over de
   batterij: kies **Instellingen openen** en zet de app op **Niet optimaliseren**.
2. Verwacht in de log: `Boot-modus: zoeken naar de W2K-2...`. De melding heet **My Sailing Logbook**.
3. Zolang de W2K-2 niet aan de hotspot hangt: elke 5 minuten opnieuw zoeken, zonder foutmelding.

## 2. De eerste ronde

1. Zodra de W2K-2 op de hotspot zit: `Boot-modus: ronde gestart (downloaden en bouwen)...`
2. In de melding loopt de voortgang mee: `Downloaden: x/y (…)`, daarna `Logboek opbouwen: x/y`,
   daarna `Reizen bouwen: x/4`.
3. Klaar: `Boot-modus: ronde klaar, volgende ronde om HH:MM.` Die tijd moet één ronde-interval later zijn.
- [ ] Beide werken: downloaden én bouwen, met bijgewerkte melding.
- [ ] Het scherm mag uit en de app mag dicht (uit recente apps vegen); de melding blijft staan.

## 3. Een ronde later, met het scherm uit

- [ ] Op het genoemde tijdstip start vanzelf de volgende ronde (`ronde gestart…`), ook met scherm uit en de app dicht.
- [ ] Tik in de melding op **Nu**: er start meteen een ronde (of een zoekpoging).
- [ ] Open de app bij een lopende modus: je ziet de log van de modus, niet het logboek, en er start geen eigen sync.

## 4. In de haven

1. Leg aan, motor uit. Na ≥ 30 minuten stil én ≥ 10 minuten motor uit, bij de eerstvolgende ronde:
   `Boot-modus: de boot ligt in de haven, laatste ronde.`
2. Daarna `Boot-modus: publiceren...` en `Boot-modus: gepubliceerd.` (en `[ok]` met de bestemming).
3. Dan `Boot-modus: wacht in de haven, volgende controle om HH:MM.`
- [ ] Het gepubliceerde logboek op de site is bijgewerkt.
- [ ] Dezelfde ligplaats wordt niet opnieuw gepubliceerd bij de volgende controles.

Mislukt publiceren (geen internet): `Boot-modus: publiceren mislukt, opnieuw om HH:MM.` Het wordt herhaald tot het lukt.

## 5. Van boord

1. Ga van de boot met de telefoon (de hotspot valt buiten bereik van de W2K-2, of zet hem uit).
2. Elke ronde: `Boot-modus: W2K-2 niet bereikbaar, opnieuw om HH:MM.`
3. Na 20 minuten aaneengesloten niet bereikbaar: `Boot-modus: van boord (W2K-2 weg), laatste ronde.`
   en publiceren, of `…van boord, niets nieuws om te publiceren.`
- [ ] Eén enkele gemiste ronde na een lange pauze telt niet als "van boord".

## 6. Herstel

- [ ] Forceer stoppen van de app tijdens een ronde, open de app weer: de melding komt terug, de ronde wordt opnieuw gedaan.
- [ ] Een eigen sync of bouw starten tijdens een ronde: `De boot-modus is bezig met een ronde of publicatie…`

## Terugsturen

Haal de log op en stuur die door (`adb exec-out run-as com.ayuus.mysailinglogbook cat files/nmea2log.log`),
met erbij: hoe laat je bent aangelegd/van boord ging, en wat je zag dat niet klopte. Let vooral op:
alarmen die uitblijven met het scherm uit (Doze), een ronde die halverwege stopt, en de tijd tot de
laatste ronde na aanleggen.
