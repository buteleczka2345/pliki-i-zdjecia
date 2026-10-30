# Pliki i Zdjęcia — jedna aplikacja z „Pliki” i „Galeria”

Połączenie aplikacji [Pliki](https://github.com/buteleczka2345/pliki) i Galeria w jeden APK (`pl.pliki.zdjecia`). Gotowy plik: `PlikiZdjecia.apk`.

- Start: menedżer plików (Ostatnie / Pobrane / Telefon / Karta SD) — opis w [repo Pliki](https://github.com/buteleczka2345/pliki).
- Przycisk **📷 Zdjęcia** w pasku zakładek otwiera galerię: siatka wszystkich zdjęć i filmów, foldery, zaznaczanie wielu, podgląd z powiększaniem, ✏️ rysowanie kredką (kopia w `Pictures/Zaznaczone`), udostępnianie, kosz z przywracaniem.
  Wstecz wraca do plików.
- Kod bez zmian w dwóch pakietach: `pl.pliki.menedzer` i `pl.galeria.zdjecia`.
- Można ją zainstalować obok starych „Pliki” i „Galeria” (inne ID aplikacji i inny `FilesProvider`).

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```
