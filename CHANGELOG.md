## main5 — correções de gravação da main3 (tempo, lacunas, duplicados, look)

Porta para a main5, mantendo o Super Estável e o foco inteligente em background:

- **Tempo exato:** `CfrTimeResampler` substitui o `CfrSlotClock` (removido junto com o teste). Frame alinhado passa direto; frame atrasado ou perdido vira saída no instante exato. Âncora com histerese (±1 intervalo) e quadro intermediário em vez de repetir frame (era a causa das travadas de 1 frame).
- **Lacunas da câmera:** interpolação com compensação de movimento na GPU (`MotionInterpolator`/`MotionShaders`: pirâmide de luma, block matching hierárquico, seleção de vetor por pixel, frame nítido quando o movimento é duvidoso), com crossfade como rede de segurança. Substitui o warp de 1 nível da main5 (o estimador leve de 1/16 continua só para o Super Estável).
- **Super Estável:** os frames recriados usam o mesmo recorte (0,90) dos frames reais estabilizados, para não pulsar de zoom a cada lacuna.
- **Duplicados:** frame idêntico ao anterior reentregue pela HAL é detectado na GPU (luma 1/32, poucos KB) e descartado.
- **Encoder:** `ALLOW_FRAME_DROP=0`, `PRIORITY=0`, `OPERATING_RATE=fps`, `LATENCY=1`, `MAX_B_FRAMES=0`, com fallback; contador de buracos de PTS.
- **Look (`VideoLook`):** nitidez, saturação e curva em S, aplicados uma vez a toda saída (frame real, estabilizado, mistura e recriado). O frame anterior guardado fica cru. A amostra do foco inteligente não recebe o look.
- **Telemetria (`SteadyVaultCfr`):** `resumo encoder`, `resumo CFR`, `movimento:` (estado, compensados, voltaram ao crossfade, duplicados, repetições da grade) e `sensor:` (histograma dos intervalos).
- `CfrInterpolationPlanner` deixa de ser usado pela ponte (continua no projeto com os testes).
- **Orientação (vídeo de lado):** em retrato o arquivo sai girado fisicamente pela GPU (encoder 2160x3840, hint do muxer 0), com a rotação escolhida compondo a matriz do `SurfaceTexture` (`resolveShaderRotation`, log `SurfaceTexture matrix=… -> shaderRotation=…`). Frame anterior, estimativa/recorte do Super Estável, interpolação e look trabalham no espaço de saída; a amostra do foco inteligente continua na orientação do sensor, então o `CaptureService` não muda.
- **Granulado (nitidez amplificava o ruído):** o unsharp RGB multiplicava o ruído por ~2,3 numa cena escura (desvio 5,8 → 13,4 no teste sintético) e ainda o deixava colorido. Agora a nitidez atua só na luma e com corte de ruído (`LOOK_CORE_LOW/HIGH`): detalhe fraco não é realçado, bordas reais continuam (ruído 5,8 → 7,7, sobra a curva/saturação; borda com o mesmo realce).
- **Denoise temporal (granulado em pouca luz):** na cópia câmera → anel, cada frame é misturado com o anterior já filtrado (peso máx. 0,5) só onde quase nada mudou (diferença de luma < 0,04, fade até 0,10); onde há movimento o frame novo passa intacto, sem rastro. Teste sintético: ruído −26% já no primeiro passo (o filtro é recursivo, então acumula em cena parada) e saída idêntica ao frame novo na região que se moveu. O histórico é descartado ao iniciar a gravação.
- **Diagnóstico das perdas de frame:** nos logs `anelCheio=0`, `swapMax=9 ms`, `ingestMedia=0`: a ponte e o encoder não seguram a câmera. As lacunas vêm da câmera (cena escura: AE alonga a exposição e o sensor entrega ~45 fps). Com "Priorizar qualidade em pouca luz" ligado o AE é livre (o FPS cai em pouca luz); desligado, a exposição é fixada (60 fps estáveis, mais ISO/ruído).
- **Super Estável derrubava frames em 4K60:** o modo pedia Preview stabilization (EIS do fabricante) à HAL além da estabilização na GPU. A câmera original gravava ~58 fps (8 intervalos longos em 374) na mesma cena enquanto a ponte recebia ~43 fps do sensor (28% de intervalos longos). Em 60 FPS o Super Estável agora usa OIS (ou nada, sem OIS), como o modo AUTO; abaixo de 60 FPS continua usando Preview stabilization/EIS.
- **Diagnóstico da câmera (`câmera(60 frames)`):** a cada 60 resultados da HAL o log mostra exposição média/máx., ISO máx., duração de frame média/máx., estado do AE e frame numbers pulados. Separa AE alongando a exposição de descarte de frames pelo ISP.
- **Telemetria de tempo:** novo log `tempos(ms)` com `swapMax/swapMedia` (espera do encoder), `ingestMax/ingestMedia`, `fluxoMax` e `warpMax` (GPU medida com `glFinish`, só nas lacunas; `TIMING_DIAGNOSTICS`) e `anelCheio` (vezes que o anel encheu e a câmera ficou esperando).
- **Lacunas não causam mais lacunas (fila de frames):** os logs mostravam `filaMax=1` e `piorFrame` de 60–90 ms: a ponte nunca ficava atrás, mas segurava a thread GL por vários frames a cada lacuna longa, a câmera ficava sem buffer e perdia mais frames. Agora cada frame da câmera é copiado na hora para um anel de 6 texturas 2D no espaço de saída (~200 MB de GPU) e o processamento consome dessa fila; entre as saídas de uma lacuna a fila é reabastecida. Os shaders de saída, Super Estável e interpolação passam a amostrar texturas 2D (sem OES/rotação por amostra). Luma e fluxo só são calculados quando há lacuna (antes: análise de luma + leitura de pixels da detecção de duplicados em TODO frame, que travava a GPU). A detecção de duplicados da câmera foi removida (`duplicadosDaCamera` era 0 em todas as gravações desde a correção da grade). O limiar de consistência do fluxo foi relaxado (2,5–6 texels 1/4): o anterior cortava ~5 dB em pans normais.
- **Câmera perdendo ~30% dos frames na main5:** a gravação pedia detecção de rosto (`STATISTICS_FACE_DETECT_MODE_SIMPLE`) à HAL, mas o resultado nunca é lido durante a gravação (o foco inteligente usa a pose da própria ponte). Removido: detecção de rosto fica desligada e o scene mode FACE_PRIORITY não é mais usado. Menos carga de ISP em 4K60.
- **Texto nos frames recriados:** letras deslocadas ou com sombra nas lacunas. O fluxo de bloco em 1/8 apaga o traço fino de texto, então foi adicionado refino em 1/4 de resolução (luma 4x4, 2 passes de 1 e 0,5 texel) e os limiares de confiança ficaram mais rígidos (custo 0,025–0,08, diferença 0,04–0,12): onde o fluxo é duvidoso sai o frame mais próximo, nítido, sem mistura. Teste sintético (headless, só GPU de software): texto em movimento deixa de sair embaralhado (fica nítido, com leve sombra residual em poucas letras); cenas de pan sintéticas ficam em 25,5–35,4 dB (antes 27,1–36,7 dB, ~1,5 dB a menos pelo critério mais rígido). Ferramenta: `tools/motion_test/run.py` (`PARAMS` em JSON, `oracle`, `noQuarter`).
- **Rasgos em blocos (movimento rápido sobre listras de texto borrado):** o fluxo agora também checa a consistência entre vetores vizinhos; se discordam (movimento ambíguo ao longo da linha), usa o frame mais próximo em vez de deslocar blocos. Medido com frames reais de uma gravação do aparelho (~0,6 dB a menos de PSNR, menos blocos rasgados).

## 1.8.266 — correção do Looper no início da gravação

- Corrige `No handler given, and current thread has no looper!` ao iniciar 4K60.
- `setRepeatingRequest` e `setRepeatingBurst` recebem Handler explícito.
- Nenhuma thread nova, watchdog, polling ou monitoramento de câmera foi adicionado.
- Mantém câmera lógica, zoom ratio, cadência fixa do sensor e MediaRecorder direto da 1.8.265.

## 1.8.265 — 4K60 deixa de ser bloqueado por metadados conservadores

- 30/60 FPS não são mais rejeitados por `getOutputMinFrameDuration()`/cadence confidence antes da tentativa real.
- O modo exato selecionado é montado diretamente na câmera lógica e enviado ao Camera2.
- `createCaptureSession()`/HAL passa a ser a fonte de verdade: se 4K60 funciona no aparelho, o app tenta gravar.
- Nenhum downgrade automático para 30 FPS.
- 120/240 FPS continuam usando a lista high-speed pública, pois exigem sessão de alta velocidade válida.
- MediaRecorder, uma única Surface e controle de cadência do sensor da 1.8.264 foram preservados.

## 1.8.264 — câmera lógica e cadência de sensor controlada

- câmera traseira lógica única para vídeo e zoom por ratio;
- remoção da varredura de capabilities da tela de captura;
- plano de gravação estrito: sem procurar outra câmera na hora do Start;
- 60 FPS SDR: AE mede os 3 primeiros resultados sem atrasar o início e depois fixa frame duration/shutter/ISO quando MANUAL_SENSOR existe;
- mantém Camera2 → uma Surface → MediaRecorder → MP4, sem pós-processamento.

## 1.8.263 — captura pura sobre a base estável 1.8.258

- Retorna o caminho de captura à base 1.8.258, preservando o request Camera2 e a sessão MediaRecorder que produziram o melhor resultado anterior.
- Durante a gravação não existe watchdog, heartbeat, monitor periódico de armazenamento, callback de disponibilidade, retry de câmera, recovery loop ou verificação de tela.
- A sessão continua encoder-only: Camera2 → uma única Surface do MediaRecorder → MP4 final.
- WakeLock passa a ser adquirido uma vez no início e liberado no fim, sem renovação periódica.
- Áudio usa somente o MediaRecorder; sem permissão/FGS de microfone disponível, a gravação segue como vídeo-only sem retries.
- App e widget traseiros compartilham o mesmo seletor óptico 0,6x/1x/3x/5x sem forçar cameraId físico no serviço; câmera frontal continua explícita.
- Tela preta headless da base 1.8.258 preservada, sem Surface de preview.
- Mantida apenas uma validação inicial de espaço livre antes de criar o arquivo.

## 1.8.249 — 60 FPS sem estabilização eletrônica

- Preview Stabilization/EIS deixam de participar da gravação em 60/120/240 FPS para evitar quedas periódicas de quadros observadas no Galaxy S25 Ultra.
- Em alta taxa, OIS é usado quando disponível; caso contrário, grava sem estabilização eletrônica.
- Mantida a regra de FPS exato: 30=30, 60=60, 120=120, 240=240, sem faixa variável ou fallback para outro FPS.
- Preview Stabilization continua disponível em 30 FPS.

## 1.8.247 — 4K60 sem downgrade para 30 + início rápido + lockscreen compacto

- 4K60 mantém 60 FPS mesmo quando a HAL publica AE [30,60]; [60,60] continua preferida.
- Removido fallback de alta taxa para 30 FPS; 60 é o piso de qualquer pedido >=60.
- 60 FPS começa imediatamente após Camera2 aceitar a sessão, sem espera de warm-up.
- Backend OEM 4K60 passa a aceitar a rota Samsung [30,60] mantendo EncoderProfiles em 60.
- Widget da tela de bloqueio compactado para caber os quatro controles sem recorte.

## 1.8.245
- Gravação 60+ FPS ganha pré-validação curta da cadência real na saída do encoder antes do primeiro quadro ser salvo.
- Rotas abaixo de 99,7% do FPS pedido ou com lacuna longa no warm-up são recusadas antes de abrir o MP4; o app reduz para o próximo FPS suportado e continua automaticamente.
- Tolerância do tempo mínimo publicado pela HAL foi reduzida de 0,25 ms para 0,05 ms, evitando classificar uma rota perto de 59,68 FPS como 60 FPS confirmado.
- O fallback de FPS é registrado em Diagnósticos e também aparece no estado/notificação da gravação.
- Widgets 6x1, 4x1 e tela de bloqueio usam o mesmo padrão real de botão de 58 dp e os mesmos estados ativo/desativado; o preview da tela de bloqueio foi alinhado ao 4x1.
- Base: 1.8.244; cofres, player, importação e demais recursos preservados.

# 1.8.240

- A importação nos três cofres agora pergunta, a cada novo lote, se deve **Ignorar repetidos** ou **Manter repetidos**.
- A política escolhida é persistida junto com a fila, inclusive para retomada após interrupção do app/serviço.
- Em **Ignorar repetidos**, continuam valendo a detecção rápida da origem e a confirmação SHA-256 do conteúdo; cópias idênticas são contabilizadas como ignoradas.
- Em **Manter repetidos**, o importador não elimina URI/conteúdo repetido e cria uma nova cópia com nome único, sem substituir arquivos existentes.
- Não permite misturar políticas diferentes enquanto uma fila do mesmo cofre ainda está pendente.
- Thumbnails dos três cofres passam a ser geradas em 512 px e persistidas em JPEG qualidade 95, mantendo seis workers, cache e rejeição/regeneração de frames pretos.
- O pós-processamento da importação já aquece a thumbnail em 512 px para que a grade abra com a versão de alta qualidade pronta.
- Nenhuma alteração no pipeline de gravação.

# 1.8.238

- Remove a linha explicativa do card “MODO DE VÍDEO” da tela inicial, mantendo as configurações acessíveis nos Ajustes.
- Nenhuma alteração no pipeline de gravação.

## 1.8.237 — 2026-08-22

- Corrige duração ausente/lenta nos vídeos ao abrir qualquer um dos três cofres.
- A leitura de duração e resolução passa a usar `MediaExtractor` primeiro, lendo o contêiner sem abrir decoder; `MediaMetadataRetriever` fica apenas como fallback.
- Vídeos que entram na área visível da grade solicitam metadados imediatamente por uma fila prioritária independente, sem esperar o aquecimento do restante do cofre.
- O enriquecimento em segundo plano deixa de parar nos primeiros 96 itens e passa a percorrer todas as mídias do cofre.
- O pós-processamento de importações que cede recursos para uma gravação ativa é reagendado automaticamente em vez de abandonar duração/thumbnail daquele arquivo.
- Cache persistente e índices dos cofres continuam recebendo duração, resolução e rotação para que as próximas aberturas sejam instantâneas.
- Nenhuma alteração no pipeline de gravação.

## 1.8.236 — 2026-08-22

- Adiciona **Remover mídias duplicadas** diretamente às ferramentas dos três cofres.
- A limpeza usa o mesmo mecanismo SHA-256 nos cofres principal, secundário e terciário.
- Mantém a cópia mais antiga e envia somente cópias byte a byte idênticas para a Lixeira.
- Impede a limpeza enquanto houver importação em andamento ou pendente, evitando corrida com arquivos que ainda estão entrando no cofre.
- Mantém a limpeza específica do álbum no cofre principal.
- Nenhuma alteração no pipeline de gravação.

## 1.8.235 — 2026-08-22

- Reestrutura o caminho crítico de 60 FPS a partir do vídeo real medido em ~56,9 FPS, sem interpolar, repetir quadros ou maquiar timestamps.
- Remove `KEY_LATENCY = 1` do MediaCodec para permitir que o encoder de hardware use o buffering nativo em picos de complexidade.
- Separa o drain do MediaCodec da escrita no MediaMuxer: o encoder libera seus buffers em thread urgente e um writer dedicado grava no armazenamento através de uma fila limitada de 64 samples com pool reutilizável.
- Mantém VBR quando suportado, `KEY_PRIORITY = 0`, `KEY_OPERATING_RATE` igual ao FPS solicitado, B-frames desabilitados e `KEY_ALLOW_FRAME_DROP = 0` quando disponível.
- Corrige a validação de 60 FPS para consultar primeiro o tempo mínimo da Surface real do `MediaCodec`, em vez de aceitar o menor tempo publicado por outro tipo de stream.
- Prioriza encoder de hardware com `PerformancePoint` oficial cobrindo resolução/FPS e suporte exato ao tamanho/taxa.
- Remove o Sustained Performance Mode da tela de captura para não limitar artificialmente o pico de desempenho disponível do aparelho.
- Durante uma gravação, importação em andamento pausa o item atual sem contabilizá-lo como falha e o retoma depois; thumbnails, metadados e pós-processamento também cedem CPU/I/O ao pipeline de câmera.
- Registra em Diagnósticos a cadência real produzida: FPS efetivo, número de quadros, lacunas longas, maior lacuna e pico da fila do muxer.
- Mantém `[60,60]` como primeira escolha; se o hardware/HAL realmente não sustentar o modo, a política existente continua gravando com fallback e aviso em vez de deixar de gravar.

## 1.8.234 — 2026-08-22

- Adiciona “Remover mídias duplicadas” ao gerenciamento do álbum atual no cofre principal.
- A limpeza compara arquivos candidatos por tamanho e confirma duplicidade pelo SHA-256 do conteúdo completo; nome, caminho ou data não são usados como prova de duplicidade.
- Mantém a cópia mais antiga de cada conteúdo idêntico e move somente as cópias extras para a Lixeira, permitindo restauração.
- Exibe progresso durante a análise e a remoção e informa quantas duplicatas foram encontradas, movidas ou não puderam ser movidas.
- A limpeza atua somente nas mídias associadas ao álbum escolhido e não altera mídias iguais que estejam apenas fora dele.
- Não altera câmera, encoder, FPS, áudio, timestamps ou o pipeline de gravação.

## 1.8.233 — 2026-08-22

- Refeito o fechamento da fila de importação nos três cofres: todos os itens precisam terminar como importados, repetidos ou falhos antes da conclusão.
- Deduplicação por SHA-256 do conteúdo calculado durante a própria cópia, evitando cópias repetidas mesmo com nomes ou URIs diferentes.
- A leitura da origem não depende mais de consulta de tamanho via ContentResolver antes de copiar; o tamanho é obtido pelo descritor quando disponível.
- Ao concluir, a interface confirma explicitamente processados/total e atualiza o cofre automaticamente.
- Metadados de vídeo e thumbnails passam a ser preparados em segundo plano logo após cada importação aceita.
- Thumbnails da grade foram reduzidas para 384 px, com quatro workers e descarte de backlog antigo para responder melhor ao scroll.

## 1.8.232 — 2026-08-22

- Unifica a robustez da fila de importação nos cofres principal, secundário e terciário: todos continuam usando o mesmo `VaultBulkImportRunner`, `VaultImportQueueStore` e `VaultImportService`.
- Corrige uma corrida no encerramento do serviço: uma nova mídia adicionada exatamente quando a fila anterior terminava não pode mais ficar pendente em estados como `104/107` sem um processador ativo.
- Torna a substituição do arquivo persistente da fila atômica (`ATOMIC_MOVE` + `REPLACE_EXISTING`), eliminando a janela em que a fila podia desaparecer entre apagar o arquivo antigo e publicar o novo.
- Persiste o estado terminal de cada item antes de avançar o checkpoint; ao retomar após interrupção, o maior checkpoint válido é reconciliado e a fila não volta para um item já concluído.
- Uma interrupção inesperada da thread de processamento não entra mais em loop no mesmo item exibindo `Verificando 1/N`; a fila é pausada com checkpoint preservado para retomada segura.
- Para URIs de mídia cujo nome/extensão já está presente no próprio URI, evita consultas desnecessárias ao `ContentResolver` durante `Verificando`, reduzindo travas em providers lentos.
- Remove o corte após várias falhas consecutivas ao persistir permissões: todos os URIs recebidos do seletor são tentados e falhas são registradas sem impedir o restante do lote.
- Mantém a regra de item terminal: cada mídia processada termina como importada, repetida ignorada ou falha; apenas cancelamento/pausa explícitos deixam o item para retomada.
- Não altera câmera, encoder, FPS, áudio, timestamps ou qualquer código do pipeline de gravação.

## 1.8.230 — 2026-08-21

- Corrige a fila de importação para preservar exatamente a quantidade selecionada; URIs repetidas deixam de desaparecer antes de entrar na fila e passam a ser contabilizadas como repetidas ignoradas.
- Impede uma nova seleção de sobrescrever uma importação ainda pendente: novos arquivos são acrescentados à fila existente e processados pelo mesmo serviço.
- Torna o total do progresso monotônico para que o contador nunca regrida de 107 para 104/100 por concorrência entre a tela e o serviço.
- Faz o processador reler a fila ao chegar ao fim, permitindo continuar automaticamente quando novos arquivos são anexados enquanto uma importação está em andamento.
- Exibe progresso do arquivo atual por porcentagem/bytes em cópias grandes, evitando a falsa impressão de travamento em um número como 7/100.
- Adiciona watchdog de leitura: uma origem que fica 30 s sem entregar bytes é fechada, tentada novamente e, após as tentativas, registrada como falha sem prender o restante do lote.
- Tenta persistir permissões de leitura para lotes grandes em vez de abandonar toda a persistência quando há mais de 64 itens.
- Evita espera infinita por um estado antigo de prioridade da câmera; uma gravação realmente ativa continua tendo prioridade e a importação mostra claramente que está aguardando e retoma sozinha.
- Mantém câmera, encoder, FPS, áudio, timestamps e pipeline de gravação inalterados.

## 1.8.228 — 2026-08-21

- Torna o seek interativo do player imediato: toque na timeline e botões ±10 s usam busca rápida por quadro sincronizado, evitando decodificar um GOP inteiro em 4K60 antes de responder.
- Mantém busca exata somente em operações de precisão, como corte; após cada busca rápida o Media3 volta ao modo exato para não contaminar operações posteriores.
- No fallback VLC, usa o modo de seek rápido para navegação normal e preserva seek preciso quando necessário.
- Remove a segunda busca exata automática que era disparada durante scrub rápido, evitando fila duplicada de decodificação.
- Redefine a amostra do monitor de saúde ao saltar na timeline para que um seek pesado não seja confundido com travamento do decoder.
- Adia a análise detalhada de cadência enquanto o usuário acabou de abrir/navegar no vídeo, reduzindo disputa de I/O com o seek.
- Mantém o player pausado mostrando o novo ponto após seek e conserva a reprodução quando o vídeo já estava tocando.
- Não altera câmera, encoder, FPS, áudio, timestamps ou qualquer parte do pipeline de gravação.

## 1.8.226 — 2026-08-20

- Remove a paginação acionada pelo scroll dos três cofres: a ordenação passa a trabalhar sobre a coleção completa sem trocar/reinserir páginas ao chegar ao fim da grade.
- Atualizações leves de metadados deixam de reenviar toda a lista ao adapter durante a rolagem, evitando saltos de posição.
- Libera o cache de miniaturas antes/depois de abrir o visualizador para reduzir pressão de memória em vídeos grandes.
- Desativa o gesto vertical de fechar apenas para vídeos, evitando saídas acidentais durante a reprodução.
- Remove a preparação automática do filmstrip de corte durante playback normal; ele só é criado ao entrar no modo Cortar.
- Substitui o popup de fim do vídeo por controles centrais ao tocar: −10 s, reproduzir/pausar/reproduzir novamente e +10 s.
- Torna o widget da tela de bloqueio mais compacto, adiciona restauração explícita e atualização periódica para melhorar a permanência no host.
- Não altera câmera, encoder, FPS, áudio, timestamps ou o pipeline de gravação.

## 1.8.223 — 2026-08-16

- Corrige importação em massa dos três cofres sem alterar o pipeline de gravação.
- Reabre a origem e tenta novamente falhas transitórias de leitura `EIO`, alternando `openInputStream` e `openFileDescriptor`, com backoff curto e cópia transacional limpa a cada tentativa.
- Evita varrer diretórios de sistema/cache conhecidos em importação por pasta, incluindo `System Cache - Do not delete`, `LOST.DIR`, `System Volume Information`, `$RECYCLE.BIN` e `Android/data`/`Android/obb`, mantendo `Android/media` disponível.
- Corrige o relatório que mostrava no máximo 50 falhas como se fossem o total real; agora exibe a contagem verdadeira e somente uma amostra curta, deixando todos os detalhes em Diagnósticos.
- Nomes de itens inacessíveis passam a ser compactados no relatório em vez de exibir o caminho SAF inteiro.
- Mantém a seleção e a grade dos cofres independentes da paginação visual de 160 itens.

## 1.8.222 — 2026-08-16

- Torna 60 FPS estritamente `[60,60]` em toda a seleção Camera2: uma faixa variável como `[30,60]` deixa de ser aceita como perfil de gravação de 60 FPS.
- O request `TEMPLATE_RECORD` reforça `CONTROL_AE_TARGET_FPS_RANGE = [60,60]` quando o perfil selecionado é 60 FPS, inclusive como proteção contra perfil/cache antigo.
- Preview e matriz de capacidades usam a mesma política para impedir que outro caminho reintroduza uma faixa variável de 60 FPS.
- Mantém os demais parâmetros de gravação inalterados.

## 1.8.220 — 2026-08-15

- Remove integralmente a calibração e a recomendação **Máximo sustentável do hardware**: saem `DeviceAutoConfigurator`, `DeviceCalibrationStore`, `AutoConfigurationPolicy`, interface, testes e auditorias correspondentes.
- Mantém a matriz Camera2/MediaCodec apenas como validação de capacidades; resolução, FPS, codec e bitrate continuam sendo escolhas do usuário.
- Remove o teto automático de bitrate usado em 60/120/240 FPS e também o acréscimo oculto de 8% no HDR. O valor escolhido agora segue direto ao encoder e só é limitado pelo intervalo publicado pelo próprio MediaCodec.
- Mantém o bitrate fixo durante toda a captura, sem adaptação dinâmica ou reconfiguração no meio do vídeo.
- Preserva o caminho de maior fluidez já obtido: uma única Surface, `Camera2 → MediaCodec → MediaMuxer`, request congelado, PTS real, B-frames zero, baixa latência, prioridade de tempo real e sem frame drop do encoder quando suportado.
- Atualiza a revisão do pipeline para `manual-direct-single-surface-1.8.220` e adiciona auditoria para impedir a volta do calibrador ou de tetos automáticos de bitrate.

## 1.8.219 — 2026-08-15

- Unifica a calibração em uma única recomendação, **Máximo sustentável do hardware**, e remove os perfis alternativos de fluidez e qualidade.
- Usa taxa exata do encoder, `PerformancePoint`, FPS alcançável, faixa Camera2 fixa e sessão validada para selecionar a maior combinação que o aparelho consegue sustentar de verdade.
- Torna toda gravação estritamente encoder-only: Camera2 envia para uma única `Surface` do MediaCodec, inclusive quando a captura parte da tela com preview. A interface permanece visual, mas deixa de competir com o encoder por uma segunda saída durante o arquivo.
- Fixa o encoder de vídeo em B-frames zero, baixa latência, prioridade de tempo real, `operating rate` igual ao FPS e `allow frame drop = 0` quando suportado; Android 15+ também recebe a maior importância de codec para vídeo.
- Prefere VBR quando confirmado pelo encoder e usa CBR apenas como fallback. O codec de áudio recebe importância menor para não disputar recursos com a cadência de vídeo.
- Prioriza automaticamente a faixa AE exata e o stream use case `VIDEO_RECORD`; remove os controles antigos de faixa fixa, stream use case, modo de bitrate, B-frames, operating rate, correção de lacunas, suavização de alto FPS e pós-processamento automático oculto.
- Elimina a rota de reabertura de câmera que ficou sem consumidor e metadados de capacidades que só alimentavam controles removidos.
- Atualiza a revisão do pipeline para `maximum-hardware-single-surface-1.8.219` e adiciona auditoria contra regressão para Surface extra e configurações aposentadas.

## 1.8.218 — 2026-08-15

- Corrige a causa de software dos engasgos periódicos vistos no `56141.mp4`: PTS válidos deixam de ser arredondados para uma grade nominal. A cadência real do encoder é preservada igualmente em 30/60/120/240 FPS; somente timestamps duplicados ou regressivos avançam o mínimo de 1 µs.
- Prioriza, em todos os modos, a faixa AE exata `[FPS,FPS]`, a câmera calibrada e um encoder com suporte de taxa exato. Metadados conservadores continuam tendo fallback seguro e nunca reduzem a resolução ou o FPS pedidos.
- No codec Automático, um encoder com taxa exata vence um codec disponível apenas por fallback antes da comparação de folga/desempenho.
- Inclui `smoothHighFps` na assinatura do cache e muda a revisão do pipeline, impedindo a reutilização de uma configuração antiga com faixa diferente.
- Divide o início em duas fases: `arm()` drena saídas anteriores, Camera2 instala uma única vez o request/burst definitivo e `commitStart()` confirma a época e pede o IDR inicial. O MP4 só abre em um keyframe decodificável.
- O gate inicial não mede FPS, pixels, brilho ou tamanho do sample e nunca cancela o comando. Se o codec ignorar o novo IDR, um GOP anterior válido é liberado por um fallback limitado a 250 ms, ¼ de segundo em quadros ou 24 MiB.
- Mantém o request congelado, sem callback Camera2 por quadro, interpolação, repetição, mudança de bitrate ou reconfiguração durante o vídeo.
- Em 60 FPS ou mais, ignora solicitações de ISP de alta qualidade no caminho crítico e prefere redução de ruído mínima/off, edge off e correções opcionais off, usando apenas valores publicados pela câmera.
- A opção de fluidez em alta taxa permanece ativa mesmo quando a HAL não publica faixa fixa: a faixa abrangente compatível pode gravar sem reprovação de cadência.
- A calibração de hardware testa o codec de verdade, usa faixa fixa como desempate e limita o bitrate recomendado ao teto publicado pelo encoder.
- Adiciona testes de cadência real, jitter, lacunas, regressão de PTS, 30/60/120/240 FPS e gate de IDR. O prólogo preto pós-commit ainda requer validação física no Samsung porque não pode ser distinguido de uma cena realmente escura sem analisar conteúdo.

## 1.8.217 — 2026-08-15

- Mantém o caminho zero-copy mais curto disponível no Android: `Camera2 → Surface do MediaCodec → MediaMuxer`, sem CameraX, OpenGL/Vulkan, cópia de pixels, preview no headless ou callback por quadro.
- Arma o encoder e solicita o IDR antes da única submissão `setRepeatingRequest`/`setRepeatingBurst`, fechando a corrida que poderia descartar o primeiro quadro decodificável.
- Retira a preparação de `AudioRecord`/AAC do caminho que publica a Surface: câmera e encoder de vídeo começam enquanto o áudio é preparado em paralelo.
- Prepara a faixa AAC com um curto buffer de silêncio antes de abrir o microfone. No fluxo normal o muxer começa sem backlog de vídeo; 750 ms/12 MiB permanecem apenas como defesa se o encoder AAC não responder.
- Usa uma época `BOOTTIME` comum: o PTS absoluto do primeiro quadro representa a captura, não o dequeue tardio do encoder. O primeiro PCM que cruza esse instante é recortado exatamente e som capturado depois conserva seu atraso real.
- Falhas de microfone, AAC ou permissão de áudio não impedem nem invalidam o vídeo; clipes curtos também finalizam sem esperar uma faixa AAC inexistente.
- Câmera é a única permissão obrigatória para gravar. O microfone pode ser liberado separadamente nos Ajustes e nunca segura o comando de vídeo.
- Valida clipes pelo recebimento de sample de vídeo, não por um tamanho arbitrário de arquivo, e adia a liberação de codecs/muxer até as threads proprietárias realmente saírem.
- Envia EOS vazio com o PTS final ao muxer, preservando a duração inclusive em clipes de um único quadro e na cauda do áudio.
- Evita que o watchdog reabra a câmera quando a thread está escrevendo no muxer/armazenamento.
- Dá prioridade de thread ao vídeo sobre o processamento PCM e adia toda limpeza de MP4 até o fim da gravação.
- O padrão de áudio passa a ser PCM direto para AAC, sem ganho/low-cut em Kotlin; o processamento só percorre amostras quando o usuário o ativa manualmente.
- Em 60 FPS ou mais, força blocos ISP leves (`MINIMAL`/`OFF`/`FAST`) e nunca solicita tonemap de alta qualidade como fallback.
- Remove o `captureSingleRequest` de autofocus anterior à gravação; vídeo usa foco contínuo no único request definitivo.
- Remove GameManager, classificação de jogo, transcode/correção automática e geração imediata de thumbnail. Otimização continua manual no Cofre.
- Remove o módulo de Macrobenchmark do projeto; o Baseline Profile já empacotado permanece no app sem executar benchmark em uso normal.
- O widget dispara o serviço headless antes de inflar a tela preta e instalar gestos.

## 1.8.216 — 2026-08-15

- Corrige o congelamento no início dos vídeos: o primeiro quadro realmente salvo passa a ser o tempo zero do track de vídeo, eliminando a frente parada causada por um PTS inicial positivo.
- Remove completamente warm-up/callback de início da sessão de gravação. O `CaptureRequest`/burst definitivo é submetido uma única vez sem callback e permanece congelado durante o vídeo.
- A promoção do foreground service para microfone deixa de bloquear o começo do vídeo. Camera2 + MediaCodec começam primeiro e a tentativa de áudio roda fora da thread crítica.
- Se o Android 16 demorar ou recusar temporariamente o AppOp/FGS do microfone, o vídeo continua sendo capturado; o áudio entra assim que estiver disponível e, se falhar de vez, a gravação é preservada como vídeo-only.
- Aumenta apenas a tolerância do watchdog antes do PRIMEIRO sample de vídeo para 3,5 s; depois que o fluxo começou, continua usando 1,5 s para detectar um stall verdadeiro.
- Mantém headless encoder-only, B-frames desligados, baixa latência, frame-drop desabilitado, operating rate e bitrate definidos antes da captura.
- O vídeo `56122.mp4` usado para validar a correção tinha primeiro PTS em ~1,8498 s e keyframes a cada ~2 s; após o início apresentou 407 frames, apenas três intervalos de ~33,33 ms e o restante em ~16,67 ms, indicando que o maior defeito visual era a frente congelada, não uma queda contínua de 4K60.

# 1.8.215 — gravação não é mais bloqueada pela cadência inicial

- Remove completamente `FrameCadenceMonitor` e qualquer aprovação/reprovação de FPS antes de iniciar a gravação. Uma leitura inicial como 51,2 FPS não pode mais cancelar 4K60, 120 ou 240 FPS.
- O warm-up vira somente um assentamento curto por quantidade de frames: no caminho headless de widget/tela preta usa aproximadamente 50 ms e depois inicia, independentemente da cadência observada.
- Se a HAL não entregar `CaptureResult` dentro de 120 ms no headless, o gravador começa mesmo assim; o watchdog existente continua responsável por recuperar um travamento real depois que a captura já foi solicitada.
- Antes de salvar o primeiro frame, o callback temporário é removido e o mesmo `CaptureRequest`/burst continua sem listener durante todo o vídeo.
- Preserva uma única Surface do MediaCodec no headless e não adiciona preview, ajuste dinâmico de AE, bitrate, estabilização, interpolação ou repetição de frames.
- Só falhas reais de Camera2/MediaCodec/foreground service podem impedir o início; uma estimativa de cadência abaixo do alvo nunca mais interrompe a captura solicitada.

# 1.8.214 — captura determinística e capacidades cache-first

- Remove o aprendizado automático de estabilização e todo o histórico persistente correspondente. O modo Automático passa a ser determinístico: 120/240 FPS priorizam Off; 60 FPS usa OIS somente quando a lente o confirma como segura; abaixo de 60 FPS prioriza OIS e depois os modos digitais suportados.
- Remove do `HardwareRecorder` a telemetria de cadência por frame usada pelo aprendizado: saem o `LongArray` de 30.000 amostras, `synchronized(cadenceLock)`, `cadenceSummary()` e `RawCadenceAnalyzer` do código de produção. O thread do MediaCodec agora apenas normaliza o PTS, atualiza o watchdog e grava no muxer.
- Remove integralmente `RecordingQualityAnalyzer` e a classificação de saúde/cadência pós-gravação. Salvar um MP4 não agenda nem mantém uma segunda varredura de timestamps em background.
- Torna a matriz Camera2/MediaCodec estritamente cache-first: com cache válido, abrir Gravar/Ajustes ou retornar ao app não cria thread de reanálise.
- O cache de capacidades é invalidado somente quando muda o firmware (`Build.FINGERPRINT`), muda a versão instalada do SteadyVault, o usuário toca em `Reanalisar hardware`/calibração ou uma configuração selecionada falha de verdade em runtime.
- Adiciona `Reanalisar hardware` em Ajustes e faz `Ver modos reais` reutilizar o cache existente em vez de forçar nova varredura.
- Mantém sem alteração o headless encoder-only, warm-up curto por `SENSOR_TIMESTAMP`, request congelado, bitrate fixo durante o vídeo, watchdog de travamento e recuperação de arquivo.

# 1.8.213 — remoção total do diagnóstico automático

- Remove a tela de diagnóstico automático, seu repositório de relatórios/capturas, exportação e entrada em Ajustes/Manifest.
- Remove instrumentação criada exclusivamente para esse diagnóstico: métricas de latência de início, snapshots térmicos de diagnóstico, inspeção de AE dedicada e callbacks de primeiro sample.
- Remove a categoria correspondente de “Tudo que o app salva” e as regras de auditoria que exigiam a funcionalidade.
- Mantém o warm-up curto por `SENSOR_TIMESTAMP`, o request Camera2 congelado, o caminho headless encoder-only, o watchdog de travamento real, a proteção térmica normal e o aprendizado de estabilização pelas gravações reais.
- Nenhum teste automático inicia câmera, microfone, WebView, foto ou gravações descartáveis dentro do aplicativo.

# 1.8.212 — headless encoder-only rígido para widget e tela preta

- Torna widget, tela preta, botão/atalho headless caminhos explicitamente `encoder-only`: a sessão Camera2 recebe somente a `Surface` criada pelo `MediaCodec`.
- Adiciona `RecordingServiceRouter.startHeadless(...)` para impedir que esses pontos de entrada sejam marcados acidentalmente como captura de preview em alterações futuras.
- Ao entrar em headless, limpa `CameraPreviewRegistry`, força `previewCaptureRequested=false` e proíbe `shouldAttachRecordingPreviewSurface()` de anexar qualquer saída visual.
- `createRecordingSession()` monta diretamente `listOf(recorderSurface)` em headless e valida em runtime que exista exatamente uma Surface na sessão.
- A `DiscreetRecordingActivity` continua sendo somente uma janela preta; seu layout não contém `SurfaceView`, `TextureView` ou qualquer destino Camera2.
- Mantém integralmente o warm-up curto por `SENSOR_TIMESTAMP` da 1.8.211 e remove o callback antes do primeiro frame salvo; request, bitrate e estabilização continuam congelados durante o vídeo.
- A gravação com preview continua disponível apenas quando iniciada explicitamente pelos controles do preview; o comportamento headless não depende de FPS e nunca usa preview como fallback.

# 1.8.211 — warm-up real curto + request congelado

- Reconstrói o início da gravação com a arquitetura que funcionava melhor nas versões 1.8.137/1.8.160/1.8.165: Camera2 mede somente alguns `SENSOR_TIMESTAMP` antes do primeiro frame salvo, confirma que a cadência assentou e remove integralmente o callback antes de iniciar o `HardwareRecorder`.
- 60/120/240 FPS começam assim que a pequena janela recente fica estável; em captura headless a política continua na ordem de ~50–120 ms. O warm-up nunca altera AE, FPS, estabilização, bitrate ou o request durante o arquivo.
- Depois do warm-up, o mesmo `CaptureRequest`/burst é reenviado com listener nulo. Não existe `CaptureResult` por frame durante a gravação, rebuild de repeating request, AE adaptativo, mudança dinâmica de bitrate ou estabilização.
- Em 60+ FPS uma cadência inicialmente comprovadamente instável não é aceita silenciosamente. Antes de existir arquivo o serviço tenta retirar preview compartilhado quando aplicável; se o modo dedicado continuar instável, falha em vez de produzir conscientemente um vídeo travando.
- O `VideoTimestampNormalizer` leve passa a ser usado em 30/60/120/240 FPS: O(1), sem fila/reorder/repetição/interpolação, preservando o offset inicial e qualquer slot realmente perdido.
- Em SDR o MediaCodec volta a escolher `profile/level` internamente; HDR mantém o profile de 10 bits necessário. Permanecem `KEY_MAX_B_FRAMES=0`, `KEY_LATENCY=1`, `KEY_ALLOW_FRAME_DROP=0`, prioridade realtime e `KEY_OPERATING_RATE`.
- Mantém a regra observada nos testes atuais: CBR preferencial em 60/120 e VBR em 240 para preservar headroom no encoder.
- Auto Teste e auditoria passam a verificar explicitamente que o callback existe somente no warm-up e é removido antes de `professionalRecorder.start()`.

# 1.8.210 — correções do Auto Teste 1.8.209

- Mantém o 4K60 no caminho direto que atingiu 59,69 FPS sem perdas no MP4; nenhuma normalização é aplicada em 60 FPS ou abaixo.
- Em 120/240 FPS adiciona somente o `VideoTimestampNormalizer` leve da arquitetura antiga: O(1), sem fila, reorder, repetição ou interpolação. Ele encaixa jitter pequeno na grade nominal e preserva lacunas reais.
- Em 240 FPS o bitrate Automático prefere VBR para recuperar margem do encoder, mantendo CBR preferencial em 60/120 FPS.
- Corrige apenas o metadata nominal `KEY_FRAME_RATE` entregue ao `MediaMuxer`, evitando tracks declarados como 4 FPS/235 FPS sem alterar PTS ou duração dos samples.
- O foreground service inicia como câmera e só promove para câmera+microfone imediatamente antes do `AudioRecord`, com retry da promoção; isso corrige a corrida que fazia a primeira gravação 4K30 falhar no Android 16.
- A calibração de estabilização passa a aprender com o MP4 final analisado depois da gravação, em vez de usar telemetria intermediária do encoder.
- O Auto Teste usa o MP4 como fonte de verdade para o status de cadência; telemetria do encoder permanece visível apenas como diagnóstico.

# 1.8.209 — request congelado e caminho rápido de gravação

- A gravação passa a usar um único `CaptureRequest` construído com o snapshot atual dos Ajustes; depois que a sessão começa, o request não é reconstruído, não há callback por quadro e nenhuma política de AE/FPS/estabilização altera a câmera durante o vídeo.
- Remove do caminho ativo o `VideoExposureController` adaptativo e reduz o componente a mera inspeção de capacidade usada pelo Auto Teste. A antiga `ExposureCadencePolicy` foi removida do projeto.
- Adiciona apenas uma espera fixa e curta com a câmera já ligada ao encoder (60 ms em headless, 120 ms em 60+ FPS e 160 ms em modos comuns), sem benchmark, sem decisão por cadência e sem retry; depois disso o gravador começa diretamente.
- Em 60 FPS ou mais não há disparo AF isolado imediatamente antes da gravação. O foco contínuo do próprio request permanece ativo quando selecionado.
- O encoder volta à configuração enxuta usada nas versões antigas mais estáveis: sem `KEY_CAPTURE_RATE`/`KEY_MAX_FPS_TO_ENCODER`, com B-frames zero, baixa latência, `KEY_ALLOW_FRAME_DROP=0`, prioridade realtime e CBR preferencial quando o usuário não força outro modo.
- Mantém o PTS direto da 1.8.208: sem normalizador, reorder, repetição, interpolação ou CFR por software durante a captura.
- O Auto Teste identifica explicitamente o núcleo direto e deixa de anexar telemetria Camera2/AE por frame às gravações reais; encoder e MP4 permanecem as fontes principais de cadência.
- Exportação fácil para Arquivos/Compartilhar da 1.8.208 foi preservada integralmente.

# 1.8.208 — núcleo direto Camera2 → MediaCodec → MediaMuxer

- Restaura no gravador principal a filosofia das versões antigas que apresentavam a melhor fluidez: a Surface criada pelo `MediaCodec` continua sendo o destino direto da sessão Camera2 e cada sample comprimido segue diretamente para o `MediaMuxer`.
- Remove do caminho ativo `VideoTimestampNormalizer` e `PresentationOrderBuffer`; os dois utilitários e seus testes foram eliminados do projeto para impedir regressão acidental para reorder/CFR durante a captura.
- O PTS do encoder não é mais encaixado em grade de 16,67/8,33 ms, não recebe slot artificial em 120/240 FPS e não tem o `KEY_FRAME_RATE` do track sobrescrito. Só a origem temporal é alinhada ao início da gravação e o muxer mantém a proteção mínima de monotonicidade de 1 µs.
- O encoder volta a receber apenas hints prévios de hardware (`KEY_FRAME_RATE`, `KEY_CAPTURE_RATE`, `KEY_OPERATING_RATE` e `KEY_MAX_FPS_TO_ENCODER`), sem `KEY_ALLOW_FRAME_DROP=0`, repetição de frame ou adaptação de bitrate durante o vídeo.
- Mantém os recursos atuais fora do caminho dos frames: perfis por FPS/câmera, HDR, áudio AAC, estabilização, exposição, tela preta, widgets, watchdog, recuperação, telemetria térmica, cofres, Auto Teste e otimização opcional pós-gravação.
- `Exigir 60/120 FPS reais` passa a ser explicitamente uma política pré-gravação: seleciona/valida câmera e encoder, mas nunca altera timestamps ou fabrica quadros.
- O Auto Teste desliga `Reparar cadência após gravar` durante as amostras para que o relatório sempre meça o MP4 bruto produzido pelo núcleo direto; o relatório também identifica explicitamente a arquitetura usada.
- As ferramentas de correção e otimização permanecem disponíveis, porém fora do núcleo: só podem modificar o arquivo depois da gravação quando a opção correspondente estiver explicitamente ativa.

# 1.8.207 — perfis realmente ativos, tela preta imediata e cadência high-speed estável

- Corrige a sincronização dos perfis na abertura do app e dos Ajustes: a câmera/FPS exibidos passam a ser ativados no `CameraProfileStore` antes de montar a interface, evitando mostrar uma opção como ativa enquanto a gravação usava outro perfil persistido.
- Trocar 30/60/120/240 FPS nos Ajustes agora salva o perfil anterior e carrega imediatamente o perfil próprio do novo FPS, em vez de reaproveitar os controles visuais do FPS anterior e sobrescrever o perfil de destino.
- Aplicar novamente uma opção que já está ativa não reinicia câmera/preview nem altera o resultado; seletores da tela de Ajustes também ignoram `Aplicar` sem mudança real.
- Corrige o roteamento da tela preta: o botão Gravar da tela principal usa a preferência “Tela preta ao gravar pelo app”, atalhos rápidos usam a preferência própria e a tela discreta abre assim que o serviço aceita o início, mantendo o broadcast como fallback.
- Em 120/240 FPS, o normalizador deixa de interpretar timestamps pareados/quantizados do encoder como frames perdidos. Cada sample codificado recebe um slot CFR consecutivo e apenas uma interrupção real longa é preservada; 30/60 FPS continuam detectando perdas curtas reais.
- Em fluidez estrita, o teto de bitrate passa a ser mais conservador antes de abrir o encoder: até 120% do recomendado em 60 FPS e 115% em 120/240 FPS. O valor escolhido pelo usuário continua salvo, mas não pode sacrificar cadência apenas para empurrar bitrate excessivo.
- O preview do modo Automático usa a mesma base conservadora da gravação em 60 FPS ou mais, evitando mostrar OIS/EIS ativo no preview quando a captura real começaria por Off para preservar cadência.
- Corrige textos cortados nos seletores: descrições podem ocupar todas as linhas necessárias dentro do diálogo rolável; botões longos dos Ajustes passam a crescer verticalmente e o valor selecionado do spinner não tenta comprimir uma descrição redundante dentro do campo.
- Reorganiza Ajustes por fluxo: Vídeo → Câmera e estabilização → Gravação discreta → Áudio → Otimização após gravação → Reprodução → Privacidade e cofres → Apps → Segundo plano → Cache → Navegador → Aparência → Atalhos → Diagnóstico.
- Adiciona testes para PTS pareado em 120 FPS e para interrupção real longa em high-speed, preservando a cobertura de 60 FPS, reordenação e detecção de gaps reais.

# 1.8.206 — FGS robusto, 120 FPS com encoder de maior headroom e Auto Teste confiável

- Corrige a falha intermitente do Android 16/One UI ao promover o serviço de gravação para câmera + microfone: o CaptureService mantém o prazo do foreground service com câmera provisória, tenta novamente a permissão de microfone e só abre encoder/câmera depois da promoção completa.
- O encerramento de uma tentativa que falhou antes da câmera agora libera monitor de armazenamento, lease da câmera, estado de inicialização e foreground imediatamente, evitando contaminar 4K30, calibração e foto seguintes.
- O Auto Teste passa a respeitar de verdade o `cameraId` explícito também em gravação headless; a seleção automática de zoom não pode trocar silenciosamente a câmera escolhida pela matriz de teste.
- Em 120/240 FPS, a escolha de encoder ganha score por FPS máximo declarado, performance points e headroom real para 240 FPS; isso evita empate que podia levar 1080p120 a um encoder mais fraco enquanto 1080p240 usava outro caminho estável.
- O relatório de cadência registra nome/MIME/score do encoder, câmera usada, janela sustentada e arquivo completo, permitindo identificar diretamente qualquer diferença de codec sem novo diagnóstico manual.
- A análise do MP4 usa aproximadamente 500 ms centrais sem bordas como fonte principal de fluidez e mantém o arquivo completo no relatório. Abertura/EOS não derrubam um modo saudável, mas gaps no miolo continuam reprovando normalmente.
- O PhotoService agora repete de forma limitada a promoção do FGS de câmera e a abertura da câmera quando há corrida transitória `CAMERA_IN_USE`/`MAX_CAMERAS_IN_USE`/serviço da câmera; se falhar, preserva a exceção real no relatório em vez da mensagem genérica “serviço de câmera indisponível”.
- O Auto Teste aumenta o intervalo entre serviços para permitir liberação completa da HAL/AppOps e renderiza os cards finais antes da captura de tela. O cabeçalho mostra quantidade de falhas/avisos ou “Tudo aprovado”, em vez de sempre dizer apenas “Auto teste concluído”.
- Corrige a detecção de gravação interrompida no `onDestroy`: a existência de arquivo pendente é calculada antes de marcar a parada como solicitada pelo usuário, preservando recuperação quando o sistema encerra o serviço.

# 1.8.205 — 120 FPS por ordem de apresentação e foto com confirmação terminal

- Corrige o caminho específico de 1080p120 em encoders que devolvem buffers comprimidos fora da ordem de apresentação: uma janela limitada reordena os samples pelo PTS antes da normalização e do muxer, sem alterar o caminho 240 FPS que já estava estável.
- O track de vídeo passa a declarar explicitamente o FPS solicitado quando o encoder Samsung publica um valor derivado incorreto no formato de saída; os PTS continuam sendo a fonte de verdade da cadência.
- Mantém lacunas realmente perdidas visíveis: a reordenação corrige apenas a ordem de entrega e não fabrica frames nem comprime interrupções reais da câmera/encoder.
- Corrige a corrida do Auto Teste fotográfico entre `startForegroundService()` e `PhotoCaptureStateStore.begin()`: o teste agora aguarda um resultado terminal pertencente à captura atual, com sucesso/erro e mensagem persistidos pelo serviço.
- O PhotoService valida o JPEG por decodificação de dimensões antes de declarar sucesso; um arquivo vazio/corrompido passa a falhar no próprio serviço em vez de aparecer apenas depois no Auto Teste.
- O diagnóstico de foto informa quando nenhum arquivo novo foi criado e, havendo arquivos inválidos, mostra quantidade e maior tamanho para diferenciar falha de captura de falha de decodificação.
- Corrige a calibração de estabilização explícita: OIS/EIS/Preview não podem mais selecionar silenciosamente um perfil que não satisfaça o modo pedido e cair para Off; falhas de amostra passam a registrar o motivo no relatório.
- Amplia os testes puros para cobrir reordenação de PTS em 120 FPS antes da normalização, preservando a cadência nominal.

# 1.8.204 — Cadência real de 60 FPS e diagnóstico por estágio

- O MP4 final passa a ser a fonte principal de verdade da fluidez, com limites mais rígidos: ~57 FPS reais não podem mais aparecer como PASS de uma meta de 60 FPS.
- A normalização de timestamps suaviza somente jitter pequeno e preserva slots realmente perdidos; uma lacuna real de ~33 ms em 60 FPS permanece visível no arquivo e não é mais comprimida.
- O Auto Teste separa a cadência por estágio: timestamps do sensor/Camera2, PTS do encoder sustentado e completo, e PTS do MP4 final, incluindo p99, maior gap e perdas estimadas.
- A calibração Fluidez/AUTO só aceita estabilizações que atinjam uma meta mínima de cadência. Se Off/OIS/EIS/Preview falharem, usa Off como fallback de menor carga em vez de declarar um vencedor ruim como satisfatório.
- O score de estabilização passa a penalizar explicitamente perdas de quadro, p99 e gaps, além de FPS e irregularidade; o aprendizado antigo é invalidado para não reaproveitar scores calculados pela regra anterior.
- O modo Fluidez em 60 FPS reduz também o processamento de bordas do ISP quando suportado, sem alterar o caminho high-speed de 120/240 FPS.
- Bitrate e modo de bitrate deixam de ser restaurados por perfis antigos de câmera/FPS; a seleção global atual permanece ao trocar outras configurações.
- Restaura `android:extractNativeLibs=true`, exigido pelo executável nativo do downloader e pelo auditor estático do projeto.
- Adiciona testes puros e unitários cobrindo 57 FPS vs 60 FPS, preservação de um frame perdido e fallback AUTO quando todos os modos ficam abaixo da meta.

# 1.8.203 — Auto teste robusto e Fluidez/AUTO determinístico

- Corrige falha transitória ao iniciar o foreground service de câmera+microfone após atualização em Android/One UI, com retry somente para a SecurityException específica de FGS.
- Calibração de estabilização passa a consolidar as 3 amostras pela mediana antes de gravar o aprendizado; a ordem dos testes não pode mais fazer OIS/EIS vencer indevidamente o modo mais fluido.
- Em 120/240 FPS, a telemetria de cadência passa a usar os PTS efetivamente escritos no MP4, eliminando falso 100% de irregularidade causado pelo jitter bruto da HAL; 60 FPS continua usando PTS bruto para comparar estabilização.
- CaptureResult do Auto Teste agora informa a estabilização efetiva junto dos valores EIS/OIS solicitados e aplicados.
- Mantém 4K60/120/240 sem mudança no caminho normal de gravação; o retry não adiciona atraso quando o foreground service inicia normalmente.

## 1.8.201

## 1.8.202
- Corrige a calibração de estabilização em 60 FPS: a escolha AUTO passa a usar a janela sustentada, descartando apenas 500 ms de abertura/fechamento do encoder.
- Mantém a telemetria completa no relatório para diagnóstico, mas impede um gap comum de borda de 33 ms de derrubar artificialmente todos os modos para ~58 FPS.
- Calibração de estabilização agora usa 3 amostras de 4 s e mediana, reduzindo decisões por ruído de uma gravação curta.
- O relatório separa `PTS sustentado` de `bruto completo`, deixando claro quando a perda ocorreu somente na borda.
- Lacunas no meio da gravação continuam sendo penalizadas normalmente; a correção não mascara travamentos reais.

- Corrige a telemetria bruta de cadência em 120/240 FPS: os PTS do encoder agora são analisados em ordem de apresentação ao finalizar, sem assumir a ordem de dequeue do MediaCodec e sem adicionar sort/alocação no caminho por quadro.
- Evita o falso `100% irregular` em high-speed causado por buffers entregues fora de ordem; o MP4 final continua sendo a fonte de verdade e a telemetria bruta passa a representar corretamente os timestamps pré-normalização.
- Em 120/240 FPS, ausência de `AE Priority`/`CaptureResult` passa a aparecer explicitamente como indisponibilidade da HAL em sessão high-speed, não como indício de falha de gravação.
- A calibração Off/OIS/EIS/Preview agora republica deterministicamente as amostras válidas no `StabilizationPerformanceStore` ao concluir; o vencedor medido passa a alimentar diretamente o modo Fluidez/Automático sem sobrescrever escolhas explícitas do usuário.
- Adiciona teste puro que reproduz PTS de 120 FPS fora de ordem e garante FPS/gap/irregularidade corretos.

## 1.8.200
- Corrige os falsos FAIL restantes do Auto Teste: a validade do MP4 final passa a ser a fonte de verdade da gravação, mesmo se o estado visual já tiver voltado para “Pronto para gravar” antes da leitura do teste.
- Garante as três medições de partida quando o arquivo foi realmente criado e validado, evitando encerrar 4K60 com apenas duas amostras por uma corrida de estado.
- A classificação de cadência passa a usar o MP4 final reproduzível; a cadência bruta do encoder continua no relatório como telemetria informativa, mas não reprova um arquivo com zero perdas e pacing final estável.
- Mantém a calibração Off/OIS/EIS/Preview válida quando vídeo e telemetria foram concluídos, mesmo que a mensagem terminal do serviço já tenha sido substituída pelo estado de repouso.
- Preserva integralmente o pipeline de captura, bitrate e normalização de timestamps da 1.8.199; esta versão corrige o diagnóstico sem mascarar perdas reais no arquivo final.

## 1.8.199
- Corrige o falso FAIL do Auto Teste em 4K30/4K60: o estado “Salvando original no cofre…” permanece em FINALIZING e o teste só considera sucesso após a confirmação terminal “Vídeo salvo no cofre”.
- Torna o Auto Teste mais robusto contra corrida entre encerramento do encoder, publicação/validação do MP4 e leitura do arquivo pelo diagnóstico.
- Suaviza ainda mais os timestamps de vídeo em 60/120/240 FPS: a correção gradual de fase cai para 1,5% do período nominal, reduzindo microjitter sem esconder interrupções reais longas.
- Reescreve o diagnóstico de cadência para usar p95/p99 do erro de intervalo, lacunas severas e estimativa de frames perdidos, evitando marcar como falha pequenas oscilações de poucos décimos de milissegundo.
- O relatório passa a mostrar também a cadência bruta do encoder antes da normalização, permitindo diferenciar jitter da HAL de irregularidade introduzida no MP4.
- Adiciona calibração automática de estabilização no Auto Teste em 60 FPS: Off, OIS, EIS e Preview compatíveis são gravados duas vezes e o modo Automático aprende com a cadência real do aparelho.
- O aprendizado de estabilização exige duas amostras antes de confiar em um modo e, em 60 FPS ou mais, só troca Off por OIS/EIS/Preview quando a melhora de score é significativa, evitando priorização prematura de OIS.
- Ajusta o diagnóstico de sincronismo A/V de clipes curtos para considerar o priming normal do AAC, reduzindo avisos falsos em diferenças pequenas de duração.
- Adiciona testes automatizados para publicação/finalização, estabilização automática e jitter de timestamps em alta taxa de quadros.

## 1.8.198
- O modo Automático de estabilização deixa de privilegiar OIS por regra fixa. Em 60 FPS ou mais começa pelo caminho de menor carga e aprende, por câmera/resolução/FPS, qual opção entrega melhor cadência real nas gravações concluídas.
- O aprendizado usa timestamps brutos do encoder antes da normalização, combinando FPS medido, jitter e maior lacuna; escolhas explícitas de OIS, EIS, Preview ou Off continuam sendo respeitadas integralmente.
- Remove o viés de OIS/EIS da escolha automática da câmera, para a seleção do sensor priorizar sessão, resolução, FPS e nível de hardware.
- Remove mudanças de bitrate em pleno vídeo via MediaCodec.setParameters; o teto seguro continua sendo escolhido antes de iniciar o encoder, evitando pausas introduzidas por reconfiguração do codec.
- Reduz a correção máxima do relógio de vídeo de 10% para 4% por quadro e suaviza o ganho de fase, evitando alternâncias de ~16,7 ms para ~18,3 ms em 4K60 sem esconder lacunas reais.
- O diagnóstico de cadência passa a considerar desvios acima de 4% como irregulares em 60 FPS ou mais, tornando o relatório sensível a microtravadas que antes apareciam como 0% de irregularidade.
- Adiciona a ação “Reaprender estabilização automática” nos Ajustes para limpar o histórico e recalibrar a escolha.

## 1.8.197
- Player mostra imediatamente o menu ao terminar vídeo, com reproduzir novamente e próximo vídeo; o próximo arquivo é pré-carregado durante a reprodução para evitar tela preta esperando a lista.
- Bitrate manual não é mais sobrescrito ao mudar FPS, resolução, codec ou perfil de vídeo, inclusive pelos controles rápidos da câmera; a edição rápida agora também usa seleção sem campo de texto.
- Modo automático prioriza OIS também na escolha da câmera; EIS/Preview explícitos agora exigem suporte real do perfil selecionado. O perfil Fluidez volta a usar estabilização Automática e bitrate Automático como ponto de partida, para não herdar EIS/CBR pesado de um perfil anterior.
- Fluidez estrita limita bitrates excessivos antes de iniciar o encoder e reduz dinamicamente a taxa somente se a cadência recente cair, sem descer abaixo do bitrate recomendado do modo. Fora da fluidez estrita, o limite manual de 240 Mbps continua disponível quando o encoder aceitar.
- Bitrate automático prefere VBR em 60 FPS ou mais para reduzir pressão no encoder.
- Mantido o empacotamento nativo exigido pelo downloader; o aviso do AGP não altera a gravação.

## 1.8.193

- Ajustado o widget expandido 6x1 para encaixar melhor no launcher da One UI, com dimensões adaptativas conforme a largura realmente entregue pelo host.
- O preview do 6x1 agora usa uma representação proporcional menor e dedicada, evitando recorte ou ampliação excessiva no seletor de widgets.
- O preview dinâmico continua recebendo tema, identidade visual, ícones e estado de zoom do widget real.
- O widget expandido ganhou largura mínima mais conservadora e redimensionamento horizontal, mantendo áreas de toque adequadas mesmo com margens próprias da One UI.

## 1.8.192
- Faz os widgets usarem a mesma paleta selecionada no aplicativo: shell, divisória, área do logo, botões normais e estados desabilitados agora acompanham Esmeralda, Oceano, Violeta, Âmbar, Gelo, AMOLED, Grafite, Ciano, Rosa, Rubi, Meia-noite e Cobre.
- Fundo neutro e preto puro também são refletidos no shell dos widgets, mantendo a cor de destaque do tema escolhido.
- Foto, sequência, zoom e início de gravação deixam de usar cores fixas verde/ciano e passam a usar a cor de destaque do tema; vermelho continua reservado a estados semânticos de gravação/parada.
- O seletor de widgets do Android 15+ passa a receber previews gerados com exatamente o mesmo layout e as mesmas regras visuais do widget instalado, incluindo tema, fundo, identidade, ícones neutros e disponibilidade do zoom.
- Em Android anteriores, o preview estático também passa a apontar para o próprio layout real do widget, removendo a miniatura separada que podia divergir em tamanho e proporções.
- Widgets já instalados são reaplicados na inicialização do SteadyVault, evitando manter a aparência de uma versão anterior depois de uma atualização.
- Navegação inferior e estados dinâmicos que ainda recuperavam a cor fixa do recurso passam a consultar diretamente a paleta atual.
- Notificações de gravação, foto, importação, captura protegida e otimização passam a usar a cor de destaque do tema como cor do aplicativo no sistema.

## 1.8.191
- Amplia os temas escuros para 12 opções: Esmeralda, Oceano, Violeta, Âmbar, Gelo, AMOLED, Grafite, Ciano, Rosa, Rubi, Meia-noite e Cobre, incluindo os destaques equivalentes nos widgets.
- Separa a cor do tema do estilo de fundo, permitindo manter qualquer destaque com fundo do tema, neutro ou preto puro.
- Adiciona três níveis de contraste e usa contraste forte como padrão, reforçando bordas e a diferença visual entre chaves ligadas, desligadas e desabilitadas.
- Adiciona densidade Compacta, Confortável e Espaçosa para a tela de ajustes, alterando altura e espaçamento dos controles sem diminuir a área segura de toque.
- A tela de ajustes passa a usar rolagem protegida: gestos de arrastar bloqueiam cliques por uma pequena janela após o movimento e chaves ignoram qualquer toque que vire rolagem.
- Seletores da tela de ajustes passam a exigir confirmação em “Aplicar”, evitando que um toque acidental durante a navegação altere a configuração imediatamente.
- Botões e diálogos de escolha passam a usar detecção de toque mais estrita, com tolerância menor a deslocamento e proteção compartilhada contra cliques durante o scroll.

## 1.8.190
- Revisa todos os temas escuros para manter identidade própria sem perder contraste; o AMOLED continua com preto puro e recebe contornos mais visíveis.
- Chaves de liga/desliga passam a usar contraste independente do tema: trilha ativa em cor de destaque, polegar de alto contraste, estado desligado claramente separado da superfície e estados desabilitados próprios.
- O modo “Exigir 60/120 FPS reais” agora recusa faixas variáveis como 30–60 em alta taxa de quadros; a gravação só inicia quando a HAL confirma a faixa fixa solicitada.
- Invalida a configuração antiga de câmera/encoder para refazer a seleção com as regras de cadência desta versão.
- Em alta taxa de quadros com fluidez estrita, reduz ainda mais a carga do ISP desligando correções não essenciais quando a câmera oferece modo OFF, preservando redução de ruído mínima e tone mapping rápido.
- Em 60 FPS ou mais, evita o Stream Use Case VIDEO_RECORD no modo de fluidez estrita, exceto quando EIS foi solicitado explicitamente, reduzindo a chance de tuning do fabricante priorizar processamento pesado sobre cadência.
- Mantém encoder em prioridade realtime, operating rate do FPS solicitado, sem B-frames e sem descarte de quadros em 60 FPS ou mais.
- O diagnóstico de gravação passa a medir FPS pela duração média real de todos os intervalos e usa limites mais rigorosos, identificando corretamente arquivos sustentados abaixo do FPS escolhido.

## 1.8.189
- Fotos individuais passam a usar a maior resolução JPEG realmente exposta pela Camera2, incluindo o modo de resolução máxima do sensor no Android 12+ quando a HAL oferece esse caminho.
- O botão de foto do preview agora entrega a captura ao serviço de qualidade máxima em vez do caminho rápido limitado; usa JPEG 100, processamento de nitidez/ruído em alta qualidade e aguarda foco, exposição, balanço de branco e lente estabilizarem antes do disparo.
- OIS da foto usa a mesma detecção lógica/física revisada do vídeo.
- Em 60 FPS ou mais com fluidez ativada, o modo automático escolhe o encoder por margem de desempenho anunciada pelo hardware, em vez de aceitar o primeiro encoder compatível.
- Mantém o caminho de vídeo de alta taxa com processamento ISP mínimo, sem EIS automático quando isso pode custar cadência e sem criar quadros artificiais para mascarar perdas reais.

## 1.8.188
- Corrige detecção e aplicação de OIS em câmeras lógicas/físicas quando a HAL expõe o controle de forma incompleta.
- Prioriza cadência real em 60/120 FPS, evitando EIS automático no modo de FPS estrito quando OIS seguro não está disponível.
- Adiciona tema AMOLED com fundo preto puro.
- Ajustes passam a preservar a posição da rolagem e removem campos numéricos/textuais em favor de seleções.

# 1.8.187 — 2026-08-11

- Restaura `android:appCategory="game"` para que Android/Samsung possam reconhecer o SteadyVault como workload de jogo/performance.
- Restaura `android.game_mode_config` e bloqueia explicitamente `allowGameDownscaling` e `allowGameFpsOverride`, impedindo intervenções do OEM que alterem resolução de backbuffer ou imponham FPS ao app.
- Durante toda a captura, o `CaptureService` informa `GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE`; ao finalizar ou destruir o serviço, volta para `MODE_NONE`.
- O Game State é atualizado apenas no início e no fim da captura, sem callback, thread ou processamento adicional por frame.
- Mantém `SustainedPerformanceMode`, WakeLock, prioridades das threads críticas e todo o pipeline Camera2 → Surface do MediaCodec → MediaMuxer da 1.8.186.

# 1.8.186 — 2026-08-11

- Separa responsabilidades de sessão Camera2, orquestração, monitor de saúde, finalização, métricas de início e telemetria térmica em componentes próprios, mantendo o estado crítico da gravação no mesmo fluxo e sem adicionar processamento paralelo por quadro.
- Unifica Cofre secundário e Cofre terciário em `PrivateVaultGalleryActivity` e `PrivateVaultRepositoryCore`, removendo a duplicação estrutural das duas telas.
- Implementa paginação real por lotes de 160 itens no cofre principal, secundário e terciário, carregando novas páginas conforme a rolagem em vez de materializar todos os `MediaItem` na memória.
- Mede três inícios reais por modo no Auto Teste e registra comando de início, abertura da câmera, configuração da sessão, repeating request, início do recorder, primeiro frame codificado e primeiro sample no MP4, com mediana e pior caso.
- Adiciona diagnóstico de `CaptureResult` para EIS, OIS, AWB/CCT, faixa de FPS, exposição e ISO somente no Auto Teste.
- Usa `COLOR_CORRECTION_MODE_CCT`, temperatura e tint no Android 16/API 36 quando a HAL confirma suporte, preservando o balanço de branco anterior como fallback.
- Adiciona `PowerManager.OnThermalStatusChangedListener` e thermal headroom à telemetria, sem rebaixamento automático de resolução/FPS.
- Recupera `WebView` após `onRenderProcessGone`, recriando o renderer e restaurando a URL.
- Troca screenshots de diagnóstico para PixelCopy quando a view está anexada à janela, com fallback seguro para `View.draw`.
- Sincroniza o MP4 final e o diretório em modo best-effort depois do `MediaMuxer.stop()`, reduzindo a janela de perda após finalização sem afetar FPS durante a gravação.
- Adiciona `androidTest`, Macrobenchmark com `StartupTimingMetric`, variante `benchmark`, `ProfileInstaller`, gerador de Baseline Profile e perfil inicial em `src/main/baseline-prof.txt`.

# 1.8.185 — 2026-08-11

- Muda o gesto da tela preta: dois toques rápidos agora solicitam `STOP` uma única vez e fecham imediatamente a tela discreta, sem reabrir a interface de gravação.
- Confirma o duplo toque com a vibração de parada quando a opção de vibração está habilitada; o serviço recebe um marcador de confirmação para não vibrar novamente ao finalizar o mesmo `STOP`.
- Se o duplo toque ocorrer ainda durante a preparação, a solicitação continua sendo cancelamento do usuário antes do primeiro quadro, nunca uma interrupção recuperável.
- Atualiza Ajustes, README e auditoria para tornar o duplo toque um comando explícito de parada e impedir regressão para o comportamento antigo de restaurar controles.

# 1.8.184 — 2026-08-11

- Adiciona controle adaptativo de `AE Exposure-Time Priority` no Android 16/API 36 para gravações regulares de 60 FPS ou mais quando câmera e CaptureResult confirmam suporte completo.
- Mantém AE normal enquanto a exposição e a duração real do frame permanecem dentro do orçamento do FPS; a prioridade só entra após risco sustentado de queda de cadência, evitando fixar 1/60 em cenas claras.
- Quando a prioridade entra, limita a exposição ao orçamento de um frame e deixa o AE compensar principalmente pela sensibilidade; quando o ISO chega repetidamente perto do mínimo, libera a prioridade e devolve latitude total ao AE.
- Não injeta o controlador adaptativo em `CameraConstrainedHighSpeedCaptureSession`: 120/240 FPS high-speed continuam usando o mecanismo dedicado da HAL, sem trabalho extra no caminho crítico.
- Reverte internamente uma transição de AE Priority se a HAL rejeitar a atualização do repeating request, preservando o estado real da sessão e evitando controlador dessincronizado.
- Amplia o Auto Teste para registrar suporte, modo adaptativo, alvo de exposição, exposição/frame efetivos, ISO, número de transições e motivo de ativação/desativação por modo gravado.
- Adiciona testes puros para orçamento de frame, limiar de risco de cadência, ativação e liberação por ISO, mantendo a política separada das APIs Android.

# 1.8.183 — 2026-08-11

- Faz nova revisão integral do pipeline e remove a classificação artificial como jogo/Game Mode; mantém apenas `SustainedPerformanceMode` durante gravação ativa quando suportado.
- Torna OIS estrito à câmera selecionada e ao suporte lógico confirmado; remove qualquer troca silenciosa de câmera e a promoção de OIS apenas “não verificado”.
- Corrige balanço de branco no request final de vídeo: `lockWhiteBalance` volta a valer e a redução de amarelo usa a mesma correção centralizada do preview/foto quando há medições 3A recentes.
- Centraliza estado 3A e correção de balanço de branco para evitar regras divergentes entre preview, foto e vídeo.
- Melhora o áudio de vídeo com `MediaRecorder.AudioSource.CAMCORDER`, direção de microfone frontal/traseira e timestamps de captura via `AudioTimestamp.TIMEBASE_MONOTONIC`.
- Remove `KEY_LATENCY` do encoder de alta taxa de quadros e mantém controle explícito de B-frames; informa perfil + nível do AVC/HEVC quando publicados pelo encoder.
- Preserva qualquer trecho interrompido, reproduzível ou quebrado, primeiro em “Vídeos com erro / recuperados”, fora do cache e sem publicar automaticamente no cofre normal.
- Amplia o Auto Teste: grava amostras reais nos maiores modos confirmados de 30/60/120/240 FPS, mede FPS real, intervalos irregulares e maior lacuna, valida perfil/nível/cor/áudio e diferença de duração A/V, testa a área persistente de recuperação e faz uma foto JPEG real descartável.
- Melhora o navegador para mostrar falhas de carregamento/HTTP do frame principal em vez de aparentar carregamento infinito.
- Usa um único `CaptureRequest` regular já configurado para a gravação, evitando reconstrução imediatamente antes do início; a trava de WB só é aplicada com medição 3A recente.
- Remove atributos depreciados de cor das barras do sistema no tema e reforça o auditor contra retorno de câmera dupla, GameManager, `KEY_LATENCY`, fonte `MIC`, request duplicado e fallback silencioso de OIS.
- No Android 16/API 36, declara explicitamente `CONTROL_ZOOM_METHOD_ZOOM_RATIO` quando suportado para retirar a ambiguidade entre crop e zoom ratio em 1,0x.
- Centraliza toda exportação pública dos três cofres em `PublicMediaExporter`, registra as cópias criadas pelo app e permite visualizá-las/apagá-las em “Tudo que o app salva”; falhas de exclusão permanecem registradas em vez de perder o rastreamento.
- O Auto Teste agora inclui resumo de recursos confirmados por câmera, valida o catálogo completo de armazenamento e faz uma exportação MediaStore real de uma foto de teste, reabre e remove a cópia no final.

# 1.8.182 — 2026-08-11

- Remove integralmente a captura com múltiplas câmeras do serviço, Manifest, configurações, matriz de capacidades, relatórios e interface; resta apenas um pipeline Camera2 → MediaCodec → MediaMuxer.
- Adiciona Auto Teste completo com relatório compartilhável, capturas de tela do navegador/layout, teste de armazenamento, capacidades, WebView/Google, rede, motor de mídia e gravação real curta descartada ao final.
- Preserva vídeos interrompidos ou quebrados em `filesDir/vaults/recovery`, fora do cache, e permite abrir, mover para qualquer cofre ou apagar manualmente.
- Adiciona “Tudo que o app salva”, com visão e limpeza de cofres, lixeira, recuperação, testes, logs, miniaturas, índice/bancos locais, dados privados do navegador, temporários, caches, downloads públicos registrados, dados auxiliares internos/externos e reset completo do aplicativo.
- Centraliza a configuração do WebView, usa o User-Agent da implementação realmente instalada e remove WebSQL depreciado; o Google passa a ser a página inicial simples e o Auto Teste valida carregamento/título.
- Ativa `Window.setSustainedPerformanceMode` somente quando o aparelho declara suporte, durante a tela de captura.
- Migra o build para AGP 9.3.1 + Kotlin embutido, Gradle 9.5.1, AndroidX Core 1.19.0, Activity 1.13.0, Media3 1.10.1 e WebKit 1.16.0.
- Remove APIs/UA obsoletos, código sem consumidor e reforça a auditoria estática contra resíduos de funcionalidades removidas e referências mortas.

# 1.8.180

- Gravação direta: sessão compatível configurada inicia Camera2 + encoder imediatamente, sem warm-up/benchmark bloqueante.
- Removida a opção de preparação antes da gravação e as classes de timing que bloqueavam o início.
- Aplicativo marcado como categoria game com Game Mode config próprio, sem downscaling nem override de FPS do OEM.
- Game State API sinaliza captura em tempo real como não interrompível no Android 13+.
- Mantidos foreground service, wake lock e prioridades elevadas das threads críticas.
- Foto continua JPEG 100/processamento de alta qualidade e recebe hint de carga em tempo real.

# SteadyVault 1.8.179

- Gravação prioriza início rápido e respeita resolução/FPS selecionados; cadência de aquecimento virou telemetria e não cancela nem reduz o modo.
- Primeiro CaptureResult válido libera a gravação; ausência real de quadros ainda aciona recuperação do mesmo modo.
- Encoder drena pre-roll sem gravá-lo para evitar bloqueio de Surface e vídeo preto.
- Foto única inicia foco/preparo mais rápido mantendo resolução, JPEG 100 e processamento de alta qualidade.

## 1.8.178 — 2026-08-10

- Corrige o caso em que o modo de resolução `Automática` seleciona 4K60, mas a tentativa especial de compatibilidade não era acionada porque o código só reconhecia 4K explicitamente selecionado; agora a combinação efetiva 3840×2160@60 também entra na janela completa de aquecimento.
- Mantém a primeira tentativa rápida e, somente após falha real de cadência, repete 4K60 com sessão compatível e aquecimento completo antes de concluir que o aparelho não sustenta 60 FPS.
- No Android 16/API 36, quando a câmera publica `CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY`, adiciona uma tentativa que mantém a autoexposição ativa, mas limita o tempo de exposição para caber em um quadro de 60 FPS; o FPS continua sendo validado por `SENSOR_TIMESTAMP`.
- Se a prioridade de exposição não existir ou ainda não sustentar 60 FPS e a câmera publicar `MANUAL_SENSOR`, faz uma última tentativa com `SENSOR_FRAME_DURATION` de aproximadamente 16,67 ms, exposição curta e ISO compensado a partir dos valores reais obtidos pela AE imediatamente anterior.
- O fallback manual só é usado quando os ranges de exposição/sensibilidade e o modo AE OFF são realmente suportados; caso contrário, a combinação continua sendo recusada em vez de produzir 60 FPS falsos.
- Preserva bitrate, MediaCodec/MediaMuxer, timestamps do arquivo, finalização, política térmica e os três cofres; as novas tentativas ocorrem antes do primeiro frame oficial gravado.

## 1.8.177 — 2026-08-10

- Corrige o caso real do Galaxy S25 Ultra em que 4K60 cria a sessão Camera2, mas nenhuma captura é concluída enquanto a única Surface pertence a um MediaCodec temporariamente suspenso.
- Se a primeira tentativa 4K60 não entrega nem o primeiro quadro, ela agora entra na tentativa de compatibilidade em vez de cancelar imediatamente; a compatibilidade libera a entrada do encoder apenas durante o aquecimento.
- Os frames de aquecimento são drenados e descartados enquanto a gravação oficial ainda está inativa; o muxer só recebe amostras depois que a cadência real foi validada.
- Adiciona proteção no encoder para descartar qualquer buffer de aquecimento com timestamp anterior ao início oficial, evitando quadro antigo no começo do vídeo.
- Mantém 4K60 como modo exato: não reduz resolução/FPS silenciosamente e continua recusando cadência que não sustente 60 FPS reais.

## 1.8.176 — 2026-08-10

- Corrige a falsa rejeição precoce de 4K60 em captura sem preview: a primeira tentativa continua rápida, mas se a faixa Samsung for variável (como `[30,60]`) e a cadência ainda não tiver assentado, a tentativa de compatibilidade recebe uma janela completa de aquecimento antes de declarar o modo inviável.
- A tentativa de compatibilidade 4K60 agora também é acionada por rejeição de cadência/“não sustentou 60 FPS”, além de falhas de sessão/HAL; continua sem reduzir resolução ou FPS silenciosamente.
- Corrige definitivamente `CaptureModeCatalog.resolve()` sem `return@map` ambíguo e com smart-cast explícito de `validated`, incorporando os dois erros encontrados pelo compilador Kotlin real.
- A interface deixa de chamar uma capacidade apenas enumerada de “máximo confirmado”: `DETECTED` passa a ser “máximo detectado”; somente `VALIDATED` significa gravação real bem-sucedida.
- Aumenta o cartão de status para até quatro linhas para exibir a causa completa de uma rejeição de Camera2 em vez de cortar a mensagem após os dois-pontos.
- Mantém bitrate, MediaCodec/MediaMuxer, política térmica, finalização do vídeo e os três cofres sem alteração.

## 1.8.175 — 2026-08-10

- Corrige a falha Samsung `submitRequestList: Invalid physical camera id`: OIS continua lendo metadados das lentes físicas para diagnóstico, mas nenhum `CaptureRequest` lógico recebe mais `physicalCameraId`, `setPhysicalCameraKey` ou resultado físico forçado.
- Amplia a descoberta de vídeo para unir saídas `ImageFormat.PRIVATE`, `MediaCodec` e `MediaRecorder`; no Android 15+ combinações regulares relevantes também são consultadas em runtime com `CameraDeviceSetup.isSessionConfigurationSupported`.
- Não exige mais `[60,60]` para sequer testar 60+ FPS quando o HAL anuncia uma faixa cobrindo o alvo, como `[30,60]`; com Fluidez/FPS fixo, a captura só começa se `SENSOR_TIMESTAMP` comprovar a cadência real e tenta outra configuração antes de salvar um falso 60 FPS.
- Corrige o catálogo de modos para a matriz atual da câmera selecionada vencer histórico antigo; um 1080p60 gravado anteriormente não pode esconder um 4K60 recém-confirmado nem reaparecer em outra lente incompatível.
- Invalida o cache antigo da matriz de capacidades e melhora o relatório de diagnóstico para listar os maiores modos efetivamente confirmados por câmera, FPS e encoder.
- Incorpora as três correções encontradas pelo build Android real: `ByteArray.indexOf` sem `startIndex`, `fastPreviewCapture` removido em favor de `fastSingleCapture` e `Context` explícito no indicador do widget.
- Remove as duas flags WebView de file-URL obsoletas e migra a ação de cancelar importação para `Notification.Action`, reduzindo warnings sem tocar na gravação.
- Mantém política térmica, três cofres independentes, bitrate/codec e finalização audiovisual existentes.

## 1.8.174 — 2026-08-10

- Corrige a combinação de build para Android 16/API 36: Android Gradle Plugin `8.10.1` com Gradle Wrapper `8.11.1` e Kotlin `2.2.10`, mantendo JDK 17 no CI e no alvo JVM.
- Fixa o SHA-256 oficial da distribuição Gradle 8.11.1 no Wrapper e valida a integridade do launcher para impedir um pacote de build ausente ou adulterado.
- Adiciona `android:extractNativeLibs="true"`, exigido pelo conjunto `youtubedl-android`/FFmpeg/aria2c usado pelo downloader para disponibilizar os binários nativos no aparelho.
- Corrige o executável externo do downloader para `libaria2c.so`, conforme a integração Android da biblioteca, evitando falha ao tentar chamar o nome desktop `aria2c`.
- Torna `androidx.fragment:fragment-ktx:1.8.9` uma dependência explícita porque o app usa `FragmentActivity` diretamente, eliminando dependência acidental de classpath transitivo.
- Executa pente-fino de compilação: parser real do compilador Kotlin em todos os fontes/scripts, índice completo de recursos Android, imports internos, classes do Manifest, custom views, packages, FQCNs duplicados e referências canônicas dos três cofres.
- Reforça o auditor para validar também IDs gerados por layout, recursos em diretórios qualificados, scripts completos do Gradle Wrapper e repositórios de dependências, distinguindo corretamente recursos locais de `android.R`.
- Mantém o caminho crítico de gravação sem alterações funcionais nesta revisão.

## 1.8.173 — 2026-08-10

- IDs internos dos cofres padronizados para `primary`, `secondary` e `tertiary` para instalação limpa.
- Classes da área principal/secundária renomeadas para nomes semânticos (`PrimaryVault*` / `SecondaryVault*`).
- Diretórios privados padronizados em `vaults/primary`, `vaults/secondary` e `vaults/tertiary`.
- PIN, biometria, importação, recuperação, lixeira, navegador, captura de tela e extras passaram a usar a mesma identidade canônica.
- Layouts secundário e terciário possuem recursos/IDs próprios; não existe mais `decoy` na produção.
- Compatibilidade de IDs antigos removida porque esta versão é destinada a instalação limpa.

## 1.8.172 — 2026-08-10

- Padroniza a nomenclatura visível para `Cofre principal`, `Cofre secundário` e `Cofre terciário`, além de `Lixeira dos cofres`, `Importar para o cofre`, `Otimização após gravação` e `Captura privada`; identificadores internos legados (`main`, `secondary`, `tertiary`) permanecem inalterados para preservar preferências, PINs, biometria, filas e arquivos existentes.
- Mantém integralmente o caminho de início, aquecimento, Camera2, `HardwareRecorder`, bitrate, codec, estabilização e política térmica da 1.8.171; não adiciona limite de duração nem parada automática por tempo.
- Remove propriedades e contadores comprovadamente sem consumidores e amplia a auditoria para variáveis sem uso global, nomenclatura atual, preservação dos três cofres, finalização completa do encoder e ausência de limite artificial de duração.
- Restaura `gradle-wrapper.jar`, que havia ficado ausente no pacote 1.8.171, permitindo que `gradlew` inicialize corretamente antes de resolver a distribuição configurada.

## 1.8.171 — 2026-08-10

- Adiciona Assistente de calibração do aparelho sem alterar o pipeline de gravação: testa a matriz real Camera2, valida combinações de sessão em runtime no Android 15+ e inicializa de fato o encoder de hardware com Surface antes de recomendar qualquer modo.
- Cria perfis automáticos Fluidez, Qualidade e Máximo do hardware; o perfil Fluidez prioriza 60 FPS sustentáveis e só altera resolução, FPS, codec, bitrate e controles de cadência quando o usuário escolhe Aplicar. Áudio, cor, segurança, cofres e configuração térmica atual são preservados.
- Adiciona Saúde da última gravação, analisada somente depois que o MP4 final já foi publicado: mede FPS real pelos timestamps, intervalos irregulares, maior lacuna, bitrate e codec em thread de background; se uma nova captura começa, a análise para imediatamente e é remarcada para depois.
- Torna a cópia de importação transacional em cada um dos três cofres independentes: escreve em `.svimport.partial`, sincroniza os dados com `fsync`, valida tamanho e só então publica o nome final por rename atômico quando possível; temporários de interrupção ficam visíveis no Diagnóstico.
- Adiciona medição leve de desempenho de foto (preparo, sensor/JPEG, escrita e tempo total) sem alterar qualidade, câmera ou processamento da captura.
- Endurece o navegador privado com HTTPS estrito por padrão; mixed content passa a exigir opção explícita de compatibilidade.
- Mantém sem mudanças a política térmica existente e mantém Cofre principal, Cofre secundário e Cofre terciário como áreas/repositórios separados.
- Amplia a validação contínua com JUnit da política de auto-configuração e `lintDebug` no CI, além da auditoria estática de imports, referências, recursos, widgets e código morto já existente.

## 1.8.170 — 2026-08-10

- Move toda importação de arquivos e pastas para um foreground service `dataSync` com fila persistente em `noBackupFilesDir`, wake lock renovável, progresso por mídia e posição salva após cada item concluído.
- Adiciona cancelamento da importação diretamente pela notificação e mantém o cancelamento seguro também durante a varredura de pastas; temporários e fila restante não são descartados silenciosamente.
- Trata o limite de execução do Android 15+ como pausa distinta de cancelamento: preserva a fila, publica aviso acionável e retoma quando o usuário reabre o cofre, sem reimportar itens já concluídos.
- Mantém gravação/câmera com prioridade sobre importação: o worker usa prioridade de background e espera entre arquivos enquanto uma captura crítica está ativa; Ajustes também ganha atalho direto para a política de otimização de bateria do aparelho.
- Adiciona identidade discreta configurável para widgets e notificações, com perfis SteadyVault, Arquivos, Utilitário, Notas e Câmera, rótulo personalizado, textos neutros e um conjunto opcional de ícones genéricos nos widgets; posições, ações e acessibilidade continuam funcionais.
- Adiciona 8K UHD à matriz de resolução e ao modo automático, mas somente expõe/seleciona 8K quando Camera2 e um encoder de hardware confirmam a combinação de resolução/FPS; preserva 4K60 como perfil recomendado para fluidez no Galaxy S25 Ultra.
- Remove imports e recursos duplicados surgidos durante a integração e reforça a auditoria para imports, declarações privadas, arquivos Kotlin, recursos órfãos, widgets, referências locais e prioridades de background.

## 1.8.169 — 2026-08-10

- Adiciona a Central de recuperação protegida por PIN/biometria, lista temporários recuperáveis/incompletos e vídeos já recuperados, permite tentar recuperação, abrir e mover o resultado entre cofres e elimina exclusão automática por idade de gravações interrompidas.
- Adiciona Diagnóstico e armazenamento com logs de erro/aviso, histórico de saídas do processo no Android 11+, captura de exceções não tratadas, tamanho dos cofres/caches/recuperação e limpeza individual ou segura sem tocar nos brutos recuperáveis.
- Reorganiza importações grandes em fila sequencial cancelável, com painel de progresso que não cobre a galeria; leitura por pasta também respeita cancelamento e a limpeza de estado não remove gravações protegidas.
- Mantém o caminho sem preview dedicado exclusivamente ao encoder e acrescenta tela preta opcional por origem (app, widget e atalhos), brilho mínimo, barras imersivas e dois toques para restaurar os controles sem interromper a gravação.
- Torna o botão Parar e salvar da notificação utilizável na tela de bloqueio com canal público de conteúdo genérico e canal versionado, mantendo início/parada por app e widgets.
- Expande a personalização para cinco temas, quatro famílias de fonte, três níveis de cantos e três níveis de contorno; telas, diálogos dinâmicos, PIN, spinners, corte de vídeo, overlay de captura e widgets acompanham a aparência selecionada.
- Publica previews dinâmicos dos widgets no Android 15+ e mantém previewLayout escalável como fallback; esconde zoom do widget quando a lente selecionada não oferece zoom útil.
- Acelera foto única com foco contínuo quando suportado, caminho de assentamento reduzido e ZSL para captura still quando disponível, preservando a qualidade JPEG configurada.
- Amplia o navegador/downloader independente com abertura de links http/https, mídia direta, análise por extrator, sessão/cookies do navegador, perfis rápido/equilibrado/compatível, Wi‑Fi opcional, aria2, metadados, checagem de espaço, seleção de qualidade/resolução/destino e cancelamento real de downloads para o cofre.
- Remove qualquer branding herdado do downloader de referência e elimina o atalho que abria a tela genérica de ajustes do launcher; os ícones extras do próprio SteadyVault passam a ser controlados dentro do app.
- Reforça a auditoria para falhar com controles XML sem referência de runtime, placeholders TODO/FIXME, branding externo, configuração de vídeo fora da ordem, recuperação/cancelamento ausentes, preview dinâmico ausente e regressão da tela genérica do launcher.

## 1.8.168 — 2026-08-10

- Cria uma matriz única e persistente de capacidades por câmera, lente física, resolução, FPS e encoder; a primeira abertura analisa em segundo plano e as seguintes já usam o retrato válido, com nova leitura após permissão, mudança de firmware ou versão do esquema.
- Mantém metadados incompletos como `não verificados`, permitindo a validação pelo request real em vez de esconder uma função por falso negativo; negativas conclusivas continuam removendo o controle e normalizando uma configuração antiga para um valor seguro.
- Torna o OIS tolerante a HALs contraditórias que anunciam apenas `OFF` na câmera lógica, mas aceitam `ON`: consulta lentes físicas, usa overrides físicos quando disponíveis, tenta a chave lógica e memoriza a aceitação do request por câmera.
- Remove da interface o item ainda não implementado de zebra/focus peaking e oculta escolhas de processamento que o pipeline high-speed deliberadamente entrega à HAL para preservar a cadência.
- Adiciona testes puros para a política de suporte e para o fallback OIS, além de ampliar a auditoria estática da detecção lógica/física.

## 1.8.167 — 2026-08-10

- Corrige o falso aviso `OIS indisponível` em câmeras lógicas, consultando também as características das lentes físicas que compõem o conjunto multi-câmera.
- Usa a chave de request como fallback somente quando o metadado opcional de modos OIS está ausente, preservando uma negativa explícita de uma lente realmente sem estabilização.
- Cria requests com override físico quando o HAL oferece essa capacidade e aplica OIS tanto no request lógico quanto na lente física adequada.
- Não trata a omissão de `LENS_OPTICAL_STABILIZATION_MODE` no resultado como OIS desligado; somente um `OFF` explícito impede a confirmação inicial.
- Quando OIS foi escolhido manualmente e a câmera presa ao preview não oferece o recurso, procura outra câmera traseira OIS-capaz sem reduzir resolução nem FPS; o modo automático continua priorizando OIS em 4K60.
- Alinha o preview à mesma prioridade OIS → EIS → Preview Stabilization usada na gravação de alta taxa e adiciona testes puros para metadado lógico, físico, ausente e explicitamente desligado.

## 1.8.166 — 2026-08-09

- Elimina os saltos artificiais de 33 ms causados pelo arredondamento absoluto dos timestamps; a deriva normal da câmera agora é corrigida gradualmente sem perder sincronismo com o áudio.
- Suspende a entrada do encoder durante o aquecimento quando o codec oferece suporte, impedindo quadros antigos de chegarem atrasados depois do início oficial.
- Reutiliza `MediaCodec.BufferInfo` no áudio e no muxer e remove a função exponencial por amostra do limitador, reduzindo alocações, CPU e pausas de coleta de lixo no caminho de 60/120 FPS.
- Atualiza o watchdog no recebimento do quadro pelo encoder, sem interpretar uma espera transitória de escrita como congelamento da câmera.
- Reserva a saída Camera2 exclusivamente para o encoder em 60/120 FPS, exige suporte declarado à taxa escolhida no modo suave e evita trocar o repeating request durante a recuperação de um arquivo já em gravação.
- Aplica a prioridade da gravação a todos os pontos de entrada, faz a captura de tela ceder o encoder, rebaixa otimização, downloads e miniaturas e pausa importações entre arquivos enquanto a câmera estiver ativa.
- Amplia os testes de timestamps para deriva prolongada, regressão, lacuna curta suavizada e interrupção longa preservada.

## 1.8.165 — 2026-07-31

- Restaura o caminho leve de início da referência 1.8.161: o serviço volta a apenas publicar o estado por broadcast, sem persistir `SharedPreferences` no executor que prepara e abre a câmera.
- Mantém fase, proprietário e identificador da sessão no broadcast; o receptor principal persiste esses dados fora do caminho crítico e continua restaurando todos os widgets ao parar.
- Substitui `UUID.randomUUID()` por um identificador monotônico sem inicialização de entropia durante o clique de gravação.
- Prepara o monitor de espaço antes da abertura da câmera, evitando criar sua thread no instante dos primeiros quadros; as consultas continuam somente a cada 30 segundos e em prioridade de fundo.
- Preserva a publicação transacional, a recuperação dos trechos, o bloqueio de finalização concorrente e a suspensão de manutenção durante a gravação.
- Confirma o mesmo `HardwareRecorder`, Camera2, MediaCodec, resolução, FPS, bitrate, codec, HDR, estabilização, áudio e zoom da versão de referência.

## 1.8.164 — 2026-07-31

- Corrige a transição final dos widgets: ao terminar de salvar, todos os tamanhos recebem uma atualização forçada e voltam imediatamente aos botões normais.
- Elimina a comparação incorreta no receptor de estado que ignorava o fim da gravação porque o serviço já havia persistido o novo estado antes do broadcast.
- Adiciona uma conclusão de sessão vinculada ao identificador da gravação, liberando os controles sem apagar o estado de uma captura posterior.
- Retira a consulta de espaço do monitor de cadência executado a cada 500 ms; a proteção passa a rodar a cada 30 segundos em thread de baixa prioridade, com reserva suficiente para finalizar o arquivo.
- Adia e suspende manutenção, recuperação e limpeza inicial enquanto uma câmera estiver gravando, evitando disputa de CPU e armazenamento com o encoder.
- Mantém inalterados resolução, FPS, bitrate, codec, HDR, estabilização, áudio, zoom e o fluxo direto Camera2/MediaCodec.

## 1.8.163 — 2026-07-31

- Renomeia para “Excluir” o botão superior exibido durante a seleção de mídias no cofre, pois ele abre tanto a opção recuperável de mover para a lixeira quanto a exclusão definitiva.
- Mantém o botão separado “Lixeira” para abrir a lixeira protegida e preserva integralmente o comportamento de remoção, a gravação e a qualidade capturada.

## 1.8.162 — 2026-07-31

- Preserva exatamente a resolução, FPS, bitrate, codec, HDR, estabilização, áudio e zoom selecionados; nenhuma etapa nova recodifica a gravação original.
- Torna a publicação do MP4 transacional: tenta movimentação atômica, sincroniza e valida a cópia de fallback e mantém o bruto quando não puder provar que o destino ficou íntegro.
- Mantém o serviço com heartbeat, wake lock renovável, recuperação de sessão e monitor contínuo de espaço; ao atingir espaço crítico, finaliza e preserva o trecho em vez de perder o arquivo.
- Abre o vídeo antes de ler FPS e timestamps, executa a análise em thread de baixa prioridade e mantém o seek rápido durante reprodução e exato quando pausado.
- Pré-carrega os frames do corte após o primeiro assentamento do player e mantém cache persistente invalidado automaticamente quando a mídia é movida ou excluída.
- Adiciona índice SQLite persistente, paginação inicial e resumo do cofre, removendo varreduras repetidas e exibindo as primeiras mídias mais cedo.
- Serializa recuperação, manutenção e limpeza inicial e inicializa o motor de download somente quando necessário, reduzindo disputa de CPU e disco com câmera e player.
- Torna imports e downloads duráveis, canceláveis e monitorados por espaço, restringe o FileProvider aos diretórios de mídia e reforça a configuração segura do WebView.
- Persiste o índice de apps lançáveis, invalida-o em instalação/remoção, mantém biometria/PIN em Ajustes e adiciona limitação progressiva de tentativas de PIN.
- Amplia a verificação de espaço dos presets manuais pela duração e bitrate, atualiza a auditoria estática e adiciona testes de estado e publicação sem perda.

## 1.8.161 — 2026-07-30

- Deixa a tela Apps protegidos dedicada apenas a adicionar, remover, visualizar, abrir e bloquear os atalhos selecionados.
- Centraliza em Ajustes o PIN dos apps, biometria, acesso aos ajustes de ocultação do launcher e a autorização de print/gravação de tela protegidos.
- Move integralmente para Ajustes a escolha do cofre de destino, a validação do PIN e as permissões de sobreposição, notificação e projeção de tela, sem manter métodos ou controles duplicados na tela Apps.
- Substitui nos três widgets reais os `ImageButton` por `ImageView` clicáveis via `RemoteViews`, eliminando o flash visual de estado pressionado sem liberar comandos durante foto, preparação ou gravação.
- Mantém `alpha=1` no layout inicial e em cada atualização parcial dos controles, evitando que o widget herde um estado esmaecido anterior.
- Torna opacas internamente as cores do widget de bloqueio, preservando a mesma aparência escura e discreta sem depender da transição do papel de parede/AOD ao acender a tela.
- Atualiza a prévia do widget de bloqueio com as mesmas cores estáveis do widget real e preserva os demais previews.
- Mantém inalterados Camera2, encoder, 60/120 FPS, bitrate, áudio, zoom, player, corte, recuperação e comandos de início/parada.
- Amplia a auditoria contra configurações duplicadas na tela Apps, botões de widget com estado pressionado, alpha visual incorreto e recursos sem uso.

## 1.8.160 — 2026-07-28

- Reestrutura o caminho crítico de 60/120 FPS para capturar quadros reais diretamente por Camera2 e MediaCodec, sem interpolação nem processamento pesado obrigatório.
- Restaura o muxer direto e de baixa sobrecarga da referência estável: áudio e vídeo são drenados por seus próprios threads, eliminando cópias e descargas de blocos AAC no thread de vídeo.
- Faz a estabilização automática em 60/120 FPS preferir OIS, quando disponível, antes de EIS e Preview Stabilization, reduzindo a carga de recorte e transformação por quadro; escolhas manuais continuam respeitadas.
- Mantém faixa fixa `[60,60]`, parâmetros de sessão Camera2, taxa operacional do encoder, prioridade em tempo real, B-frames desativados e descarte de frames desabilitado quando suportado.
- Preserva 4K, HEVC, bitrate, áudio, zoom, início rápido, gravação com tela apagada, parada explícita e recuperação preventiva dos trechos.
- Desliga uma única vez o reparo automático herdado e o mantém desligado por padrão; ele continua disponível como opção explícita e não inventa imagens.
- Renomeia a opção de alta taxa para “Exigir 60/120 FPS reais”, deixando claro que ela exige uma faixa fixa confirmada pela câmera e não é um filtro posterior.
- Amplia a auditoria estática para impedir o retorno de filas de áudio no thread do vídeo ou da estabilização digital prioritária no modo automático de 60 FPS.

## 1.8.159 — 2026-07-28

- Corrige a finalização pelo botão da notificação para que um MP4 bruto com qualquer dado gravado nunca seja tratado como uma gravação cancelada.
- Usa o conteúdo real do arquivo temporário, além do marcador de início, para decidir entre publicar, recuperar ou reservar o trecho.
- Se a finalização normal do muxer falhar, tenta publicar imediatamente o vídeo válido; se ainda não estiver legível, mantém o bruto protegido para recuperação automática.
- Aplica a mesma precaução às duas saídas da câmera simultânea, sem apagar arquivos não finalizados que contenham dados.
- Altera a ação da notificação para “Parar e salvar”, deixando explícito que o comando deve finalizar e preservar o trecho.
- Remove a mensagem ambígua “Gravação cancelada” do fluxo que poderia conter dados e amplia a auditoria contra regressão.
- Mantém inalterados encoder, resolução, FPS, bitrate, estabilização, áudio, zoom, preview e qualidade da gravação.

## 1.8.158 — 2026-07-28

- Corrige o botão Parar nos widgets, inclusive na tela de bloqueio, enviando o comando diretamente ao `CaptureService` que a captura rápida iniciou.
- Remove a etapa intermediária de broadcast que a One UI podia deixar pendente com o aparelho bloqueado.
- Mantém a autorização explícita do usuário para parar e preserva a finalização segura do arquivo antes de liberar os controles.
- Torna o botão Parar da tela inicial idempotente: os serviços principal e simultâneo recebem tentativas independentes, sem uma falha impedir o comando correto.
- Renova automaticamente as ações dos três widgets após atualizar o APK, evitando que algum deles conserve o clique antigo.
- Exclui o receptor antigo e amplia a auditoria para impedir código solto ou regressão no caminho direto de parada.
- Mantém inalterados encoder, resolução, FPS, bitrate, estabilização, áudio, zoom, preview e qualidade da gravação.

## 1.8.157 — 2026-07-28

- Padroniza o zoom persistente `0,6x`, `1x`, `3x` e `5x` entre Foto, Sequência e Vídeo nas capturas rápidas sem preview.
- A foto seleciona a mesma lente física do zoom exibido no widget e aplica a proporção residual tanto na preparação Camera2 quanto no request JPEG final.
- Aplica o mesmo comportamento aos botões rápidos da tela inicial e aos widgets 6×1 e de bloqueio.
- Mantém o zoom interno do preview totalmente independente, sem alterar lente, enquadramento ou controles já aprovados.
- Atualiza descrições de acessibilidade para indicar “zoom da foto e do vídeo”, sem adicionar textos visíveis aos widgets.
- Amplia a auditoria para impedir que o zoom da foto volte a ficar restrito a apenas um widget ou deixe de alcançar o JPEG final.

## 1.8.156 — 2026-07-28

- Corrige o início lento pelo widget da tela de bloqueio quando o reconhecimento facial da One UI ocupa temporariamente o subsistema Camera2.
- Trata `ERROR_CAMERA_IN_USE` e `ERROR_MAX_CAMERAS_IN_USE` como disputa transitória, sem marcar o perfil 4K/FPS como incompatível.
- Mantém câmera, perfil e encoder já preparados e repete somente `openCamera` assim que o Android sinaliza a lente disponível.
- Usa uma verificação de segurança de 90 ms quando o firmware não envia o callback de disponibilidade, evitando tanto espera longa quanto tentativas agressivas contra o reconhecimento facial.
- Cancela e remove o callback de disponibilidade ao abrir a câmera, parar ou destruir o serviço, sem deixar métodos ou listeners soltos.
- Mantém inalterados aquecimento, foco, estabilização, resolução, FPS, bitrate, codec, áudio, gravação, preview e widgets.

## 1.8.155 — 2026-07-27

- Corrige a compilação de `RecordingRecoveryRepository`: o fallback de `canonicalPath` agora usa uma variável `File` nomeada, impedindo que o parâmetro `Throwable` de `getOrElse` seja tratado incorretamente como arquivo.
- Mantém integralmente as proteções de continuidade e recuperação da 1.8.154, sem alterar o pipeline ou os parâmetros da gravação.

## 1.8.154 — 2026-07-27

- Mantém a gravação de câmera ativa até um comando explícito no botão Parar; fechar o preview e tocar duas vezes na tela preta deixam de ser atalhos de encerramento.
- Adiciona heartbeat do foreground service e monitor contínuo de quadros para detectar suspensão da câmera após bloquear, apagar ou reacender a tela.
- Reabre a sessão Camera2 com tentativas contínuas quando outra câmera, o firmware ou a troca de estado da tela interrompe a entrega de quadros, mantendo o mesmo encoder e o mesmo arquivo sempre que possível.
- Valida o MP4 por amostras reais de vídeo antes de descartar qualquer temporário e mantém candidatos não recuperados por sete dias para nova tentativa.
- Protege temporários de gravação contra o botão de limpeza, recupera pendências no início do aplicativo e preserva gravações de tela mesmo quando a finalização normal do MediaRecorder falha.
- Mantém inalterados resolução, FPS, bitrate, codec, HDR, estabilização, áudio, aquecimento inicial e todo o pipeline de qualidade da gravação.

## 1.8.148 — 2026-07-26

- Corrige o aspecto circular dos botões nas três prévias usando raio proporcional de 9 dp para os controles representativos de 34 dp.
- Reproduz em fundos exclusivos de prévia as mesmas cores, gradientes e contornos dos botões reais de Zoom, Sequência, Foto, Gravar e Parar.
- Aplica a mesma proporção arredondada ao cartão do logo na prévia 6×1, mantendo todos os botões como quadrados arredondados.
- Preserva integralmente os três widgets reais, o compartilhamento de links do MediaGrab, os tamanhos das prévias e o restante do aplicativo.
- Confirma por auditoria que os novos recursos estão referenciados e que não existem arquivos, recursos, imports ou declarações privadas sem uso.

## 1.8.147 — 2026-07-26

- Adiciona o MediaGrab ao compartilhamento de texto do Android e abre a primeira URL HTTP/HTTPS recebida diretamente no navegador interno, mantendo disponíveis a análise da página e os destinos de download já existentes.
- Transforma o navegador em uma única instância de tarefa, impede documentos separados e trata novos compartilhamentos em `onNewIntent`, evitando vários cartões do SteadyVault nos aplicativos recentes.
- Adiciona um parser isolado e testado para extrair links de mensagens compartilhadas sem carregar como endereço o texto adicional enviado pelo aplicativo de origem.
- Aumenta somente de 6 para 8 dp as margens inicial e final do widget real 6×1 e ajusta sua largura declarada para 393 dp, sem alterar botões nem espaços entre controles.
- Substitui nas prévias 4×1 e 6×1 os fundos de contorno interno por versões representativas de contorno único, eliminando os anéis duplicados vistos no seletor da One UI.
- Usa uma lupa de 18 dp nas prévias e amplia o controle de zoom do bloqueio para 34 dp, fazendo o valor `1x` aparecer inteiro sem alterar o widget real.
- Confirma por auditoria que não ficaram recursos, arquivos, imports ou declarações privadas sem uso e mantém intactos gravação, player, corte, captura, cofres, senha e biometria.

## 1.8.146 — 2026-07-26

- Corrige os cantos apagados das barras 6×1 e compacta movendo somente o contorno 2 dp para dentro do fundo, fora da área recortada pela máscara da One UI.
- Preserva integralmente os tamanhos, as cores, o raio externo de 16 dp, os botões e todos os espaços internos dos widgets 6×1 e compacto da versão anterior.
- Remove do widget real da tela de bloqueio o fundo, o gradiente e a borda externos, mantendo apenas Zoom, Foto, Gravar e Parar sobre contorno totalmente transparente.
- Atualiza as três prévias com os mesmos fundos e contornos dos widgets reais; a prévia de bloqueio também passa a exibir somente os quatro botões.
- Exclui o drawable de bloqueio que ficou sem referência e confirma por auditoria que não existem recursos, arquivos, imports ou declarações privadas sem uso.
- Mantém sem alterações a gravação, o player, o corte, a captura, a aba de apps, a senha e a biometria.

## 1.8.145 — 2026-07-26

- Reverte integralmente no widget real 6×1 e no compacto as cores, os fundos, os contornos, as alturas e os espaços internos da versão 1.8.143.
- Restringe a correção de excesso vertical ao widget da tela de bloqueio: a barra de 48 dp fica centralizada na área transparente do host e mantém 2 dp de folga acima e abaixo dos botões de 44 dp.
- Mantém os contornos originais discretos dos três widgets e o raio de 16 dp, sem introduzir nova cor de borda.
- Conserva as prévias menores, mas devolve suas margens externas e folgas internas: 6×1 com 233×52 dp, compacto com 164×52 dp e bloqueio com 142×40 dp.
- Preserva no bloqueio a ordem Zoom, Foto, Gravar e Parar e mantém todo o comportamento sem piscadas.
- Mantém sem alterações a gravação, o player, o corte, a captura, a aba de apps, a senha e a biometria.

## 1.8.144 — 2026-07-26

- Restaura a leitura colorida dos cantos com um contorno único de 1,5 dp na cor de destaque, mantendo o raio padronizado de 16 dp nos três widgets e nas respectivas prévias.
- Separa a área transparente reservada pelo host da barra visível, impedindo que a One UI estique o fundo acima e abaixo dos botões.
- Reduz a altura visível do 6×1 para 66 dp, do compacto para 60 dp e do widget de bloqueio para 46 dp, sem diminuir os botões reais de 62, 58 e 44 dp.
- Reduz novamente somente as prévias: o 6×1 passa a 225×46 dp, o compacto a 156×44 dp e o bloqueio a 138×36 dp.
- Preserva no widget de bloqueio e em sua prévia a ordem Zoom, Foto, Gravar e Parar, além do comportamento sem piscadas.
- Mantém sem alterações a gravação, o player, o corte, a aba de apps protegidos, a senha e a biometria.

## 1.8.143 — 2026-07-25

- Padroniza em 16 dp os cantos das barras 6×1, compacta e de bloqueio, do cartão do logo, de todos os botões e das respectivas prévias.
- Adiciona Foto ao widget real da tela de bloqueio, mantendo a ordem Zoom, Foto, Gravar e Parar e reutilizando a mesma captura direta dos demais widgets.
- Separa no renderer as capacidades Foto e Sequência, permitindo ao widget de bloqueio oferecer Foto sem referenciar um botão de sequência inexistente.
- Atualiza o provider da tela de bloqueio para 4×1 e 186×48 dp, preservando os botões discretos de 44 dp.
- Reconstrói a prévia desse widget com os mesmos quatro controles, ordem, cores e ícones, em escala representativa de 166×44 dp.
- Mantém a atualização sem piscadas, o carregamento otimizado de apps, a biometria e todo o pipeline de gravação da versão anterior.

## 1.8.142 — 2026-07-25

- Reduz novamente somente a miniatura 6×1, deixando seus módulos com 42 dp e uma representação centralizada de 281×60 dp.
- Torna o widget real da tela de bloqueio menor e mais discreto: 140×48 dp, botões de 44 dp, borda de menor contraste e cantos externos de 14 dp.
- Ajusta a prévia 3×1 para 128×44 dp, preservando a mesma proporção e os cartões arredondados do widget real.
- Mantém Gravar e Parar tecnicamente habilitados nos três widgets e representa a disponibilidade por ícone, cor e ação segura, evitando a animação de transparência da One UI que causava piscadas ao iniciar ou parar.
- Acelera a aba Apps protegidos com bloqueio de consultas duplicadas, cache da lista por 10 minutos, carregamento inicial somente dos atalhos selecionados e ícones visíveis carregados progressivamente.
- Adiciona dentro da própria aba Apps o seletor `Acessar com biometria`, sincronizado com a configuração já disponível nos ajustes e mantendo `Usar PIN` como alternativa.
- Mantém sem alterações o pipeline de gravação, o player, o editor de corte e o preview da câmera.

## 1.8.141 — 2026-07-25

- Transforma as miniaturas 3×1, 4×1 e 6×1 em representações menores, centralizadas e independentes do tamanho total da célula do seletor.
- Reduz somente os controles das prévias para 40, 42 e 48 dp, respectivamente, criando folga suficiente para a máscara da One UI não cortar as bordas.
- Mantém nas três prévias os mesmos cartões, cantos de 16 dp, cores, ícones e estados visuais usados pelo widget 6×1.
- Confirma que os três widgets reais já compartilham o padrão arredondado do 6×1 e preserva integralmente seus tamanhos, comandos e comportamento.
- Mantém sem alterações a gravação, o player, o editor de corte e o preview da câmera.

## 1.8.140 — 2026-07-25

- Acrescenta 6 dp de respiro interno nas laterais da barra do widget 6×1 real e de sua miniatura no seletor.
- Amplia a largura mínima do 6×1 de 379 para 389 dp para acomodar as novas margens sem reduzir ou deformar logo e botões.
- Mantém os seis módulos do widget real com 62×62 dp e preserva sua ordem, cores, ícones e funcionamento.
- Mantém sem alterações os widgets 4×1 e da tela de bloqueio, a gravação, o player, o editor de corte e o preview da câmera.

## 1.8.139 — 2026-07-25

- Corrige exclusivamente a miniatura do widget 6×1 no seletor, removendo o aspecto estourado nas bordas da One UI.
- Reduz proporcionalmente logo e controles da prévia de 60 para 56 dp e reserva uma margem externa própria de 6 dp.
- Preserva a mesma ordem, cores, ícones, cartões arredondados e proporção visual do widget instalado.
- Mantém sem alterações o widget 6×1 real, seu provider, seus comandos, o 4×1, o widget da tela de bloqueio, a gravação, o player e o preview da câmera.

## 1.8.138 — 2026-07-25

- Padroniza os quatro controles do widget 4×1 com os mesmos cartões arredondados, cores e ícones do widget 6×1, eliminando os botões circulares antigos.
- Faz a miniatura do 4×1 reproduzir o desenho do widget instalado, mantendo os botões grandes e inteiramente dentro da área declarada.
- Ajusta somente a miniatura do widget da tela de bloqueio de 46 para 44 dp, evitando estouro lateral sem reduzir os botões de 48 dp do widget real.
- Preserva por comparação integral o layout, a miniatura, o provider e todas as dimensões do widget 6×1 da versão 1.8.137.
- Mantém sem alterações a gravação, o player, o editor de corte e o preview da câmera.
- Confirma por auditoria que não ficaram métodos, imports, recursos ou arquivos sem uso.

## 1.8.137 — 2026-07-25

- Confirma no novo vídeo analisado 441 quadros em 60 FPS contínuos, sem frames duplicados, lacunas de timestamps ou congelamentos do encoder.
- Confirma o alinhamento final corrigido: a diferença entre o fim do áudio e do vídeo caiu de aproximadamente 438 ms para cerca de 8 ms.
- Centraliza os títulos `ACESSO RÁPIDO`, `MODO DE VÍDEO`, `VÍDEO`, `FOTO` e `SISTEMA` dentro dos respectivos cards da tela inicial.
- Troca o azul do estado ativo do botão Gravar por amarelo real em todos os formatos de widget, mantendo o botão Parar vermelho.
- Remove a reinflação completa dos widgets na inicialização fria do aplicativo e ignora atualizações parciais repetidas quando o estado visual não mudou.
- Ao iniciar ou parar, atualiza uma única vez somente Gravar e Parar; zoom, sequência, foto e logotipo permanecem estáveis e deixam de piscar.
- Aumenta o cartão e o ícone do SteadyVault no widget 6×1 para 62×62 dp, igualando o tamanho dos cinco botões sem reduzi-los.
- Sincroniza a nova proporção do logotipo com a miniatura do seletor e amplia a largura mínima do widget para evitar compressão.
- Mantém sem alterações o pipeline de gravação da 1.8.136, o player, o editor de corte e o preview da câmera.
- Confirma por auditoria que não ficaram métodos, imports, recursos ou arquivos sem uso.

## 1.8.136 — 2026-07-25

- Prioriza a captura urgente ao iniciar pelo widget ou pela tela inicial, mantendo a estabilização eletrônica avançada da 1.8.135.
- Reduz o aquecimento sem preview para cerca de 50–80 ms em 60 FPS quando a câmera responde normalmente e impõe limite por quadros próximo de 120 ms.
- Mantém um timeout de segurança de 250 ms apenas para sessões que entregam quadros fora da velocidade esperada, sem voltar à espera de até 1,1 s.
- Preserva a validação da cadência real e não inventa, repete ou interpola frames para acelerar o início.
- Mantém sem alterações o player, o corte, o preview e todos os layouts e comandos dos widgets.

## 1.8.135 — 2026-07-25

- Corrige a origem dos pequenos saltos observados na gravação 4K60 pelo widget sem inventar, repetir ou interpolar frames.
- Faz a gravação sem preview respeitar a janela de aquecimento já definida e só liberar o encoder quando cadência, exposição, balanço, foco e estabilização estiverem assentados, com limite de segurança.
- No modo automático e sem preview, prioriza a estabilização eletrônica avançada em 60 FPS ou mais quando a câmera a anuncia, mantendo OIS como fallback e preservando as escolhas explícitas do usuário.
- Centraliza a resolução de OIS, EIS e estabilização avançada em uma única política usada pelo request, pela validação de prontidão e pelo estado exibido.
- Mantém a cadência real de 60 FPS e o normalizador de timestamps, sem repetir o quadro anterior para mascarar falhas.
- Segura somente a pequena frente do áudio no muxer e descarta a parte posterior sem vídeo ao finalizar, evitando a cauda audiovisual encontrada no arquivo analisado.
- Mantém sem alterações o player, o editor de corte, o preview da câmera e todos os layouts e comandos dos widgets.
- Confirma por auditoria que não ficaram métodos, imports, recursos ou arquivos sem uso.

## 1.8.134 — 2026-07-25

- Adiciona um provider separado `Vault Capture — tela de bloqueio`, declarado para tela inicial e keyguard e dimensionado para o espaço compacto abaixo do relógio.
- Mostra somente zoom persistente, gravar e parar em uma barra baixa de 156×56 dp, sem abrir preview e sem alterar o widget 6×1 existente.
- Reutiliza os mesmos comandos, permissões e estados da gravação em segundo plano, sem duplicar o pipeline da câmera.
- Inclui atualização parcial independente para zoom, início e parada, preservando a resposta sem piscadas também no novo formato.
- Mantém a identidade visual escura da One UI e fornece uma miniatura própria sincronizada com o layout instalado.
- Organiza os três providers em uma única lista de atualização para que nenhum modelo fique fora das mudanças de estado.
- Mantém sem alterações o player, o editor de corte, o preview da câmera e o funcionamento dos widgets anteriores.
- Confirma por auditoria que o provider, o layout, a miniatura, o manifesto e todos os novos recursos estão referenciados.

## 1.8.133 — 2026-07-25

- Centraliza o logotipo quadrado de 50 dp no widget 6×1 e remove a legenda `PRO`, eliminando a compressão sem reduzir sua área clicável.
- Mantém os cinco controles em 62×62 dp, retira os rótulos de sequência, foto, gravar e parar e deixa no zoom somente o valor persistente, como `1x` ou `3x`.
- Sincroniza a mesma composição sem textos na miniatura do seletor e preserva as atualizações parciais que evitam piscadas.
- Remove as confirmações temporárias ao mudar o zoom pela tela inicial ou pelo widget; a alteração continua visível imediatamente no próprio controle.
- Remove a notificação da otimização assim que o processamento termina, é cancelado ou falha, sem publicar uma notificação posterior de conclusão.
- Faz a tela inicial voltar diretamente ao estado pronto após uma otimização bem-sucedida.
- Reorganiza a tela inicial em cartões de acesso rápido, modo de vídeo, captura e sistema, preservando todos os botões, IDs e ações existentes.
- Mantém sem alterações o pipeline de gravação, o player, o editor de corte e o preview da câmera.
- Confirma por auditoria que não ficaram recursos, métodos, imports ou arquivos novos sem uso.

## 1.8.132 — 2026-07-25

- Redesenha o widget 6×1 como um painel Pro, com módulos de cantos moderados, gradientes discretos, bordas consistentes, ícones centralizados e rótulos curtos para identificar cada ação.
- Preserva o botão do SteadyVault em 50 dp, os cinco controles em 62×62 dp, 1 dp nas duas bordas e a largura de 367 dp da versão anterior, mantendo logo e ações nos respectivos cantos.
- Mostra `ZOOM` e o valor persistente no mesmo módulo; sequência, foto, gravação e parada passam a ter leitura imediata, inclusive nos estados desativado e gravando.
- Corrige a miniatura do seletor para representar a mesma composição do widget instalado, ampliando os controles do preview de 36 para 60 dp.
- Preserva as atualizações parciais de zoom, gravar e parar para não reinflar o widget nem reintroduzir piscadas.
- Mantém sem alterações o pipeline de gravação, o player, o editor de corte e o preview da câmera.
- Confirma por auditoria que não ficaram recursos, métodos, imports ou arquivos novos sem uso.

## 1.8.131 — 2026-07-25

- Aproxima visualmente a lupa do valor de zoom no widget 6×1, mantendo o número atualizado no próprio botão.
- Aumenta de 42 para 50 dp o botão que abre o SteadyVault e força a nova medida nas atualizações do `RemoteViews`.
- Mantém os cinco comandos com 62×62 dp, reserva exatamente 1 dp nas duas bordas laterais e amplia a largura mínima para impedir compressão.
- Aplica o mesmo espaçamento lateral e a mesma aproximação da lupa na prévia exibida pelo seletor de widgets.
- Mantém gravação, zoom persistente, preview da câmera, player e demais botões sem alteração funcional.

## 1.8.130 — 2026-07-23

- Prioriza o vídeo real do player no baixador, reconhece players incorporados e tenta o analisador avançado em páginas com streaming antes de oferecer imagens auxiliares do site.
- Mostra miniaturas remotas em memória na escolha da mídia e das qualidades, com tipo “Vídeo” ou “Imagem” identificado e sem criar um novo cache persistente.
- Acrescenta capa obtida do player, metadados públicos, poster do elemento de vídeo ou do extrator, inclusive para YouTube e páginas com iframe compatível.
- Inclui no botão de limpeza os temporários do navegador, cookies de sessão, downloads sociais interrompidos, miniaturas e metadados, protegendo arquivos que ainda estão em uso.
- Remove automaticamente miniaturas em disco e memória, duração e metadados persistentes quando uma mídia é movida, restaurada, substituída ou apagada em qualquer cofre.
- Limpa no início do app sobras antigas `.download`, `.part`, `.ytdl` e `_processing.tmp`, mantendo gravação, preview, player e widget sem alterações.
- Confirma por auditoria que os novos arquivos, métodos, imports e recursos estão conectados ao fluxo e que a suíte de lógica pura continua passando.

## 1.8.129 — 2026-07-23

- Corrige a retomada lenta ao tocar ou soltar a barra do player: o seek final sai do modo de arraste antes de reposicionar o decodificador.
- Mantém o parâmetro de busca pelo quadro sincronizado até o Media3 confirmar o salto e só então restaura a precisão exata, evitando decodificação atrasada enquanto a barra já avança.
- Reduz apenas o buffer necessário para retomar vídeos locais depois de um seek; a leitura antecipada, o fallback e os tempos de detecção e recuperação continuam preservados.
- Mantém sem alterações a gravação, o preview da câmera, o editor de corte e o widget.

## 1.8.128 — 2026-07-22

- Mostra diretamente no botão de zoom do widget o valor selecionado (`0,6x`, `1x`, `3x` ou `5x`) junto do ícone de lupa.
- Exibe o aviso imediatamente em todas as mudanças, inclusive ao voltar para `1x`, e atualiza somente o controle de zoom sem reconstruir ou fazer os demais botões piscarem.
- Mantém sem alterações a câmera, o preview, o player e o processamento da gravação.

## 1.8.127 — 2026-07-22

- Aumenta visualmente o logotipo no widget 6×1 sem reduzir a área reservada aos cinco botões nem deformar a imagem.
- Ao iniciar ou parar uma gravação, atualiza parcialmente e uma única vez apenas os controles Gravar e Parar; logo, zoom, sequência e foto não são reinflados pelo launcher e deixam de piscar.
- Aplica a mesma atualização parcial quando a gravação é iniciada pela tela principal ou pela tela preta, preservando os fluxos de gravação, preview, foto e player.

## 1.8.126 — 2026-07-21

- Corrige definitivamente a proporção do logotipo no widget: o PNG quadrado agora fica em um cartão quadrado de 42 dp, com desenho de 38 dp centralizado.
- Remove o texto minúsculo que transformava a área do logo em uma coluna estreita e elimina a aparência espremida no canto.
- Mantém os cinco botões em 62×62 dp e preserva todos os demais recursos da 1.8.125.

## 1.8.125 — 2026-07-21

- Corrige a área do logotipo no widget 6×1: ícone e nome voltam a ter espaço próprio, centralizados e sem compressão no canto.
- Mantém os cinco controles em 62×62 dp e redistribui somente as margens internas para o conjunto ocupar exatamente a largura declarada.
- Preserva sem alterações o menu ampliado, o player da 1.8.114, a gravação, o preview, as fotos e o zoom headless.

## 1.8.124 — 2026-07-21

- Restaura no widget 6×1 os controles de 62×62 dp e o padding interno de 13 dp usados na 1.8.114, inclusive no novo botão de zoom.
- Compacta apenas a área clicável do logotipo para os cinco botões grandes continuarem alinhados dentro do cartão.
- Aumenta e espaça discretamente as cinco opções do menu inferior do app, preservando o encaixe em telas de 360 dp.
- Mantém integralmente o player incorporado da 1.8.114 e não altera gravação, preview, fotos ou zoom headless.

## 1.8.123 — 2026-07-21

- Incorpora na versão 1.8.122 o seek rápido e estável validado na 1.8.114, preservando a intenção de continuar reproduzindo ao tocar na barra.
- Usa o quadro sincronizado mais próximo somente em saltos distantes com reprodução ativa; vídeo pausado e editor de corte continuam confirmando o frame exato.
- Reduz o buffer necessário para a retomada inicial depois do seek sem diminuir os buffers maiores usados na recuperação de travamentos.
- Mantém a gravação, o zoom headless, as fotos e o preview exatamente iguais à 1.8.122.
- Redesenha a lupa com círculo e sinal de mais geometricamente centralizados e adiciona a cor desativada usada pelos demais controles do widget.
- Substitui os emojis de cadeado da aba Apps por vetores centralizados com a cor de destaque do SteadyVault.

## 1.8.122 — 2026-07-21

- Parte novamente da versão original 1.8.112 e preserva integralmente o player, o editor de corte e o funcionamento do preview existente.
- Adiciona um controle separado de zoom para gravações iniciadas sem preview pela tela inicial e pelo widget 6×1; o valor fica salvo e continua válido com a tela apagada.
- Aplica o zoom somente ao caminho dedicado em segundo plano, sem alterar foto, gravação com preview, inicialização, áudio, encoder ou finalização do gravador.
- Inclui a aba Apps protegidos com PIN próprio, biometria opcional, adição e remoção de atalhos do perfil normal e acesso aos ajustes do launcher para ocultar ícones.
- Adiciona print imediato e gravação de tela por controle flutuante, com gravação transacional diretamente no cofre principal, de disfarce ou terciário escolhido.
- Mostra carregamento e tratamento de falha nas listagens do cofre principal, cofre de disfarce, cofre terciário, lixeira e aba de apps.
- Corrige a altura da faixa de seleção para que a moldura e as ações não cubram os botões dos cards.
- Amplia a otimização com presets de 720p, 1080p e 4K para Apple, Android, web, redes sociais, criação e arquivo, mantendo transcodificação por hardware e áudio quando solicitado.
- Remove chamadas Android obsoletas do serviço de captura de tela e mantém todos os novos arquivos, componentes e métodos ligados à interface ou ao manifesto.

## 1.8.112 — 2026-07-17

- Padroniza a barra principal, o playhead e as duas alças de corte no mesmo scrubbing nativo do player.
- Agrupa movimentos contínuos em uma atualização por frame da tela, sempre substituindo o destino pendente pelo ponto mais recente e confirmando o frame exato ao soltar.
- Prepara os nove quadros da faixa de corte em segundo plano assim que o vídeo fica pronto; o botão Cortar só é liberado depois da preparação e a tela já abre com a faixa preenchida.
- Usa posições reais ao longo de toda a duração, corrige a orientação e recorta cada miniatura pelo centro sem deformá-la dentro do retângulo.
- Remove a camada antiga de bitmap, o controlador de preview paralelo, o cache preditivo correspondente, seus métodos e seus testes, evitando dois decodificadores disputando o mesmo vídeo.
- Renomeia e reaproveita a fila de destino mais recente para o frame da tela e para o refinamento exato, com testes de substituição, consumo e cancelamento.
- Confirma por auditoria que não restaram arquivos, métodos, imports ou recursos órfãos após a unificação.

## 1.8.111 — 2026-07-17

- O editor de corte agora atualiza diretamente o frame do player enquanto o usuário arrasta a alça inicial, a alça final ou o playhead.
- Remove a espera pelo decodificador de miniaturas nessa tela e limpa qualquer bitmap antigo antes de pedir a nova posição.
- Mantém o seek exato, a fricção selecionada e a confirmação final no mesmo frame mostrado durante o corte.

## 1.8.110 — 2026-07-17

- Remove miniaturas globais de 720 px/RGB565 da camada de prévia; somente o quadro exato pedido pode cobrir o vídeo durante o arraste.
- Aumenta a prévia nítida até 2160 px, valida a resolução realmente entregue pelo decodificador e tenta o quadro completo quando a versão escalada vier abaixo do necessário.
- Substitui a varredura contínua de toda a duração por uma janela local adaptativa em ARGB8888, orientada pela direção, velocidade e FPS do gesto.
- Mantém dois decodificadores independentes, descarta previsões antigas quando o dedo muda de posição e limita dinamicamente a memória conforme a resolução.
- Aplica o mesmo caminho preciso à barra principal, ao playhead e às duas alças de corte, inclusive nos modos ½×, ¼× e ⅛×.
- Adiciona testes de resolução, limite adaptativo de memória, previsão lenta, rápida, reversa e em vídeos de 240 FPS.

## 1.8.109 — 2026-07-17

- A prévia exata sob o dedo agora usa um decodificador exclusivo; a preparação em segundo plano possui outro decodificador e não consegue mais bloquear o próximo quadro do arraste.
- O vídeo é coberto progressivamente por 72 posições em memória: uma primeira passagem disponibiliza quadros sincronizados rapidamente e a segunda refina esses mesmos pontos para os quadros mais próximos.
- Cada movimento antecipa até três quadros na direção do gesto, preserva os passos curtos de vídeos em 120/240 FPS e os prioriza sobre o restante da preparação.
- Pedidos idênticos do início do gesto são deduplicados, o alvo ativo sempre substitui o anterior e somente o tempo exato solicitado pode ser tratado como quadro final nítido.
- A pré-carga continua transitória: não cria arquivos, não aumenta o cache de armazenamento e é inteiramente liberada ao fechar o player.
- Ampliados os testes da distribuição global e adicionados casos para previsão nos dois sentidos e limites da linha do tempo.

## 1.8.108 — 2026-07-17

- Corrigida a perda de nitidez introduzida na prévia do arraste: os quadros imediatos agora usam até 720p e o quadro exato ativo usa a resolução real da tela, limitado a 1440p.
- Separados os caches de resposta rápida e alta qualidade; um quadro aproximado nunca substitui um quadro nítido já disponível.
- Os controles agora aparecem como `Vel. 1×` (velocidade de reprodução) e `Arraste 1×` (precisão da barra), com largura responsiva, linha única e descrição ao manter pressionado.

## 1.8.107 — 2026-07-17

- A prévia ao arrastar o vídeo agora usa um decodificador leve separado e pausa o player principal uma única vez, evitando disputa de codec e seeks repetidos em vídeos longos ou 4K.
- O cache transitório prepara progressivamente 72 pontos de toda a duração, prioriza sempre o pedido mais recente e permanece limitado em memória; nenhum arquivo de cache novo é criado.
- O quadro mostrado acompanha a posição do dedo e o player recebe o seek exato ao soltar, com uma curta retenção da prévia para evitar o piscar do quadro antigo.
- O botão de fricção usa frações indivisíveis (`½×`, `¼×` e `⅛×`), autoajuste e linha única, eliminando definitivamente a quebra visual em fontes ampliadas.
- Adicionados testes das regras de distribuição e escolha dos quadros do cache.

## 1.8.106 — 2026-07-17

### Scrub contínuo, botão estável e corte sem áudio

- Impede que movimentos contínuos adiem indefinidamente o próximo frame exato da prévia.
- Mantém uma atualização exata já agendada, troca apenas seu destino pelo ponto mais recente e limita o ciclo a um pedido pendente.
- Atualiza frames intermediários a cada 32 ms durante deslocamentos rápidos, preservando o seek sincronizado para reposicionamento imediato e a confirmação exata ao soltar.
- Mantém o botão 1×, 1/2×, 1/4× e 1/8× sempre em uma única linha, com medidas que continuam cabendo em telas menores.
- Adiciona ao editor de corte a opção “Remover áudio da cópia”, com confirmação explícita e preservação do arquivo original.
- Reaproveita o pipeline existente de transcodificação sem criar uma segunda implementação para retirar a faixa de áudio.
- Adiciona testes da fila de destino mais recente para movimentos contínuos, novo ciclo e cancelamento.

## 1.8.105 — 2026-07-17

### Preview constante do início ao fim do vídeo

- Evita o acúmulo de buscas exatas longas que reduzia a taxa de atualização ao chegar ao meio do vídeo.
- Usa o quadro sincronizado mais próximo durante deslocamentos grandes e rápidos para a imagem continuar acompanhando o dedo.
- Refina automaticamente para o frame exato após uma pausa curta no movimento e confirma novamente o frame exato ao soltar.
- Mantém buscas exatas em movimentos curtos e nos modos de precisão 1/2×, 1/4× e 1/8×.
- Delega ao modo nativo de scrubbing do Media3 a supressão temporária da reprodução e do áudio, removendo pausas, mudanças de volume e estados repetidos a cada movimento.
- Preserva a intenção de reprodução durante o arraste e evita uma busca exata duplicada ao finalizar.
- Adiciona testes para movimentos rápidos, lentos, com fricção e em diferentes pontos da linha do tempo.

## 1.8.104 — 2026-07-16

### Preview fluido durante o arraste

- Ativa o modo nativo de scrubbing do Media3 somente enquanto o dedo está na barra ou na faixa de corte.
- Mantém seeks exatos, aumenta a prioridade operacional do codec e evita reinicializações desnecessárias entre quadros compatíveis.
- Encaixa cada destino no quadro real mais próximo de acordo com o FPS detectado, inclusive em 60, 120 e 240 FPS.
- Descarta pedidos duplicados para o mesmo quadro e continua mantendo somente o destino mais recente por frame da tela.
- Desativa imediatamente o modo intensivo ao soltar e confirma no player exatamente o mesmo quadro mostrado na prévia.
- Aplica a mesma coordenação à barra principal, ao playhead e às alças do editor de corte, preservando 1x, 1/2x, 1/4x e 1/8x.
- Corrige o import do relógio monotônico usado na liberação segura de mídias, mantendo o projeto compilável.

## 1.8.103 — 2026-07-15

### Correção dos imports de atalhos Android

- Corrige `ShortcutInfo` e `ShortcutManager` para o pacote oficial `android.content.pm`.
- Remove a dependência de `BuildConfig`, que não é gerada nesta configuração, e usa uma versão interna do esquema para atualizar o cache dos atalhos.
- Revisa o arquivo completo dos atalhos configuráveis para evitar o próximo erro de importação relacionado.

## 1.8.102 — 2026-07-15

### Correção de compilação do player

- Substitui o atributo XML inválido `android:compoundDrawablePadding` por `android:drawablePadding` no botão de precisão do player.
- Confirma que não existem outras referências ao atributo inválido nos layouts.

## 1.8.101 — 2026-07-14

### Precisão, exclusão direta e atalhos configuráveis

- Substitui o gesto vertical difícil por um botão de precisão no player, alternando entre 1×, 1/2×, 1/4× e 1/8× tanto na barra quanto nas alças do corte.
- Mantém a prévia em tempo real limitada ao destino mais recente de cada quadro da tela e confirma uma busca exata ao soltar, inclusive durante movimentos rápidos.
- Oferece “Excluir direto” junto da lixeira nos cofres, menus de três pontos e player, inclusive depois de cancelar com segurança uma otimização em andamento.
- Adiciona o botão OK à escolha do cofre de destino, permitindo confirmar imediatamente a opção que já estava marcada.
- Torna configuráveis os atalhos de foto e vídeo com quatro nomes/ícones para cada ação: nome direto ou disfarces de scanner, documentos, notas, gravador, monitor e agenda.
- Disponibiliza separadamente os três formatos do Android: ações ao segurar o ícone principal, atalhos fixados na tela inicial e ícones opcionais semelhantes a apps na gaveta.
- Mantém o ícone principal e o menu interno Gravar inalterados; os ícones adicionais podem ser ativados e removidos nas configurações.
- Publica e atualiza atalhos em uma fila de segundo plano, evita atualizações repetidas e remove o XML estático e os textos que ficaram sem uso.

## 1.8.100 — 2026-07-14

### Reels públicos sem login

- Separa o acesso público do Instagram dos cookies do navegador, impedindo que cookies incompletos ou antigos façam um reel público cair indevidamente no fluxo de login.
- Replica o reparo de compatibilidade do Media Grab: após uma falha, atualiza o extrator pelo canal mais recente com intervalo seguro entre tentativas e repete a análise anônima.
- Tenta os endereços públicos de embed com extrator genérico, referenciador do Instagram e agente móvel antes de considerar uma sessão autenticada.
- Adiciona uma última rota pública que lê `og:video` e URLs de vídeo do HTML e baixa o arquivo direto para o cofre, sem passar pelo extrator social.
- Só reaproveita cookies quando existe um `sessionid` real e deixa esse caminho por último, preservando suporte a mídias privadas sem exigir login para conteúdo público.
- Normaliza qualquer link de post/reel e remove parâmetros de rastreamento; nenhum endereço usado no teste ficou fixo no aplicativo.
- Inclui testes das regras de normalização, ordem dos fallbacks, detecção de sessão e rejeição de domínios semelhantes ao Instagram.

## 1.8.99 — 2026-07-14

### Detalhes sob demanda e controle dos caches

- Adiciona nas configurações dois modos para os detalhes: preparar a mídia ao abrir seu menu de três pontos ou carregar somente ao tocar em “Detalhes”.
- Usa por padrão o pré-carregamento da mídia selecionada, em uma fila exclusiva que não atrasa exportação, exclusão, importação nem a primeira renderização da grade.
- Reaproveita detalhes de fotos e vídeos em um cache persistente pequeno e em uma memória limitada a 64 itens, evitando abrir novamente o arquivo sem necessidade.
- Exibe o uso de miniaturas, detalhes/metadados e arquivos temporários, separando armazenamento permanente de memória usada enquanto o app está aberto.
- Permite atualizar os números e limpar miniaturas, detalhes, temporários e registros órfãos sem apagar mídias, lixeira, álbuns, favoritos ou configurações da câmera.
- Inclui na limpeza as miniaturas mantidas pelas galerias em memória e impede a remoção de temporários durante uma gravação.
- Respeita o cancelamento da espera por detalhes e não abre o resultado depois que o usuário fecha o progresso.

## 1.8.98 — 2026-07-13

### Cofres rápidos, corte profissional e detalhes da mídia

- Os três cofres exibem a grade imediatamente sem abrir cada vídeo de forma bloqueante para ler duração e resolução.
- Metadados ausentes são preenchidos em segundo plano, em lotes pequenos, e persistidos em cache para as próximas aberturas.
- O editor de corte recebe uma linha do tempo visual com miniaturas, duas alças independentes, playhead e prévia em tempo real durante o arraste.
- A linha do tempo mantém o ajuste fino vertical em 1/2×, 1/4× e 1/8×, com retorno tátil e seleção mínima segura.
- Remove a instrução fixa que cobria os controles de zoom e ajuste; a orientação de precisão agora aparece somente durante o gesto.
- Recalcula o posicionamento dos controles conforme a altura real do player e oculta a barra de zoom durante o corte.
- Adiciona “Detalhes” ao menu de três pontos das mídias nos três cofres, com nome, tipo, resolução, duração, tamanho e data.
- Remove os botões e métodos antigos de marcação de início/fim substituídos pela faixa de corte com duas alças.

## 1.8.97 — 2026-07-13

### Player, corte preciso e modais profissionais

- Remove a camada de bitmap atrasada que cobria os quadros atuais durante o arraste.
- O Media3 passa a renderizar diretamente o quadro exato solicitado, limitado à posição mais recente de cada frame da tela.
- A barra acompanha a reprodução a cada 100 ms e responde a movimentos mínimos do dedo.
- Adiciona ajuste de fricção: arrastar o dedo para cima reduz a velocidade para 1/2×, 1/4× e 1/8×, com indicação visual e resposta tátil.
- O modo de corte usa a mesma prévia em tempo real e mantém início/fim dentro de um intervalo válido.
- Reduz e realinha títulos de todos os modais, melhora margens e padroniza o cabeçalho com botão X.
- Adiciona o mesmo cabeçalho e botão X ao teclado de PIN, único diálogo fora do componente central.
- Remove campos, métodos, import e recurso visual antigos do preview; a varredura final não encontrou símbolos ou arquivos órfãos.

## 1.8.96 — 2026-07-13

### Motor de download baseado no Media Grab

- Links de Instagram e demais redes compatíveis agora passam primeiro pelo motor avançado, antes da varredura de recursos internos da página.
- Impede que falhas do extrator social abram uma lista bruta de JavaScript, CSS ou requisições internas.
- A varredura DOM considera players, fontes, metadados públicos de vídeo e links com extensão real de mídia.
- URLs com nomes de mídia apenas em parâmetros não são mais classificadas como arquivos diretos.
- Adota o fallback de embed do Media Grab com extrator genérico, referenciador e agente móvel, sem fixar links de teste no app.

## 1.8.95 — 2026-07-13

### Build sem APIs obsoletas

- Remove a configuração WebSQL obsoleta do WebView; armazenamento DOM permanece ativo.
- Substitui `FLAG_FULLSCREEN` pela API moderna de insets ao abrir e fechar vídeos em tela cheia.
- Atualiza a leitura de URIs compartilhadas para `IntentCompat`, compatível com Android 10+.
- Marca bibliotecas nativas de terceiros já pré-compiladas para não passarem por uma tentativa inútil de remoção de símbolos.

## 1.8.94 — 2026-07-13

### Correção da limpeza do navegador

- Remove `clearSessionOnly`, que havia perdido sua única função após a retirada da preferência antiga de última URL.
- Remove a chamada correspondente ao encerrar a sessão privada.
- Elimina a referência residual a `KEY_LAST_URL` que impedia a compilação.

## 1.8.93 — 2026-07-13

### Limpeza de código e recursos

- Remove o drawable de exclusão definitiva que não possuía referência no projeto.
- Remove o cache Python gerado pela ferramenta de auditoria.
- Remove métodos públicos sem chamadas do player, navegador e catálogo de formatos.
- Remove a preferência antiga de última URL, que não era mais utilizada nem persistida.
- Elimina comparadores duplicados do modelo de ordenação; a ordenação efetiva permanece inalterada.
- Confirma por varredura que não restaram métodos ou propriedades declarados sem referência direta.

## 1.8.92 — 2026-07-13

### Menu interno restaurado

- Restaura o botão Gravar do menu inferior ao comportamento original: apenas abrir a tela de captura.
- Remove o extra e o fluxo que iniciavam vídeo automaticamente pelo menu interno.
- Mantém “Gravar vídeo” e “Tirar foto” exclusivamente nos atalhos do ícone do app no Android.

## 1.8.91 — 2026-07-13

### Atalhos no ícone do Android

- Remove o botão Foto adicionado ao menu inferior interno e restaura o menu original.
- Adiciona “Gravar vídeo” e “Tirar foto” ao menu exibido ao manter pressionado o ícone do SteadyVault na tela inicial ou na lista de apps.
- Remove extras e código de navegação que ficaram desnecessários após mover o atalho de foto para o launcher.

## 1.8.90 — 2026-07-13

### Atalho de foto

- Adiciona o comando Foto ao menu inferior para capturar uma imagem imediatamente.
- Mantém o comando Gravar separado e preserva as validações de câmera, permissões e perfil ativo.
- Compacta os cinco itens do menu para manter espaçamento uniforme em telas menores.

## 1.8.89 — 2026-07-13

### Captura, teclado, pop-ups e duração

- O item Gravar do menu inferior agora abre a tela e inicia a captura, respeitando permissões e validações existentes.
- O teclado é recolhido ao confirmar uma pesquisa/endereço no navegador.
- Todos os pop-ups personalizados receberam botão X, alinhamento consistente à esquerda e acabamento visual uniforme.
- A duração dos vídeos é lida durante a carga inicial dos três cofres, sem exigir que a mídia seja aberta antes.

## 1.8.88 — 2026-07-12

### Filtro de mídia do navegador

- Impede que JavaScript, CSS, JSON, mapas de código, fontes e páginas HTML sejam exibidos como opções de mídia.
- Remove correspondências genéricas por palavras como `video` e `media` no endereço.
- Mantém somente MIME de foto/vídeo, extensões suportadas e endpoints de streaming reconhecidos.

## 1.8.87 — 2026-07-12

### Downloads e player

- Conectado o baixador avançado já existente ao comando “Baixar mídia da página”.
- URLs sociais passam pelo extrator atualizado e genérico, com fallback para mídia direta quando necessário.
- Removido o modo forçado de Instagram que podia exigir login mesmo em Reels públicos.
- Cookies do Instagram agora são compartilhados corretamente entre os subdomínios usados pelo extrator.
- Removido acesso ao WebView fora da thread principal durante downloads.
- O seek do Media3 agora solicita o quadro exato a cada movimento da barra sem iniciar reprodução temporária.

## 1.8.56 — 2026-07-12

### Correção de compilação Kotlin

- Corrigido erro `Class is prohibited here` em `SecondaryVaultActivity.kt`.
- Movido o `Holder` do `GalleryAdapter` interno para o corpo da Activity.
- Aplicado o mesmo ajuste preventivo no cofre terciário.
- Mantida a limpeza anterior: falhas de importação aparecem somente em popup, sem gerar `.txt`.

## 1.8.55 - Importação: falhas só em popup

- Remove geração de relatório `.txt` de falhas na importação.
- Mantém a visualização dos nomes dos arquivos com falha diretamente no popup final da importação.
- Remove código antigo de relatório em arquivo e limpa relatórios legados `ImportReports` na manutenção de abertura do app.


## 1.8.53 - Cofres: miniaturas mais limpas e importação em lote

- Diminui a borda/arredondamento das miniaturas nos cofres.
- Mantém o card da miniatura com tamanho quadrado estável mesmo enquanto a imagem ainda não carregou.
- Remove margem interna exagerada no cofre disfarçado/terciário para a imagem preencher melhor o card.
- Adiciona importação de vários arquivos de uma vez.
- Adiciona importação de uma pasta inteira com busca em subpastas.
- Importação em lote agora é sequencial e mostra progresso, preparada para coleções grandes.
- Limita aquecimento prévio de miniaturas aos primeiros itens visíveis para não sobrecarregar coleções com centenas/milhares de arquivos.



## 1.8.50 - Correção CFR fixa para lacunas
- Restaurada a correção de timestamps anormais como CFR real: 60 FPS passa a usar ~16,66 ms por quadro, removendo saltos de ~33 ms sem adicionar imagens.
- Conferida a configuração `gapCorrection`: ela está ligada em Ajustes, salva em `CaptureSettings`, usada ao finalizar gravação e aplicada por `TimelineRepairer`.
- Textos dos ajustes atualizados para deixar claro que a opção força cadência fixa no arquivo final.

## 1.8.49 - Captura dedicada por widget e botão inicial
- Captura dedicada limpa qualquer ponte de preview registrada e cancela otimização em andamento para não competir com câmera, ISP ou encoder.

# Changelog

Este arquivo registra mudanças confirmadas no código-fonte disponibilizado. O formato segue a organização geral do Keep a Changelog, sem declarar compatibilidade estrita com versionamento semântico.

## 1.8.48 — 2026-07-11

### Desempenho

- Metadados e callbacks Camera2 passaram a ser usados somente durante o aquecimento inicial.
- Antes do primeiro quadro salvo, a sessão continua com o mesmo request e listener nulo, mantendo as imagens sem gerar metadados por quadro.
- Reutilização de MediaCodec.BufferInfo no áudio e no muxer para reduzir alocações e pressão de coleta de lixo.
- Mantida a configuração visual aprovada da versão 1.8.47.

### Qualidade de código

- Removidos todos os @Suppress existentes.
- Removidos parâmetros sem uso.
- Removidos métodos vazios e suas chamadas.
- A auditoria passou a reprovar supressões de warning e métodos privados vazios.
- Mantidas verificações de imports sem uso, declarações privadas mortas, arquivos Kotlin órfãos e recursos sem referência.

## 1.8.47 — 2026-07-11

### Gravação

- Timeline passou a respeitar o relógio real da câmera, preservando sincronização com o áudio.
- Lacunas reais deixaram de ser escondidas por compressão indiscriminada dos timestamps.
- A gravação sem preview passou a aguardar quadros reais antes de iniciar áudio e muxer.
- Barreira inicial definida em 2 quadros para 30 FPS, 3 para 60 FPS e 4 para 120/240 FPS.
- Adicionado fallback curto de 350 ms para o início sem preview.
- Removida a troca tardia do repeating request durante gravações de 60 FPS ou mais.
- Arquivo bruto movido para o cache interno, reduzindo picos de I/O no caminho crítico.
- OIS priorizado no modo automático de 4K60.
- A opção de faixa fixa de FPS passou a exigir intervalos exatos como 60–60.

### Validação

- Auditoria estática aprovada para 89 arquivos Kotlin, 136 XML e 395 referências locais no pacote analisado.

## 1.8.46 — versão recebida

### Estado do projeto

- Versão com Camera2, MediaCodec e MediaMuxer.
- Serviços de gravação em primeiro plano e captura sem preview.
- Cofres, lixeira, miniaturas, widgets, player interno e exportação.
- Políticas de aquecimento, cadência, armazenamento e proteção térmica.
- Ferramentas de análise, reparo de timeline e transcodificação por hardware.

As alterações anteriores à versão 1.8.46 não foram reconstruídas por falta de histórico versionado completo. Consulte o repositório Git original, quando disponível, para detalhes anteriores.

## 1.8.48 - Correção de lacunas em 4K60
- Corrigida a regularização de timeline para remover saltos longos de timestamp mantendo a duração do vídeo, sem inventar frames.
- Em 4K60/120, a gravação não compartilha mais a Surface do preview com o encoder para reduzir carga do ISP/HAL e evitar buracos de frame.
- FPS alto agora prioriza faixa fixa da câmera; faixas variáveis como 30-60 são rejeitadas quando o modo suave está ativo.
- Bitrate padrão 4K60 HEVC ajustado para 60 Mbps para reduzir quedas de frame no encoder mantendo alta qualidade visual.

## 1.8.224
- Cofres: o resumo deixa de expor o tamanho interno do lote visual (160); mostra somente a quantidade total real do cofre.
- Interface: removido o destaque quadrado/cinza nativo do Android nos controles clicáveis; permanecem os estados visuais definidos pelo SteadyVault.
- Ajustes: títulos e descrições reescritos para explicar claramente a função de cada controle.
- Ajustes: bitrate, exposição, ganho, buffer, processamento de ruído/nitidez e bitrate AAC passam a ter descrições específicas por opção.
- Gravação: nenhum código de captura, câmera, encoder, FPS, timestamps ou áudio foi alterado.

## 1.8.225
- Cofres: ordenações passam a atuar sobre o índice lógico completo, não apenas sobre o lote visual já carregado.
- Cofres: mais recentes/antigas, nome A-Z/Z-A e tamanho maior/menor agora reordenam imediatamente toda a coleção.
- Cofres: duração e resolução completam metadados em segundo plano antes da ordenação e persistem os dados no índice correto de cada cofre.
- Cofres: adicionadas as direções menor duração e menor resolução para completar os pares crescente/decrescente.
- Cofres: a grade continua carregando miniaturas sob demanda, sem limitar a ordenação ao antigo lote de 160 itens.
- Gravação: nenhum arquivo de captura, câmera, encoder, FPS, timestamps ou áudio foi alterado nesta versão.

## 1.8.239
- Melhorada a geração de thumbnails nos três cofres para aparecerem mais rápido.
- Thumbnails de vídeo agora rejeitam frames quase totalmente pretos e tentam um frame alternativo automaticamente.
- Cache de thumbnails pretas antigas é invalidado e regenerado.
- Mais threads para geração de thumbnails e fila maior para reduzir demora ao abrir cofres grandes.
- Tamanho alvo das miniaturas reduzido para carregar mais rápido sem perder qualidade perceptível na grade.
- Nenhuma alteração no pipeline de gravação.

## 1.8.241
- Importação: removidos falsos positivos de repetidos por nome, tamanho, URI ou histórico da origem; somente SHA-256 do conteúdo confirma duplicidade.
- Importação: arquivos antigos de mesmo tamanho têm hash calculado e cacheado sem limite arbitrário de candidatos, evitando também falsos negativos em lotes grandes.
- Cofre: fluxo biométrico bloqueia prompts/seletores duplicados e ignora callbacks repetidos em sequência.
- Cofre: a tela de escolha após a digital é aberta antes da vibração de sucesso e não pode ser empilhada sobre outra.
- Cofre principal: manutenção de inicialização foi retirada do caminho crítico do primeiro carregamento após desbloquear; a grade é entregue antes e a manutenção roda em seguida.
- Gravação: nenhuma alteração no pipeline de captura.

## 1.8.242
- Pipeline 60 FPS em modo cadence-first: CBR em 60+ quando suportado e menor complexidade do encoder hardware.
- Fila de vídeo ampliada para absorver picos de I/O sem devolver backpressure à Surface da câmera.
- Thread de muxer fica abaixo do drain urgente do vídeo; áudio mantém prioridade padrão para preservar continuidade sem preemptar o encoder.
- Cache de thumbnails em RAM é liberado antes da gravação, preservando o cache em disco.
- Em 60+ FPS, estabilização solicitada usa OIS quando disponível para reduzir carga do ISP sem perder estabilização.
- Watchdog de stall reage muito mais cedo a uma interrupção real do pipeline.
- Mantida a regra de continuar gravando com fallback e aviso, nunca parar apenas por queda de FPS.

## 1.8.243
- Revertido o CBR obrigatório de 60+ FPS após o vídeo de teste mostrar quedas periódicas de ~33,5 ms; VBR volta a ser preferido.
- Removida a complexidade mínima forçada do encoder em 60+ FPS.
- A estabilização volta a respeitar exatamente a escolha do usuário, sem troca automática para OIS.
- Em 60 FPS, câmeras físicas dedicadas passam a ter prioridade sobre logical multi-camera quando o fabricante as expõe diretamente.
- Mantidos o buffer assíncrono do muxer, a proteção contra stall longo e o isolamento de tarefas de cofre durante a gravação.

## 1.8.244
- Corrigida a seleção 60 FPS para priorizar uma rota Camera2 cuja HAL confirme tempo mínimo de frame compatível com 60 FPS.
- Cache/matriz não podem mais reaproveitar silenciosamente uma rota 60/60 publicamente lenta quando existe uma rota confirmada.
- Preferência por câmera física/lógica virou apenas desempate depois da capacidade real de cadência.
- Mantido VBR e todo o pipeline de encoder/muxer da 1.8.243.
- Corrigidos previews 4x1, tela de bloqueio e 6x1 no seletor de widgets da One UI.

## 1.8.245
- 60+ FPS passa por validação de cadência real do HardwareRecorder antes de abrir oficialmente o MP4.
- Rotas com lacunas longas ou FPS real abaixo da margem profissional caem para o próximo FPS sustentável sem cancelar a gravação.
- Fallback de FPS é registrado em Diagnósticos e mostrado no estado/notificação.
- Widgets 6×1, 4×1 e tela de bloqueio foram padronizados em tamanho, família visual e estados.

## 1.8.246
- 4K60 regular ganhou backend OEM dedicado com `MediaRecorder + EncoderProfiles`.
- O perfil OEM é aceito somente quando o fabricante declara exatamente 3840×2160 a 60 FPS para a câmera selecionada.
- Camera2 continua dona da sessão e 4K60 exige faixa AE fixa `[60,60]`; `[30,60]` não é aceita como 60 FPS profissional.
- `MediaRecorder.prepare()` e a Surface OEM ficam prontos antes da sessão; `MediaRecorder.start()` só ocorre depois que a sessão Camera2 aceitou o repeating request definitivo `[60,60]`.
- Falha do backend OEM antes do início reinicia a mesma tentativa 4K60 no `HardwareRecorder` atual sem cancelar a solicitação do usuário.
- Falha do backend OEM durante a gravação tenta preservar o trecho válido e reinicia automaticamente no `HardwareRecorder`, registrando aviso em Diagnósticos e na notificação.
- 120/240 FPS permanecem exclusivamente no caminho Camera2 constrained high-speed + `HardwareRecorder`; o backend OEM não é elegível para high-speed.
- Widgets e recursos visuais da 1.8.245 foram preservados sem alterações.

## 1.8.248
- FPS selecionado virou contrato exato em toda a captura: 30 usa `[30,30]`, 60 usa `[60,60]`, 120 usa `[120,120]` e 240 usa `[240,240]`.
- Removido todo fallback automático entre taxas de quadros; backend, câmera e encoder podem mudar, mas o FPS solicitado não muda.
- Sessões regulares forçam `Range(targetFps, targetFps)` diretamente no Camera2, inclusive quando a HAL não anuncia a faixa fixa; se o firmware recusar, a tentativa falha sem usar faixa variável.
- Sessões constrained high-speed aceitam somente a faixa fixa exata publicada para 120/240; não existe substituição 120→240 nem faixa 30–120/60–240.
- O backend OEM 4K60 exige Camera2 `[60,60]` e rejeita `[30,60]`.
- A validação de warm-up high-speed não reduz mais o FPS quando encontra cadência ruim; registra o problema e mantém o contrato exato.
- Corrigido o widget da tela de bloqueio: o renderer ainda sobrescrevia os 46 dp do XML para 58 dp em runtime. Layout real e renderer agora usam 40 dp, com quatro controles em 4×1 e largura mínima de 168 dp.
- Preview do widget e widget instalado passam a usar geometria compatível, eliminando o caso em que somente o preview cabia corretamente.
## 1.8.250 — FPS exato com estabilização preservada

- Preview stabilization, EIS, OIS e OFF passam a ser respeitados exatamente como selecionados; FPS alto não troca mais o modo de estabilização.
- Se a HAL não suportar a estabilização escolhida na configuração atual, o app informa a incompatibilidade em vez de substituí-la silenciosamente.
- HardwareRecorder valida uma janela curta de cadência antes de abrir o arquivo regular, sem alterar o FPS selecionado.
- Ao finalizar, o MP4 é validado pelo FPS real/PTS. Lacunas ou desvio de cadência acionam reconstrução CFR por GPU com ADAPTIVE_BLEND no mesmo FPS selecionado.
- A saída reparada é validada novamente antes de ser publicada no cofre.
- 30/60/120/240 nunca fazem fallback para outro FPS.


## 1.8.251 — crash-safe ao iniciar gravação

- Corrigido crash ao tocar em Gravar quando a HAL Samsung recusava uma chave de estabilização/configuração dentro de `CameraDevice.onOpened()`.
- Toda criação de sessão agora é protegida; falha da HAL vira diagnóstico e estado controlado, nunca exceção não tratada do processo.
- Removido o warm-up bloqueante do caminho regular 30/60 FPS; a gravação começa imediatamente e a validação CFR permanece no fechamento.
- FPS exato e estabilização selecionada continuam independentes e imutáveis.

## 1.8.252

- Corrigido falso negativo de OIS em câmeras lógicas Samsung: a capacidade declarada deixa de bloquear a tentativa real no CaptureRequest.
- Estabilização escolhida permanece exata; não há substituição automática entre OIS, EIS, Preview stabilization e Off.
- 4K60 mantém [60,60] e não altera FPS para contornar estabilização.
- Removido texto antigo que indicava retirada automática de estabilização eletrônica em alta taxa.


## 1.8.253
- Removida qualquer interpretação de correção CFR como pós-estabilização: estabilização termina no Camera2.
- Vídeos já CFR no FPS escolhido são publicados diretamente, sem transcode/interpolação.
- Reparo automático temporal ficou conservador: sem mistura quando não há lacunas e no máximo 1 frame misturado por lacuna curta real.
- Diagnóstico informa explicitamente quando não houve pós-processamento.


## 1.8.254 — MediaRecorder direto, zero pós-processamento

- Remove completamente `HardwareRecorder` do projeto e o fallback de gravação via `MediaCodec + MediaMuxer` manual.
- Toda captura passa a usar `Camera2 -> Surface do MediaRecorder -> MP4 final no cofre`.
- O MediaRecorder grava diretamente no arquivo definitivo do cofre; não existe temporário para publicação após o `stop()`.
- Remove do fluxo de captura `AutomaticCfrRepairPolicy`, reparo CFR, retiming, interpolação, blend e transcodificação automática.
- O botão Parar chama `MediaRecorder.stop()` imediatamente após interromper o repeating request, sem tail drain artificial.
- `EncoderProfiles` exato é priorizado; SDR pode usar configuração direta do MediaRecorder quando o OEM não publicar um perfil exato. HLG10 continua exigindo perfil OEM compatível.
- 30/60/120/240 usam um único backend MediaRecorder; nenhuma falha troca silenciosamente para outro gravador.

## 1.8.255 — configuração como fonte única da verdade

- A gravação direta continua sem qualquer pós-processamento: Camera2 -> MediaRecorder -> MP4 final.
- Resolução, FPS, codec e bitrate usados pelo MediaRecorder passam a vir da configuração atual do usuário, sem substituição pelo bitrate de EncoderProfiles.
- EncoderProfiles pode ser consultado apenas para compatibilidade técnica de perfil/nível e HLG10; quando usado para HLG10, resolução/FPS/bitrate configurados são reaplicados explicitamente antes do prepare().
- O início da gravação não reativa mais um perfil histórico de câmera por cima da configuração atualmente salva.
- Removidas migrações de settings que podiam trocar Edge OFF por FAST ou zerar preferências de áudio silenciosamente ao carregar a configuração.
- A validação de armazenamento usa o bitrate de vídeo e áudio escolhidos na configuração, não valores OEM.
- Se a câmera/HAL recusar a combinação solicitada, a sessão falha informando incompatibilidade; não reduz FPS, não muda estabilização e não altera bitrate automaticamente.

## 1.8.258
- Pipeline de captura revertido exatamente ao núcleo estável da 1.8.255 usado nos testes 67492/67493.
- Removidas da captura as alterações de cadência/diagnóstico introduzidas depois do melhor resultado.
- Ordem de parada, request Camera2 e processamento 60 FPS voltam ao comportamento da versão de melhor fluidez.
- Mantidas somente as correções visuais de widgets: previews alinhados ao estilo do lock screen e cantos conforme o tema.
- MediaRecorder direto, sem pós-processamento do vídeo.

- **Vídeo escuro com "pouca luz" desligado:** a cadência fixa congelava exposição/ISO nos 3 primeiros resultados, antes de o AE convergir (ele começa escuro), e o vídeo todo saía subexposto. Agora espera `AE_STATE` convergido (máx. 45 resultados) antes de congelar. `TIMING_DIAGNOSTICS` (glFinish) desligado.
- **Diagnóstico da câmera (`SteadyVaultCfr`):** a sonda agora também está no caminho de cadência fixa (antes ficava de fora, por isso `câmera(60 frames)` nunca aparecia). Novas linhas: `cadência fixa:` (resultados esperados, AE convergiu, exposição/ISO observados → aplicados), `câmera: intervalo longo=…` (um por buraco >25 ms, com AF/AE/foco/OIS/exposição/ISO atual e anterior), `câmera: falha/buffer perdido` e, no resumo de 60 frames, `luz(exp*iso)`, `longosComAF`, `afVarrendo`, `falhas`, `buffersPerdidos`.
- **Telemetria de entrega:** `entrega:` no fim de cada gravação (`sinaisDaCamera`, `sinaisComBuraco`, `ingeridos`, `ingestTardios`, `maiorParadaDoLaco`) e `swapsLentos(>20ms)` em `tempos`; a sonda da câmera soma `totalResultados`/`totalLongos`. Separa perda na câmera/HAL de perda na ponte/encoder.
- **Limitador de custo da interpolação (reação em cadeia):** com a câmera quase perfeita (2 intervalos longos em 600), a ponte teve 17 lacunas e 22 buracos de entrega. Cada preenchimento com fluxo óptico em 4K parava o laço ~80 ms (fluxo ~15–54 ms + warp ~6–25 ms), a câmera ficava sem buffer e perdia outro frame. Agora, se já há ≥2 frames esperando, ou se a lacuna anterior passou de 40 ms, as lacunas seguintes por 400 ms usam a mistura barata. Contador `limitadosPorCusto` na linha `movimento`.
- **Recepção da câmera em thread própria (fim da inanição de buffers):** os logs de 12:10 mostraram 36 travadas >20 ms em `eglSwapBuffers` (encoder lento em cena escura/ruidosa) e a câmera perdendo frame a cada ~18 (64 buracos de entrega contra 16 intervalos longos no sensor). Com tudo numa thread GL, qualquer bloqueio na saída segurava os buffers da câmera. Agora há duas threads/contextos compartilhados: **recepção** (`ingestLoop`: dona da SurfaceTexture, copia câmera → anel com o filtro temporal, `glFinish`, enfileira) e **saída** (`renderLoop`: fila → lacunas → encoder). Anel de 8 slots (~130 ms de folga); o slot só volta à lista livre depois de `glFinish` na saída, e o histórico do filtro nunca é escolhido como destino. Contexto de recepção surfaceless (ou pbuffer como alternativa). `startOutput()` abre uma "geração" que as duas threads usam para descartar o que sobrou.
- **Limitador relaxado após a separação em threads:** com a recepção independente (anel de 8), a saída pode atrasar sem prejudicar a câmera. O limitador ficava ativo demais (28 de 45 lacunas viraram mistura simples, que dá fantasma/tranco): agora só limita com ≥5 frames esperando, orçamento 120 ms e pausa de 150 ms. Espera do AE antes de congelar a cadência: 45 → 90 resultados (nos logs ele ainda estava em busca aos 45).
- **Sem borrão no caminho de reserva:** quando a lacuna não usa movimento compensado (limitador ou erro), a saída passa a ser o frame real mais próximo (nítido) em vez da mistura de dois frames, que em movimento gera fantasma.
- **Nunca repetir frame:** revertido o "frame mais próximo" no caminho de reserva (repetia frame). A lacuna é sempre interpolada de forma bidirecional (o warp leva anterior e atual ao instante da saída); a mistura temporal só entra se o movimento falhar. O limitador só atua com ≥7 frames esperando no anel de 8 (orçamento 250 ms).
- **Sincronia entre as duas threads GL (suspeita de "vídeo voltando"):** a cópia da recepção agora termina com uma cerca EGL (`eglCreateSyncKHR`) que a saída espera antes de ler o slot (padrão para textura compartilhada entre contextos; `glFinish` se a cerca não existir). Cada slot recebe um número de ordem; `foraDeOrdem` e `semCerca` aparecem na linha `movimento`. Branch `main5-1thread` guarda a versão de uma thread só (commit 338490b) para comparar.
- **Piscada de granulação nos frames recriados:** num clipe real (4K60, ~238 Mbps) o ruído de alta frequência cai 15–25% exatamente nos frames interpolados (a cada ~19 frames), porque a média dos dois frames suaviza o grão; isso pulsa a ~3 Hz. Agora, onde os dois frames concordam, cada pixel vem de UM dos dois (sorteio por pixel com probabilidade alpha) em vez da média, mantendo o mesmo grão dos frames reais. PSNR nas panorâmicas sintéticas praticamente igual (−0,3 dB). Não havia flicker de brilho nem frames voltando para trás nesse clipe.
- **Perfil de cor agora vale na gravação CFR:** o "look" da GPU (nitidez/saturação/curva S) era fixo e ia contra "Natural suave"/"Baixo contraste" (que já aplicam curva no sensor). Agora `VideoLook` tem perfis: Natural 0.9/1.2/0.4, Suave 0.6/1.08/0.12, Baixo contraste 0.3/1.0/0.0 (nitidez/saturação/curva), levados de `CaptureService` até os shaders da ponte e do interpolador.
- **Menos granulado:** filtro espacial que preserva bordas no ingest (4 diagonais; croma suavizado forte, luma leve) antes do denoise temporal. Simulação: ruído de luma −36%, de croma −55%, bordas e textura fina preservadas.
- **Fallback visível:** se o MediaCodec direto for recusado e a gravação cair no MediaRecorder (sem CFR/interpolação/look), agora há `Log.e` "FALLBACK" e aviso na notificação. HDR HLG10 continua no MediaRecorder (a ponte é 8 bits).
- **Log do foco inteligente (`SteadyVaultCfr`):** mostra o que foi identificado (`foco inteligente: identificou <tipo> em x,y` ou "nada identificado"), "sem alvo recente" e a região de foco aplicada no sensor.
- **Log `modo de exposição:`** no início da gravação diz se a cadência fixa (exposição ≤1/120 s) ou o AE automático (até 1/60 s, mais borrão) está ativo e por quê. Num clipe real a exposição ficou em 1/60 s o tempo todo porque "Auto FPS em pouca luz" (padrão ligado) mantém o AE automático.
- **Denoise temporal adaptativo (menos granulado):** o filtro do ingest tinha limiar fixo por pixel; com ISO 1500–2200 o ruído do sensor passava do limiar e o filtro quase não atuava (simulação: σ=0,05 → só ~7% de redução). Agora: (1) movimento medido na média de 5 amostras (ruído ~2,2x menor), (2) limiar proporcional ao ISO (`SensorNoiseHint`, 1,0 em ISO 1600, 0,7–1,8), (3) confiança por pixel no canal alfa do anel (frames parados seguidos; peso = idade/(idade+1), teto 5 → 0,83; zera em movimento). Simulação: ruído em área parada 0,023→0,009 (σ=0,03) e 0,046→0,018 (σ=0,05), sem rastro maior no objeto em movimento.
- **Alinhamento por movimento global no denoise temporal (letras/bordas em panorâmica):** durante uma panorâmica o filtro temporal desligava em todos os pixels (o histórico estava deslocado), então letras e bordas em movimento ficavam com o ruído inteiro. Novo `GlobalMotionEstimator` (GPU, sem readback no caminho crítico): luma 1/8 do frame, SAD de 17x17 deslocamentos candidatos (±64 px/frame) em 8 faixas, mínimo + parábola sub-pixel em textura 1x1; o ingest lê o deslocamento (inteiro, em px de saída) e alinha o histórico (e a idade de confiança) antes de misturar. Só confia se o mínimo for claramente melhor que "parado" e que a média (parede lisa → 0). O controle por pixel continua valendo, então movimento local (mão) não ganha rastro. Teste headless (WebGL, `tools/motion_test/run_gmc.py`): erro de estimativa ~0,5–1 px até 63 px/frame, imune a ruído σ=0,06; `run_gmc_filter.py` (pan de 14 px/frame, σ=0,04): erro vs. cena limpa 0,025 → 0,017 (−33%) sobre o filtro sem alinhamento. Log: `movimento global: ativo` no início e `movimento global: dx dy` a cada ~1 s. Se a GPU recusar o shader, o filtro volta ao comportamento anterior.
- **Foco inteligente mais sensível (mão/pé sozinhos):** o ML Kit de pose precisa de corpo/rosto e o quadro de análise era só 240x135 (com 1 análise a cada 1,5 s). Agora: quadro de análise até 480x270, análise a cada 0,7 s, probabilidade mínima 0,48 → 0,40 e um plano B por cor (`SkinBlobFinder`): quando a pose não acha ninguém, procura pele "nova" no quadro (cor estrita Cr>150, R/G≥1,4, saturação≥0,42 + modelo de fundo por célula, 2 quadros só aprendendo). Validado em vídeos reais: mão sobre a tela detectada; parede bege e pelúcia amarela, nenhuma detecção (pele larga em Cb/Cr dava 32–40% de falso positivo na parede, por isso a cor estrita). Novo tipo `SKIN` ("mão/pé"), região de foco 20% do sensor.
- **Mão sozinha não vira "mão na boca":** o modelo de pose inventava boca/rosto em volta de uma mão e o foco ia para um ponto entre a mão e a boca falsa. Rosto/boca só valem com ≥2 marcos de olhos/nariz/orelhas com probabilidade ≥0,6; sem isso a pose não vira rosto nem mão-na-boca. Pele nova (mão/pé) agora tem prioridade sobre corpo/mãos "adivinhados" pela pose (rosto e mão-na-boca confiáveis continuam na frente).
- **Diagnóstico do foco inteligente só por log (sem enviar mp4):** novas linhas `SteadyVaultCfr`: `foco inteligente: pose=<tipo|nada> marcos=N/33 rostoConfiavel=N/5 maoMax=… pele=<área centro corCr R/G|nenhuma> -> <tipo escolhido>`; `foco inteligente: nitidez alvo=… quadro=… razão=…` (Laplaciano na região do alvo vs. quadro todo, mostra se o alvo ficou mais nítido); e no resumo de 60 frames `foco=min-max afPedido=[…] afAplicado=[…]` (região pedida no request vs. a devolvida pelo HAL e faixa de distância focal). Revela se o HAL ignora a região de AF no modo contínuo.
- **Travadas a cada ~3 s (medido no mp4 SV_..._143903):** o sensor entrega 16,747 ms por frame (59,7 fps) e a grade é 60 fps; a cada ~193 frames a grade precisa inserir UM frame. Medindo o deslocamento entre frames, a inserção aparecia como (a) meio passo + meio passo (pan a 60% da velocidade por 2 frames) ou (b) repetição seguida de salto duplo (frame intermediário colado no anterior, ex.: 735 → 0 px e depois 2 passos). Agora, em lacuna (≥2 saídas por frame real) as saídas intermediárias ficam igualmente espaçadas entre o frame anterior e o atual, em vez de dependerem da fase da grade (corrige o caso b). Novo log `lacuna: saídas=… intervaloSensor=…ms movimento=… custo=…ms fila=…` (até 40 por gravação) para ver cada inserção.
- **Foco inteligente (ajustes após log + mp4):** (1) rosto/"mão na boca" da pose só valem se houver tom de pele no ponto do rosto (testado no vídeo: parede/monitor 0%, rosto/mão 56–100%); (2) pele nova grande (rosto/mão em close) agora vale até 85% do quadro (antes 50% → "nada" com o rosto em close); (3) alvo guardado por 8 s (antes 5); (4) alvo suavizado (50%), re-mira só com movimento ≥0,09 ou troca de tipo, região de pele 30% do sensor (antes 20%) para a mão em movimento não reiniciar a busca do AF a cada análise.
- **Diagnóstico do player do app por log (`SteadyVaultPlayer`):** o player do app trava mais que o da Galeria da Samsung. Novas linhas: `diagnóstico: motor=… perfil=… fps=… limiteDescarte=…ms telaHz=…`, `decodificador=<nome>`, `formato=… bitrate=…`, `taxa pedida à tela=…Hz`, `N quadros descartados` e, a cada 2 s, `exibidos/descartados/pulados/maxDescartesSeguidos/bufferado`. Para 60 fps o limite de descarte é 18 ms (quadro >18 ms atrasado é descartado antes de decodificar, o que em 4K60 de ~240 Mbps pode virar travada); só será alterado depois de ver o log.
- **Granulado voltou em cena mais escura (ISO 2200–3700):** no teste de 14:57 o ISO ficou em 2200–3675 (14:39: 1200–2300) e o filtro espacial tinha limiares FIXOS: com mais ruído os vizinhos ruidosos eram rejeitados como "borda" e a luma só caía 17% (simulação σ=0,04), contra 35% em ISO ~1600. Agora os limiares espaciais crescem com o ISO (`uNoiseScale`, nunca abaixo do base; simulação: −33% de ruído em ISO 1,5x, textura preservada) e o teto da "idade" do filtro temporal sobe de 5 para até 8 em ISO alto (peso máx. 0,83 → 0,89). O código de denoise não foi alterado nos commits anteriores; a causa foi a cena mais escura.
- **Player do app (log de 14:57):** sem quadros descartados, 60 fps exibidos, decodificador de hardware `c2.qti.hevc.decoder`, tela pedida a 60 Hz. A trava vem do conteúdo do arquivo (lacunas de 33 ms do sensor em pouca luz), não do player.
- **Foco (inteligente e toque) com zoom:** com `CONTROL_ZOOM_RATIO` (Android 11+) as regiões AF/AE são expressas em coordenadas PÓS-zoom (o array ativo inteiro = campo de visão com zoom), mas o código usava o recorte físico centralizado (`sensorRegion`), puxando o foco para o centro (2x: alvo em x=0,9 virava ~0,7). Novo `CameraZoom.meteringArray` (array ativo inteiro no caminho de zoom ratio; recorte só no caminho antigo `SCALER_CROP_REGION`), usado no foco inteligente e no toque para focar da prévia.
- **Miniaturas nítidas e que sempre carregam:** (1) o `ThumbnailUtils` devolvia o frame encaixado em 512x512 (ex.: 288x512 em vídeo vertical) e o recorte quadrado o ampliava ~1,8x → borrado; agora o `MediaMetadataRetriever` vem primeiro, pedindo o tamanho de cobertura, e entre até 3 quadros fica o de maior nitidez (energia de gradiente no centro); o `ThumbnailUtils` virou reserva (pedindo 2x). (2) cache de disco versionado (v2): as miniaturas borradas antigas são descartadas uma vez. (3) as grades guardavam no cache de memória o ÍCONE provisório (gravando, falha ou mudança de geração) e a miniatura ficava presa nele; agora `isPlaceholder()` impede isso nas três grades (cofre, galeria privada, lixeira) e a próxima vinculação tenta de novo. (4) uma falha passageira de geração tenta de novo após 350 ms.
- **Granulado com zoom:** o grão do sensor cresce com o recorte/telefoto; `SensorNoiseHint.zoom` (+8% de tolerância por 1x acima de 1) entra na escala de ruído do denoise espacial/temporal (limite 0,7–2,2).
- **Vídeo ORIGINAL (sem processamento de imagem), só foco + tempo:** no clipe de 15:46 o granulado variou ~±30% ao longo do vídeo (1 s: de 0,25 a 0,35 em unidades de ruído, acompanhando brilho/ISO e o filtro adaptativo), então o processamento foi retirado: o ingest é cópia pura do pixel da câmera (`uPassthrough`), o alinhamento por movimento global nem é inicializado, e o "look" (nitidez/saturação/curva) fica desligado em todos os caminhos (frame real, movimento compensado e mistura de reserva) por `VideoLook.ENABLED = false`. Continuam: reamostragem CFR por timestamp (sem repetir frame; lacunas interpoladas), cadência/AE da câmera e foco inteligente. Para voltar ao processamento: `ORIGINAL_IMAGE = false` (ponte). O Perfil de cor das Configurações volta a valer apenas com o look ligado (o perfil do sensor continua valendo).
- **Cor de volta, sem nitidez (3x ficou opaco):** no clipe de 3x (16:51) as sombras ficaram levantadas (preto ~54/255) e o contraste baixo (desvio da luma 37, contra 54 sem zoom) depois de desligar o look. `VideoLook.ENABLED = true` com NITIDEZ 0 em todos os perfis: ficam só saturação e curva de contraste do Perfil de cor (Natural 1,2/0,4; Suave 1,08/0,12; Baixo contraste 1,0/0,0). Denoise e alinhamento seguem desligados (imagem original); o Perfil de cor das Configurações volta a valer.
- **AE própria na cadência fixa (vídeo escuro ao mudar de cena):** a cadência fixa (AE_MODE_OFF, quadro 16,67 ms, sem o quadro pulado do AE da HAL) congelava exposição/ISO do início; parede clara -> mesa escura ficava preta. Novo `SoftAutoExposure` mede a luminância do centro a cada 250 ms (amostra da ponte, antes do look), calibra o alvo nas 2 primeiras medidas e ajusta a "luz" (exposição x ISO) com zona morta e limite de passo (sem pulsar); estouro de brilho corta pela metade. `SensorCadencePolicy.resolveFromLight` reparte a luz: 1/120 s por padrão, ou até ~1/60 s com "pouca luz" ligado (ISO de conforto 800). A duração do quadro nunca muda. Log `AE própria: luma=… alvo=… luz=… -> exp=… iso=…` a cada 1 s.
- **Sem granulado (denoise de volta, força constante):** `ORIGINAL_IMAGE=false`. O filtro espacial+temporal volta com piso fixo de força (`NOISE_SCALE_FLOOR=1.5` nos limiares espacial/temporal e no teto de idade), para o grão não aparecer e sumir conforme ISO/cena; luma espacial mais forte (peso 0,4 -> 0,65). A AE própria passa a priorizar exposição longa (até ~1/60 s) antes de subir o ISO acima de 400 (`SOFT_AE_COMFORT_ISO`), reduzindo o grão na origem; custo: um pouco mais de borrão de movimento em cena escura.
- **Gravação PURA (sem processamento):** `PURE_FRAMES=true`: um frame da câmera vira exatamente um frame do arquivo, com o instante real do sensor como PTS (VFR leve). Nenhum frame é criado (interpolação/mistura), repetido ou descartado pelo reamostrador CFR. `ORIGINAL_IMAGE=true` (sem denoise/alinhamento) e `VideoLook.ENABLED=false` (sem cor/nitidez): o pixel da câmera vai direto ao encoder. Continuam só controles da câmera: foco (inteligente) e cadência fixa de 16,67 ms com AE própria (exposição 1/120 s por padrão; ISO de conforto 800 com "pouca luz").
- **Configurações enxutas (gravação pura):** saem da tela de configurações e dos menus rápidos do preview: Perfil de cor, Redução de ruído, Nitidez/contornos, Temperatura/Neutralizar amarelo, "Travar AE/AWB para preservar cadência" e a opção "Super Estável" (estabilização na GPU, agora sempre desligada). Esses valores ficam fixos nos padrões (cor natural, ruído/nitidez Auto, AE livre). Ficam as configs de vídeo: resolução, FPS, codec, bitrate, I-frame, estabilização da câmera, foco, foco inteligente, antiflicker, balanço de branco (+travar cor), exposição, "pouca luz", áudio, térmica.
- **"Priorizar qualidade em pouca luz" removido (tudo automático):** a AE própria sempre usa 1/120 s com luz boa e sobe a exposição até ~1/60 s só quando o ISO passaria de 800; o ISO só sobe além disso no limite. O interruptor sai da tela de configurações.
- **Limpeza de código sem uso:** a ponte GPU (`RealTimeCfrSurfaceBridge`) foi reescrita só com o caminho da gravação pura (cópia câmera -> anel -> encoder com PTS do sensor): saem o reamostrador CFR, a interpolação com movimento, a mistura, o Super Estável na GPU, o denoise espacial/temporal, o alinhamento por movimento global e o look de cor (arquivos `CfrTimeResampler`, `MotionInterpolator`, `MotionShaders`, `GlobalMotion`, `VideoLook`, `SensorNoiseHint`, `CfrInterpolationPlanner`, mais os testes/simuladores `CfrTimeResamplerTest`, `CfrInterpolationPlannerTest`, `tools/cfr_sim`, `tools/motion_test`). Também saem métodos/constantes sem nenhuma referência (menus rápidos de cor/ruído/nitidez/amarelo, `buildLocked3ARequest`, `prepareHeadlessInParallel`, `RecordingFinalizer`, `RecordingFilePublisher`, `HighFpsCadenceValidator`, `FileDurability` etc.). Conferido com o compilador Kotlin: nenhum erro novo de referência/sintaxe em relação ao estado anterior (o SDK Android não está disponível aqui, então o build completo precisa ser feito no Android Studio).
- **FPS máximo do sensor (120 FPS verificado pelo próprio aparelho):** `SUPPORTED_FPS = {30, 60, 120}`. A matriz de capacidades já testa cada combinação resolução x FPS com sessão real (e exige encoder de hardware), então "120 FPS" só aparece nos Ajustes (e no diálogo de taxa de quadros) nas resoluções que o aparelho realmente aceita; use "Reanalisar hardware" depois de instalar. A cadência fixa + AE própria passa a valer para 60 e 120 FPS (`SensorCadencePolicy.supportsFixedCadence`; teto de exposição 1/(2·fps)). A duração do quadro agora respeita o mínimo real do sensor naquele tamanho (`sensorMinFrameDurationNs`, ex.: 16,747 ms em 4K "60"), em vez de pedir menos e a HAL corrigir sozinha. Bitrate padrão de 120 FPS = 1,6x o de 60 FPS. Novo log `FPS do sensor: entregue=… nominal=… (%)` no fim de cada gravação, medido nos timestamps reais do sensor.
- **2K (1440p) entra na verificação de modos:** a matriz de capacidades só testava 8K, 4K, 1080p e 720p; "2K / QHD" aparecia nos Ajustes mas nunca era confirmado pelo aparelho. Agora 2560x1440 é testado como os demais (e pontuado entre 4K e 1080p ao escolher o melhor modo). Requer "Reanalisar hardware".
- **Suporte a 120 e 240 FPS em todo o app:** `SUPPORTED_FPS = {30, 60, 120, 240}`; botões rápidos 120/240 na tela de gravação e no preview (aparecem só quando o aparelho confirma o modo), Ajustes, widgets e player já eram genéricos. A matriz de capacidades agora também varre os modos constrained high-speed da HAL (`highSpeedVideoSizes` + `getHighSpeedVideoFpsRangesFor`, faixa fixa 120/240 + encoder de hardware) e persiste `highSpeed` (cache schema 11; use "Reanalisar hardware"). Escolha da rota: 120 usa o modo regular (cadência fixa + AE própria) quando a matriz o confirmou; 240 (e 120 sem modo regular) usa a sessão high-speed (`createHighSpeedRequestList` + `setRepeatingBurst`), sem HDR. Preview limitado a 60 FPS (a sessão regular não aceita 120/240).
- **Gravação não fica escura em 120/240:** 120 regular usa a AE própria (agora vale para toda taxa com `supportsFixedCadence`; antes o gatilho era só 60). No high-speed a AE é da HAL (exposição ≤ 1/fps), então há um laço de brilho: começa com +1 EV e, a cada 250 ms, mede a luminância da ponte e sobe/desce a compensação de EV (nunca abaixo do valor do usuário; só troca o burst repetido, sem mudar a duração do quadro). Log `brilho high-speed: luma=… EV(índice)=…`. Limite físico: 240 FPS expõe no máximo 1/240 s, então cenas muito escuras ainda podem ter mais ruído.
- **120/240 FPS: resolução ajustada e diagnóstico:** ao escolher uma taxa alta que não existe na resolução atual (ex.: 4K 120), `preferredResolution` agora cai para a maior resolução confirmada pela matriz para aquela taxa (antes ficava na resolução pedida e a sessão falhava). Corrigido também `CaptureModeCatalog.remember`, que apagava logo em seguida as resoluções lembradas de 120/240. A análise de hardware registra os tamanhos/faixas high-speed anunciados pela câmera (visível no relatório de capacidades).
- **"Não foi possível finalizar o MP4" em 120 FPS (log de 07:55):** duas causas. (1) O 4K "120" passou na matriz (a consulta de sessão aceita a faixa 120-120), mas a HAL entregou 16,66 ms por quadro (60 FPS reais; log `FPS do sensor: 47,6%`); a matriz agora exige, a partir de 120 FPS, que o tempo mínimo de quadro do tamanho caiba na taxa (schema 12: reanalise o hardware). (2) O portão de início do arquivo no encoder (`DirectMediaCodecBackend.drain`) só abria após 4 intervalos de PTS iguais ao nominal (1/120 s); como o sensor entrega a própria cadência (pura, sem grade CFR), nunca abria: `gravados=0 descartadosNoInicio=380` e o MP4 ficava vazio. Agora o arquivo começa no primeiro quadro-chave depois do início (pedindo um se preciso), sem exigir cadência.
- **120/240 high-speed: só 1/4 dos quadros chegava e a imagem ficava escura (log de 07:58):** com a SurfaceTexture da ponte GPU como única saída da sessão constrained high-speed, a HAL a trata como "preview" e entrega só 30 fps (120) / 60 fps (240) (`FPS do sensor: 29,83` e `60,03`). Agora, em high-speed, a câmera grava DIRETO na Surface do encoder (`DirectMediaCodecBackend(directCamera=true)`, como o MediaRecorder; rotação pelo hint do muxer), e uma segunda saída pequena (ImageReader YUV, tamanho high-speed ≤720p, mesma faixa de FPS) só mede o brilho; se a HAL recusar o par, repete a sessão só com o encoder. Brilho: AE da HAL + compensação de EV (até o máximo); se ficar escuro (<0,20) por ~1 s no máximo, passa a exposição/ISO manuais (exposição no teto do quadro, ISO guiado pela luminância, alvo 0,40; `SoftAutoExposure(fixedTarget)`). `supportsFixedCadence` inclui 240. Novo log `FPS gravado no arquivo: …` no resumo do encoder. Não foi testado no aparelho.
- **"surface 35 is not for preview or hardware video encoding" (high-speed):** a saída de análise de brilho era um `ImageReader` YUV comum (só CPU), que a sessão constrained high-speed não aceita como preview. Agora ele é criado com uso `GPU_SAMPLED_IMAGE | CPU_READ_OFTEN` (Surface de textura, aceita como preview); se a HAL ainda recusar (exceção ao criar a sessão ou o burst), a sessão é refeita só com a Surface do encoder, sem laço de brilho por quadro.
- **Player pulando quadros em 120 FPS (log de 08:05):** a gravação high-speed agora entrega o ritmo certo (`FPS gravado no arquivo: 119,38` / `235,64`), mas a HAL carimba os quadros em lotes: em 120 FPS os PTS saíam em pares (~4 ms e ~12 ms), então dois quadros caíam no mesmo vsync de 8,33 ms da tela de 120 Hz e o Media3 pulava ~40% (`exibidos=146 pulados=94` em 2 s), e a mediana dos intervalos fazia o player achar 240 fps (`fonte=239.98`). Agora, na gravação direta (high-speed), o encoder suaviza o PTS escrito no MP4 (previsão pelo intervalo médio + correção de 10%; lacunas/saltos reais > 1,5 quadro seguem o sensor): nenhum quadro é criado ou removido, só o carimbo de tempo fica regular. Em 240 FPS numa tela de 120 Hz o player continuará exibindo no máximo 120 quadros/s (os demais aparecem como "pulados"): é o limite da tela, não erro do arquivo.
- **120/240 FPS tocam em câmera lenta por padrão no player:** a velocidade automática passa a ser a que deixa a saída em ~30 quadros/s (120 FPS -> 0,25x; 240 FPS -> 0,125x), como o slow motion da câmera Samsung; até 60 FPS segue em tempo real. Novas velocidades 0,125x e 0,25x no botão de velocidade (`UiBehaviorRules.sanitizedPlaybackSpeed` e lista do player). Como a saída fica em 30 quadros/s, a tela de 120 Hz mostra todos os quadros (sem "pulados").
- **Câmera lenta automática só em 240 FPS:** 120 FPS volta a tocar em tempo real (a tela de 120 Hz mostra todos os quadros); 240 FPS continua a 0,125x. O botão de velocidade segue aceitando 0,125x e 0,25x.
- **Trocar de FPS mudava a resolução do outro:** ao tocar num botão de FPS (`selectRecordingMode`), a resolução pedida era a do FPS ATUAL; se o novo FPS não a suportasse (ex.: 4K -> 120 cai para 1080p), o 1080p era gravado como a do novo FPS e, ao voltar, o outro FPS herdava essa resolução. Agora a escolha parte da resolução guardada para o próprio FPS (`resolutionForFps`), e cada FPS mantém a sua.
- **Resolução nos Ajustes vale para todos os FPS:** ao escolher a resolução do arquivo, ela é aplicada a todos os FPS que a suportam; os que não suportam ficam com a maior resolução que o aparelho confirma para eles (ex.: 4K -> 4K em 30/60 e 1080p em 120/240; 1080p -> 1080p em todos que suportam).
- **Widget grava sempre o modo selecionado agora:** o FPS ia "congelado" no PendingIntent do widget (lido só quando o widget era redesenhado); se o modo mudasse e o widget não atualizasse, gravava o antigo. O botão agora não leva FPS e o receiver lê o modo atual no toque (a resolução já vinha das configurações atuais do FPS).
- **Rótulo do 2K igual em todo lugar:** o modo efetivo vindo do serviço mostrava "2560×1440" enquanto os Ajustes/botões mostravam "2K / QHD" (o `sizeName`/`resolutionValue` do serviço não conheciam 2560x1440). Agora ambos usam "2K / QHD" e o valor de resolução 2K (também no `CaptureStateStore.resolutionValueFromLabel`).
- **"2560×1440" ainda na tela inicial:** o rótulo antigo ficava salvo no histórico do modo efetivo por FPS. `CaptureStateStore` agora normaliza ao ler (altura 1440 -> "2K / QHD", e o mesmo para 4K/1080p/720p), então gravações antigas também aparecem com o nome oficial.
- **Pente fino (revisão dos fluxos de captura, ajustes, player):**
  - 120/240 só aparecem (botões, Ajustes, diálogo de FPS) quando a análise confirmou o modo nesta câmera; resolução não suportada nessas taxas fica indisponível (antes ficavam "não confirmado" e selecionáveis, e a gravação falhava; trocar para a câmera frontal exibia o 120 da traseira).
  - Perfil por FPS: ativar o perfil de um FPS não é mais sobrescrito pelos ajustes do FPS anterior (o botão de FPS gravava o perfil destino com zoom/WB/exposição do FPS antigo) e a resolução vem sempre de `resolution_<fps>`, então "a resolução dos Ajustes vale para todos" não se perde ao trocar de FPS.
  - Player: o detector de travamento considerava 0,125x como "travado" (piso fixo de 180 ms/s) e trocaria/recuperaria o motor em todo vídeo 240 FPS; o piso agora acompanha a velocidade. O alvo de atualização da tela também usa a velocidade real (0,125x).
  - Serviço: AE própria (`softAe/softAePlan`) é zerada a cada nova sessão (vazava para gravações com 3A travado); se a HAL recusar o `setRepeatingBurst` com a saída de análise, repete só com o encoder (antes só o `createHighSpeedRequestList` tinha retry); a configuração lembrada é descartada se a rota regular/high-speed mudou depois de uma nova análise; o ImageReader de análise usa thread própria e 4 buffers (um main thread ocupado podia segurar os buffers do high-speed).
  - HDR ao vivo fica indisponível em 120/240 FPS; o suavizador de PTS não incorpora lacunas reais no intervalo médio.
- **Removido "Pós-processar agora" (galeria):** a ação manual reconstruía o vídeo numa grade de quadros fixa (CFR), contrariando a gravação pura (1 quadro da câmera = 1 quadro do arquivo). Saem o item do menu da mídia e `AutoGapRepairService.repairNow`.
- **Reparo automático de lacunas removido:** sai o pacote `processing/auto` (`AutoGapRepairService`, `AutoGapRepairQueueStore`, `AutoGapRepairSettings`, ~1.400 linhas) e as ligações dele no cofre principal (progresso "Reparo", receiver, cancelamentos) e no player. Nada mais enfileirava reparos; a gravação é pura (sem reconstrução de quadros).
- **Tela da câmera: mostrador de teste:** o painel superior do preview ganhou um HUD leve (toque para expandir) com resolução • fps selecionado • prévia medida/alvo (verde ≥95%, amarelo ≥80%, vermelho abaixo; a prévia é limitada a 60 fps), tempo de exposição e ISO lidos do `TotalCaptureResult` já recebido (sem custo extra) e, expandido, codec, bitrate e "Último teste" (fps realmente gravado no arquivo, vindo de `LastRecordingStats`, em memória). Durante gravação/foto o HUD mostra o estado da sessão.
- **Diálogo de FPS da câmera:** os itens mostravam a resolução máxima detectada (ex.: "4K • 60 FPS") em vez da resolução escolhida nos Ajustes para aquele FPS; agora seguem a escolha.
- **Resolução voltava para 4K ao abrir a câmera:** `preferredResolution` caía na MAIOR resolução confirmada quando nada igual ou menor ao pedido estava confirmado (ex.: 720p/1080p escolhido nos Ajustes e a análise sem esse modo no FPS), e a sincronização da tela da câmera gravava esse 4K nos Ajustes. Agora sobe apenas até a menor resolução confirmada acima do pedido.
- **Entrar em FOTO mudava a resolução de vídeo dos Ajustes (ex.: 2K virava 4K):** ativar o perfil de foto gravava a resolução/FPS desse perfil nas preferências globais de vídeo. Agora a ativação de foto preserva a resolução e o FPS atuais; o HUD mostra "FOTO" em vez de resolução/FPS de vídeo nesse modo.
- **HUD:** em modo FOTO o alvo da prévia é 30 fps (a sessão de foto roda a 30), não 60; a cor e o "X/Y" passam a usar esse alvo.
- **Prévia abaixo de 60 fps com foco inteligente:** a cada ~1,6 s o foco por pose reaplicava a mesma região e reemitia o request repetido da câmera (derrubando alguns quadros). Agora só reemite quando a região realmente muda.
