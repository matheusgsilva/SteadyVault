## Captura direta com configuração manual (1.8.220)

- Resolução, FPS, codec e bitrate ficam sob controle do usuário; não existe calibração nem perfil de “máximo sustentável” que aplique ajustes automaticamente.
- A matriz Camera2/MediaCodec continua sendo usada somente para mostrar combinações que o hardware publica como utilizáveis e para impedir parâmetros fora do intervalo declarado pelo encoder.
- Toda gravação usa uma única saída Camera2: `Camera2 → Surface do MediaCodec → MediaCodec → MediaMuxer`. Preview, tela preta e widget não criam uma segunda Surface durante a gravação.
- O bitrate selecionado é enviado diretamente ao encoder; o app não cria teto por FPS nem muda a taxa durante a captura.
- O PTS publicado pelo encoder é preservado; o gravador não reordena, interpola, repete nem encaixa frames em uma grade CFR durante a captura.
- O encoder de vídeo permanece em prioridade de tempo real, operating rate igual ao FPS, B-frames desligados, baixa latência e descarte de Surface desabilitado quando suportado.
- `Window.setSustainedPerformanceMode` continua sendo usado somente durante a gravação ativa quando o aparelho oferece suporte; isso é uma política de energia do Android, não uma configuração automática de resolução/FPS/bitrate.

# SteadyVault

## Engenharia 1.8.220

- Componentes de sessão, orquestração, saúde, finalização, métricas de início e telemetria térmica foram separados do serviço sem criar analisador ou thread por quadro.
- Android 16/API 36 usa CCT/tint quando a câmera confirma suporte; aparelhos anteriores ou HALs sem CCT continuam no caminho de balanço de branco já existente.
- A telemetria térmica registra status e headroom sem reduzir automaticamente resolução ou FPS.
- Os três cofres carregam mídia por páginas; cofres secundário e terciário compartilham a mesma tela compartilhada e o mesmo núcleo de repositório/indexação.
- A finalização do MP4 faz sincronização best-effort do arquivo/diretório após o muxer, fora do período ativo de captura.
- Há testes instrumentados Android, módulo Macrobenchmark com `StartupTimingMetric`, gerador de Baseline Profile e `baseline-prof.txt` inicial.

### Rodar os testes de engenharia

- Testes Android: `./gradlew :app:connectedDebugAndroidTest`
- Macrobenchmark: selecione a variante `benchmark` e rode `./gradlew :benchmark:connectedCheck` em aparelho físico.


SteadyVault é um aplicativo Android de câmera e cofre de mídia voltado a gravação discreta em segundo plano, captura de alta qualidade e armazenamento privado no dispositivo. O projeto foi ajustado principalmente para o Galaxy S25 Ultra, preservando compatibilidade com outros aparelhos Android que exponham os modos necessários pelo Camera2.

- Versão documentada: 1.8.237
- Pacote Android: com.steadyvault.camera
- Android mínimo: 10 / API 29
- Compile SDK 37 e Target SDK 36

## Principais recursos

- Gravação em segundo plano por foreground service, inclusive com a tela apagada, com monitor de quadros, heartbeat do serviço e recuperação automática da câmera ao bloquear/desbloquear.
- Parada normal aceita somente por comando explícito: botão Parar ou dois toques rápidos na tela preta. Ocultar o preview, remover o widget ou alternar a tela não encerra o vídeo.
- Rede de segurança para preservar trechos após falha de câmera/encoder: temporários antigos são validados e copiados para `filesDir/vaults/recovery`, fora do cache; a origem só é removida depois da cópia íntegra.
- Pipeline Camera2 → MediaCodec → MediaMuxer com codificação por hardware.
- HEVC/H.265 e AVC/H.264, com HLG10 opcional quando câmera e encoder oferecem suporte.
- Resoluções de 720p, 1080p, 4K e 8K, com 30, 60, 120 e 240 FPS limitados às combinações realmente expostas por Camera2 e pelo encoder de hardware.
- Interface configurada por uma matriz persistente de capacidades por câmera: controles de gravação só aparecem quando câmera + encoder confirmam suporte, evitando usar a própria gravação como tentativa de compatibilidade.
- Faixa fixa de FPS, como 60–60, priorizada automaticamente sempre que a câmera a publica.
- Tela preta opcional por origem de gravação sem preview, com brilho mínimo e dois toques rápidos para parar uma única vez, vibrar quando habilitado e sair imediatamente, sem acrescentar uma superfície de preview ao pipeline.
- Central de recuperação para gravações incompletas/recuperadas, protegida pelo PIN do cofre principal e biometria configurada.
- Tela de diagnóstico com logs de erros, falhas não tratadas, saídas anteriores do processo e inspeção/limpeza segura de cache.
- Cinco temas, quatro fontes e níveis configuráveis de cantos/contornos aplicados também a componentes dinâmicos e widgets.
- Identidade discreta opcional para notificações e widgets, com ícones genéricos, nome personalizado e ações neutras sem remover controles obrigatórios.
- Início urgente sem preview: valida rapidamente a cadência e limita o assentamento da câmera para não perder o momento.
- Vídeo usa diretamente os deltas de PTS produzidos pelo MediaCodec, apenas alinhando a origem temporal ao início oficial da gravação; áudio continua ancorado em `AudioTimestamp.TIMEBASE_BOOTTIME` para reduzir deriva A/V.
- Finalização audiovisual limitada ao último frame efetivo, sem cauda excedente do microfone.
- Áudio AAC com 48 kHz, mono/estéreo conforme suporte, ganho, filtro de graves, AGC e redução de ruído configuráveis.
- Estabilização automática, avançada, OIS, EIS ou desligada; OIS explícito só aparece quando a câmera lógica confirma suporte utilizável, sem trocar silenciosamente de câmera ou promover capacidade apenas “não verificada”.
- Cofre interno, PIN, biometria, Cofre secundário, Cofre terciário e Lixeira dos cofres.
- Thumbnails, exportação para a galeria e player interno com Media3 e fallback libVLC.
- Widgets de captura rápida com cartões arredondados padronizados, sem reinflação ao iniciar a gravação, barra compacta e controle de parada; zoom só aparece quando a lente realmente o suporta e Android 15+ recebe preview dinâmico no seletor.
- Zoom persistente e independente para fotos, sequências e vídeos sem preview pela tela inicial ou pelos widgets com zoom.
- Aba de apps protegidos por PIN ou biometria, com atalhos adicionáveis e removíveis.
- Prints e gravações de tela salvos diretamente no cofre escolhido por controle flutuante.
- Validação de espaço, proteção térmica e finalização transacional dos arquivos.
- Análise, reparo de timeline e transcodificação por hardware opcionais, com presets para Apple, Android, web, social, criação e arquivo.
- Navegador/downloader profissional com abertura de links http/https, identificação do player, mídia direta, análise por extrator, sessão/cookies, miniatura, qualidade/resolução, destino por cofre, perfis de desempenho, Wi‑Fi opcional, aria2, metadados, verificação de espaço e cancelamento.
- Importação por arquivos ou pasta em fila persistente, executada em foreground service, cancelável pelo app ou pela notificação e retomável após interrupção do processo/sistema.
- Saúde da gravação e desempenho da foto são analisados fora do caminho crítico; importação, thumbnails e metadados cedem imediatamente se uma nova captura começar.
- Importação transacional por cofre com arquivo `.svimport.partial`, `fsync`, validação e publicação final somente depois da cópia íntegra.
- Cofre principal, Cofre secundário e Cofre terciário permanecem independentes; não há unificação de armazenamento ou galeria.

## Perfil recomendado para Galaxy S25 Ultra

- Resolução: 4K UHD.
- FPS: 60.
- Codec: HEVC.
- Bitrate: 60 Mbps.
- HDR HLG10: desligado para maior estabilidade térmica; ative somente quando necessário.
- Estabilização: Automática.
- Preparar foco e exposição: ligado.
- Perfil `VIDEO_RECORD`: aplicado automaticamente quando a câmera o suporta.
- Proteção térmica: ligada.
- O modo de desempenho sustentado do Android não é ativado durante a captura; o pipeline prioriza o pico disponível do hardware e registra a cadência real alcançada.
- Android 16/API 36: AE Exposure-Time Priority adaptativo protege 60+ FPS regulares somente quando exposição/frame realmente ultrapassam o orçamento; em cenas claras volta ao AE normal quando o ISO chega ao piso. Sessões constrained high-speed continuam sem esse controle adicional.
- Preview durante a gravação em segundo plano: desligado para reduzir carga.

## Requisitos de desenvolvimento

- Android Studio compatível com Android Gradle Plugin 9.3.1 e Kotlin embutido do AGP.
- JDK 17.
- Android SDK 37.
- Gradle Wrapper 9.5.1 incluído no projeto, com SHA-256 oficial da distribuição.

## Compilação

No Linux ou macOS:

    ./gradlew assembleDebug

No Windows:

    gradlew.bat assembleDebug

APK de debug:

    app/build/outputs/apk/debug/app-debug.apk

Testes e verificações recomendados:

    ./gradlew testDebugUnitTest
    ./gradlew lintDebug
    python3 tools/verify_project.py

O verificador local reprova imports sem uso, métodos privados mortos ou vazios, supressões de warning, arquivos Kotlin órfãos, recursos sem referência e inconsistências de widgets/layouts.

## Estrutura principal

- app/src/main/java/com/steadyvault/camera/capture: gravador direto, serviços Camera2 e telemetria de cadência somente observacional.
- app/src/main/java/com/steadyvault/camera/core: capacidades, configurações, estado, armazenamento e validações.
- app/src/main/java/com/steadyvault/camera/photo: captura e políticas de qualidade de foto.
- app/src/main/java/com/steadyvault/camera/processing: análise, reparo, filtros e transcodificação.
- app/src/main/java/com/steadyvault/camera/storage: cofre, miniaturas, lixeira e controles de acesso.
- app/src/main/java/com/steadyvault/camera/ui: captura, configurações, player e telas do cofre.
- app/src/main/java/com/steadyvault/camera/widgets: widgets e comandos rápidos.
- tools: auditoria estática e testes de lógica pura.

## Permissões

| Permissão | Finalidade |
| --- | --- |
| CAMERA | Captura de foto e vídeo. |
| RECORD_AUDIO | Áudio das gravações. |
| POST_NOTIFICATIONS | Notificação obrigatória do serviço em primeiro plano. |
| FOREGROUND_SERVICE_CAMERA/MICROPHONE | Gravação em segundo plano conforme as regras do Android. |
| FOREGROUND_SERVICE_DATA_SYNC | Importação persistente de mídias para os cofres. |
| FOREGROUND_SERVICE_MEDIA_PROCESSING | Otimização e transcodificação em segundo plano. |
| FOREGROUND_SERVICE_MEDIA_PROJECTION | Mantém a sessão autorizada de captura de tela. |
| SYSTEM_ALERT_WINDOW | Exibe os botões flutuantes de print e gravação de tela. |
| WAKE_LOCK | Mantém o pipeline ativo com a tela apagada. |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | Permite solicitar exclusão da otimização de bateria. |
| USE_BIOMETRIC | Acesso biométrico aos cofres. |
| VIBRATE | Confirmação tátil de início e término. |

O navegador privado, a atualização do extrator e os downloads de mídia usam a permissão `INTERNET`; os arquivos baixados continuam sendo gravados no armazenamento privado do aplicativo.

## Armazenamento e segurança

As mídias do cofre ficam no diretório privado do aplicativo e não são expostas automaticamente à galeria. Exportações são feitas somente por ação do usuário. As novas cópias públicas criadas pelo SteadyVault são registradas para aparecer em “Tudo que o app salva” e poderem ser removidas pelo próprio app; cópias exportadas por versões antigas não são apagadas automaticamente porque não há uma identificação segura que as diferencie de mídias do usuário.

O armazenamento privado, PIN, biometria e FLAG_SECURE reduzem acesso casual, mas não equivalem automaticamente a criptografia individual de cada arquivo. Antes de usar o aplicativo para material altamente sensível, faça revisão de segurança, teste restauração, bloqueio, backup e comportamento em aparelho com root.

## Limitações técnicas

- Camera2 não expõe todo o processamento proprietário do aplicativo Câmera da Samsung nem o pipeline computacional do iPhone; o objetivo é reproduzir as características observáveis de boa captura (cadência, exposição/WB estáveis, sincronismo, estabilização e qualidade do encoder), não fingir equivalência ao ISP proprietário.
- No Android 16/API 36, o zoom por `CONTROL_ZOOM_RATIO` também declara `CONTROL_ZOOM_METHOD_ZOOM_RATIO` quando a câmera publica essa chave, evitando interpretação ambígua em 1,0x.
- No Android 15+, foreground services do tipo `dataSync` têm limite acumulado imposto pelo sistema; quando o limite é atingido, o SteadyVault pausa sem descartar a fila e retoma após nova interação do usuário.
- Resolução, FPS, HDR, estabilização e lentes dependem do HAL; para OIS, o app cruza a câmera lógica, as lentes físicas e as chaves de request porque alguns aparelhos omitem parte desses metadados.
- Faixas como 30–60 podem reduzir o FPS em pouca luz; o app prioriza automaticamente a faixa exata quando disponível.
- 120/240 FPS em segundo plano são mais sensíveis a temperatura, iluminação e limitações do encoder.
- Alterações no pipeline devem ser validadas fisicamente no aparelho-alvo com MediaInfo ou ffprobe; a matriz de capacidades impede oferecer combinações que o Camera2/encoder confirmem como incompatíveis.
- O widget abaixo do relógio pode ser adicionado pelo editor da tela de bloqueio quando a One UI listar widgets de terceiros; no Samsung Good Lock, use o módulo LockStar e selecione `SteadyVault — tela de bloqueio`.
- Previews personalizados por usuário no seletor de widgets usam a API gerada do Android 15+; Android 12–14 ficam limitados ao `previewLayout` estático fornecido pela plataforma.
- O downloader trabalha com arquivos/streams acessíveis por HTTP(S) e extratores suportados; não tenta contornar DRM, paywall ou proteção de acesso do site.
- O SteadyVault controla diretamente apenas seus próprios ícones/aliases extras. Atalhos fixados pelo usuário continuam sob controle do launcher, como exige o Android; o app não abre mais uma tela genérica da One UI para fingir que consegue ocultá-los.
- A aba Apps guarda e protege atalhos. Clonagem completa como Parallel Space exigiria um ambiente de virtualização e não é simulada por esta implementação.
- A captura de tela usa MediaProjection e, por exigência do Android, mantém uma notificação silenciosa enquanto a sessão estiver ativa.
- Se o Android revogar a MediaProjection, reiniciar/desligar o aparelho, forçar a parada do app ou esgotar o armazenamento, nenhum aplicativo consegue garantir continuidade absoluta. O SteadyVault tenta finalizar e preservar todos os quadros que já chegaram ao arquivo, mas não pode recuperar quadros que o sistema nunca entregou ao encoder.
- O build release está com minificação desligada e deve receber assinatura, política de release e testes adicionais antes de distribuição pública.

## Licenciamento

O projeto ainda não contém um arquivo LICENSE. A ausência de licença não concede automaticamente permissão para copiar, modificar ou redistribuir o código. As dependências de terceiros mantêm suas próprias licenças e avisos, descritos em NOTICE.md.

## Documentos

- CHANGELOG.md: histórico das versões documentadas.
- NOTICE.md: componentes de terceiros e obrigações de aviso.
