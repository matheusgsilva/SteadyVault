package com.steadyvault.camera.capture.recorder

/**
 * "Look" aplicado na GPU a TODA saída enviada ao encoder (frame real, mistura e frame
 * interpolado, sempre com o mesmo código, para o resultado não pulsar entre eles):
 *  - nitidez: máscara de desfoque (unsharp) com 4 vizinhos, só na luma e com corte de ruído;
 *  - saturação: devolve cor às imagens lavadas;
 *  - curva em S suave: aprofunda sombras e dá contraste (o "branco pálido").
 *
 * O frame anterior guardado para a interpolação NÃO recebe o look (é copiado cru), para o
 * look ser aplicado uma única vez, no final. Ajuste os três #define abaixo; 0 desliga cada um.
 * Mesmo texto usado no teste headless de tools/motion_test (sem o caractere de dólar).
 */
internal object VideoLook {
    /**
     * Perfis de cor (Configurações > Perfil de cor). Na câmera, SOFT/FLAT já aplicam uma curva de
     * tons no sensor; por isso o look da GPU fica mais leve neles, em vez de desfazer a escolha.
     */
    /**
     * true = só COR (saturação + curva de contraste) pelo perfil escolhido; a NITIDEZ da GPU fica
     * em 0 em todos os perfis (ela amplificava o grão). false = nenhum ajuste de cor.
     */
    const val ENABLED = true

    const val PROFILE_NATURAL = 0
    const val PROFILE_SOFT = 1
    const val PROFILE_FLAT = 2

    fun profileFor(colorProfile: String): Int = when (colorProfile) {
        "SOFT" -> PROFILE_SOFT
        "FLAT" -> PROFILE_FLAT
        else -> PROFILE_NATURAL
    }

    private fun defines(profile: Int): String {
        val sharpen: String
        val saturation: String
        val curve: String
        when (profile) {
            PROFILE_SOFT -> { sharpen = "0.0"; saturation = "1.08"; curve = "0.12" }
            PROFILE_FLAT -> { sharpen = "0.0"; saturation = "1.0"; curve = "0.0" }
            else -> { sharpen = "0.0"; saturation = "1.2"; curve = "0.4" }
        }
        return "#define LOOK_SHARPEN $sharpen\n#define LOOK_SATURATION $saturation\n#define LOOK_CURVE $curve\n"
    }

    const val GLSL = """
#define LOOK_CORE_LOW 0.03
#define LOOK_CORE_HIGH 0.09
uniform vec2 uLookTexel;
vec3 lookGrade(vec3 c, vec3 blur) {
    // Nitidez só na luma e com "coring": detalhe fraco (ruído, principalmente em cena escura)
    // não é realçado; só bordas reais. Sem isso o ruído sai ~2x maior e colorido.
    float detail = dot(c - blur, vec3(0.299, 0.587, 0.114));
    float edge = smoothstep(LOOK_CORE_LOW, LOOK_CORE_HIGH, abs(detail));
    vec3 s = c + vec3(LOOK_SHARPEN * edge * detail);
    float l = dot(s, vec3(0.299, 0.587, 0.114));
    s = clamp(mix(vec3(l), s, LOOK_SATURATION), 0.0, 1.0);
    vec3 curved = s * s * (3.0 - 2.0 * s);
    return mix(s, curved, LOOK_CURVE);
}
"""

    /** Raio do desfoque da máscara de nitidez, em pixels de saída (o uniform uLookTexel já vem multiplicado). */
    const val RADIUS_PX = 2f

    const val PLACEHOLDER = "LOOK_GLSL"

    fun insert(source: String, profile: Int = PROFILE_NATURAL): String =
        source.replace(PLACEHOLDER, defines(profile) + GLSL)
}
