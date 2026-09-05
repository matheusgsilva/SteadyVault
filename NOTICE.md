# Avisos e componentes de terceiros

Este NOTICE.md corresponde ao SteadyVault 1.8.186 e foi preparado a partir das dependências declaradas no Gradle. Ele não substitui os textos integrais das licenças nem uma revisão jurídica.

## Licença do SteadyVault

O código-fonte fornecido não contém um arquivo LICENSE e não declara titular ou licença própria. Este aviso não concede direitos sobre o código do SteadyVault. Antes de publicar ou redistribuir o projeto, adicione uma licença apropriada e identifique o titular dos direitos autorais.

## Dependências incluídas no aplicativo

| Componente | Versão | Licença principal | Projeto |
| --- | ---: | --- | --- |
| AndroidX Core / Core KTX | 1.19.0 | Apache License 2.0 | https://github.com/androidx/androidx |
| AndroidX Activity KTX | 1.13.0 | Apache License 2.0 | https://github.com/androidx/androidx |
| AndroidX Biometric | 1.1.0 | Apache License 2.0 | https://github.com/androidx/androidx |
| AndroidX Fragment KTX | 1.8.9 | Apache License 2.0 | https://github.com/androidx/androidx |
| AndroidX WebKit | 1.16.0 | Apache License 2.0 | https://github.com/androidx/androidx |
| AndroidX ProfileInstaller | 1.4.1 | Apache License 2.0 | https://github.com/androidx/androidx |
| AndroidX Media3 ExoPlayer | 1.10.1 | Apache License 2.0 | https://github.com/androidx/media |
| AndroidX Media3 UI | 1.10.1 | Apache License 2.0 | https://github.com/androidx/media |
| libVLC para Android / libvlc-all | 3.7.4 | libVLC e grande parte dos módulos sob LGPL 2.1 ou posterior; componentes agregados podem possuir avisos próprios | https://www.videolan.org/vlc/libvlc.html |
| youtubedl-android (library, FFmpeg e aria2c) | 0.18.1 | Agrega componentes com licenças distintas; yt-dlp usa Unlicense, aria2 usa GPL-2.0 e a configuração efetiva do FFmpeg deve ser conferida no artefato distribuído | https://github.com/junkfood02/youtubedl-android |

AndroidX e Media3 são projetos do Android Open Source Project. O repositório AndroidX publica sua licença Apache 2.0 em:

https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt

O Media3 declara Apache License 2.0 em seus metadados de publicação:

https://github.com/androidx/media

O VideoLAN informa que o mecanismo libVLC foi relicenciado sob LGPL e que módulos de reprodução também foram migrados. Consulte:

https://www.videolan.org/press/lgpl-libvlc.html
https://www.videolan.org/legal.html

O pacote libvlc-all contém bibliotecas nativas e componentes transitivos. Quem distribuir APK/AAB deve preservar os avisos fornecidos pelo artefato, verificar a configuração exata recebida do Maven e cumprir todas as obrigações da LGPL e das licenças dos módulos agregados, inclusive requisitos aplicáveis de código-fonte, substituição ou relink.

## Dependências de teste

| Componente | Versão | Licença | Projeto |
| --- | ---: | --- | --- |
| JUnit 4 | 4.13.2 | Eclipse Public License 1.0 | https://github.com/junit-team/junit4 |

Texto da licença do JUnit:

https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt

## Ferramentas de compilação

As ferramentas abaixo são usadas para compilar o projeto e normalmente não são empacotadas como bibliotecas do aplicativo:

| Ferramenta | Versão | Licença/projeto |
| --- | ---: | --- |
| Android Gradle Plugin | 9.3.1 | Android Open Source Project / Apache License 2.0 |
| Kotlin | embutido no AGP 9.3.1 | Kotlin / Apache License 2.0 |
| Gradle Wrapper | 9.5.1 | Gradle / Apache License 2.0 |
| JDK | 17 | Depende da distribuição instalada pelo desenvolvedor |

## Obrigações de distribuição

Antes de distribuir uma versão pública:

1. Gere a árvore de dependências resolvida para cada variante release.
2. Extraia e preserve arquivos LICENSE, NOTICE e COPYING dos AAR/JAR e bibliotecas nativas.
3. Disponibilize os textos integrais exigidos pelas licenças.
4. Cumpra as condições da LGPL aplicáveis ao libVLC e aos módulos nativos incluídos.
5. Confirme se fontes, ícones, nomes, marcas ou mídias adicionadas ao projeto possuem autorização própria.
6. Repita a revisão sempre que uma dependência ou versão mudar.

## Marcas

Android, AndroidX e Google são marcas de seus respectivos titulares. Samsung e Galaxy são marcas da Samsung Electronics. VLC, VideoLAN e libVLC pertencem aos seus respectivos titulares. O uso dos nomes neste arquivo serve apenas para identificação técnica e não implica endosso.


1.8.264: vídeo traseiro usa câmera lógica/zoom ratio e cadência fixa de sensor em 60 FPS quando MANUAL_SENSOR é suportado.
