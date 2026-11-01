# Bichinho (Android)

Bichinho de olhos verdes que vive por cima dos outros apps. Sem LLM: o humor
vem de impulsos (energia, paciência, afeto, tédio), sorteio ponderado e uma
rede neural pequena que aprende a SUA voz.

## O que ele faz
- Rosto: olhos que piscam e seguem o movimento, sobrancelhas/pálpebras por humor,
  boca (sorri, faz bico, reta, abre quando você fala) e ondas de voz no canto.
- Anda pela tela (janela overlay), pula, cola post-its.
- Toques rápidos enchem a paciência; arrastar ele irrita.
- Paciência baixa: protesto (cartazes). Muito baixa: **greve**. Cinco toques lentos
  ou conversa calma negociam a volta.

## Aprendendo a sua voz (tudo no aparelho)
1. No app, marque "Ouvir minha voz" e toque em **Ensinar minha voz (60 s)**; fale normalmente.
2. A cada ~0,5 s de fala o app extrai 8 números (volume, variação, ZCR, centroide
   espectral, tom e % de voz). O áudio não é gravado nem enviado.
3. Um **autoencoder** (8→4→8) é treinado online com esses números. Depois:
   - voz que ele não reconhece (erro de reconstrução alto): "quem é você?";
   - você falando bem mais alto que o SEU normal: "não grita!" e perde paciência;
   - você falando baixo e calmo: afeto sobe (e acalma a greve);
   - quando reconhece você, ajusta a rede de leve para acompanhar sua voz.
4. Pesos ficam em SharedPreferences privadas do app. Desinstalar apaga tudo.

Limite: é detecção de padrão de voz (não é reconhecimento de fala nem biometria
confiável). Ruído e outras pessoas perto podem enganar.

## Compilar o APK
**GitHub Actions:** suba a pasta num repositório; o workflow gera o artefato
`bichinho-debug-apk` e um `rea-report`. **Android Studio:** abra a pasta e rode
Build APK. **Terminal** (Android SDK 34): `gradle assembleDebug`.

## REA (morluto/rea)
O REA é uma ferramenta de engenharia reversa (JADX para APK). Aqui ele entra no
CI: depois do build, `rea inspect-android-package app-debug.apk` gera um relatório
com permissões, activity e serviço. Esperado: `SYSTEM_ALERT_WINDOW`,
`RECORD_AUDIO`, `FOREGROUND_SERVICE(_MICROPHONE/_SPECIAL_USE)`,
`POST_NOTIFICATIONS`; `PetService` não exportado.

## Instalar
Instale o APK, dê a permissão de sobreposição, toque em "Acordar o bichinho".
