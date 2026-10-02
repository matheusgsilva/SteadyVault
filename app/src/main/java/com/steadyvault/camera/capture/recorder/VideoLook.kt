package com.steadyvault.camera.capture.recorder

/**
 * "Look" aplicado na GPU a TODA saída enviada ao encoder (frame real, mistura e frame
 * interpolado, sempre com o mesmo código, para o resultado não pulsar entre eles):
 *  - nitidez: máscara de desfoque (unsharp) com 4 vizinhos de 1 pixel;
 *  - saturação: devolve cor às imagens lavadas;
 *  - curva em S suave: aprofunda sombras e dá contraste (o "branco pálido").
 *
 * O frame anterior guardado para a interpolação NÃO recebe o look (é copiado cru), para o
 * look ser aplicado uma única vez, no final. Ajuste os três #define abaixo; 0 desliga cada um.
 * Mesmo texto usado no teste headless de tools/motion_test (sem o caractere de dólar).
 */
internal object VideoLook {
    const val GLSL = """
#define LOOK_SHARPEN 0.9
#define LOOK_SATURATION 1.2
#define LOOK_CURVE 0.4
uniform vec2 uLookTexel;
vec3 lookGrade(vec3 c, vec3 blur) {
    vec3 s = c + LOOK_SHARPEN * (c - blur);
    float l = dot(s, vec3(0.299, 0.587, 0.114));
    s = clamp(mix(vec3(l), s, LOOK_SATURATION), 0.0, 1.0);
    vec3 curved = s * s * (3.0 - 2.0 * s);
    return mix(s, curved, LOOK_CURVE);
}
"""

    /** Raio do desfoque da máscara de nitidez, em pixels de saída (o uniform uLookTexel já vem multiplicado). */
    const val RADIUS_PX = 2f

    const val PLACEHOLDER = "LOOK_GLSL"

    fun insert(source: String): String = source.replace(PLACEHOLDER, GLSL)
}
