# Gaps de captura — 26/09/2026

Base analisada: `bba0441f5f0b330e798c094be6d94849de774f95` (`main`).

## Evidência disponível

O teste enviado às 08:54 usava 1920×1080, 60 FPS, HEVC, 200 Mbps e
MediaCodec direto. O log registrou 25 `GAP_SENSOR`, deltas próximos de
33,319 ms com nominal de 16,667 ms, `frameStep=1` e temperatura reportada
como `thermal=0`. O MP4 teve 473 frames, 56,990 FPS e 25 quadros ausentes.
Vários gaps aconteceram com AF focado e lente parada. A exposição já estava
limitada a 14,667 ms; reduzir exposição, sozinho, não resolveu esse teste.

Isso confirma irregularidade antes da apresentação no player, mas não prova
defeito exclusivo no sensor: bloqueios de consumo no encoder podem afetar
o restante do pipeline. Não há medição anterior de fila/escrita suficiente
para atribuir causalidade ao bitrate ou ao coletor de memória.

## Correção desta branch

- Substitui a alocação direta de um buffer a cada sample por um pool reutilizável.
- Limita os buffers em uso a 32 MiB e 120 samples, mais até 16 MiB de cache.
  Estes são limites de buffers vivos gerenciados pelo pool; a liberação nativa
  de buffers descartados continua dependendo do runtime.
- Não espera por disco enquanto segura um buffer do MediaCodec. Saturação
  interrompe a gravação com erro explícito; não descarta frames silenciosamente.
- Devolve o buffer do codec em `finally`, inclusive quando copiar/enfileirar falha.
- Aceita EOS vazio sem exigir um buffer de dados não nulo.
- Retém falhas de escrita/drain/timeout para não reportar sucesso ao parar.
- Acrescenta `ENCODER_PRESSURE` na mesma tag `SteadyVaultCapture`, a cada
  dois segundos e no EOS. Não altera timestamps, bitrate, exposição ou FPS.

`samples` e `ptsGaps` são acumulados após a abertura do vídeo; `pending` e
`bytes` são o estado atual da fila, incluindo a escrita em execução;
`peakPending`/`peakBytes` são máximos desde o início; `allocations` conta
alocações diretas do pool. `windowMaxHoldMs` e `windowMaxWriteMs` são os
maiores tempos desde o log anterior. O primeiro intervalo pode incluir
a inicialização do áudio, que já ocorria no drain antes desta alteração.

## Validação e limite

Cinco testes JUnit cobrem reutilização, limites, buffers de conteúdo idêntico,
keyframes grandes e 5.000 entregas concorrentes em ordem com conteúdo íntegro.
O backend alterado e suas dependências diretas foram compilados com Kotlin
2.2.0/JVM 17 contra classes Android API 34. Isso não substitui o build completo
do app (compileSdk 37) nem o teste físico da Camera2/MediaCodec no S25 Ultra.
Ainda não há evidência de que esta alteração elimine todos os gaps.

## Teste no aparelho

1. Compilar esta branch e gravar 30 segundos em 1080p60 HEVC a 200 Mbps,
   mantendo iluminação, cena, estabilização e método de início iguais ao teste anterior.
2. Repetir a 40 Mbps. Esse valor é uma variável de diagnóstico para reduzir
   carga, não uma garantia de estabilidade nem um novo limite do aplicativo.
3. Guardar `GAP_SENSOR`, `ENCODER_PRESSURE` e `Cadência MP4` dos dois testes.
   Para comparar o vídeo, usar também o original anterior ao reparo automático.
4. Comparar primeiro `gapsSensor` e o diagnóstico do MP4 original. Um arquivo
   CFR reparado não comprova que a captura deixou de perder frames.

Fila crescendo ou escrita lenta indica pressão de saída; fila baixa e tempos
curtos com gaps persistentes exigem investigar encoder/HAL e a sessão de câmera.
Comparar o bitrate ajuda a separar essas hipóteses, mas não identifica sozinho
qual componente causa o atraso. Só considerar resolvido após repetir testes
sem gaps no original, inclusive gravação longa e início pelo widget.

Referência: https://developer.android.com/reference/android/media/MediaCodec
(seção Data Processing: reter buffers pode paralisar o codec).
