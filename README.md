# Wrist IA (ChatGPT Client per Wear OS)

Client leggero in puro Java per **ChatGPT** (compatibile con piani **Free** e **Plus/Pro**) progettato per smartwatch circolari Wear OS come OnePlus Watch 3.

## Funzionalità
- **Chat Recenti e Cronologia**: visualizza le ultime 25 conversazioni del tuo account, leggile e continua a scrivere.
- **Nuova Chat & Selettore Modalità/Modelli**: avvia nuove conversazioni e alterna al volo tra **Auto**, **Normale** (6.2), **Ragionato** (6.2 Thinking) e **6.1**.
- **UI Circolare AMOLED**: sfondo nero `#000000`, margini calibrati per display rotondi e scorrimento tramite **corona/rotella fisica**.
- **Dettatura Vocale**: input vocale nativo Wear OS in italiano (`🎤`) oltre alla tastiera.

## Come collegare il tuo account ChatGPT
1. Accedi a [chatgpt.com](https://chatgpt.com) dal telefono o dal PC.
2. Apri nella stessa scheda: `https://chatgpt.com/api/auth/session`
3. Copia tutto il testo (oppure solo il valore `accessToken` che inizia con `eyJ...`).
4. Apri **Wrist IA** sull'orologio, incolla il testo nella casella di login e tocca **Verifica e Entra**.

## Compilazione su GitHub Actions
Carica i file di questo repository su GitHub: il workflow genererà automaticamente l'APK scaricabile nella scheda **Actions** (`WristIA-APK`).
